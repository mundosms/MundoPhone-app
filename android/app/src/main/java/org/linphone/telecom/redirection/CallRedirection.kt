/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone, a fork of linphone-android.
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
package org.linphone.telecom.redirection

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Core
import org.linphone.core.GlobalState
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log
import org.linphone.ui.redirection.CallRedirectionActivity
import org.linphone.utils.LinphoneUtils

/**
 * MundoPhone (E1): llamar con MundoPhone desde el teléfono nativo.
 *
 * Android 10+ le pasa a la app con el rol ROLE_CALL_REDIRECTION cada llamada que se marca en el teléfono o en Contactos,
 * ANTES de mandar nada a la red, y espera unos segundos a que conteste: «sigue normal» (sale por la SIM) o «cancélala»
 * (no sale nada). Lo decide [MundoPhoneCallRedirectionService]; la pregunta la enseña
 * [org.linphone.ui.redirection.CallRedirectionActivity]. Aquí está lo que comparten: el ajuste, el rol, la red, la
 * cuenta, la marca de la llamada que relanzamos por la SIM y el arranque de la llamada por MundoPhone.
 *
 * La pieza va aparte de la integración con Telecom de la app (autogestionada, org.linphone.telecom.TelecomManager), que
 * no se toca: si algo de aquí falla, la llamada sale por la SIM.
 */
object CallRedirection {
    private const val TAG = "[Call Redirection]"

    const val MODE_ASK = "ask"
    const val MODE_ALWAYS = "always"
    const val MODE_NEVER = "never"

    private const val POLL_MS = 100L

    // Si el hilo del Core no contesta a la comprobación de la cuenta, cuánto más se espera antes de decidir por la
    // configuración (Telecom solo da unos 5 s en total)
    private const val CORE_GRACE_MS = 300L

    // Lanzar la llamada con la app cerrada: el Core tiene que crearse, arrancar (On) y, si puede, registrarse
    private const val CALL_START_TIMEOUT_MS = 15000L
    private const val REGISTRATION_WAIT_MS = 4000L

    // La marca de la llamada que relanzamos por la SIM: casi inmediata con CALL_PHONE; con el marcador abierto (sin
    // permiso) el usuario aún tiene que pulsar llamar
    private const val SIM_MARK_TTL_MS = 10000L
    private const val DIALER_MARK_TTL_MS = 60000L

    private val accountSection = Regex("proxy_\\d+")

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Ajuste y rol ----

    /** Lo guardado; vacío = sin elegir. */
    @AnyThread
    fun storedMode(): String = corePreferences.mundosmsCallRedirection

    /**
     * Lo que vale de verdad: sin el rol, «nunca» (Telecom ni siquiera nos llama); con el rol y sin elegir, «preguntar».
     */
    @AnyThread
    fun effectiveMode(context: Context): String {
        if (!isRoleHeld(context)) return MODE_NEVER
        return storedMode().ifEmpty { MODE_ASK }
    }

    @WorkerThread
    fun saveModeOnCoreThread(mode: String) {
        corePreferences.mundosmsCallRedirection = mode
        Log.i("$TAG Ajuste guardado: [$mode]")
    }

    /** Desde el hilo principal (la pregunta, «Recordar mi elección»): espera al hilo del Core si aún no existe. */
    @MainThread
    fun saveMode(mode: String) {
        runOnCoreThreadWhenReady(
            SystemClock.elapsedRealtime() + CALL_START_TIMEOUT_MS,
            { Log.e("$TAG El hilo del Core no está listo: no se guarda el ajuste [$mode]") }
        ) {
            saveModeOnCoreThread(mode)
        }
    }

    // ---- Elección por número: manda sobre el ajuste general para ese número ----

    @AnyThread
    fun numberRules(): Map<String, String> = RedirectionNumbers.parseRules(corePreferences.mundosmsCallRedirectionNumbers)

    /** RedirectionNumbers.RULE_SIM, RULE_APP o null si ese número no tiene elección guardada. */
    @AnyThread
    fun ruleForNumber(number: String): String? = RedirectionNumbers.ruleFor(numberRules(), number)

    /** El ajuste para esta llamada (Telecom solo nos llama con el rol): la elección del número y, si no hay, el general. */
    @AnyThread
    fun modeForNumber(number: String): String = when (ruleForNumber(number)) {
        RedirectionNumbers.RULE_SIM -> MODE_NEVER
        RedirectionNumbers.RULE_APP -> MODE_ALWAYS
        else -> storedMode().ifEmpty { MODE_ASK }
    }

    /** [rule] null la quita. */
    @WorkerThread
    fun saveNumberRuleOnCoreThread(number: String, rule: String?) {
        corePreferences.mundosmsCallRedirectionNumbers =
            RedirectionNumbers.serializeRules(RedirectionNumbers.withRule(numberRules(), number, rule))
        Log.i("$TAG Elección para [${mask(number)}]: [${rule ?: "preguntar como el ajuste general"}]")
    }

    /** Desde el hilo principal (la pregunta, la ficha del contacto): espera al hilo del Core si aún no existe. */
    @MainThread
    fun saveNumberRule(number: String, rule: String?) {
        runOnCoreThreadWhenReady(
            SystemClock.elapsedRealtime() + CALL_START_TIMEOUT_MS,
            { Log.e("$TAG El hilo del Core no está listo: no se guarda la elección para [${mask(number)}]") }
        ) {
            saveNumberRuleOnCoreThread(number, rule)
        }
    }

    @WorkerThread
    fun clearNumberRulesOnCoreThread() {
        val count = numberRules().size
        corePreferences.mundosmsCallRedirectionNumbers = ""
        Log.i("$TAG Olvidadas [$count] elecciones por número")
    }

    @AnyThread
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION)
    }

    @AnyThread
    fun isRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)
    }

    /** La ventana del sistema que concede el rol (null antes de Android 10 o si el rol no existe en el aparato). */
    @AnyThread
    fun createRoleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isRoleAvailable(context)) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION)
    }

    // ---- Números (en RedirectionNumbers, que se prueba en la JVM) ----

    /** Para el log: nunca el número entero (ver [RedirectionNumbers.mask]). */
    @AnyThread
    fun mask(number: String): String = RedirectionNumbers.mask(number)

    /** Códigos de operadora, desvíos, `*#06#`…: siempre por la SIM. */
    @AnyThread
    fun isOperatorCode(number: String): Boolean = RedirectionNumbers.isOperatorCode(number)

    /** El número limpio para MundoPhone, o null si sale por la SIM (ver [RedirectionNumbers.dialableNumber]). */
    @AnyThread
    fun dialableNumber(number: String): String? = RedirectionNumbers.dialableNumber(number)

    // ---- Marca de la llamada que relanzamos por la SIM ----

    private val simMark = SimRelaunchMark { SystemClock.elapsedRealtime() }

    /** La siguiente llamada a [number] que pase por el servicio, antes de [ttlMs], sale por la SIM sin preguntar. */
    @AnyThread
    @Synchronized
    fun markSimRelaunch(number: String, ttlMs: Long) {
        simMark.mark(number, ttlMs)
        Log.i("$TAG La próxima llamada a [${mask(number)}] en [$ttlMs] ms sale por la SIM sin preguntar")
    }

    /** ¿Es la que relanzamos nosotros? La marca vale una sola vez y caduca. */
    @AnyThread
    @Synchronized
    fun consumeSimRelaunch(number: String): Boolean {
        return simMark.consume(number) { expired ->
            Log.i("$TAG Caducada la marca de la llamada por la SIM a [${mask(expired)}]")
        }
    }

    @AnyThread
    @Synchronized
    private fun clearSimMark() {
        simMark.clear()
    }

    // ---- Red ----

    class NetworkState(val available: Boolean, val wifi: Boolean)

    /** Red con Internet comprobada por Android (una wifi con portal cautivo no vale). */
    @AnyThread
    fun networkState(context: Context): NetworkState {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkState(available = false, wifi = false)
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return NetworkState(available = false, wifi = false)
        val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return NetworkState(available, wifi)
    }

    // ---- Cuenta ----

    class AccountCheck(val ok: Boolean, val reason: String)

    /**
     * ¿Puede llamar MundoPhone? Cuenta por defecto registrada (y, si la app está en «solo wifi», con wifi). Contesta en
     * el hilo principal en menos de [timeoutMs] + [CORE_GRACE_MS]:
     * - con el Core en marcha, manda su estado: «Ok» sí; «Failed», sin cuenta o sin registro, no; registrándose (tras
     *   un cambio de red), espera hasta el plazo y, si no termina, no;
     * - con la app cerrada (el servicio arranca el proceso y el Core aún no está On), sí en cuanto se sabe que hay cuenta
     *   que se registra: el arranque en frío ya se come parte de los ~5 s de Telecom, y la llamada esperará al Core y al
     *   registro ([startAppCall]);
     * - si el hilo del Core no contesta a tiempo, decide la configuración: sí si hay alguna cuenta.
     */
    @MainThread
    fun checkAccount(net: NetworkState, timeoutMs: Long, callback: (AccountCheck) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var done = false

        fun finish(check: AccountCheck) {
            if (done) return
            done = true
            callback(check)
        }

        mainHandler.postDelayed({
            finish(accountFromConfig("el hilo del Core no ha contestado a tiempo"))
        }, timeoutMs + CORE_GRACE_MS)

        fun askCore() {
            if (done) return
            if (!coreContext.isReady()) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    finish(accountFromConfig("el Core aún no está creado"))
                } else {
                    mainHandler.postDelayed({ askCore() }, POLL_MS)
                }
                return
            }
            coreContext.postOnCoreThread { core ->
                val coreWasOn = core.globalState == GlobalState.On
                evaluateAccount(core, net, deadline, coreWasOn) { check ->
                    coreContext.postOnMainThread { finish(check) }
                }
            }
        }
        askCore()
    }

    @WorkerThread
    private fun evaluateAccount(
        core: Core,
        net: NetworkState,
        deadline: Long,
        coreWasOn: Boolean,
        done: (AccountCheck) -> Unit
    ) {
        if (core.isWifiOnlyEnabled && !net.wifi) {
            done(AccountCheck(false, "la app está en «solo wifi» y no hay wifi"))
            return
        }
        val account = LinphoneUtils.getDefaultAccount()
        if (account == null) {
            done(AccountCheck(false, "no hay cuenta por defecto"))
            return
        }
        if (!account.params.isRegisterEnabled) {
            done(AccountCheck(false, "la cuenta por defecto no se registra"))
            return
        }
        when (val state = account.state) {
            RegistrationState.Ok, RegistrationState.Refreshing -> done(AccountCheck(true, "cuenta por defecto registrada [$state]"))
            RegistrationState.Failed, RegistrationState.Cleared -> done(AccountCheck(false, "registro de la cuenta por defecto [$state]"))
            else -> {
                if (!coreWasOn) {
                    done(AccountCheck(true, "app cerrada: el Core arranca [${core.globalState}], registro [$state]; la llamada lo esperará"))
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    coreContext.postOnCoreThreadDelayed({ evaluateAccount(it, net, deadline, true, done) }, POLL_MS)
                } else {
                    done(AccountCheck(false, "cuenta por defecto sin registrar a tiempo [$state]"))
                }
            }
        }
    }

    /** Sin el hilo del Core: ¿hay alguna cuenta en la configuración? (los [proxy_N] que lee el Core al crearse) */
    @AnyThread
    private fun accountFromConfig(why: String): AccountCheck {
        val config = corePreferences.config
        val hasAccount = config.sectionsNamesList.any {
            accountSection.matches(it) && !config.getString(it, "reg_identity", "").isNullOrEmpty()
        }
        return if (hasAccount) {
            AccountCheck(true, "$why; hay cuenta en la configuración")
        } else {
            AccountCheck(false, "$why y no hay cuentas en la configuración")
        }
    }

    // ---- La pregunta en pantalla ----

    // Al pulsar llamar, la pantalla de llamada del teléfono sale a la vez que la nuestra y la tapa: en el A52s (26/10) la
    // pregunta no se creó hasta que se colgó, 13 s después, y como se esperaba a verla para cancelar, la llamada salió
    // por la SIM. Ahora el servicio la cancela en Telecom en cuanto Android acepta abrir la pantalla (su tarea existe):
    // la de llamada se va y sale la pregunta. Si en PROMPT_FALLBACK_MS no ha salido, notificación con las dos opciones
    // (en «siempre», la llamada sale por MundoPhone sin ella). Pasado PROMPT_MAX_AGE_MS ya no se llama.
    private const val PROMPT_FALLBACK_MS = 2500L
    private const val PROMPT_MAX_AGE_MS = 60000L

    private const val PROMPT_CHANNEL_ID = "mundophone_call_redirection"
    private const val PROMPT_NOTIFICATION_TAG = "call_redirection"
    private const val PROMPT_NOTIFICATION_ID = 1

    /** Una llamada cancelada en Telecom que espera a que el usuario elija (en la pantalla o en la notificación). */
    private class Prompt(
        val id: Long,
        val number: String,
        val phoneAccount: PhoneAccountHandle?,
        val auto: Boolean,
        val requestedAt: Long
    )

    private var lastPromptId = 0L
    private var ownedPrompt: Prompt? = null

    @MainThread
    fun newPromptId(): Long = ++lastPromptId

    /** La llamada ya está cancelada en Telecom y la pantalla pedida: desde aquí, la decisión es nuestra. */
    @MainThread
    fun takeOverPrompt(context: Context, id: Long, number: String, phoneAccount: PhoneAccountHandle?, auto: Boolean) {
        val prompt = Prompt(id, number, phoneAccount, auto, SystemClock.elapsedRealtime())
        ownedPrompt = prompt
        val appContext = context.applicationContext
        mainHandler.postDelayed({ promptFallback(appContext, prompt) }, PROMPT_FALLBACK_MS)
    }

    /** La pantalla de la petición [id] está a la vista. true = sigue pendiente y no ha caducado: se pregunta o se llama. */
    @MainThread
    fun promptShown(context: Context, id: Long): Boolean {
        val prompt = ownedPrompt
        if (prompt == null || prompt.id != id) return false
        ownedPrompt = null
        NotificationManagerCompat.from(context).cancel(PROMPT_NOTIFICATION_TAG, PROMPT_NOTIFICATION_ID)
        val age = SystemClock.elapsedRealtime() - prompt.requestedAt
        Log.i("$TAG [${mask(prompt.number)}] Pantalla a la vista a los $age ms de cancelar en Telecom")
        return age <= PROMPT_MAX_AGE_MS
    }

    @MainThread
    private fun promptFallback(context: Context, prompt: Prompt) {
        if (ownedPrompt !== prompt) return // ya salió la pantalla
        val masked = mask(prompt.number)
        if (prompt.auto) {
            ownedPrompt = null
            Log.w("$TAG [$masked] La pantalla no ha salido en $PROMPT_FALLBACK_MS ms: ajuste «siempre», se llama por MundoPhone sin ella")
            startAppCall(prompt.number) { started ->
                if (!started) Log.e("$TAG [$masked] No se ha podido llamar por MundoPhone sin la pantalla")
            }
            return
        }
        val shown = showPromptNotification(context, prompt)
        Log.w("$TAG [$masked] La pantalla no ha salido en $PROMPT_FALLBACK_MS ms: notificación con las dos opciones [$shown]")
    }

    /**
     * Alta para que salga como aviso emergente encima de lo que haya; sin sonido ni vibración: el usuario acaba de
     * pulsar llamar y está mirando el móvil. Si el canal ya existe, Android se queda con lo que haya (y lo que el usuario
     * o la capa del fabricante le hayan cambiado).
     */
    @MainThread
    private fun ensurePromptChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(PROMPT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.call_redirection_notification_channel))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    @MainThread
    private fun showPromptNotification(context: Context, prompt: Prompt, fullScreen: Boolean = false): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                )
        ) {
            return false
        }
        ensurePromptChannel(context)

        fun open(choice: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            CallRedirectionActivity.createIntent(context, prompt.number, prompt.phoneAccount, prompt.id, prompt.auto, choice),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = context.getString(R.string.call_redirection_dialog_message)
        val notification = NotificationCompat.Builder(context, PROMPT_CHANNEL_ID)
            .setSmallIcon(R.drawable.linphone_notification)
            .setContentTitle(context.getString(R.string.call_redirection_dialog_title, prompt.number))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(CallRedirectionActivity.CHOICE_NONE, 0))
            .addAction(0, context.getString(R.string.call_redirection_dialog_app_button), open(CallRedirectionActivity.CHOICE_APP, 1))
            .addAction(0, context.getString(R.string.call_redirection_dialog_sim_button), open(CallRedirectionActivity.CHOICE_SIM, 2))
            .setTimeoutAfter(PROMPT_MAX_AGE_MS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply {
                // Sin pantalla posible: la de pantalla completa es la que más prioridad tiene para salir encima (con el
                // móvil en uso, Android la enseña como aviso emergente; si la capa del fabricante la deja, abre la pregunta)
                if (fullScreen) setFullScreenIntent(open(CallRedirectionActivity.CHOICE_NONE, 3), true)
            }
            .build()
        return try {
            manager.notify(PROMPT_NOTIFICATION_TAG, PROMPT_NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.e("$TAG No se puede enseñar la notificación: $e")
            false
        }
    }

    // ---- Cuando Android no deja abrir la pantalla ----
    // En Xiaomi/HyperOS (y otras capas) abrir una pantalla desde segundo plano pide un permiso de la marca («Mostrar
    // ventanas emergentes mientras se ejecuta en segundo plano») aunque Telecom lo autorice: sin él, startActivity no
    // falla pero no crea la tarea, y la pantalla llega minutos después o nunca, y la
    // llamada salía entonces por la SIM SIN preguntar (5 de 13 desvíos en un Xiaomi con Android 16). Por eso la
    // pregunta sale en la notificación de respaldo, que no necesita ese permiso; solo si tampoco puede salir, por la SIM.

    /** ¿Saldrá la notificación como aviso emergente? Si no (apagadas, canal rebajado, No molestar), no se cancela nada. */
    @MainThread
    fun canAskByNotification(context: Context): Boolean {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        // Se crea antes de mirarlo: algunas capas (MIUI/HyperOS) rebajan la importancia de los canales nuevos, y con
        // menos de «alta» la notificación no sale encima: el usuario vería irse la llamada sin pregunta a la vista
        ensurePromptChannel(context)
        val importance = manager.getNotificationChannel(PROMPT_CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_NONE
        val filter = manager.currentInterruptionFilter
        val ok = importance >= NotificationManager.IMPORTANCE_HIGH && filter == NotificationManager.INTERRUPTION_FILTER_ALL
        if (!ok) Log.w("$TAG La notificación no saldría encima: importancia del canal [$importance], No molestar [$filter]")
        return ok
    }

    /**
     * La llamada ya está cancelada en Telecom y la pantalla no se pudo abrir: se pregunta en la notificación. Si la
     * pantalla llega tarde (antes de [PROMPT_MAX_AGE_MS]), también vale y quita la notificación. Si la notificación no
     * se puede enseñar, se relanza por la SIM para no perder la llamada.
     */
    @MainThread
    fun askByNotification(context: Context, id: Long, number: String, phoneAccount: PhoneAccountHandle?) {
        val appContext = context.applicationContext
        val prompt = Prompt(id, number, phoneAccount, false, SystemClock.elapsedRealtime())
        ownedPrompt = prompt
        val shown = showPromptNotification(appContext, prompt, fullScreen = true)
        Log.i("$TAG [${mask(number)}] Android no deja abrir la pantalla: pregunta en la notificación [$shown]")
        if (!shown) {
            ownedPrompt = null
            placeCallWithSim(appContext, number, phoneAccount)
        }
    }

    /** Ajuste «siempre» sin pantalla: sale por MundoPhone directamente; si MundoPhone no puede, por la SIM. */
    @MainThread
    fun callWithoutPrompt(context: Context, number: String, phoneAccount: PhoneAccountHandle?) {
        val appContext = context.applicationContext
        val masked = mask(number)
        startAppCall(number) { started ->
            if (!started) {
                Log.e("$TAG [$masked] No se ha podido llamar por MundoPhone sin la pantalla: se relanza por la SIM")
                placeCallWithSim(appContext, number, phoneAccount)
            }
        }
    }

    // ---- Medida del arranque ----
    // Telecom da 5 s desde que enlaza el servicio, y con la app cerrada en ese tiempo entra también lo que tarda Android
    // en crear el proceso, que desde dentro no se ve. Vídeo del 01/11 (Xiaomi, de madrugada): el aviso «MundoPhone no
    // puede realizar la llamada» salió a los 5,3 s sin registro de ese momento. Con esto, el próximo dirá quién se los
    // comió: Android antes de arrancarnos o nosotros después.
    private const val RECENT_START_MS = 60000L

    /** Si el proceso acaba de arrancar: cuánto lleva vivo y, desde Android 15, cuánto hace que Android pidió lanzarlo. */
    @AnyThread
    fun recentStartup(context: Context): String? {
        val aliveMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        if (aliveMs > RECENT_START_MS) return null
        var launched = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                val info = context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessStartReasons(1)?.firstOrNull()
                val launchNs = info?.startupTimestamps?.get(ApplicationStartInfo.START_TIMESTAMP_LAUNCH)
                if (launchNs != null) {
                    val sinceLaunchMs = (SystemClock.elapsedRealtimeNanos() - launchNs) / 1_000_000
                    launched = ", Android pidió lanzarlo hace [$sinceLaunchMs] ms"
                }
            } catch (e: Exception) {
                launched = ", sin datos de lanzamiento ($e)"
            }
        }
        return "proceso arrancado hace [$aliveMs] ms$launched"
    }

    /**
     * Para el registro: lo que Android sabe de la batería de la app. 07/11: el aviso de los 5 s salía con la app CONGELADA
     * por HyperOS (12 s sin una línea antes de la llamada) y el registro no decía si estaba exenta de la optimización de
     * batería: con esto, el siguiente dirá si la congelan aun exenta (y entonces solo vale el servicio «mantener activa»).
     */
    @AnyThread
    fun powerState(context: Context): String {
        val exempt = try {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            null
        }
        val restricted = try {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
        } catch (e: Exception) {
            null
        }
        val bucket = try {
            context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
        } catch (e: Exception) {
            null
        }
        val bucketName = when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "activa"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "uso habitual"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frecuente"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rara"
            STANDBY_BUCKET_RESTRICTED -> "restringida"
            else -> "$bucket"
        }
        return "batería sin optimizar [$exempt], segundo plano restringido [$restricted], grupo de espera [$bucketName]"
    }

    // UsageStatsManager.STANDBY_BUCKET_RESTRICTED es de Android 11 (minSdk 28): el valor, para no depender del nivel
    private const val STANDBY_BUCKET_RESTRICTED = 45

    // ---- Llamar por MundoPhone ----

    /** Una llamada por MundoPhone pedida y aún no lanzada; [cancel] la anula si todavía espera al Core o al registro. */
    class AppCallRequest {
        @Volatile
        var cancelled = false
            private set

        @AnyThread
        fun cancel() {
            cancelled = true
        }
    }

    @MainThread
    private fun runOnCoreThreadWhenReady(deadline: Long, onTimeout: () -> Unit, block: (Core) -> Unit) {
        if (coreContext.isReady()) {
            coreContext.postOnCoreThread(block)
            return
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            onTimeout()
            return
        }
        mainHandler.postDelayed({ runOnCoreThreadWhenReady(deadline, onTimeout, block) }, POLL_MS)
    }

    /**
     * Llama por MundoPhone con el mismo camino que el teclado de la app (interpretUrl + CoreContext.startAudioCall),
     * desde la cuenta por defecto, que es la que ha comprobado [checkAccount] (con varias extensiones y sin favorita, la
     * app preguntaría desde cuál, y aquí no hay pantalla de la app donde hacerlo). Con la app cerrada espera a que el
     * Core esté On y, unos segundos, a que la cuenta se registre. [callback] en el hilo principal: true si se lanzó.
     */
    @MainThread
    fun startAppCall(number: String, callback: (Boolean) -> Unit): AppCallRequest {
        val request = AppCallRequest()
        val masked = mask(number)
        val deadline = SystemClock.elapsedRealtime() + CALL_START_TIMEOUT_MS
        runOnCoreThreadWhenReady(deadline, {
            Log.e("$TAG El Core no se ha creado en [$CALL_START_TIMEOUT_MS] ms: no se llama a [$masked] con MundoPhone")
            callback(false)
        }) { core ->
            waitAndCall(core, number, request, deadline, 0L) { started ->
                coreContext.postOnMainThread { callback(started) }
            }
        }
        return request
    }

    @WorkerThread
    private fun waitAndCall(
        core: Core,
        number: String,
        request: AppCallRequest,
        deadline: Long,
        registrationDeadline: Long,
        done: (Boolean) -> Unit
    ) {
        val masked = mask(number)
        if (request.cancelled) {
            Log.i("$TAG Llamada a [$masked] con MundoPhone anulada por el usuario antes de salir")
            done(false)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (core.globalState != GlobalState.On) {
            if (now >= deadline) {
                Log.e("$TAG El Core sigue [${core.globalState}] tras [$CALL_START_TIMEOUT_MS] ms: no se llama a [$masked] con MundoPhone")
                done(false)
            } else {
                coreContext.postOnCoreThreadDelayed({ waitAndCall(it, number, request, deadline, 0L, done) }, POLL_MS)
            }
            return
        }

        val account = LinphoneUtils.getDefaultAccount()
        if (account == null) {
            Log.e("$TAG No hay cuenta por defecto: no se llama a [$masked] con MundoPhone")
            done(false)
            return
        }
        val state = account.state
        val regDeadline = if (registrationDeadline == 0L) now + REGISTRATION_WAIT_MS else registrationDeadline
        if ((state == RegistrationState.None || state == RegistrationState.Progress) && now < regDeadline) {
            coreContext.postOnCoreThreadDelayed({ waitAndCall(it, number, request, deadline, regDeadline, done) }, POLL_MS)
            return
        }
        if (state == RegistrationState.Failed || state == RegistrationState.Cleared) {
            Log.e("$TAG Registro de la cuenta por defecto [$state]: no se llama a [$masked] con MundoPhone")
            done(false)
            return
        }

        val address = core.interpretUrl(number, LinphoneUtils.applyInternationalPrefix())
        if (address == null) {
            Log.e("$TAG [$masked] no se puede convertir en dirección: no se llama con MundoPhone")
            done(false)
            return
        }
        Log.i("$TAG Llamando a [$masked] con MundoPhone desde la cuenta por defecto (registro [$state])")
        // La red ya la ha comprobado Android: con la app recién arrancada, el Core aún puede creer que no hay
        coreContext.startAudioCall(address, localAddress = account.params.identityAddress, skipNetworkReachabilityTest = true)
        done(true)
    }

    // ---- Llamar por la SIM ----

    /**
     * Relanza la llamada por la SIM (con la cuenta de la SIM que eligió el teléfono, si la pasó) y deja la marca para
     * que el servicio la deje pasar sin preguntar. Necesita CALL_PHONE: false si no está concedido o Telecom la rechaza.
     */
    @MainThread
    fun placeCallWithSim(context: Context, number: String, phoneAccount: PhoneAccountHandle?): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.w("$TAG Sin CALL_PHONE: no se puede relanzar por la SIM la llamada a [${mask(number)}]")
            return false
        }
        val telecomManager = context.getSystemService(android.telecom.TelecomManager::class.java) ?: return false
        val extras = Bundle()
        if (phoneAccount != null) {
            extras.putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccount)
        }
        markSimRelaunch(number, SIM_MARK_TTL_MS)
        return try {
            telecomManager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
            Log.i("$TAG Llamada a [${mask(number)}] relanzada por la SIM (con la cuenta de la SIM elegida [${phoneAccount != null}])")
            true
        } catch (e: Exception) {
            Log.e("$TAG No se ha podido relanzar por la SIM la llamada a [${mask(number)}]: $e")
            clearSimMark()
            false
        }
    }

    /**
     * Sin CALL_PHONE: abre el marcador del teléfono con el número escrito; al pulsar llamar, el servicio la deja salir
     * por la SIM (la marca dura [DIALER_MARK_TTL_MS]).
     */
    @MainThread
    fun openDialerWithNumber(context: Context, number: String) {
        markSimRelaunch(number, DIALER_MARK_TTL_MS)
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // El del sistema: MundoPhone también atiende ACTION_DIAL y podría salir en el selector
        val dialer = context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
        if (dialer != null) intent.setPackage(dialer)
        try {
            context.startActivity(intent)
            Log.i("$TAG Marcador [$dialer] abierto con [${mask(number)}]")
        } catch (e: ActivityNotFoundException) {
            Log.e("$TAG No se ha podido abrir el marcador [$dialer]: $e")
            clearSimMark()
        }
    }
}
