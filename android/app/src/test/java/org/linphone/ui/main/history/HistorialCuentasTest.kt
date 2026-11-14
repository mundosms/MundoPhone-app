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
package org.linphone.ui.main.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.ui.main.history.HistorialCuentas.todas

/** Recientes con las llamadas de todas las cuentas por defecto y «Esta cuenta» como opción. */
class HistorialCuentasTest {
    @Test
    fun `con todas las cuentas se ensenan todas`() {
        assertTrue(todas(todasLasCuentas = true, numeroDeCuentas = 3, hayCuentaPorDefecto = true))
        assertTrue(todas(todasLasCuentas = true, numeroDeCuentas = 1, hayCuentaPorDefecto = true))
    }

    @Test
    fun `con esta cuenta y varias cuentas solo la por defecto`() {
        assertFalse(todas(todasLasCuentas = false, numeroDeCuentas = 2, hayCuentaPorDefecto = true))
    }

    @Test
    fun `con una sola cuenta el filtro no cambia nada`() {
        assertTrue(todas(todasLasCuentas = false, numeroDeCuentas = 1, hayCuentaPorDefecto = true))
        assertTrue(todas(todasLasCuentas = false, numeroDeCuentas = 0, hayCuentaPorDefecto = false))
    }

    @Test
    fun `sin cuenta por defecto no hay esta cuenta`() {
        assertTrue(todas(todasLasCuentas = false, numeroDeCuentas = 2, hayCuentaPorDefecto = false))
    }
}
