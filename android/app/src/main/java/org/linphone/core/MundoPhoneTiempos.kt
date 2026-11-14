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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log

/**
 * MundoPhone (F4): marcas de tiempo del arranque por push y del descolgar, para medir con el próximo registro del A52s
 * (B4, #6: la subida tardía de ~0,5 s). SOLO mide: no cambia nada de lo que hace la app.
 *
 * Todas las líneas empiezan por «[MundoPhone tiempos]». Dos fases:
 * - «entrante»: [proceso → Application.onCreate → Core creado → Core On, si el proceso arrancó hace menos de 15 s] →
 *   push recibido (FCM) → REGISTER en curso → REGISTER 200 → push procesado por el Core → INVITE recibido → timbre;
 * - «descolgar»: toque en contestar (pantalla, notificación, Telecom o automático) → call.accept → modo de audio de
 *   Android y dispositivo de comunicación → 200 OK enviado (Connected) → StreamsRunning → primer RTP enviado y recibido.
 *
 * Las marcas que el registro ya trae con su hora (Global state On, estados del REGISTER y de la llamada) no se repiten:
 * se guardan y salen solo en la línea «resumen» del final de la fase, con los ms desde el inicio de la fase y desde la
 * marca anterior, en orden de tiempo. Las que el registro no tenía salen además en su propia línea al ocurrir.
 * Reloj: SystemClock.elapsedRealtime (el mismo que Process.getStartElapsedRealtime), no la hora del registro.
 */
object MundoPhoneTiempos {
    private const val TAG = "[MundoPhone tiempos]"

    /** Si el proceso arrancó hace menos que esto al llegar el push, es un arranque en frío por ese push. */
    private const val COLD_START_WINDOW_MS = 15_000L

    /** Sin INVITE (o sin timbre) en este tiempo desde el inicio de la fase «entrante», se cierra con lo que haya. */
    private const val INCOMING_PHASE_TIMEOUT_MS = 20_000L

    /** Tras el accept, cuánto se espera al primer RTP en cada sentido antes de cerrar la fase «descolgar». */
    private const val ANSWER_PHASE_TIMEOUT_MS = 5_000L

    /** Cada cuánto se miran los contadores RTP de la llamada recién contestada (resolución de la marca). */
    private const val RTP_POLL_MS = 20L

    private const val FIRST_RTP_SENT = "primer RTP enviado"
    private const val FIRST_RTP_RECEIVED = "primer RTP recibido"

    private class Mark(val name: String, val time: Long)

    private class Phase(val name: String, val detail: String, val start: Long) {
        val marks = arrayListOf<Mark>()

        fun has(name: String) = marks.any { it.name == name }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Marcas del arranque del proceso: entran en la fase «entrante» solo si es un arranque en frío
    private val startupMarks = arrayListOf<Mark>()
    private var processStart = 0L

    private var incoming: Phase? = null
    private var incomingEndedAt = 0L
    private var answer: Phase? = null
    private var answerAcceptedAt = 0L
    private var answerCallId: String? = null

    private var modeListener: Any? = null
    private var deviceListener: Any? = null

    // ----- Arranque del proceso -----

    /** LinphoneApplication.onCreate: el arranque del proceso y el de onCreate ya los escribe «MundoPhone startup». */
    @AnyThread
    @Synchronized
    fun processStarted(onCreateStart: Long) {
        processStart = Process.getStartElapsedRealtime()
        startupMarks.add(Mark("proceso", processStart))
        startupMarks.add(Mark("Application.onCreate", onCreateStart))
    }

    /** CoreContext.run, justo después de createCoreWithConfig (el registro solo tenía el «Creating Core» de antes). */
    @AnyThread
    @Synchronized
    fun coreCreated() {
        val now = SystemClock.elapsedRealtime()
        startupMarks.add(Mark("Core creado", now))
        Log.i("$TAG Core creado: +${now - processStart} ms desde el inicio del proceso")
    }

    // ----- Fase «entrante» -----

    /** MundoPhoneFirebaseMessaging: el SDK escribe su «Received, sent [x] ms ago» solo en logcat, no en el registro. */
    @AnyThread
    @Synchronized
    fun pushReceived(sentAgoMs: Long, priority: String) {
        val now = SystemClock.elapsedRealtime()
        val label = "push recibido (FCM, enviado hace $sentAgoMs ms, prioridad $priority)"
        val phase = incoming
        when {
            phase != null -> {
                addMark(phase, label, now, log = true)
            }
            incomingEndedAt != 0L && now - incomingEndedAt < COLD_START_WINDOW_MS -> {
                // La llamada ya entró por la conexión abierta: el push llega tarde, sin fase nueva
                Log.i("$TAG $label ${now - incomingEndedAt} ms después del timbre de la llamada anterior")
            }
            else -> {
                val newPhase = startIncoming(now, cold(now, "arranque en frío por push", "push con el proceso vivo"))
                addMark(newPhase, label, now, log = true)
            }
        }
    }

    @WorkerThread
    @Synchronized
    private fun coreOn() {
        val now = SystemClock.elapsedRealtime()
        if (now - processStart < COLD_START_WINDOW_MS) startupMarks.add(Mark("Core On", now))
        incoming?.let { addMark(it, "Core On", now, log = false) }
    }

    @WorkerThread
    @Synchronized
    private fun registration(state: RegistrationState) {
        val name = when (state) {
            RegistrationState.Progress -> "REGISTER en curso"
            RegistrationState.Ok -> "REGISTER 200"
            RegistrationState.Failed -> "REGISTER fallido"
            else -> return
        }
        val now = SystemClock.elapsedRealtime()
        val phase = incoming
        if (phase != null) {
            // Con varias cuentas, la primera de cada tipo
            if (!phase.has(name)) addMark(phase, name, now, log = false)
        } else if (now - processStart < COLD_START_WINDOW_MS && startupMarks.none { it.name == name }) {
            startupMarks.add(Mark(name, now))
        }
    }

    @WorkerThread
    @Synchronized
    private fun incomingCallState(state: Call.State) {
        val now = SystemClock.elapsedRealtime()
        val name = if (state == Call.State.PushIncomingReceived) "push procesado por el Core" else "INVITE recibido"
        val phase = incoming ?: startIncoming(now, cold(now, "arranque en frío sin push", "sin push"))
        if (!phase.has(name)) addMark(phase, name, now, log = false)

        if (state == Call.State.IncomingReceived) {
            // El SDK arranca el tono en un post al hilo del Core desde su propio listener (AndroidPlatformHelper). Dos
            // posts seguidos aseguran que esta marca va detrás, se haya llamado antes a ese listener o a este.
            coreContext.postOnCoreThread {
                coreContext.postOnCoreThread {
                    ring()
                }
            }
        }
    }

    @WorkerThread
    @Synchronized
    private fun ring() {
        val phase = incoming ?: return
        val now = SystemClock.elapsedRealtime()
        // Su desglose fino (URI, getRingtone, play) lo da la línea «Ringtone timings (MundoSMS patch)» del SDK
        addMark(phase, "timbre (tras startRinging del SDK)", now, log = true)
        endIncoming(null)
    }

    private fun cold(now: Long, coldDetail: String, warmDetail: String): String =
        if (now - processStart < COLD_START_WINDOW_MS) coldDetail else warmDetail

    private fun startIncoming(now: Long, detail: String): Phase {
        val cold = detail.startsWith("arranque en frío")
        // En frío, la fase empieza con el proceso y trae las marcas del arranque (Core, REGISTER) ya guardadas
        val phase = Phase("entrante", detail, if (cold) processStart else now)
        if (cold) phase.marks.addAll(startupMarks)
        incoming = phase
        mainHandler.postDelayed({ timeoutIncoming(phase) }, INCOMING_PHASE_TIMEOUT_MS)
        return phase
    }

    @Synchronized
    private fun timeoutIncoming(phase: Phase) {
        if (incoming === phase) endIncoming("sin timbre en ${INCOMING_PHASE_TIMEOUT_MS / 1000} s")
    }

    private fun endIncoming(note: String?) {
        val phase = incoming ?: return
        incoming = null
        incomingEndedAt = SystemClock.elapsedRealtime()
        startupMarks.clear()
        summary(phase, note)
    }

    // ----- Fase «descolgar» -----

    /**
     * El usuario (o Telecom, o la respuesta automática) pide contestar. La primera marca manda: el toque de la
     * notificación llega a CallActivity y de ahí a la pantalla, y solo cuenta como «notificación».
     */
    @AnyThread
    @Synchronized
    fun answerRequested(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (answer != null) return
        val phase = startAnswer(now)
        addMark(phase, "toque en contestar ($source)", now, log = true)
    }

    /** CoreContext.answerCall, alrededor de call.accept: dentro van el 200 OK (Connected) y casi siempre StreamsRunning. */
    @WorkerThread
    @Synchronized
    fun accepting(call: Call) {
        val now = SystemClock.elapsedRealtime()
        val phase = answer ?: startAnswer(now)
        answerCallId = call.callLog.callId
        answerAcceptedAt = now
        addMark(phase, "call.accept llamado", now, log = true)
        mainHandler.postDelayed({ timeoutAnswer(phase) }, ANSWER_PHASE_TIMEOUT_MS)
    }

    @WorkerThread
    @Synchronized
    fun accepted() {
        val phase = answer ?: return
        val now = SystemClock.elapsedRealtime()
        addMark(phase, "call.accept vuelve", now, log = true)
    }

    @WorkerThread
    @Synchronized
    private fun answeredCallState(call: Call, state: Call.State) {
        val phase = answer ?: return
        if (call.callLog.callId != answerCallId) return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Call.State.Connected -> {
                addMark(phase, "200 OK enviado (Connected)", now, log = false)
                coreContext.postOnCoreThread { checkRtp(call, phase) }
            }
            Call.State.StreamsRunning -> if (!phase.has("StreamsRunning")) addMark(phase, "StreamsRunning", now, log = false)
            Call.State.End, Call.State.Error, Call.State.Released -> endAnswer("la llamada terminó ($state)")
            else -> {}
        }
    }

    /**
     * El primer audio de subida que se puede ver sin tocar el SDK: el contador de paquetes RTP enviados de la llamada (sube
     * cuando la captura entrega el primer bloque y el codificador lo manda). El de recibidos, para comparar los dos sentidos.
     * Se mira cada RTP_POLL_MS en el hilo del Core desde Connected; leerlo no cambia nada (CallStats.update solo copia).
     */
    @WorkerThread
    @Synchronized
    private fun checkRtp(call: Call, phase: Phase) {
        if (answer !== phase) return
        val state = call.state
        if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) return
        val stats = call.audioStats
        val now = SystemClock.elapsedRealtime()
        if (stats != null) {
            if (stats.rtpPacketSent > 0 && !phase.has(FIRST_RTP_SENT)) addMark(phase, FIRST_RTP_SENT, now, log = true)
            if (stats.rtpPacketRecv > 0 && !phase.has(FIRST_RTP_RECEIVED)) addMark(phase, FIRST_RTP_RECEIVED, now, log = true)
        }
        if (phase.has(FIRST_RTP_SENT) && phase.has(FIRST_RTP_RECEIVED)) {
            endAnswer(null)
        } else {
            coreContext.postOnCoreThreadDelayed({ checkRtp(call, phase) }, RTP_POLL_MS)
        }
    }

    private fun startAnswer(now: Long): Phase {
        val phase = Phase("descolgar", "llamada entrante", now)
        answer = phase
        answerAcceptedAt = 0L
        answerCallId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listenAudio(phase)
        }
        // Por si nunca llega el accept (la llamada se cuelga antes): la fase no se queda abierta
        mainHandler.postDelayed({ timeoutAnswer(phase) }, ANSWER_PHASE_TIMEOUT_MS * 3)
        return phase
    }

    @Synchronized
    private fun timeoutAnswer(phase: Phase) {
        if (answer !== phase) return
        if (answerAcceptedAt != 0L && SystemClock.elapsedRealtime() - answerAcceptedAt < ANSWER_PHASE_TIMEOUT_MS) return
        val missing = listOf(FIRST_RTP_SENT, FIRST_RTP_RECEIVED).filterNot { phase.has(it) }
        endAnswer(
            if (answerAcceptedAt == 0L) {
                "sin call.accept"
            } else {
                "sin ${missing.joinToString(" ni ")} en ${ANSWER_PHASE_TIMEOUT_MS / 1000} s tras el accept"
            }
        )
    }

    private fun endAnswer(note: String?) {
        val phase = answer ?: return
        answer = null
        answerCallId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            stopListeningAudio()
        }
        summary(phase, note)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenAudio(phase: Phase) {
        val context = coreContext.context
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val executor = ContextCompat.getMainExecutor(context)
        Log.i(
            "$TAG Al empezar a descolgar: modo de audio ${modeName(audioManager.mode)}, dispositivo de comunicación ${deviceName(audioManager.communicationDevice)}"
        )
        val mode = AudioManager.OnModeChangedListener { newMode ->
            mark(phase, "modo de audio ${modeName(newMode)}")
        }
        val device = AudioManager.OnCommunicationDeviceChangedListener { newDevice ->
            mark(phase, "dispositivo de comunicación ${deviceName(newDevice)}")
        }
        try {
            audioManager.addOnModeChangedListener(executor, mode)
            audioManager.addOnCommunicationDeviceChangedListener(executor, device)
            modeListener = mode
            deviceListener = device
        } catch (e: Exception) {
            Log.w("$TAG No se pueden seguir los cambios de audio de Android: $e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopListeningAudio() {
        val audioManager = coreContext.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { audioManager.removeOnModeChangedListener(it) }
            (deviceListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                audioManager.removeOnCommunicationDeviceChangedListener(it)
            }
        } catch (e: Exception) {
            Log.w("$TAG No se pueden quitar los oyentes de audio de Android: $e")
        }
        modeListener = null
        deviceListener = null
    }

    @Synchronized
    private fun mark(phase: Phase, name: String) {
        if (answer === phase) addMark(phase, name, SystemClock.elapsedRealtime(), log = true)
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
        else -> "modo $mode"
    }

    private fun deviceName(device: AudioDeviceInfo?): String = when (device?.type) {
        null -> "ninguno"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "auricular"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "altavoz"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "auriculares con micro"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "auriculares"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_HEARING_AID -> "audífono"
        else -> "tipo ${device.type}"
    }

    // ----- Común -----

    private fun addMark(phase: Phase, name: String, time: Long, log: Boolean) {
        phase.marks.add(Mark(name, time))
        if (log) Log.i("$TAG ${phase.name}: $name (+${time - phase.start} ms)")
    }

    private fun summary(phase: Phase, note: String?) {
        val marks = phase.marks.sortedBy { it.time }
        var previous = phase.start
        val steps = marks.joinToString(" · ") { mark ->
            val step = "${mark.name} +${mark.time - phase.start} (Δ${mark.time - previous})"
            previous = mark.time
            step
        }
        val total = (marks.lastOrNull()?.time ?: phase.start) - phase.start
        Log.i("$TAG resumen ${phase.name} (${phase.detail}), ms: $steps · total $total ms${if (note != null) " · $note" else ""}")
    }

    /** Se añade en CoreContext.run, junto al listener del Core de la app. */
    val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            if (state == GlobalState.On) coreOn()
        }

        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (state != null) registration(state)
        }

        @WorkerThread
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            when (state) {
                Call.State.PushIncomingReceived, Call.State.IncomingReceived -> incomingCallState(state)
                Call.State.Connected, Call.State.StreamsRunning, Call.State.End, Call.State.Error, Call.State.Released ->
                    answeredCallState(call, state)
                else -> {}
            }
        }
    }
}
