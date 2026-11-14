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
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log

/**
 * MundoPhone: marcas de tiempo de cada cambio de salida de audio durante una llamada (auricular↔altavoz sobre todo), para
 * medir en el registro el corte que se oye al cambiar. Medido en un Samsung A52s: 1,2-1,4 s sin audio de
 * bajada (la apertura del reproductor AAudio bloqueaba ~0,8 s y el arranque ~0,5 s) y ~0,9 s de subida. SOLO mide: no
 * cambia nada de lo que hace la app.
 *
 * Todas las líneas empiezan por «[MundoPhone altavoz]». Una medida por cambio, desde el toque (botón, lista de
 * dispositivos o notificación; «app» cuando lo pide la propia app, p. ej. al activarse el vídeo) hasta que el audio se
 * calma ([QUIET_MS] sin marcas tras el cambio en el Core, o [MAX_MS] como mucho):
 * toque → petición a Telecom → respuesta de Telecom y endpoint que anuncia Android → dispositivo de comunicación de
 * AudioManager → cambio en el Core → lo que hace el plugin AAudio con el reproductor (desconectado, reinicio, cerrado,
 * abierto, arrancado) y con la grabación (se queda el micro; o reinicio, abierta, arrancada).
 *
 * Las marcas de la app salen en su propia línea al ocurrir. Las del plugin ya tienen la suya en el registro: se recogen
 * escuchando el LoggingService (con la hora a la que llegan, que es la del registro con 1-2 ms de diferencia) y solo
 * salen en el resumen. El resumen da cada marca con los ms desde el toque y desde la anterior, el total sin audio de
 * bajada (de la desconexión o el reinicio del reproductor a su arranque) y de subida (del reinicio o la desconexión de la
 * grabación a su arranque; si no se reinicia, «sin corte»), y lo que se esperó a Android.
 * Reloj: SystemClock.elapsedRealtime.
 */
object MundoPhoneAltavoz {
    private const val TAG = "[MundoPhone altavoz]"

    /** Tras el cambio en el Core, sin marcas nuevas en este tiempo se da el cambio por terminado. */
    private const val QUIET_MS = 1500L

    /** Tope de una medida (los reintentos del reproductor con espera llegan a ~3,5 s). */
    private const val MAX_MS = 8000L

    private const val CHECK_MS = 250L

    private enum class Kind { APP, PLAYER_DOWN, PLAYER_STARTED, RECORDER_DOWN, RECORDER_STARTED, NATIVE }

    private class Mark(val name: String, val time: Long, val kind: Kind)

    private class Change(val origin: String, val start: Long) {
        val marks = arrayListOf<Mark>()
        var from: String = "?"
        var to: String = "?"
        var coreRequested = false
        var coreChanged = false
        var telecomRequestedAt = 0L
        var telecomConfirmedAt = 0L
        var telecomNote: String? = null
        var lastMarkAt = start
    }

    /** Líneas del plugin AAudio (SDK 5.5.38 con nuestros parches) que interesan, por su comienzo. */
    private val nativeMarks = listOf(
        "[AAudio Player] Player stream has disconnected" to ("reproductor desconectado" to Kind.PLAYER_DOWN),
        "[AAudio Player] Restarting stream" to ("reproductor: reinicio" to Kind.PLAYER_DOWN),
        "[AAudio Player] Player stream closed" to ("reproductor cerrado" to Kind.NATIVE),
        "[AAudio Player] Open stream for player failed" to ("reproductor: falla la apertura" to Kind.NATIVE),
        "[AAudio Player] Player stream opened" to ("reproductor abierto" to Kind.NATIVE),
        "[AAudio Player] Start stream for player failed" to ("reproductor: falla el arranque" to Kind.NATIVE),
        "[AAudio Player] Player stream started" to ("reproductor arrancado" to Kind.PLAYER_STARTED),
        "[AAudio Player] Waiting [" to ("reproductor: espera para reintentar" to Kind.NATIVE),
        "[AAudio Recorder] Speaker device is being used, keeping current microphone" to
            ("grabación: se queda el micro" to Kind.NATIVE),
        "[AAudio Recorder] Restarting stream due to device ID" to ("grabación: reinicio" to Kind.RECORDER_DOWN),
        "[AAudio Recorder] Recorder stream has disconnected" to ("grabación desconectada" to Kind.RECORDER_DOWN),
        "[AAudio Recorder] Recorder stream opened" to ("grabación abierta" to Kind.NATIVE),
        "[AAudio Recorder] Recorder stream started" to ("grabación arrancada" to Kind.RECORDER_STARTED)
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    // Nada de Log.* con este cerrojo cogido: la escucha del LoggingService llega desde los hilos que escriben en el
    // registro, y escribir en él desde dentro podría esperar a esos mismos hilos.
    private val lock = Any()

    private var current: Change? = null

    // Lectura sin cerrojo en la escucha del registro: casi siempre no hay medida y se sale al momento
    @Volatile
    private var active = false

    private var deviceListener: Any? = null

    /**
     * Toque del usuario que cambia la salida (botón altavoz, elección en la lista o botón de la notificación). [time] es
     * la hora del toque, tomada en el hilo de la interfaz antes de pasar al hilo del Core.
     */
    @AnyThread
    fun tap(origin: String, time: Long = SystemClock.elapsedRealtime()) {
        val previous = synchronized(lock) {
            val previous = current
            current = Change(origin, time).also { it.marks.add(Mark("toque ($origin)", time, Kind.APP)) }
            active = true
            previous
        }
        if (previous != null) summary(previous, "otro cambio antes de terminar")
        Log.i("$TAG toque ($origin)")
        startWatching()
    }

    /** AudioUtils, en el hilo del Core: la app va a cambiar la salida de la llamada de [from] a [to]. */
    @AnyThread
    fun outputChangeRequested(from: String, to: String) {
        var previous: Change? = null
        var started = false
        synchronized(lock) {
            var change = current
            if (change == null || change.coreRequested) {
                // Sin toque: lo pide la propia app (vídeo, notificación de Telecom…), o un segundo cambio seguido
                previous = change
                change = Change("app", SystemClock.elapsedRealtime())
                current = change
                active = true
                started = true
            }
            change.coreRequested = true
            change.from = from
            change.to = to
        }
        previous?.let { summary(it, "otro cambio antes de terminar") }
        if (started) startWatching()
        logState("cambio pedido $from→$to")
    }

    @AnyThread
    fun telecomRequested(endpoint: String) {
        val now = SystemClock.elapsedRealtime()
        if (!addMark("petición a Telecom ($endpoint)", now, Kind.APP) { it.telecomRequestedAt = now }) return
        Log.i("$TAG petición a Telecom ($endpoint)")
    }

    @AnyThread
    fun telecomResult(result: String) {
        if (!addMark("respuesta de Telecom ($result)", SystemClock.elapsedRealtime(), Kind.APP)) return
        Log.i("$TAG respuesta de Telecom ($result)")
    }

    /** Endpoint que anuncia Telecom (currentCallEndpoint) mientras hay una medida abierta; [requested] si es el pedido. */
    @AnyThread
    fun androidEndpoint(endpoint: String, requested: Boolean) {
        val name = "Android anuncia el endpoint $endpoint${if (requested) " (el pedido)" else ""}"
        if (!addMark(name, SystemClock.elapsedRealtime(), Kind.APP)) return
        Log.i("$TAG $name")
    }

    /**
     * Se cambia la salida en el Core. [how]: por qué ahora (confirmado por Android, sin confirmación en el tope, sin
     * esperar a Telecom…); [afterTelecom] si antes se le pidió la ruta a Telecom.
     */
    @AnyThread
    fun coreChanging(to: String, how: String, afterTelecom: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val ok = addMark("cambio en el Core ($to; $how)", now, Kind.APP) {
            it.coreChanged = true
            if (afterTelecom) it.telecomConfirmedAt = now
            it.telecomNote = how
        }
        if (!ok) return
        Log.i("$TAG cambio en el Core ($to; $how)")
    }

    fun deviceTypeName(type: AudioDevice.Type?): String = when (type) {
        null -> "ninguno"
        AudioDevice.Type.Earpiece -> "auricular"
        AudioDevice.Type.Speaker -> "altavoz"
        AudioDevice.Type.Bluetooth -> "Bluetooth"
        AudioDevice.Type.HearingAid -> "audífono"
        AudioDevice.Type.Headset -> "auriculares con micro"
        AudioDevice.Type.Headphones -> "auriculares"
        else -> type.name
    }

    /**
     * Escucha del LoggingService (CoreContext): llega desde cualquier hilo que escriba en el registro. Sin medida
     * abierta, sale al momento. Aquí no se escribe nada en el registro.
     */
    @AnyThread
    fun onNativeLog(message: String) {
        if (!active || !message.startsWith("[AAudio ")) return
        for ((prefix, mark) in nativeMarks) {
            if (message.startsWith(prefix)) {
                addMark(mark.first, SystemClock.elapsedRealtime(), mark.second)
                return
            }
        }
    }

    private inline fun addMark(name: String, time: Long, kind: Kind, update: (Change) -> Unit = {}): Boolean {
        synchronized(lock) {
            val change = current ?: return false
            change.marks.add(Mark(name, time, kind))
            change.lastMarkAt = time
            update(change)
            return true
        }
    }

    private fun startWatching() {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listenCommunicationDevice()
        }
        mainHandler.postDelayed(::check, CHECK_MS)
    }

    private fun check() {
        val now = SystemClock.elapsedRealtime()
        val (done, note) = synchronized(lock) {
            val change = current ?: return
            when {
                now - change.start >= MAX_MS -> {
                    current = null
                    active = false
                    change to if (change.coreChanged) "tope de ${MAX_MS / 1000} s" else "sin cambio en el Core en ${MAX_MS / 1000} s"
                }
                change.coreChanged && now - change.lastMarkAt >= QUIET_MS -> {
                    current = null
                    active = false
                    change to null
                }
                else -> null to null
            }
        }
        if (done == null) {
            mainHandler.postDelayed(::check, CHECK_MS)
            return
        }
        summary(done, note)
    }

    private fun logState(what: String) {
        val context = coreContext.context
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            deviceName(audioManager.communicationDevice)
        } else {
            "? (API ${Build.VERSION.SDK_INT})"
        }
        Log.i("$TAG $what; ahora Android tiene de dispositivo de comunicación $device")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenCommunicationDevice() {
        if (deviceListener != null) return
        val context = coreContext.context
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            val name = "Android: dispositivo de comunicación ${deviceName(device)}"
            if (addMark(name, SystemClock.elapsedRealtime(), Kind.APP)) Log.i("$TAG $name")
        }
        try {
            audioManager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), listener)
            deviceListener = listener
        } catch (e: Exception) {
            Log.w("$TAG No se pueden seguir los cambios del dispositivo de comunicación: $e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopListeningCommunicationDevice() {
        val listener = deviceListener as? AudioManager.OnCommunicationDeviceChangedListener ?: return
        deviceListener = null
        try {
            val audioManager = coreContext.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.removeOnCommunicationDeviceChangedListener(listener)
        } catch (e: Exception) {
            Log.w("$TAG No se puede quitar el oyente del dispositivo de comunicación: $e")
        }
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

    /** Hueco sin audio: de la primera marca de [down] a la última de [up] que va detrás. */
    private fun gap(marks: List<Mark>, down: Kind, up: Kind): String? {
        val first = marks.firstOrNull { it.kind == down } ?: return null
        val last = marks.lastOrNull { it.kind == up && it.time >= first.time }
            ?: return "sin arrancar al cerrar la medida (desde ${first.name})"
        return "${last.time - first.time} ms (${first.name} → ${last.name})"
    }

    private fun summary(change: Change, note: String?) {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !active) stopListeningCommunicationDevice()
        }
        val marks = change.marks.sortedBy { it.time }
        var previous = change.start
        val steps = marks.joinToString(" · ") { mark ->
            val step = "${mark.name} +${mark.time - change.start} (Δ${mark.time - previous})"
            previous = mark.time
            step
        }
        val down = gap(marks, Kind.PLAYER_DOWN, Kind.PLAYER_STARTED) ?: "el reproductor no se reabrió"
        val up = gap(marks, Kind.RECORDER_DOWN, Kind.RECORDER_STARTED) ?: "sin corte (la grabación no se reinicia)"
        val opened = marks.count { it.name == "reproductor abierto" }
        val waited = if (change.telecomRequestedAt != 0L && change.telecomConfirmedAt != 0L) {
            "${change.telecomConfirmedAt - change.telecomRequestedAt} ms (${change.telecomNote})"
        } else {
            change.telecomNote ?: "sin cambio en el Core"
        }
        val total = (marks.lastOrNull()?.time ?: change.start) - change.start
        Log.i(
            "$TAG resumen ${change.from}→${change.to} (${change.origin}), ms: $steps · bajada sin audio $down" +
                (if (opened > 1) " ($opened aperturas del reproductor)" else "") +
                " · subida $up · espera a Android $waited · total $total ms" +
                (if (note != null) " · $note" else "")
        )
    }
}
