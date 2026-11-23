/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone (fork of linphone-android).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.AnyThread
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log

/**
 * MundoPhone: ¿deja Android que la app despierte con el push de una llamada estando cerrada?
 *
 * Medido en el borde (K5, 16-19/11/2026, 3.258 pushes): de los avisos de llamada que no consiguieron ni un REGISTER en
 * 60 s, 114 de 146 eran de Xiaomi (el 12 % de sus avisos, frente al 1 % de Samsung), y cuanto más tiempo llevaba el
 * móvil sin usar la app, más fallaban (más de 6 h sin actividad: 37-48 %). FCM da el push por entregado, pero MIUI/HyperOS
 * no arranca una app que ha detenido si no tiene el «Inicio automático» y la batería «Sin restricciones». Nada de eso se
 * puede cambiar desde la app: solo detectarlo, avisar y llevar al usuario a los ajustes.
 */
object MundoPhoneSegundoPlano {
    private const val TAG = "[MundoPhone Segundo Plano]"

    // Grupos de UsageStatsManager (sus constantes, sin depender de la versión de compilación)
    private const val BUCKET_RESTRICTED = 45

    // «Inicio automático» de MIUI/HyperOS: una operación interna de AppOpsManager (no hay API pública)
    private const val OP_INICIO_AUTOMATICO_MIUI = 10008

    /** Lo que Android deja hacer a la app en segundo plano, con las APIs oficiales. */
    class Estado(
        val bateriaOptimizada: Boolean,
        val segundoPlanoRestringido: Boolean,
        val grupo: Int?,
        val xiaomi: Boolean,
        /** Solo en Xiaomi: true o false si el sistema lo dice; null si no se puede saber. */
        val inicioAutomatico: Boolean?
    ) {
        val grupoRestringido: Boolean get() = grupo == BUCKET_RESTRICTED
    }

    @AnyThread
    fun esXiaomi(): Boolean {
        val fabricante = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        val marca = Build.BRAND.orEmpty().lowercase(Locale.ROOT)
        return fabricante == "xiaomi" || marca in setOf("xiaomi", "redmi", "poco")
    }

    @AnyThread
    fun estado(context: Context): Estado {
        val power = context.getSystemService(PowerManager::class.java)
        val optimizada = power?.isIgnoringBatteryOptimizations(context.packageName) == false
        val activity = context.getSystemService(ActivityManager::class.java)
        val restringido = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) activity?.isBackgroundRestricted == true else false
        val grupo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val xiaomi = esXiaomi()
        return Estado(optimizada, restringido, grupo, xiaomi, if (xiaomi) inicioAutomaticoMiui(context) else null)
    }

    /**
     * «Inicio automático» de MIUI/HyperOS: true/false, o null si no se puede saber. No hay API pública: MIUI lo guarda como
     * la operación 10008 de AppOpsManager (0 = permitido, 1 = denegado) y se lee por reflexión. Si el sistema no la tiene
     * o no deja leerla, null: entonces la guía pide comprobarlo y vale el «Ya está» del usuario.
     */
    @AnyThread
    fun inicioAutomaticoMiui(context: Context): Boolean? {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
            val metodo = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            when (metodo.invoke(appOps, OP_INICIO_AUTOMATICO_MIUI, Process.myUid(), context.packageName) as? Int) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * La pantalla de información de la app: en MIUI/HyperOS es donde están juntos el «Inicio automático» y el «Ahorro de
     * batería» (sin depender de las pantallas internas de Xiaomi, que cambian de nombre entre versiones); en el resto, la
     * «Batería» de la app. Devuelve false si no hay pantalla que abrir.
     */
    @AnyThread
    fun abrirAjustes(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("$TAG No app details settings screen: $e")
            false
        }
    }

    /**
     * La pantalla de «Inicio automático» de MIUI/HyperOS, la que más cuesta encontrar: en HyperOS (visto en el Xiaomi de
     * un cliente) no está en la información de la app. Se prueba la actividad del centro de seguridad y la acción de
     * MIUI; si ninguna se puede abrir, la información de la app. Devuelve false si no se abrió nada.
     */
    @AnyThread
    fun abrirInicioAutomatico(context: Context): Boolean {
        val candidatos = listOf(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )
        for (intent in candidatos) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i("$TAG Autostart settings opened [${intent.component?.className ?: intent.action}]")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w("$TAG No autostart screen [${intent.component?.className ?: intent.action}]")
            } catch (e: SecurityException) {
                Log.w("$TAG Autostart screen not allowed [${intent.component?.className ?: intent.action}]: $e")
            }
        }
        return abrirAjustes(context)
    }

    /**
     * Para el registro: el estado de ahora y por qué cerró Android la app las últimas veces (Android 11+). Con eso se ve,
     * caso a caso, si una llamada perdida fue el sistema matándola, una detención forzada o el congelador. Fuera del
     * arranque por push: lo llama un hilo aparte unos segundos después, y otra vez justo antes de subir los registros a
     * soporte (SupportLogsUpload), para que vaya en todos los envíos sea cual sea la marca aunque la app lleve días abierta.
     * Además de lo de segundo plano, lo que en cualquier Android deja la llamada sin sonar: Ahorro de datos, «Pausar
     * actividad si no se usa», notificaciones y pantalla completa.
     */
    @AnyThread
    fun registrar(context: Context) {
        try {
            val e = estado(context)
            val autostart = if (e.xiaomi) (e.inicioAutomatico?.toString() ?: "unknown") else "n/a"
            Log.i(
                "$TAG Manufacturer [${Build.MANUFACTURER}] brand [${Build.BRAND}] model [${Build.MODEL}] Android [${Build.VERSION.RELEASE}], " +
                    "Xiaomi layer [${capaXiaomi() ?: "n/a"}], " +
                    "battery optimized [${e.bateriaOptimizada}], background restricted [${e.segundoPlanoRestringido}], " +
                    "standby bucket [${nombreGrupo(e.grupo)}], autostart [$autostart]"
            )
            Log.i(
                "$TAG Data saver [${ahorroDeDatos(context)}], pause app if unused [${pausaSiNoSeUsa(context)}], " +
                    "notifications [${Compatibility.areNotificationsEnabled(context)}], full screen intent [${pantallaCompleta(context)}]"
            )
            // Permisos y ajustes que deciden si una llamada se oye y suena, que solo salían en el registro cuando algo fallaba
            Log.i(
                "$TAG Permissions: microphone [${permiso(context, Manifest.permission.RECORD_AUDIO)}], " +
                    "camera [${permiso(context, Manifest.permission.CAMERA)}], " +
                    "contacts [${permiso(context, Manifest.permission.READ_CONTACTS)}], nearby devices [${dispositivosCercanos(context)}]"
            )
            Log.i(
                "$TAG Call notification channels: ${canalesDeLlamada(context)}; do not disturb [${noMolestar(context)}], " +
                    "ringer [${timbre(context)}], battery saver [${context.getSystemService(PowerManager::class.java)?.isPowerSaveMode}]"
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val activity = context.getSystemService(ActivityManager::class.java) ?: return
            val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            for (salida in activity.getHistoricalProcessExitReasons(context.packageName, 0, 5)) {
                Log.i(
                    "$TAG Previous exit [${formato.format(Date(salida.timestamp))}]: reason [${nombreSalida(salida.reason)}], " +
                        "importance [${salida.importance}], status [${salida.status}], description [${salida.description}]"
                )
            }
        } catch (e: Exception) {
            Log.w("$TAG Can't read background state: $e")
        }
    }

    /**
     * La capa de Xiaomi y su versión («HyperOS OS2.0.12.0…» o «MIUI V14»), o null fuera de Xiaomi o si no se puede leer.
     * Es lo que más va a decidir si se puede leer «Inicio automático»: el modelo solo no basta.
     */
    @AnyThread
    fun capaXiaomi(): String? {
        if (!esXiaomi()) return null
        val hyperOs = propiedad("ro.mi.os.version.incremental").ifEmpty { propiedad("ro.mi.os.version.name") }
        if (hyperOs.isNotEmpty()) return "HyperOS $hyperOs"
        val miui = propiedad("ro.miui.ui.version.name")
        return if (miui.isNotEmpty()) "MIUI $miui" else null
    }

    /**
     * Para el campo «móvil» del envío a soporte: la capa de Xiaomi y la lectura de «Inicio automático». Va en el propio
     * envío (no solo dentro del registro), así que con el tiempo se puede cruzar modelo, versión y si la lectura funciona.
     * Vacío fuera de Xiaomi.
     */
    @AnyThread
    fun resumenParaSoporte(context: Context): String {
        if (!esXiaomi()) return ""
        val lectura = when (inicioAutomaticoMiui(context)) {
            true -> "sí"
            false -> "no"
            null -> "?"
        }
        return listOfNotNull(capaXiaomi(), "inicio automático: $lectura").joinToString(" / ", prefix = " / ")
    }

    // Propiedad del sistema (android.os.SystemProperties, sin API pública); "" si no se puede leer
    private fun propiedad(nombre: String): String {
        return try {
            val clase = Class.forName("android.os.SystemProperties")
            (clase.getMethod("get", String::class.java).invoke(null, nombre) as? String).orEmpty().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** Ahorro de datos de Android para esta app: DISABLED (apagado), WHITELISTED (exenta) o ENABLED (le corta en segundo plano). */
    private fun ahorroDeDatos(context: Context): String {
        return when (context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "DISABLED"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "WHITELISTED"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ENABLED"
            else -> "unknown"
        }
    }

    /** «Pausar la actividad de la aplicación si no se usa» (Android 11+): on si puede quitarle permisos y notificaciones. */
    private fun pausaSiNoSeUsa(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "n/a"
        return if (context.packageManager.isAutoRevokeWhitelisted) "off" else "on"
    }

    /** Permiso de pantalla completa para la llamada entrante (Android 14+ lo puede negar). */
    private fun pantallaCompleta(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return "n/a"
        return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent()?.toString() ?: "unknown"
    }

    /** Un permiso de ejecución: granted o denied. */
    private fun permiso(context: Context, nombre: String): String {
        return if (ContextCompat.checkSelfPermission(context, nombre) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
    }

    /** «Dispositivos cercanos» (Android 12+): sin él la app no ve los auriculares Bluetooth. */
    private fun dispositivosCercanos(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "n/a"
        return permiso(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * La importancia de los canales de las llamadas. El usuario puede bajarlos o apagarlos uno a uno aunque las notificaciones
     * de la app sigan activadas, y con el de entrantes en OFF, MIN o LOW la llamada no suena ni sale en pantalla.
     */
    private fun canalesDeLlamada(context: Context): String {
        val notificaciones = context.getSystemService(NotificationManager::class.java) ?: return "unknown"
        return listOf(
            "incoming" to R.string.notification_channel_incoming_call_id,
            "incoming without ringtone" to R.string.notification_channel_without_ringtone_incoming_call_id,
            "call" to R.string.notification_channel_call_id,
            "missed" to R.string.notification_channel_missed_call_id
        ).mapNotNull { (nombre, id) ->
            // El que no existe no se usa (la app crea el de entrantes con o sin tono según quién pone el tono): fuera
            notificaciones.getNotificationChannel(context.getString(id))?.let { "$nombre [${nombreImportancia(it.importance)}]" }
        }.joinToString(", ").ifEmpty { "none" }
    }

    private fun nombreImportancia(importancia: Int): String = when (importancia) {
        NotificationManager.IMPORTANCE_NONE -> "OFF"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        else -> importancia.toString()
    }

    /** «No molestar» del sistema: off, priority (solo prioritarias), alarms o total silence. */
    private fun noMolestar(context: Context): String {
        return when (context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "total silence"
            else -> "unknown"
        }
    }

    /** Modo del timbre: normal, vibrate o silent. */
    private fun timbre(context: Context): String {
        return when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> "unknown"
        }
    }

    private fun nombreGrupo(grupo: Int?): String = when (grupo) {
        null -> "unknown"
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        BUCKET_RESTRICTED -> "RESTRICTED"
        else -> grupo.toString()
    }

    private fun nombreSalida(motivo: Int): String = when (motivo) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        14 -> "FREEZER" // REASON_FREEZER, Android 14
        15 -> "PACKAGE_STATE_CHANGE" // Android 14
        16 -> "PACKAGE_UPDATED" // Android 14
        else -> "UNKNOWN($motivo)"
    }
}
