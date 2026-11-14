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
package org.linphone.ui.main.history.model

import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.TouchListener

open class NumpadModel
    @UiThread
    constructor(
    private val inCallNumpad: Boolean,
    private val onDigitClicked: (value: String) -> (Unit),
    private val onVoicemailClicked: () -> (Unit),
    private val onBackspaceClicked: () -> (Unit),
    private val onCallClicked: () -> (Unit),
    private val onTransferCallClicked: () -> (Unit),
    private val onClearClicked: () -> (Unit)
) {
    companion object {
        private const val TAG = "[Numpad Model]"
        private const val VOLUMEN_TONOS = 80 // el del marcador de AOSP (TONE_RELATIVE_VOLUME)
        private const val DURACION_TONO_MS = 150
    }

    private var tonos: ToneGenerator? = null

    val digits = MutableLiveData<String>()

    val voicemailAvailable = MutableLiveData<Boolean>()

    val showLetters = MutableLiveData<Boolean>()

    val systemPolicyAllowsDtmf: Boolean

    val touchListener = object : TouchListener {
        override fun onPressed(view: View): Boolean {
            Log.i("$TAG Numpad digit [${view.tag}] pressed")
            // MundoPhone: la vibración corta del marcador del móvil (respeta «Respuesta táctil»)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startPlayingDtmf(view.tag.toString())
            return false
        }

        override fun onReleased(view: View): Boolean {
            Log.i("$TAG Numpad digit [${view.tag}] released")
            stopPlayingDtmf(view.tag.toString())
            return false
        }
    }

    init {
        var dtmfAllowed = false
        try {
            dtmfAllowed = Settings.System.getInt(
                coreContext.context.contentResolver,
                Settings.System.DTMF_TONE_WHEN_DIALING
            ) != 0
            if (!dtmfAllowed) {
                Log.w("$TAG Numpad DTMF tones are disabled in system settings, not playing them")
            }
        } catch (snfe: SettingNotFoundException) {
            Log.e("$TAG DTMF_TONE_WHEN_DIALING system setting not found: $snfe")
            dtmfAllowed = false
        }
        systemPolicyAllowsDtmf = dtmfAllowed

        coreContext.postOnCoreThread {
            showLetters.postValue(corePreferences.showLettersOnDialpad)

            val account = LinphoneUtils.getDefaultAccount()
            val voicemailAddress = account?.params?.voicemailAddress
            voicemailAvailable.postValue(voicemailAddress != null)
        }
    }

    @UiThread
    fun onDigitClicked(value: String) {
        Log.i("$TAG Clicked on digit [$value]")
        onDigitClicked.invoke(value)
    }

    @UiThread
    fun onDigitLongClicked(value: String): Boolean {
        if (value == "0") { // Special case
            Log.i("$TAG Long clicked on digit [$value]")
            onDigitClicked.invoke("+")
            return true
        }
        return false
    }

    @UiThread
    fun onVoicemailLongClicked(): Boolean {
        if (voicemailAvailable.value == true) {
            Log.i("$TAG Long clicked on voicemail icon")
            onVoicemailClicked.invoke()
            return true
        }
        return false
    }

    @UiThread
    fun onBackspaceClicked() {
        Log.i("$TAG Clicked on backspace")
        onBackspaceClicked.invoke()
    }

    @UiThread
    fun onBackspaceLongClicked(): Boolean {
        Log.i("$TAG Long clicked on backspace, clearing input")
        onClearClicked.invoke()
        return true
    }

    @UiThread
    fun onCallClicked() {
        Log.i("$TAG Starting call")
        onCallClicked.invoke()
    }

    @UiThread
    fun onBlindTransferClicked() {
        Log.i("$TAG Transferring call")
        onTransferCallClicked.invoke()
    }

    @UiThread
    private fun startPlayingDtmf(dtmf: String) {
        if (dtmf.isEmpty() || (!inCallNumpad && !systemPolicyAllowsDtmf)) return

        if (!inCallNumpad) {
            tonoDelMarcador(dtmf[0])
            return
        }
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Start playing DTMF [$dtmf]")
            core.playDtmf(dtmf[0], -1)
        }
    }

    @UiThread
    private fun stopPlayingDtmf(dtmf: String) {
        if (dtmf.isEmpty() || (!inCallNumpad && !systemPolicyAllowsDtmf)) return
        if (!inCallNumpad) return // el tono del marcador dura lo suyo (tonoDelMarcador)

        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Stop playing DTMF [$dtmf]")
            core.stopDtmf()
        }
    }

    /**
     * MundoPhone: el pitido del marcador fuera de llamada, como la app
     * Teléfono. Antes iba por core.playDtmf en el hilo del Core: el tono empezaba ~180 ms después de soltar la tecla y se
     * paraba en el mismo milisegundo, o sea que a veces no sonaba. ToneGenerator suena al momento en este hilo, 150 ms,
     * con el ajuste «tonos del teclado» (systemPolicyAllowsDtmf) y solo con el móvil en modo sonido, como el marcador.
     */
    @UiThread
    private fun tonoDelMarcador(tecla: Char) {
        val tono = when (tecla) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (tecla - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        val audio = coreContext.context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val generador = tonos ?: ToneGenerator(AudioManager.STREAM_DTMF, VOLUMEN_TONOS).also { tonos = it }
            generador.startTone(tono, DURACION_TONO_MS)
        } catch (e: RuntimeException) {
            Log.w("$TAG Couldn't play dialpad tone: $e")
        }
    }

    /** Suelta el ToneGenerator (lo llama el ViewModel del marcador al cerrarse). */
    @UiThread
    fun liberarTonos() {
        tonos?.release()
        tonos = null
    }
}
