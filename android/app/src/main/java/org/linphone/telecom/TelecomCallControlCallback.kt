/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
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
package org.linphone.telecom

import android.os.Build
import android.os.SystemClock
import android.telecom.DisconnectCause
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.MundoPhoneAltavoz
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class TelecomCallControlCallback(
    private val call: Call,
    private val callControl: CallControlScope,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "[Telecom Call Control Callback]"

        /**
         * MundoPhone: cuánto se espera a que Android confirme el cambio de ruta pedido antes de cambiar la salida del SDK
         * de todas formas. Pasado este tiempo se hace lo de antes (cambiar en el SDK sin confirmación).
         */
        const val ENDPOINT_CONFIRMATION_TIMEOUT_MS = 400L
    }

    private var mutedByTelecomManager = false

    // MundoPhone: lo que Telecom dice de la ruta de audio, para pedirle el cambio auricular↔altavoz ANTES de cambiar la
    // salida del SDK (changeEndpointBeforeCore)
    @Volatile
    private var availableEndpoints: List<CallEndpointCompat> = emptyList()

    @Volatile
    private var currentEndpoint: CallEndpointCompat? = null

    private val endpointLock = Any()
    private var pendingEndpointType: Int? = null
    private var pendingEndpointApply: ((String, Boolean) -> Unit)? = null
    private var pendingEndpointRequestedAt = 0L
    private var pendingEndpointId = 0

    private val callListener = object : CallListenerStub() {
        @WorkerThread
        override fun onStateChanged(call: Call, state: Call.State?, message: String) {
            Log.i("$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$state]")
            if (state == Call.State.Connected) {
                if (call.dir == Call.Dir.Incoming) {
                    answerCall()
                } else {
                    scope.launch {
                        Log.i("$TAG Setting call active")
                        val result = callControl.setActive()
                        if (result is CallControlResult.Error) {
                            Log.e("$TAG Failed to set call control active: $result")
                        }
                    }
                }
            } else if (state == Call.State.End) {
                callEnded()
            } else if (state == Call.State.Error) {
                callError(message)
            } else if (state == Call.State.Pausing) {
                scope.launch {
                    Log.i("$TAG Pausing call")
                    val result = callControl.setInactive()
                    if (result is CallControlResult.Error) {
                        Log.e("$TAG Failed to set call control inactive: $result")
                    }
                }
            } else if (state == Call.State.Resuming) {
                scope.launch {
                    Log.i("$TAG Resuming call")
                    val result = callControl.setActive()
                    if (result is CallControlResult.Error) {
                        Log.e("$TAG Failed to set call control active: $result")
                    }
                }
            }
        }
    }

    init {
        // NEVER CALL ANY METHOD FROM callControl OBJECT IN HERE!
        Log.i("$TAG Created callback for call")
        coreContext.postOnCoreThread {
            call.addListener(callListener)
        }
    }

    fun onCallControlCallbackSet() {
        Log.i(
            "$TAG Callback have been set for call, Telecom call ID is [${callControl.getCallId()}]"
        )

        coreContext.postOnCoreThread {
            val state = call.state
            Log.i("$TAG Call state currently is [$state]")
            when (state) {
                Call.State.Connected, Call.State.StreamsRunning -> answerCall()
                Call.State.End -> callEnded()
                Call.State.Error -> callError("")
                Call.State.Released -> callEnded()
                else -> {} // doing nothing
            }
        }

        callControl.availableEndpoints.onEach { list ->
            // MundoPhone: se guarda para pedirle a Telecom el altavoz o el auricular (changeEndpointBeforeCore); lo demás
            // (Bluetooth, cable) se sigue llevando solo en el SDK, como upstream
            Log.i(
                "$TAG New available audio endpoints list [${list.joinToString { "${it.name}/${endpointTypeToString(it.type)}" }}], only used to ask Telecom for speaker/earpiece before the SDK"
            )
            availableEndpoints = list
        }.launchIn(scope)

        // currentCallEndpoint es un Channel (receiveAsFlow): un segundo colector se repartiría las emisiones con este,
        // así que la confirmación de un cambio pedido por nosotros sale de aquí
        callControl.currentCallEndpoint.onEach { endpoint ->
            currentEndpoint = endpoint
            onEndpointAnnounced(endpoint)
        }.launchIn(scope)

        callControl.isMuted.onEach { muted ->
            coreContext.postOnCoreThread {
                val callState = call.state
                Log.i(
                    "$TAG We're asked to [${if (muted) "mute" else "unmute"}] the call in state [$callState]"
                )
                // Only follow un-mute requests for not outgoing calls (such as joining a conference muted)
                // and if connected to Android Auto that has a way to let user mute/unmute from the car directly
                // or if we muted the call previously following Telecom Manager request.
                if (muted || mutedByTelecomManager || (!LinphoneUtils.isCallOutgoing(callState, false) && coreContext.isConnectedToAndroidAuto)) {
                    mutedByTelecomManager = muted
                    call.microphoneMuted = muted
                    coreContext.refreshMicrophoneMuteStateEvent.postValue(Event(true))
                } else {
                    if (coreContext.isConnectedToAndroidAuto) {
                        Log.w(
                            "$TAG Not following unmute request because call is in state [$callState]"
                        )
                    } else {
                        Log.w(
                            "$TAG Not following unmute request because user isn't connected to Android Auto and call is in state [$callState]"
                        )
                    }
                }
            }
        }.launchIn(scope)
    }

    /**
     * MundoPhone: pide a Telecom el altavoz o el auricular y llama a [apply] cuando Android lo confirma (o pasados
     * [ENDPOINT_CONFIRMATION_TIMEOUT_MS], o si Telecom contesta con error), para que el SDK reabra el reproductor AAudio
     * con la ruta de Android YA cambiada.
     *
     * Por qué: al poner el altavoz, el SDK reabría el reproductor hacia el altavoz mientras Android seguía con la ruta
     * del auricular, y en un Samsung A52s la apertura bloqueaba ~0,8 s y el arranque ~0,5 s más: 1,2-1,4 s
     * sin audio. El mismo reproductor, abierto en el altavoz con la ruta ya en el altavoz (el tono de colgar de esa misma
     * llamada), abrió en ~0,1 s y arrancó en 24 ms. Y con la ruta de Telecom distinta de la del SDK, Samsung tira de la
     * de Telecom (21/10: AAUDIO_ERROR_DISCONNECTED al pasar al altavoz).
     *
     * Solo altavoz y auricular: Bluetooth y cable siguen solo en el SDK, como
     * upstream (sus endpoints de Telecom no siempre se actualizan al desconectar y reconectar). [apply] recibe el motivo
     * y si Android lo confirmó; se llama una sola vez, desde un hilo de Telecom. Si se pide otra ruta antes de que llegue
     * la confirmación, la pendiente se descarta y manda la última. Devuelve false si Telecom no conoce esa ruta (el
     * llamante cambia el SDK directamente).
     */
    @AnyThread
    fun changeEndpointBeforeCore(type: AudioDevice.Type, apply: (String, Boolean) -> Unit): Boolean {
        val endpointType = when (type) {
            AudioDevice.Type.Speaker -> CallEndpointCompat.TYPE_SPEAKER
            AudioDevice.Type.Earpiece -> CallEndpointCompat.TYPE_EARPIECE
            else -> return false
        }
        val typeName = endpointTypeToString(endpointType)
        val endpoint = availableEndpoints.find { it.type == endpointType }
        if (endpoint == null) {
            Log.w("$TAG No [$typeName] endpoint known by Telecom, changing the SDK audio route without asking Telecom")
            return false
        }

        val id: Int
        synchronized(endpointLock) {
            if (pendingEndpointType == endpointType) {
                Log.i("$TAG Already waiting for Telecom to switch to [$typeName], nothing more to do")
                return true
            }
            if (pendingEndpointType == null && currentEndpoint?.type == endpointType) {
                // Ya está ahí: pedírselo no traería confirmación (no cambia nada) y solo se esperaría al tope
                Log.i("$TAG Telecom is already on [$typeName], changing the SDK audio route right away")
                return false
            }
            if (pendingEndpointType != null) {
                Log.w(
                    "$TAG Dropping pending switch to [${endpointTypeToString(pendingEndpointType!!)}], [$typeName] requested since"
                )
            }
            pendingEndpointId += 1
            id = pendingEndpointId
            pendingEndpointType = endpointType
            pendingEndpointApply = apply
            pendingEndpointRequestedAt = SystemClock.elapsedRealtime()
        }

        scope.launch {
            Log.i("$TAG Asking Telecom to switch to [${endpoint.name}/$typeName] before the SDK")
            MundoPhoneAltavoz.telecomRequested("${endpoint.name}/$typeName")
            val result = try {
                callControl.requestEndpointChange(endpoint)
            } catch (e: Exception) {
                // El CallControl de la plataforma lanza IllegalStateException si la llamada ya no está en Telecom: sin
                // esto, la excepción tumbaría la app (este scope no tiene manejador)
                Log.e("$TAG Exception asking Telecom to switch to [${endpoint.name}/$typeName]: $e")
                MundoPhoneAltavoz.telecomResult("excepción: $e")
                completePendingEndpoint(id, "Telecom no pudo ($e)", false)
                return@launch
            }
            Log.i("$TAG Telecom answered the switch to [${endpoint.name}/$typeName]: $result")
            MundoPhoneAltavoz.telecomResult(result.toString())
            when {
                result is CallControlResult.Error -> completePendingEndpoint(id, "Telecom no pudo ($result)", false)
                // Desde la API 34 el resultado llega cuando Telecom ya ha cambiado la ruta (lo devuelve con el cambio
                // de endpoint, o en el acto si ya estaba en él). Antes, la versión de compatibilidad devuelve Success
                // nada más pedirlo: ahí se espera al endpoint.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    completePendingEndpoint(id, "confirmado por Telecom", true)
            }
        }
        scope.launch {
            delay(ENDPOINT_CONFIRMATION_TIMEOUT_MS)
            completePendingEndpoint(id, "sin confirmación de Android en $ENDPOINT_CONFIRMATION_TIMEOUT_MS ms", false)
        }
        return true
    }

    /**
     * Endpoint que anuncia Telecom. Si es el que le hemos pedido (changeEndpointBeforeCore), es la confirmación y se
     * aplica en el SDK la ruta que pedimos. Los que Android propone por su cuenta se siguen ignorando, como upstream:
     * la ruta la decide la app y, en llamada, Bluetooth y cable los lleva la política de CoreContext.
     */
    private fun onEndpointAnnounced(endpoint: CallEndpointCompat) {
        val name = "${endpoint.name}/${endpointTypeToString(endpoint.type)}"
        var waitingFor: Int? = null
        val id = synchronized(endpointLock) {
            waitingFor = pendingEndpointType
            if (pendingEndpointType == endpoint.type) pendingEndpointId else null
        }
        MundoPhoneAltavoz.androidEndpoint(name, id != null)
        if (id == null) {
            Log.i("$TAG Android requests us to use [${endpoint.name}] audio endpoint with type [${endpointTypeToString(endpoint.type)}], ignoring it")
            waitingFor?.let { Log.w("$TAG Still waiting for Telecom to switch to [${endpointTypeToString(it)}]") }
            return
        }
        Log.i("$TAG Android confirms the [$name] audio endpoint we asked for, applying it in the SDK now")
        completePendingEndpoint(id, "confirmado por Android", true)
    }

    private fun completePendingEndpoint(id: Int, reason: String, confirmed: Boolean) {
        val apply: (String, Boolean) -> Unit
        val waited: Long
        synchronized(endpointLock) {
            if (id != pendingEndpointId) return // Otra petición la ha sustituido
            apply = pendingEndpointApply ?: return // Ya aplicada
            waited = SystemClock.elapsedRealtime() - pendingEndpointRequestedAt
            pendingEndpointApply = null
            pendingEndpointType = null
        }
        apply("$reason, a los $waited ms", confirmed)
    }

    private fun answerCall() {
        val isVideo = LinphoneUtils.isVideoEnabled(call)
        val type = if (isVideo) {
            CallAttributesCompat.CALL_TYPE_VIDEO_CALL
        } else {
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        }
        scope.launch {
            Log.i("$TAG Answering [${if (isVideo) "video" else "audio"}] call")
            val result = callControl.answer(type)
            if (result is CallControlResult.Error) {
                Log.e("$TAG Failed to answer call control: $result")
            }
        }

        if (isVideo && corePreferences.routeAudioToSpeakerWhenVideoIsEnabled) {
            Log.i("$TAG Answering video call, routing audio to speaker")
            AudioUtils.routeAudioToSpeaker(call)
        }
    }

    private fun callEnded() {
        val reason = call.reason
        val direction = call.dir
        scope.launch {
            val disconnectCause = when (reason) {
                Reason.NotAnswered -> DisconnectCause.REMOTE
                Reason.Declined -> DisconnectCause.REJECTED
                Reason.Busy -> {
                    if (direction == Call.Dir.Incoming) {
                        DisconnectCause.MISSED
                    } else {
                        DisconnectCause.BUSY
                    }
                }
                else -> DisconnectCause.LOCAL
            }
            Log.i("$TAG Disconnecting [${if (direction == Call.Dir.Incoming)"incoming" else "outgoing"}] call with cause [${disconnectCauseToString(disconnectCause)}] because it has ended with reason [$reason]")
            try {
                val result = callControl.disconnect(DisconnectCause(disconnectCause))
                if (result is CallControlResult.Error) {
                    Log.e("$TAG Failed to disconnect call control: $result")
                }
            } catch (ise: IllegalArgumentException) {
                Log.e("$TAG Couldn't disconnect call control with cause [${disconnectCauseToString(disconnectCause)}]: $ise")
            }
        }
    }

    private fun callError(message: String) {
        val reason = call.reason
        scope.launch {
            // For some reason DisconnectCause.ERROR or DisconnectCause.BUSY triggers an IllegalArgumentException with following message
            // Valid DisconnectCause codes are limited to [DisconnectCause.LOCAL, DisconnectCause.REMOTE, DisconnectCause.MISSED, or DisconnectCause.REJECTED]
            val disconnectCause = DisconnectCause.REJECTED
            Log.w("$TAG Disconnecting call with cause [${disconnectCauseToString(disconnectCause)}] due to error [$message] and reason [$reason]")
            try {
                val result = callControl.disconnect(DisconnectCause(disconnectCause))
                if (result is CallControlResult.Error) {
                    Log.e("$TAG Failed to disconnect call control: $result")
                }
            } catch (ise: IllegalArgumentException) {
                Log.e("$TAG Couldn't disconnect call control with cause [${disconnectCauseToString(disconnectCause)}]: $ise")
            }
        }
    }

    private fun disconnectCauseToString(cause: Int): String {
        return when (cause) {
            DisconnectCause.UNKNOWN -> "UNKNOWN"
            DisconnectCause.ERROR -> "ERROR"
            DisconnectCause.LOCAL -> "LOCAL"
            DisconnectCause.REMOTE -> "REMOTE"
            DisconnectCause.CANCELED -> "CANCELED"
            DisconnectCause.MISSED -> "MISSED"
            DisconnectCause.REJECTED -> "REJECTED"
            DisconnectCause.BUSY -> "BUSY"
            DisconnectCause.RESTRICTED -> "RESTRICTED"
            DisconnectCause.OTHER -> "OTHER"
            DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> "CONNECTION_MANAGER_NOT_SUPPORTED"
            DisconnectCause.ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE"
            DisconnectCause.CALL_PULLED -> "CALL_PULLED"
            else -> "UNEXPECTED: $cause"
        }
    }

    private fun endpointTypeToString(type: Int): String {
        return when (type) {
            CallEndpointCompat.TYPE_UNKNOWN -> "UNKNOWN"
            CallEndpointCompat.TYPE_EARPIECE -> "EARPIECE"
            CallEndpointCompat.TYPE_BLUETOOTH -> "BLUETOOTH"
            CallEndpointCompat.TYPE_WIRED_HEADSET -> "WIRED HEADSET"
            CallEndpointCompat.TYPE_SPEAKER -> "SPEAKER"
            CallEndpointCompat.TYPE_STREAMING -> "STREAMING"
            else -> "UNEXPECTED: $type"
        }
    }
}
