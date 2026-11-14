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
package org.linphone.utils

import android.Manifest
import org.linphone.R

/**
 * MundoPhone: sin micrófono, y en vídeo sin cámara, la llamada no sale con el
 * micrófono en silencio; MainActivity pide lo que falta y, si se concede, CoreContext la relanza. Aquí lo que no depende
 * de Android, para probarlo en la JVM.
 */
object CallPermissions {
    /** Una petición más vieja que esto al llegar a MainActivity es de una llamada que el usuario ya no espera. */
    const val REQUEST_MAX_AGE_MS = 5000L

    /** [permissions]: los que faltan; [requestedAt]: SystemClock.elapsedRealtime() al pedir la llamada. */
    class Request(val permissions: List<String>, val requestedAt: Long)

    /** Lo que hace falta para llamar: el micrófono y, en vídeo, también la cámara. */
    fun required(isVideoCall: Boolean): List<String> = if (isVideoCall) {
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    } else {
        listOf(Manifest.permission.RECORD_AUDIO)
    }

    fun missing(isVideoCall: Boolean, isGranted: (String) -> Boolean): List<String> =
        required(isVideoCall).filterNot(isGranted)

    /** El aviso cuando se ha denegado algo de [results] (permiso → concedido); null si no se denegó nada. */
    fun deniedMessage(results: Map<String, Boolean>): Int? {
        val microphone = results[Manifest.permission.RECORD_AUDIO] == false
        val camera = results[Manifest.permission.CAMERA] == false
        return when {
            microphone && camera -> R.string.call_video_permissions_not_granted_toast
            microphone -> R.string.call_audio_record_permission_not_granted_toast
            camera -> R.string.call_video_permission_not_granted_toast
            else -> null
        }
    }

    /**
     * Android ya no enseña la ventana (denegado para siempre) y solo queda ir a los ajustes: ni antes de pedirlo ni
     * después había que explicarlo. Si nunca se había pedido también es falso antes, pero tras la primera negativa pasa
     * a verdadero, y la segunda negativa lo deja falso con «antes» verdadero: en esos dos casos no se manda a los ajustes.
     * Sin saber cómo estaba antes (null: la actividad se ha recreado), tampoco.
     */
    fun deniedForever(rationaleBefore: Boolean?, rationaleAfter: Boolean): Boolean =
        rationaleBefore == false && !rationaleAfter
}
