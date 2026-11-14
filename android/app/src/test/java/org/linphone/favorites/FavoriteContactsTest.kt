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
package org.linphone.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.favorites.FavoriteContacts.CallableAddress

/** Favoritos del icono y del widget (E4): a qué se llama cuando el contacto tiene varias direcciones o números. */
class FavoriteContactsTest {
    private val nuestroDominio = "push.mundosms.es"

    private fun sip(usuario: String, dominio: String = nuestroDominio) =
        CallableAddress("sip:$usuario@$dominio", "sip", usuario, dominio)

    private fun elige(direcciones: List<CallableAddress>, numeros: List<String>, dominioCuenta: String? = nuestroDominio) =
        FavoriteContacts.chooseCallTarget(direcciones, numeros, dominioCuenta)

    @Test
    fun `con una sola dirección se llama a esa`() {
        assertEquals("sip:1001@push.mundosms.es", elige(listOf(sip("1001")), emptyList()))
    }

    @Test
    fun `con un solo número se llama a ese, tal como está en el contacto`() {
        assertEquals("600 12 31 23", elige(emptyList(), listOf("600 12 31 23")))
    }

    @Test
    fun `con varias opciones gana la dirección del equipo`() {
        assertEquals("sip:jmartin@push.mundosms.es", elige(listOf(sip("1001"), sip("jmartin")), listOf("600123123")))
        // Aunque sea de otro dominio que el de la cuenta
        assertEquals("sip:jmartin@otra.com", elige(listOf(sip("1001"), sip("jmartin", "otra.com")), emptyList()))
    }

    @Test
    fun `sin equipo, la del dominio de la cuenta`() {
        assertEquals(
            "sip:1001@push.mundosms.es",
            elige(listOf(sip("600123123", "sip.otro.com"), sip("1001")), emptyList())
        )
    }

    @Test
    fun `sin equipo ni dominio de la cuenta, la primera dirección`() {
        val direcciones = listOf(sip("600123123", "sip.otro.com"), sip("1001", "sip.otro2.com"))
        assertEquals("sip:600123123@sip.otro.com", elige(direcciones, listOf("611111111")))
        assertEquals("sip:600123123@sip.otro.com", elige(direcciones, emptyList(), dominioCuenta = null))
    }

    @Test
    fun `una dirección y un número, gana la dirección`() {
        assertEquals("sip:600123123@sip.otro.com", elige(listOf(sip("600123123", "sip.otro.com")), listOf("611111111")))
    }

    @Test
    fun `sin direcciones y con varios números, el primero`() {
        assertEquals("600111111", elige(emptyList(), listOf("600111111", "600222222")))
    }

    @Test
    fun `los números en blanco no cuentan`() {
        assertEquals("600123123", elige(emptyList(), listOf(" ", "600123123")))
        assertEquals("sip:1001@push.mundosms.es", elige(listOf(sip("1001")), listOf("", "  ")))
    }

    @Test
    fun `sin nada que marcar no hay favorito`() {
        assertNull(elige(emptyList(), emptyList()))
        assertNull(elige(emptyList(), listOf(" ")))
    }
}
