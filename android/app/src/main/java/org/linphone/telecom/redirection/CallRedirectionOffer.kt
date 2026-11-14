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

/**
 * MundoPhone: el ofrecimiento, UNA vez por instalación, de «Llamar con MundoPhone desde el teléfono» (MainActivity). Android
 * no deja que la app se dé el rol a sí misma: hace falta que el usuario lo acepte en una ventana del sistema, y si solo
 * está en Configuración > Llamadas casi nadie lo encuentra. Sin Android, para probarlo en la JVM.
 */
object CallRedirectionOffer {
    /**
     * Se ofrece si no se ha ofrecido antes, si el usuario no ha elegido nada (tampoco «nunca»), si el rol existe en el
     * aparato y no lo tenemos, si hay al menos una cuenta registrada (sin ella la llamada iría por la SIM igual) y si no
     * hay ninguna llamada en curso.
     */
    fun shouldOffer(
        alreadyOffered: Boolean,
        storedMode: String,
        roleAvailable: Boolean,
        roleHeld: Boolean,
        accountRegistered: Boolean,
        calls: Int
    ): Boolean = !alreadyOffered &&
        storedMode.isEmpty() &&
        roleAvailable &&
        !roleHeld &&
        accountRegistered &&
        calls == 0
}
