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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo se ofrece, una vez por instalación, «Llamar con MundoPhone desde el teléfono». */
class CallRedirectionOfferTest {
    private fun ofrece(
        yaOfrecido: Boolean = false,
        guardado: String = "",
        rolExiste: Boolean = true,
        rolConcedido: Boolean = false,
        cuentaRegistrada: Boolean = true,
        llamadas: Int = 0
    ) = CallRedirectionOffer.shouldOffer(yaOfrecido, guardado, rolExiste, rolConcedido, cuentaRegistrada, llamadas)

    @Test
    fun `con una cuenta registrada y sin nada elegido se ofrece`() {
        assertTrue(ofrece())
    }

    @Test
    fun `solo una vez por instalación`() {
        assertFalse(ofrece(yaOfrecido = true))
    }

    @Test
    fun `si ya eligió algo en Configuración no se le pregunta, tampoco si eligió nunca`() {
        assertFalse(ofrece(guardado = CallRedirection.MODE_NEVER))
        assertFalse(ofrece(guardado = CallRedirection.MODE_ASK))
        assertFalse(ofrece(guardado = CallRedirection.MODE_ALWAYS))
    }

    @Test
    fun `si ya tiene el permiso o el aparato no lo trae, no hay nada que ofrecer`() {
        assertFalse(ofrece(rolConcedido = true))
        assertFalse(ofrece(rolExiste = false))
    }

    @Test
    fun `sin cuenta registrada o en mitad de una llamada, todavía no`() {
        assertFalse(ofrece(cuentaRegistrada = false))
        assertFalse(ofrece(llamadas = 1))
    }
}
