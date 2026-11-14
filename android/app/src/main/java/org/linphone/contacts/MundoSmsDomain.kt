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

import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Address

/**
 * Los contactos de la centralita van en el dominio de la identidad de nuestras cuentas ([corePreferences.mundosmsDomain],
 * push.mundosms.es). Sin reg_route ni Service-Route, liblinphone solo manda la llamada por el proxy (push:6060, el
 * borde de las apps) si el dominio del destino es el de la identidad (`make_routes_for_account`, linphonecore.c): un
 * contacto @voip.mundosms.es saldría directo al DNS de voip, por UDP 5060 y fuera del borde. La centralita, en cambio,
 * llama desde @voip. No quitarlo mientras la identidad sea @push y no haya ruta.
 */
object MundoSmsDomain {
    private const val PBX_DOMAIN = "voip.mundosms.es"
    private const val LINPHONE_DOMAIN = "sip.linphone.org"

    /** Al guardar un contacto: @voip (y @sip.linphone.org, el dominio por defecto de upstream) pasan al nuestro. */
    @WorkerThread
    fun forSaving(address: Address): Address {
        return if (address.domain == PBX_DOMAIN || address.domain == LINPHONE_DOMAIN) {
            address.clone().apply { domain = corePreferences.mundosmsDomain }
        } else {
            address
        }
    }

    /** Al buscar quién llama: la centralita llama desde @voip y los contactos están en el nuestro. Sobre una copia. */
    @WorkerThread
    fun forLookup(address: Address): Address {
        return if (address.domain == PBX_DOMAIN) {
            address.clone().apply { domain = corePreferences.mundosmsDomain }
        } else {
            address
        }
    }
}
