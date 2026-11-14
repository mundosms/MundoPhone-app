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
package org.linphone.ui.main

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import java.time.LocalDate
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log

/**
 * Actualización integrada de Google Play (In-App Updates): que nadie se quede en una versión vieja.
 *
 * - Flexible (se descarga en segundo plano y luego se pide reiniciar). Si el usuario la ignora, esa versión no se le
 *   vuelve a ofrecer hasta el día siguiente (hora local); aceptarla no cuenta, y una versión más nueva se ofrece al
 *   momento.
 * - Inmediata (pantalla que bloquea hasta actualizar) si la versión nueva lleva DAYS_BEFORE_IMMEDIATE días disponible.
 * - Nunca con una llamada en curso; solo cuando el usuario abre la app (MainActivity), no al despertarla un push.
 * - Una sola oferta a la vez (si no, el diálogo de actualizar salía dos veces seguidas): no se ofrece mientras hay una
 *   comprobación o un diálogo de Play en marcha, ni otra vez la versión recién aceptada mientras Play empieza a bajarla
 *   (al volver de su diálogo la app comprueba de nuevo, y Play puede tardar en decir que ya la descarga), y el aviso de
 *   «descargada, reiniciar» no se repite si ya está a la vista (lo piden a la vez el aviso de fin de descarga y la vuelta
 *   a la app).
 * Solo funciona en instalaciones desde Play (también la prueba interna). Cualquier fallo se ignora: la app sigue igual.
 */
class AppUpdateChecker(private val activity: MainActivity) {
    companion object {
        private const val TAG = "[App Update Checker]"

        private const val PREFS = "app_update_prefs"
        private const val DECLINED_DAY = "declined_day"
        private const val DECLINED_CODE = "declined_version_code"
        private const val DAYS_BEFORE_IMMEDIATE = 21
    }

    // Perezoso: al construirse (como propiedad de MainActivity) la actividad aún no tiene contexto
    private val manager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(activity) }

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Log.i("$TAG Update flow finished with result code [${result.resultCode}]")
        busy = false
        // Solo cuenta si la ignora («Ahora no», atrás): si la acepta, no se apunta nada
        if (result.resultCode == Activity.RESULT_CANCELED && offeredCode != 0) {
            // Desde que la app quedó oculta tras el diálogo (onStop); si no llegó a ocultarse (diálogo translúcido en algún
            // móvil), desde que empezó a taparla (onPause); y si ni eso, desde que se pidió. Visto en un
            // móvil: pedido 42,753, tapa 42,820, oculta 43,656, cerrado 44,225 → 0,6 s a la vista
            val since = when {
                flowShownAt != 0L -> flowShownAt
                flowPausedAt != 0L -> flowPausedAt
                else -> flowStartedAt
            }
            val shownMs = SystemClock.elapsedRealtime() - since
            if (UpdatePromptPolicy.countsAsDeclined(shownMs)) {
                markDeclined(offeredCode)
            } else {
                Log.i("$TAG Update [$offeredCode] dialog closed after [$shownMs] ms: taken as accidental, offered again next time")
            }
        } else if (result.resultCode == Activity.RESULT_OK) {
            acceptedCode = offeredCode
        }
    }

    // Comprobación o diálogo de Play en marcha: no se ofrece otra encima
    private var busy = false

    // La versión aceptada en esta sesión: no se vuelve a ofrecer mientras Play empieza a bajarla
    private var acceptedCode = 0

    private var restartDialog: AlertDialog? = null

    // Cuándo se pidió el diálogo de Play y cuándo tapó la app (para no contar como ignorada un cierre sin querer)
    private var flowStartedAt = 0L
    private var flowPausedAt = 0L
    private var flowShownAt = 0L

    /** Lo que pasó al buscar a mano (Ayuda > Buscar actualizaciones). */
    enum class ManualResult { OFFERED, DOWNLOADING, UP_TO_DATE, ERROR }

    /**
     * Ayuda > «Buscar actualizaciones»: pregunta a Play ahora y, si hay versión nueva, la ofrece aunque se haya ignorado
     * hoy (lo pide el usuario). Sin Play (no instalada desde Play) o sin red, ERROR.
     */
    @UiThread
    fun checkNow(onResult: (ManualResult) -> Unit) {
        try {
            manager.appUpdateInfo
                .addOnSuccessListener { info -> onResult(handleManual(info)) }
                .addOnFailureListener { e ->
                    Log.w("$TAG Manual check failed: $e")
                    onResult(ManualResult.ERROR)
                }
        } catch (e: Exception) {
            Log.w("$TAG Manual check failed: $e")
            onResult(ManualResult.ERROR)
        }
    }

    @UiThread
    private fun handleManual(info: AppUpdateInfo): ManualResult {
        Log.i("$TAG Manual check: availability [${info.updateAvailability()}], install status [${info.installStatus()}]")
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            offerRestart()
            return ManualResult.OFFERED
        }
        if (info.installStatus() == InstallStatus.DOWNLOADING ||
            info.installStatus() == InstallStatus.PENDING ||
            info.installStatus() == InstallStatus.INSTALLING
        ) {
            return ManualResult.DOWNLOADING
        }
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            start(info, AppUpdateType.IMMEDIATE)
            return ManualResult.OFFERED
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return ManualResult.UP_TO_DATE
        if (busy) return ManualResult.OFFERED
        val stalenessDays = info.clientVersionStalenessDays() ?: 0
        val immediate = stalenessDays >= DAYS_BEFORE_IMMEDIATE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        val type = if (immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        if (!info.isUpdateTypeAllowed(type)) return ManualResult.ERROR
        Log.i("$TAG Update [${info.availableVersionCode()}] requested by the user, starting ${if (immediate) "immediate" else "flexible"} flow")
        offeredCode = info.availableVersionCode()
        start(info, type)
        return ManualResult.OFFERED
    }

    // La versión que se acaba de ofrecer
    private var offeredCode = 0

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            offerRestart()
        }
    }

    private var listenerRegistered = false

    @UiThread
    fun onResume() {
        if (busy) return
        try {
            manager.appUpdateInfo
                .addOnSuccessListener { info -> handle(info) }
                .addOnFailureListener { e -> Log.w("$TAG Can't check for update: $e") }
        } catch (e: Exception) {
            Log.w("$TAG Can't check for update: $e")
        }
    }

    /** La actividad deja de estar delante: si hay un diálogo de Play pedido, es él quien empieza a taparla. */
    @UiThread
    fun onPause() {
        if (busy && flowPausedAt == 0L) flowPausedAt = SystemClock.elapsedRealtime()
    }

    /** La actividad ya no se ve: si hay un diálogo de Play pedido, desde aquí está a la vista. */
    @UiThread
    fun onStop() {
        if (busy && flowShownAt == 0L) flowShownAt = SystemClock.elapsedRealtime()
    }

    @UiThread
    fun onDestroy() {
        if (listenerRegistered) {
            manager.unregisterListener(installListener)
            listenerRegistered = false
        }
    }

    @UiThread
    private fun handle(info: AppUpdateInfo) {
        // Una actualización inmediata empezada y sin terminar: retomarla
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            start(info, AppUpdateType.IMMEDIATE)
            return
        }
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            offerRestart()
            return
        }
        // Aceptada y descargándose: no se vuelve a ofrecer encima
        if (info.installStatus() == InstallStatus.DOWNLOADING ||
            info.installStatus() == InstallStatus.PENDING ||
            info.installStatus() == InstallStatus.INSTALLING
        ) {
            return
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return
        if (busy) return

        val stalenessDays = info.clientVersionStalenessDays() ?: 0
        val immediate = stalenessDays >= DAYS_BEFORE_IMMEDIATE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        if (!immediate && !UpdatePromptPolicy.shouldOffer(info.availableVersionCode(), today(), declinedCode(), declinedDay())) {
            return
        }
        val type = if (immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        if (!info.isUpdateTypeAllowed(type)) return
        if (type == AppUpdateType.FLEXIBLE && info.availableVersionCode() == acceptedCode) {
            Log.i("$TAG Update [$acceptedCode] already accepted, waiting for Play to download it")
            return
        }

        // Ocupado desde ya: la consulta de llamadas va y vuelve por el hilo del Core, y otra vuelta a la app en medio
        // lanzaría un segundo diálogo
        busy = true
        coreContext.postOnCoreThread { core ->
            val inCall = core.callsNb > 0
            coreContext.postOnMainThread {
                if (inCall || activity.isFinishing || activity.isDestroyed) {
                    busy = false
                    return@postOnMainThread
                }
                Log.i("$TAG Update [${info.availableVersionCode()}] available for [$stalenessDays] day(s), starting ${if (immediate) "immediate" else "flexible"} flow")
                offeredCode = info.availableVersionCode()
                start(info, type)
            }
        }
    }

    @UiThread
    private fun start(info: AppUpdateInfo, type: Int) {
        try {
            if (type == AppUpdateType.FLEXIBLE && !listenerRegistered) {
                manager.registerListener(installListener)
                listenerRegistered = true
            }
            flowStartedAt = SystemClock.elapsedRealtime()
            flowPausedAt = 0L
            flowShownAt = 0L
            busy = manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        } catch (e: Exception) {
            busy = false
            Log.w("$TAG Can't start update flow: $e")
        }
    }

    @UiThread
    private fun offerRestart() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (restartDialog?.isShowing == true) return
        restartDialog = AlertDialog.Builder(activity)
            .setMessage(R.string.app_update_downloaded_message)
            .setPositiveButton(R.string.app_update_restart) { _, _ -> manager.completeUpdate() }
            .setNegativeButton(R.string.app_update_later, null)
            .show()
    }

    // Día en hora local (antes, UTC: de 00:00 a 01:00 seguía siendo «ayer»)
    private fun today(): Long = LocalDate.now().toEpochDay()

    private fun declinedDay(): Long =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DECLINED_DAY, -1L)

    private fun declinedCode(): Int =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(DECLINED_CODE, 0)

    private fun markDeclined(code: Int) {
        Log.i("$TAG Update [$code] declined: not offered again today")
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(DECLINED_DAY, today())
            .putInt(DECLINED_CODE, code)
            .apply()
    }
}
