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
import org.linphone.utils.DireccionVisible.bajoNombre
import org.linphone.utils.DireccionVisible.esNumero
import org.linphone.utils.DireccionVisible.extension
import org.linphone.utils.DireccionVisible.paraMostrar
import org.linphone.utils.DireccionVisible.repiteNombre

/** Lo que se enseña de una dirección SIP: la extensión, y nada bajo un nombre que ya lo es. */
class DireccionVisibleTest {
    @Test
    fun `la extension es la parte de usuario sin esquema, dominio, puerto ni parametros`() {
        assertEquals("200", extension("sip:200@push.mundosms.es"))
        assertEquals("200", extension("sips:200@voip.mundosms.es:5061;transport=tls"))
        assertEquals("200", extension("SIP:200@push.mundosms.es;transport=udp"))
        assertEquals("200", extension("200@push.mundosms.es"))
        assertEquals("recepcion", extension("sip:recepcion@push.mundosms.es?subject=hola"))
        assertEquals("200", extension("\"Juan Pérez\" <sip:200@push.mundosms.es;transport=tcp>;tag=abc"))
    }

    @Test
    fun `un numero externo es el numero`() {
        assertEquals("+34600123123", extension("sip:+34600123123@push.mundosms.es;user=phone"))
        assertEquals("+34600123123", extension("tel:+34600123123"))
        assertEquals("+34600123123", extension("sip:+34600123123;phone-context=mundosms@push.mundosms.es"))
    }

    @Test
    fun `sin usuario queda el dominio y nunca el puerto`() {
        assertEquals("push.mundosms.es", extension("sip:push.mundosms.es:5060;transport=udp"))
        assertEquals("[2001:db8::1]", extension("sip:[2001:db8::1]:5060"))
        assertEquals("200", extension("200"))
        assertEquals("", extension(""))
        assertEquals("", extension(null))
    }

    @Test
    fun `con la preferencia se ensena el usuario y sin ella la direccion limpia`() {
        assertEquals("200", paraMostrar("200", "sip:200@push.mundosms.es", true))
        assertEquals("sip:200@push.mundosms.es", paraMostrar("200", "sip:200@push.mundosms.es", false))
        assertEquals("push.mundosms.es", paraMostrar(null, "sip:push.mundosms.es", true))
        assertEquals("push.mundosms.es", paraMostrar(" ", "sip:push.mundosms.es", true))
    }

    @Test
    fun `nombre igual a la extension sin contar mayusculas ni espacios repite`() {
        assertTrue(repiteNombre("200", "200"))
        assertTrue(repiteNombre("Recepcion", "recepcion"))
        assertTrue(repiteNombre(" 2 00 ", "200"))
        assertFalse(repiteNombre("Juan Pérez", "200"))
        assertFalse(repiteNombre("200", "201"))
    }

    @Test
    fun `el mismo numero escrito de otra forma repite`() {
        // getDisplayName enseña un número sin contacto sin el +34
        assertTrue(repiteNombre("600123123", "+34600123123", "+34"))
        assertTrue(repiteNombre("600 12 31 23", "+34600123123", "+34"))
        assertTrue(repiteNombre("600123123", "+34600123123"))
        assertFalse(repiteNombre("600123124", "+34600123123", "+34"))
        // Un nombre que lleva un número no es ese número
        assertFalse(repiteNombre("Juan 600123123", "+34600123123", "+34"))
        // Las extensiones cortas no son números de teléfono: solo valen iguales
        assertFalse(repiteNombre("0200", "200"))
    }

    @Test
    fun `sin nombre se ensena la extension porque es lo unico que queda`() {
        assertFalse(repiteNombre("", "200"))
        assertFalse(repiteNombre(null, "200"))
        assertEquals("200", bajoNombre("", "200"))
        assertEquals("200", bajoNombre(null, "200"))
    }

    @Test
    fun `bajo el nombre va la extension o nada`() {
        assertEquals("200", bajoNombre("Juan Pérez", "200"))
        assertEquals("", bajoNombre("200", "200"))
        assertEquals("", bajoNombre("600123123", "+34600123123", "+34"))
        assertEquals("", bajoNombre("Juan", ""))
        assertEquals("", bajoNombre("Juan", null))
        // Sin la preferencia la dirección completa nunca coincide con el nombre: se enseña como upstream
        assertEquals("sip:200@push.mundosms.es", bajoNombre("200", "sip:200@push.mundosms.es"))
    }

    @Test
    fun `una extension numerica es un telefono, con o sin el mas`() {
        assertTrue(esNumero("600123123"))
        assertTrue(esNumero("+34600123123"))
        assertTrue(esNumero("200"))
        assertTrue(esNumero(extension("sip:+34600123123@push.mundosms.es")))
        assertFalse(esNumero("recepcion"))
        assertFalse(esNumero("200a"))
        assertFalse(esNumero("+"))
        assertFalse(esNumero(""))
        assertFalse(esNumero(null))
        assertFalse(esNumero("600 12 31 23"))
    }
}
