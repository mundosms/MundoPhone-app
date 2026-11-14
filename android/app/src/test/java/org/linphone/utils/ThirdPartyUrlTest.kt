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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL de la ficha del cliente (A10, X-3partyurl). La comprobación final de que tenga host la hace
 * CurrentCallViewModel.safeThirdPartyUrl con android.net.Uri, que no está en la JVM.
 */
class ThirdPartyUrlTest {
    private fun url(raw: String?) = ThirdPartyUrl.candidate(raw)

    @Test
    fun `http y https se quedan tal cual`() {
        assertEquals("https://crm.empresa.com/ficha?id=7", url("https://crm.empresa.com/ficha?id=7"))
        assertEquals("http://crm.interno/ficha", url("http://crm.interno/ficha"))
        assertEquals("HTTPS://CRM.EMPRESA.COM", url("HTTPS://CRM.EMPRESA.COM"))
        assertEquals("https://crm.empresa.com", url("  https://crm.empresa.com  "))
    }

    @Test
    fun `sin los dos puntos se corrige`() {
        assertEquals("http://crm.interno/ficha", url("http//crm.interno/ficha"))
        assertEquals("https://crm.empresa.com/ficha", url("https//crm.empresa.com/ficha"))
        assertEquals("https://crm.empresa.com", url("HTTPS//crm.empresa.com"))
    }

    @Test
    fun `sin esquema se le pone https`() {
        assertEquals("https://crm.empresa.com/ficha?id=7", url("crm.empresa.com/ficha?id=7"))
        // Un «http:» más adelante no es el esquema
        assertEquals("https://crm.empresa.com/buscar?u=http://otro", url("crm.empresa.com/buscar?u=http://otro"))
    }

    @Test
    fun `sin esquema y con puerto también se le pone https`() {
        assertEquals("https://crm.empresa.com:8080/ficha", url("crm.empresa.com:8080/ficha"))
        assertEquals("https://localhost:8080", url("localhost:8080"))
        assertEquals("https://192.168.1.10:8443/ficha", url("192.168.1.10:8443/ficha"))
    }

    @Test
    fun `otros esquemas se descartan`() {
        assertNull(url("javascript:alert(1)"))
        assertNull(url("intent://ficha#Intent;scheme=http;end"))
        assertNull(url("file:///sdcard/ficha.html"))
        assertNull(url("content://es.mundosms.fileprovider/ficha"))
        assertNull(url("ftp://crm.empresa.com/ficha"))
        assertNull(url("mailto:ventas@empresa.com"))
        // Con una sola barra no es «http//» ni «https://»
        assertNull(url("https:/crm.empresa.com"))
    }

    @Test
    fun `vacío no da botón`() {
        assertNull(url(null))
        assertNull(url(""))
        assertNull(url("   "))
    }
}
