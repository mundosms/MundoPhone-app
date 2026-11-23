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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.core.MundoPhoneGuiaXiaomi.Ajuste

class MundoPhoneGuiaXiaomiTest {
    private fun lecturas(
        inicio: Boolean?,
        bateria: Boolean?,
        pantalla: Boolean?,
        otros: Boolean?
    ) = listOf(
        Ajuste.INICIO_AUTOMATICO to inicio,
        Ajuste.BATERIA to bateria,
        Ajuste.PANTALLA_COMPLETA to pantalla,
        Ajuste.OTROS_PERMISOS to otros
    )

    @Test
    fun `todo bien no deja paso`() {
        assertNull(MundoPhoneGuiaXiaomi.siguiente(lecturas(true, true, true, true), emptySet()))
    }

    @Test
    fun `lo que se lee mal va antes que lo que no se puede leer`() {
        // Un móvil sin lectura de «Inicio automático», con la batería optimizada y sin pantalla completa
        val l = lecturas(null, false, false, null)
        assertEquals(Ajuste.BATERIA, MundoPhoneGuiaXiaomi.siguiente(l, emptySet()))
        assertEquals(Ajuste.PANTALLA_COMPLETA, MundoPhoneGuiaXiaomi.siguiente(l, setOf(Ajuste.BATERIA)))
        assertEquals(Ajuste.INICIO_AUTOMATICO, MundoPhoneGuiaXiaomi.siguiente(l, setOf(Ajuste.BATERIA, Ajuste.PANTALLA_COMPLETA)))
    }

    @Test
    fun `una lectura que no cambia no repite el paso`() {
        val l = lecturas(null, true, true, null)
        assertEquals(Ajuste.INICIO_AUTOMATICO, MundoPhoneGuiaXiaomi.siguiente(l, emptySet()))
        assertEquals(Ajuste.OTROS_PERMISOS, MundoPhoneGuiaXiaomi.siguiente(l, setOf(Ajuste.INICIO_AUTOMATICO)))
        assertNull(MundoPhoneGuiaXiaomi.siguiente(l, setOf(Ajuste.INICIO_AUTOMATICO, Ajuste.OTROS_PERMISOS)))
    }

    @Test
    fun `un ajuste abierto que sigue mal no vuelve a salir en la misma pasada`() {
        val l = lecturas(true, false, true, true)
        assertEquals(Ajuste.BATERIA, MundoPhoneGuiaXiaomi.siguiente(l, emptySet()))
        assertNull(MundoPhoneGuiaXiaomi.siguiente(l, setOf(Ajuste.BATERIA)))
    }

    @Test
    fun `sin el paso de pantalla completa (Android 13 o anterior) no se propone`() {
        val l = listOf(Ajuste.INICIO_AUTOMATICO to true, Ajuste.BATERIA to true, Ajuste.OTROS_PERMISOS to false)
        assertEquals(Ajuste.OTROS_PERMISOS, MundoPhoneGuiaXiaomi.siguiente(l, emptySet()))
    }

    @Test
    fun `otros permisos junta las dos lecturas`() {
        assertEquals(true, MundoPhoneGuiaXiaomi.juntar(true, true))
        assertEquals(false, MundoPhoneGuiaXiaomi.juntar(true, false))
        assertEquals(false, MundoPhoneGuiaXiaomi.juntar(null, false))
        assertEquals(false, MundoPhoneGuiaXiaomi.juntar(false, null))
        assertNull(MundoPhoneGuiaXiaomi.juntar(true, null))
        assertNull(MundoPhoneGuiaXiaomi.juntar(null, null))
    }
}
