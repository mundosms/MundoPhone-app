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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MundoPhoneIdentidadTest {
    @Test
    fun mismoAparatoNoEsClon() {
        assertFalse(MundoPhoneIdentidad.esOtroAparato("a1b2", "a1b2", "a1b2"))
        assertFalse(MundoPhoneIdentidad.esOtroAparato("a1b2", null, "a1b2"))
        assertFalse(MundoPhoneIdentidad.esOtroAparato(null, "a1b2", "a1b2"))
    }

    @Test
    fun sinHuellaNoSePuedeSaber() {
        // Instalación nueva o configuración anterior a las dos huellas: no se toca nada
        assertFalse(MundoPhoneIdentidad.esOtroAparato(null, null, "a1b2"))
        assertFalse(MundoPhoneIdentidad.esOtroAparato("", "", "a1b2"))
        // Sin ANDROID_ID tampoco
        assertFalse(MundoPhoneIdentidad.esOtroAparato("a1b2", "a1b2", null))
        assertFalse(MundoPhoneIdentidad.esOtroAparato("a1b2", "a1b2", ""))
    }

    @Test
    fun huellaPropiaDeOtroAparato() {
        assertTrue(MundoPhoneIdentidad.esOtroAparato("viejo", null, "nuevo"))
        // La propia manda aunque el SDK ya haya escrito la del aparato actual
        assertTrue(MundoPhoneIdentidad.esOtroAparato("viejo", "nuevo", "nuevo"))
    }

    @Test
    fun sinHuellaPropiaValeLaDelSdk() {
        // Primera vez con la versión nueva en una configuración clonada que el SDK aún no ha reescrito
        assertTrue(MundoPhoneIdentidad.esOtroAparato(null, "viejo", "nuevo"))
        assertTrue(MundoPhoneIdentidad.esOtroAparato("", "viejo", "nuevo"))
    }
}
