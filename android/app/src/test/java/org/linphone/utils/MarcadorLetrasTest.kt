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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarcadorLetrasTest {
    @Test
    fun `7272 casa con PAPA y con SARA`() {
        assertTrue(MarcadorLetras.casa("7272", "Papa"))
        assertTrue(MarcadorLetras.casa("7272", "Carlos SARAworld"))
        assertFalse(MarcadorLetras.casa("7272", "Pedro"))
    }

    @Test
    fun `casa con el inicio de cualquier palabra, no por en medio`() {
        assertTrue(MarcadorLetras.casa("227", "Carlos SARAworld"))
        assertFalse(MarcadorLetras.casa("2729", "Carlos SARAworld")) // «araw» va por en medio
    }

    @Test
    fun `sin acentos ni mayusculas, y la enie en el 6`() {
        assertTrue(MarcadorLetras.casa("25", "Álvaro"))
        assertTrue(MarcadorLetras.casa("686", "Muñoz"))
        assertTrue(MarcadorLetras.casa("367", "José Ángel ENRIQUE"))
        assertEquals(listOf("227567", "727296753"), MarcadorLetras.palabras("Carlos SARAworld"))
    }

    @Test
    fun `las cifras del nombre cuentan como tales`() {
        assertTrue(MarcadorLetras.casa("634", "Oficina 2B"))
        assertTrue(MarcadorLetras.casa("22", "Oficina 2B"))
    }

    @Test
    fun `una cifra sola, letras o nada no buscan por letras`() {
        assertFalse(MarcadorLetras.casa("7", "Papa"))
        assertFalse(MarcadorLetras.casa("72a", "Papa"))
        assertFalse(MarcadorLetras.casa("+34", "Papa"))
        assertFalse(MarcadorLetras.casa("7272", null))
        assertFalse(MarcadorLetras.casa("7272", "   "))
        assertFalse(MarcadorLetras.casa("", "Papa"))
    }
}
