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
package org.linphone.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Criterio de Equipo: dirección SIP cuyo usuario no es un número. */
class TeamTest {
    @Test
    fun `sip con usuario no numérico es del equipo`() {
        assertTrue(Team.isTeamAddress("sip", "jmartin"))
        assertTrue(Team.isTeamAddress("sips", "ana.garcia"))
        assertTrue(Team.isTeamAddress("SIP", "recepcion"))
        // Letras y cifras: no es un número
        assertTrue(Team.isTeamAddress("sip", "ventas2"))
    }

    @Test
    fun `un teléfono con el más delante no es del equipo`() {
        assertFalse(Team.isTeamAddress("sip", "+34600123123"))
    }

    @Test
    fun `un número o una extensión numérica no son del equipo`() {
        assertFalse(Team.isTeamAddress("sip", "600123123"))
        assertFalse(Team.isTeamAddress("sip", "1001"))
    }

    @Test
    fun `sin esquema sip o sin usuario no es del equipo`() {
        assertFalse(Team.isTeamAddress("tel", "jmartin"))
        assertFalse(Team.isTeamAddress(null, "jmartin"))
        assertFalse(Team.isTeamAddress("sip", ""))
        assertFalse(Team.isTeamAddress("sip", null))
    }
}
