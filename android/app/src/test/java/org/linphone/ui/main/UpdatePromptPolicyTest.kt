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
package org.linphone.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El aviso de actualización: solo se calla si el usuario ignoró esa misma versión hoy. */
class UpdatePromptPolicyTest {
    @Test
    fun `sin nada ignorado se ofrece`() {
        assertTrue(UpdatePromptPolicy.shouldOffer(3010001, 20000, 0, -1))
    }

    @Test
    fun `ignorada hoy, esa misma no se vuelve a ofrecer`() {
        assertFalse(UpdatePromptPolicy.shouldOffer(3010001, 20000, 3010001, 20000))
    }

    @Test
    fun `ignorada ayer, hoy se vuelve a ofrecer`() {
        assertTrue(UpdatePromptPolicy.shouldOffer(3010001, 20001, 3010001, 20000))
    }

    @Test
    fun `una versión más nueva que la ignorada se ofrece el mismo día`() {
        assertTrue(UpdatePromptPolicy.shouldOffer(3010002, 20000, 3010001, 20000))
    }

    @Test
    fun `un cierre en los primeros segundos no cuenta como ignorada`() {
        // Visto en un móvil: 0,6 s a la vista (oculta 43,656, cerrado 44,225), 1,5 s desde que se pidió
        assertFalse(UpdatePromptPolicy.countsAsDeclined(569L))
        assertFalse(UpdatePromptPolicy.countsAsDeclined(1476L))
        assertFalse(UpdatePromptPolicy.countsAsDeclined(1999L))
        assertFalse(UpdatePromptPolicy.countsAsDeclined(UpdatePromptPolicy.ACCIDENTAL_MS - 1))
        assertTrue(UpdatePromptPolicy.countsAsDeclined(UpdatePromptPolicy.ACCIDENTAL_MS))
        assertTrue(UpdatePromptPolicy.countsAsDeclined(2500L))
    }

    @Test
    fun `el aviso de descarga va de 10 en 10 y nunca dice 100`() {
        assertEquals(-1, UpdatePromptPolicy.downloadStep(0L, 0L)) // Play aún no sabe el tamaño
        assertEquals(-1, UpdatePromptPolicy.downloadStep(5L, -1L))
        assertEquals(0, UpdatePromptPolicy.downloadStep(0L, 52_000_000L))
        assertEquals(0, UpdatePromptPolicy.downloadStep(5_199_999L, 52_000_000L))
        assertEquals(10, UpdatePromptPolicy.downloadStep(5_200_000L, 52_000_000L))
        assertEquals(40, UpdatePromptPolicy.downloadStep(25_000_000L, 52_000_000L))
        assertEquals(90, UpdatePromptPolicy.downloadStep(51_999_999L, 52_000_000L))
        assertEquals(90, UpdatePromptPolicy.downloadStep(52_000_000L, 52_000_000L))
        assertEquals(90, UpdatePromptPolicy.downloadStep(60_000_000L, 52_000_000L)) // por si Play se pasa
    }
}
