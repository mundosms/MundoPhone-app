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
import org.linphone.utils.PhoneNumberUtils.Companion.getComparablePhoneCandidates
import org.linphone.utils.PhoneNumberUtils.Companion.getNationalNumberForDisplay
import org.linphone.utils.PhoneNumberUtils.Companion.isPhoneNumberLike
import org.linphone.utils.PhoneNumberUtils.Companion.matchesComparableCandidates
import org.linphone.utils.PhoneNumberUtils.Companion.normalizePhoneNumber

/** Emparejado flexible de números (D3): el mismo número escrito de formas distintas. */
class PhoneNumberUtilsTest {
    /** Como ContactsManager: las formas del número que se busca contra las del número guardado en el contacto. */
    private fun mismo(buscado: String, guardado: String, prefijoCuenta: String? = "+34"): Boolean =
        matchesComparableCandidates(guardado, getComparablePhoneCandidates(buscado, prefijoCuenta), prefijoCuenta)

    @Test
    fun `con espacios es el mismo que con el prefijo internacional`() {
        assertTrue(mismo("600 12 31 23", "+34600123123"))
        assertTrue(mismo("+34600123123", "600 12 31 23"))
    }

    @Test
    fun `con 00 delante es el mismo`() {
        assertTrue(mismo("0034600123123", "+34600123123"))
        assertTrue(mismo("0034 600 12 31 23", "600123123"))
        // Solo por la regla del «00»: menos de 10 cifras y sin el prefijo de la cuenta
        assertTrue(mismo("001234567", "+1234567"))
    }

    @Test
    fun `sin el prefijo de la cuenta es el mismo`() {
        // Andorra (+376) tiene números de 6 cifras: casan solo por el prefijo de la cuenta, no por las últimas 9
        assertTrue(mismo("+376812345", "812345", prefijoCuenta = "+376"))
        assertFalse(mismo("+376812345", "812345", prefijoCuenta = null))
    }

    @Test
    fun `por las 9 últimas cifras es el mismo aunque la cuenta sea de otro país`() {
        assertTrue(mismo("+34600123123", "600123123", prefijoCuenta = "+33"))
        assertTrue(mismo("34600123123", "600 12 31 23", prefijoCuenta = null))
    }

    @Test
    fun `límite conocido, como la 2_2_x - dos países con las mismas 9 últimas cifras casan`() {
        assertTrue(mismo("+44600123123", "+34600123123"))
    }

    @Test
    fun `números distintos no casan`() {
        assertFalse(mismo("600123123", "600123124"))
        assertFalse(mismo("+34600123123", "+34600123124"))
        assertFalse(mismo("1001", "1002"))
        assertFalse(mismo("", "600123123"))
        assertFalse(mismo("600123123", ""))
    }

    @Test
    fun `las formas comparables de un número`() {
        assertEquals(
            setOf("0034600123123", "34600123123", "600123123"),
            getComparablePhoneCandidates("0034 600 12 31 23", "+34")
        )
        assertEquals(setOf("34600123123", "600123123"), getComparablePhoneCandidates("+34600123123", "+34"))
        assertEquals(setOf("600123123"), getComparablePhoneCandidates("600 12 31 23", "+34"))
        assertEquals(emptySet<String>(), getComparablePhoneCandidates("sin cifras", "+34"))
    }

    @Test
    fun `de 7 a 15 cifras es un teléfono y con menos una extensión`() {
        assertFalse(isPhoneNumberLike("1001"))
        assertFalse(isPhoneNumberLike("123456"))
        assertTrue(isPhoneNumberLike("1234567"))
        assertTrue(isPhoneNumberLike("600 12 31 23"))
        assertTrue(isPhoneNumberLike("+34600123123"))
        assertTrue(isPhoneNumberLike("123456789012345"))
        assertFalse(isPhoneNumberLike("1234567890123456"))
        assertFalse(isPhoneNumberLike("jmartin"))
        assertFalse(isPhoneNumberLike(null))
    }

    @Test
    fun `normalizar deja el más de delante y las cifras`() {
        assertEquals("+34600123123", normalizePhoneNumber(" +34 (600) 12-31-23 "))
        assertEquals("600123123", normalizePhoneNumber("600.12.31.23"))
        assertEquals("", normalizePhoneNumber("+"))
        assertEquals("", normalizePhoneNumber("   "))
        assertEquals("", normalizePhoneNumber(null))
    }

    @Test
    fun `para mostrar, sin el prefijo de España ni el de la cuenta`() {
        assertEquals("600123123", getNationalNumberForDisplay("+34600123123"))
        assertEquals("600123123", getNationalNumberForDisplay("34600123123"))
        assertEquals("812345", getNationalNumberForDisplay("+376812345", "+376"))
        assertEquals("600123123", getNationalNumberForDisplay("600 12 31 23"))
        assertEquals("1001", getNationalNumberForDisplay("1001"))
        assertEquals("", getNationalNumberForDisplay(null))
    }
}
