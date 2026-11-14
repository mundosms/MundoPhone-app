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

import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import org.linphone.core.Address
import org.linphone.core.Friend

/**
 * «Equipo»: los compañeros de la centralita, o sea los contactos con una dirección SIP cuyo usuario NO es un número
 * (ana@…, no 600123123@… ni 1001@…). Es el único criterio: lo usan la pestaña Equipo, la lista de Contactos (que
 * no repite a quien solo es del equipo), el selector de direcciones (Transferir…) y la presencia (A6).
 */
object Team {
    @AnyThread
    fun isTeamAddress(address: Address): Boolean = isTeamAddress(address.scheme, address.username)

    /** El criterio con el esquema y el usuario de la dirección, sin el SDK (lo prueban las pruebas de JVM). */
    @AnyThread
    fun isTeamAddress(scheme: String?, username: String?): Boolean {
        if (!scheme.equals("sip", ignoreCase = true) && !scheme.equals("sips", ignoreCase = true)) return false
        return !username.isNullOrEmpty() && !isNumber(username)
    }

    @WorkerThread
    fun isTeamFriend(friend: Friend): Boolean = friend.addresses.any { isTeamAddress(it) }

    /** La extensión (usuario SIP, en minúsculas) del compañero: la de su primera dirección del equipo. */
    @WorkerThread
    fun teamUser(friend: Friend): String? =
        friend.addresses.firstOrNull { isTeamAddress(it) }?.username?.lowercase(java.util.Locale.ROOT)

    /** Del equipo y nada más (sin teléfonos ni direcciones numéricas): no se repite en Contactos. */
    @WorkerThread
    fun isOnlyTeam(friend: Friend): Boolean =
        friend.phoneNumbers.isEmpty() && friend.addresses.isNotEmpty() && friend.addresses.all { isTeamAddress(it) }

    // Un «+» delante como mucho y el resto cifras: un teléfono o una extensión numérica
    private fun isNumber(username: String): Boolean {
        val digits = username.removePrefix("+")
        return digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }
}
