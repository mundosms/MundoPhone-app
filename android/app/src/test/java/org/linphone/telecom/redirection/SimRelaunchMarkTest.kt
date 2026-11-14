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
package org.linphone.telecom.redirection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La marca de la llamada que relanzamos por la SIM (E1): un solo uso, caducidad y el número con o sin prefijo. */
class SimRelaunchMarkTest {
    // Reloj de mentira, en milisegundos
    private var reloj = 1_000L
    private val marca = SimRelaunchMark { reloj }
    private val caducadas = mutableListOf<String>()

    private fun pasa(numero: String) = marca.consume(numero) { caducadas.add(it) }

    @Test
    fun `la marca deja pasar una sola vez`() {
        marca.mark("+34600123123", 10_000)
        assertTrue(pasa("+34600123123"))
        assertFalse(pasa("+34600123123"))
    }

    @Test
    fun `casa el número con o sin prefijo de país`() {
        marca.mark("600 12 31 23", 10_000)
        assertTrue(pasa("+34600123123"))
        marca.mark("+34600123123", 10_000)
        assertTrue(pasa("600123123"))
    }

    @Test
    fun `otro número no gasta la marca`() {
        marca.mark("600123123", 10_000)
        assertFalse(pasa("600999999"))
        assertTrue(pasa("600123123"))
    }

    @Test
    fun `vale hasta el último milisegundo del plazo`() {
        marca.mark("600123123", 10_000)
        reloj += 10_000
        assertTrue(pasa("600123123"))
    }

    @Test
    fun `caducada no deja pasar y avisa una vez con el número que llevaba`() {
        marca.mark("600123123", 10_000)
        reloj += 10_001
        assertFalse(pasa("600123123"))
        assertEquals(listOf("600123123"), caducadas)
        // Ya está borrada: no vuelve a avisar
        assertFalse(pasa("600123123"))
        assertEquals(1, caducadas.size)
    }

    @Test
    fun `caducada se borra aunque llegue otro número`() {
        marca.mark("600123123", 10_000)
        reloj += 60_000
        assertFalse(pasa("699999999"))
        assertEquals(listOf("600123123"), caducadas)
    }

    @Test
    fun `una marca nueva sustituye a la anterior`() {
        marca.mark("600111111", 10_000)
        marca.mark("600222222", 10_000)
        assertFalse(pasa("600111111"))
        assertTrue(pasa("600222222"))
    }

    @Test
    fun `sin marca o tras borrarla no pasa nada por la SIM`() {
        assertFalse(pasa("600123123"))
        marca.mark("600123123", 10_000)
        marca.clear()
        assertFalse(pasa("600123123"))
        assertTrue(caducadas.isEmpty())
    }
}
