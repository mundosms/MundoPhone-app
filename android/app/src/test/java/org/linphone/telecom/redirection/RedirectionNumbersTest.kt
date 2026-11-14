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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.telecom.redirection.RedirectionNumbers.dialableNumber
import org.linphone.telecom.redirection.RedirectionNumbers.isOperatorCode
import org.linphone.telecom.redirection.RedirectionNumbers.RULE_APP
import org.linphone.telecom.redirection.RedirectionNumbers.RULE_SIM
import org.linphone.telecom.redirection.RedirectionNumbers.mask
import org.linphone.telecom.redirection.RedirectionNumbers.parseRules
import org.linphone.telecom.redirection.RedirectionNumbers.ruleFor
import org.linphone.telecom.redirection.RedirectionNumbers.ruleKey
import org.linphone.telecom.redirection.RedirectionNumbers.serializeRules
import org.linphone.telecom.redirection.RedirectionNumbers.sameNumber
import org.linphone.telecom.redirection.RedirectionNumbers.withRule

/** Redirección de llamadas del teléfono nativo (E1): qué números van por la SIM, cuáles casan y el log. */
class RedirectionNumbersTest {
    // ---- Qué va por la SIM sin preguntar ----

    @Test
    fun `los códigos que empiezan por asterisco o almohadilla van por la SIM`() {
        assertTrue(isOperatorCode("*#06#"))
        assertTrue(isOperatorCode("#31#600123123"))
        assertTrue(isOperatorCode("*21*600123123#"))
        assertTrue(isOperatorCode("  *100#"))
    }

    @Test
    fun `un número normal no es código de operadora`() {
        assertFalse(isOperatorCode("600123123"))
        assertFalse(isOperatorCode("+34600123123"))
        // El asterisco en medio no cuenta
        assertFalse(isOperatorCode("600*123"))
        assertFalse(isOperatorCode(""))
    }

    @Test
    fun `un número normal sale limpio de separadores para MundoPhone`() {
        assertEquals("+34600123123", dialableNumber("+34 600 12 31 23"))
        assertEquals("911234567", dialableNumber("(91) 123-45-67"))
        assertEquals("600123123", dialableNumber("600.12.31.23"))
        assertEquals("600123123", dialableNumber("600/123/123"))
        assertEquals("600123123", dialableNumber("  600123123  "))
        assertEquals("1001", dialableNumber("1001"))
    }

    @Test
    fun `con pausas o esperas va por la SIM`() {
        assertNull(dialableNumber("600123123,1234"))
        assertNull(dialableNumber("600123123;1234"))
    }

    @Test
    fun `con letras o sin cifras va por la SIM`() {
        assertNull(dialableNumber("1-800-FLOWERS"))
        assertNull(dialableNumber("600abc"))
        assertNull(dialableNumber("*#06#"))
        assertNull(dialableNumber(""))
        assertNull(dialableNumber("   "))
        assertNull(dialableNumber("+"))
        // El «+» solo vale delante
        assertNull(dialableNumber("34+600123123"))
    }

    // ---- El mismo número (para la marca de la llamada relanzada) ----

    @Test
    fun `el mismo número con y sin prefijo de país casa`() {
        assertTrue(sameNumber("+34600123123", "600123123"))
        assertTrue(sameNumber("600 12 31 23", "+34 600 123 123"))
        assertTrue(sameNumber("0034600123123", "600123123"))
        assertTrue(sameNumber("1001", "1001"))
    }

    @Test
    fun `números distintos no casan`() {
        assertFalse(sameNumber("600123123", "600123124"))
        assertFalse(sameNumber("1001", "1002"))
        // Por debajo de 9 cifras solo vale si son iguales
        assertFalse(sameNumber("12345678", "912345678"))
        // Lo que no es un número normal no casa con nada
        assertFalse(sameNumber("600123123", "600123123,1"))
    }

    // ---- El número en el log ----

    @Test
    fun `el log nunca lleva el número entero`() {
        assertEquals("+xxxxxxxxx23", mask("+34600123123"))
        assertEquals("xxx xx xx 23", mask("600 12 31 23"))
        assertEquals("xx01", mask("1001"))
        assertFalse(mask("+34600123123").contains("600"))
    }

    @Test
    fun `con dos cifras o menos no hay nada que tapar`() {
        assertEquals("12", mask("12"))
        assertEquals("*#06#", mask("*#06#"))
        assertEquals("", mask(""))
    }

    // ---- «Recordar para este número» ----

    @Test
    fun `el número se guarda sin prefijo de país y casa escrito de cualquier forma`() {
        assertEquals("600123123", ruleKey("+34 600 12 31 23"))
        assertEquals("600123123", ruleKey("0034600123123"))
        assertEquals("1004", ruleKey("1004"))
        assertNull(ruleKey("600,123"))
        val rules = withRule(emptyMap(), "+34 600-123-123", RULE_SIM)
        assertEquals(mapOf("600123123" to RULE_SIM), rules)
        assertEquals(RULE_SIM, ruleFor(rules, "600123123"))
        assertEquals(RULE_SIM, ruleFor(rules, "0034 600 123 123"))
        assertNull(ruleFor(rules, "600123124"))
        // Un número corto solo casa tal cual
        val cortos = withRule(emptyMap(), "1004", RULE_APP)
        assertEquals(RULE_APP, ruleFor(cortos, "1004"))
        assertNull(ruleFor(cortos, "11004"))
    }

    @Test
    fun `cambiar la elección de un número sustituye la anterior y quitarla deja los demás`() {
        var rules = withRule(emptyMap(), "600123123", RULE_SIM)
        rules = withRule(rules, "911222333", RULE_APP)
        rules = withRule(rules, "+34600123123", RULE_APP)
        assertEquals(mapOf("911222333" to RULE_APP, "600123123" to RULE_APP), rules)
        rules = withRule(rules, "600 123 123", null)
        assertEquals(mapOf("911222333" to RULE_APP), rules)
    }

    @Test
    fun `las elecciones se guardan y se leen, y lo que no se entiende se ignora`() {
        val rules = mapOf("600123123" to RULE_SIM, "911222333" to RULE_APP)
        assertEquals("600123123=sim;911222333=app", serializeRules(rules))
        assertEquals(rules, parseRules(serializeRules(rules)))
        assertEquals(emptyMap<String, String>(), parseRules(""))
        assertEquals(mapOf("600123123" to RULE_SIM), parseRules("600123123=sim;abc=sim;911222333=otra;=app;;"))
    }
}
