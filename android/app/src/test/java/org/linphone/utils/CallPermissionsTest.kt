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
package org.linphone.utils

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.R

/** Permisos antes de llamar. */
class CallPermissionsTest {
    private val mic = Manifest.permission.RECORD_AUDIO
    private val camera = Manifest.permission.CAMERA

    @Test
    fun `una llamada de voz solo necesita el micrófono, una de vídeo también la cámara`() {
        assertEquals(listOf(mic), CallPermissions.missing(false) { false })
        assertEquals(listOf(mic, camera), CallPermissions.missing(true) { false })
    }

    @Test
    fun `con lo necesario concedido no falta nada, aunque falte la cámara en una llamada de voz`() {
        assertTrue(CallPermissions.missing(false) { it == mic }.isEmpty())
        assertTrue(CallPermissions.missing(true) { true }.isEmpty())
        assertEquals(listOf(camera), CallPermissions.missing(true) { it == mic })
    }

    @Test
    fun `el aviso dice lo que se ha denegado`() {
        assertEquals(R.string.call_audio_record_permission_not_granted_toast, CallPermissions.deniedMessage(mapOf(mic to false)))
        assertEquals(
            R.string.call_video_permissions_not_granted_toast,
            CallPermissions.deniedMessage(mapOf(mic to false, camera to false))
        )
        assertEquals(
            R.string.call_video_permission_not_granted_toast,
            CallPermissions.deniedMessage(mapOf(mic to true, camera to false))
        )
        assertEquals(
            R.string.call_audio_record_permission_not_granted_toast,
            CallPermissions.deniedMessage(mapOf(mic to false, camera to true))
        )
    }

    @Test
    fun `todo concedido o petición cancelada sin respuesta, sin aviso`() {
        assertNull(CallPermissions.deniedMessage(mapOf(mic to true, camera to true)))
        assertNull(CallPermissions.deniedMessage(emptyMap()))
    }

    @Test
    fun `solo se manda a los ajustes cuando Android ya no enseña la ventana`() {
        // Nunca pedido y negado una vez: Android pasa a pedir explicación, se volverá a preguntar
        assertFalse(CallPermissions.deniedForever(rationaleBefore = false, rationaleAfter = true))
        // Segunda negativa del usuario, con la ventana delante: no se le lleva a los ajustes en ese momento
        assertFalse(CallPermissions.deniedForever(rationaleBefore = true, rationaleAfter = false))
        // Denegado para siempre: Android contesta que no sin enseñar nada
        assertTrue(CallPermissions.deniedForever(rationaleBefore = false, rationaleAfter = false))
        // La actividad se recreó y no se sabe cómo estaba antes
        assertFalse(CallPermissions.deniedForever(rationaleBefore = null, rationaleAfter = false))
    }
}
