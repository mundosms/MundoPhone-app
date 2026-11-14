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
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.ui.main.MainActivity

/**
 * MundoPhone (F2): «Informar de un problema» de una llamada concreta, por el formulario de registros de siempre
 * (mundophonedebug, sin cambiar el servidor).
 *
 * - [header]: lo que se antepone al «qué ha pasado» (el texto `d` de la subida; el servidor no tiene campo propio):
 *   «[Llamada <Call-ID> · <hora> · <entrante|saliente> <número> · …]». Lo usan el detalle del historial y el aviso.
 * - [coreListener]: al acabar una llamada mira SOLO señales objetivas y, si alguna salta, deja una notificación discreta
 *   («La última llamada parece que ha ido mal. ¿Nos envías el registro?»), como mucho una cada 24 h y con un ajuste
 *   para apagarla. Nunca un «¿qué tal la llamada?» tras cada una.
 *
 * Señales y umbrales (prudentes: mejor callar ante una llamada regular que molestar tras una buena):
 * - FALLO: la llamada acaba en Error con un motivo técnico (red o transporte, 488/415, 5xx de la centralita o el borde)
 *   o termina con IOError, que es como el SDK cuelga cuando deja de llegar RTP (nortp_timeout: «503 Media lost»). No
 *   cuentan ocupado, rechazada, no contesta, 404, 480, 403/401, la cancelada por el usuario ni la perdida, y tampoco un
 *   fallo sin red en el móvil (eso ya lo dice la app y no es un fallo nuestro).
 * - CALIDAD: la calidad media del SDK (CallLog.quality, 0-5, la que guarda al parar los flujos) por debajo de 2,0. Por
 *   su fórmula (mediastreamer2, qualityindicator.c) eso pide ~23 % de pérdida sostenida sumando los dos sentidos, o
 *   jitter/retardo muy altos: una llamada claramente mala. Una llamada normal da 4,5-5.
 * - PÉRDIDAS: pérdida de bajada acumulada (RTP perdido / esperado, contadores de oRTP) del 10 % o más: entrecortado
 *   audible, que con pérdida solo en un sentido no baja la calidad media de 3,3.
 * Las dos últimas solo en llamadas contestadas de 10 s o más (≥ 500 paquetes esperados para las pérdidas) y que no
 * hayan estado en espera: en espera no llega RTP y el SDK divide la calidad entre dos cada segundo.
 */
object CallProblemReport {
    private const val TAG = "[Call Problem Report]"

    /** Acción del intent de la notificación (MainActivity) y extras: la llamada y por qué se avisó. */
    const val ACTION_REPORT = "es.mundosms.action.INFORMAR_PROBLEMA_LLAMADA"
    const val EXTRA_CALL_ID = "es.mundosms.extra.CALL_ID"
    const val EXTRA_REASON = "es.mundosms.extra.MOTIVO_AVISO"

    private const val MIN_CONNECTED_SECONDS = 10
    private const val LOW_QUALITY = 2.0f
    private const val HIGH_DOWNLOAD_LOSS = 0.10f
    private const val MIN_EXPECTED_PACKETS = 500L
    private const val PROMPT_INTERVAL_SECONDS = 24 * 3600

    private const val CHANNEL_ID = "mundosms_call_problem"
    private const val NOTIFICATION_TAG = "mundosms_call_problem"
    private const val NOTIFICATION_ID = 1

    private val technicalErrors = setOf(
        Reason.IOError,
        Reason.NoResponse,
        Reason.ServerTimeout,
        Reason.BadGateway,
        Reason.NotImplemented,
        Reason.NotAcceptable,
        Reason.UnsupportedContent,
        Reason.SessionIntervalTooSmall
    )

    // Llamadas (Call-ID) que han pasado por espera: sin calidad ni pérdidas para ellas
    private val pausedCalls = hashSetOf<String>()

    /**
     * La línea que va delante del texto del usuario. Solo datos de la llamada, para soporte (en español fijo, como el
     * resto del informe): NO se escribe en el registro, porque lleva el número del otro.
     */
    @WorkerThread
    fun header(core: Core, callId: String, reason: String?): String {
        val parts = arrayListOf("Llamada $callId")
        val callLog = core.findCallLogFromCallId(callId)
        if (callLog != null) {
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss Z", Locale.ROOT).format(Date(callLog.startDate * 1000))
            parts.add(date)
            val direction = if (callLog.dir == Call.Dir.Outgoing) "saliente" else "entrante"
            parts.add("$direction ${peer(callLog.remoteAddress)}")
            parts.add("${callLog.duration} s")
            parts.add(statusName(callLog.status))
            val errorInfo = callLog.errorInfo
            if (errorInfo != null && errorInfo.reason != Reason.None) {
                parts.add(
                    listOfNotNull(errorInfo.protocol, errorInfo.protocolCode.takeIf { it > 0 }?.toString(), errorInfo.phrase)
                        .joinToString(" ")
                        .ifEmpty { errorInfo.reason.name }
                )
            }
            if (callLog.quality >= 0) parts.add("calidad ${formatQuality(callLog.quality)}/5")
        }
        if (!reason.isNullOrEmpty()) parts.add("aviso: $reason")
        return parts.joinToString(" · ", "[", "]")
    }

    private fun peer(address: Address): String {
        val username = address.username.orEmpty()
        val isNumber = username.isNotEmpty() && (username.removePrefix("+").isDigitsOnly())
        return if (isNumber) username else address.asStringUriOnly()
    }

    private fun statusName(status: Call.Status): String = when (status) {
        Call.Status.Success -> "contestada"
        Call.Status.Aborted -> "cancelada"
        Call.Status.Missed -> "perdida"
        Call.Status.Declined -> "rechazada"
        Call.Status.EarlyAborted -> "cortada antes de sonar"
        Call.Status.AcceptedElsewhere -> "contestada en otro dispositivo"
        Call.Status.DeclinedElsewhere -> "rechazada en otro dispositivo"
    }

    private fun formatQuality(quality: Float): String = String.format(Locale.ROOT, "%.1f", quality).replace('.', ',')

    val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val callId = call.callLog.callId ?: return
            when (state) {
                Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> pausedCalls.add(callId)
                Call.State.End, Call.State.Error -> evaluate(core, call, state, callId)
                Call.State.Released -> pausedCalls.remove(callId)
                else -> {}
            }
        }
    }

    @WorkerThread
    private fun evaluate(core: Core, call: Call, state: Call.State, callId: String) {
        if (call.conference != null) return
        val signal = signal(core, call, state, callId) ?: return

        if (!corePreferences.callProblemPromptEnabled) {
            Log.i("$TAG Call [$callId] seems to have gone wrong ($signal), but the prompt is disabled in settings")
            return
        }
        val now = (System.currentTimeMillis() / 1000).toInt()
        val last = corePreferences.callProblemPromptLast
        if (last > 0 && now - last in 0 until PROMPT_INTERVAL_SECONDS) {
            Log.i("$TAG Call [$callId] seems to have gone wrong ($signal), already prompted [${(now - last) / 60}] min ago, not again")
            return
        }
        if (showNotification(coreContext.context, callId, signal)) {
            corePreferences.callProblemPromptLast = now
            Log.w("$TAG Call [$callId] seems to have gone wrong ($signal), notification shown")
        }
    }

    /** La señal objetiva que salta, en palabras para soporte, o null si la llamada parece normal. */
    @WorkerThread
    private fun signal(core: Core, call: Call, state: Call.State, callId: String): String? {
        val errorInfo = call.errorInfo
        val reason = errorInfo.reason
        val code = errorInfo.protocolCode

        if (state == Call.State.Error) {
            val sipServerError = errorInfo.protocol == "SIP" && code in 500..599
            if (reason !in technicalErrors && !sipServerError) return null
            if (!core.isNetworkReachable) {
                Log.i("$TAG Call [$callId] failed with [$reason] without network, not a problem to report")
                return null
            }
            return "la llamada falló (${reason.name}${if (code > 0) " $code" else ""})"
        }

        // End: un IOError es el SDK colgando por falta de RTP (o la red cayendo a media llamada)
        if (reason == Reason.IOError) {
            return if (code == 503) "corte por falta de audio (RTP)" else "corte de red (${reason.name}${if (code > 0) " $code" else ""})"
        }

        val callLog = call.callLog
        if (callLog.status != Call.Status.Success || callLog.duration < MIN_CONNECTED_SECONDS || callId in pausedCalls) return null

        val quality = callLog.quality
        val stats = call.audioStats
        val received = stats?.rtpPacketRecv ?: 0L
        val lost = stats?.rtpCumPacketLoss?.toLong() ?: 0L
        val expected = received + lost
        val loss = if (expected >= MIN_EXPECTED_PACKETS && lost > 0) lost.toFloat() / expected else 0f
        // Sin datos personales: lo justo para ajustar los umbrales con registros reales
        Log.i(
            "$TAG Call [$callId] ended after [${callLog.duration}] s: average quality [${formatQuality(quality)}], download loss [$lost/$expected]"
        )

        if (quality >= 0 && quality < LOW_QUALITY) return "calidad media ${formatQuality(quality)}/5"
        if (loss >= HIGH_DOWNLOAD_LOSS && loss <= 1f) return "pérdida de bajada del ${(loss * 100).toInt()} %"
        return null
    }

    @AnyThread
    private fun showNotification(context: Context, callId: String, reason: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                )
        ) {
            Log.w("$TAG Notifications are disabled, can't offer to send the logs")
            return false
        }

        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_call_problem_name))
            .setShowBadge(false)
            .build()
        manager.createNotificationChannel(channel)

        val intent = Intent(ACTION_REPORT, null, context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_REASON, reason)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = context.getString(R.string.notification_call_problem_message)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.linphone_notification)
            .setContentTitle(context.getString(R.string.notification_call_problem_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.e("$TAG Can't show the notification: $e")
            false
        }
    }
}
