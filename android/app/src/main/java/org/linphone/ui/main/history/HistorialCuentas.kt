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

import androidx.annotation.AnyThread

/**
 * MundoPhone: de qué cuentas son las llamadas de Recientes. Por defecto de todas; con el embudo «Esta
 * cuenta», solo las de la cuenta por defecto ([ui] historial_todas_las_cuentas). Lo usan la lista, «Borrar historial» y
 * el detalle de una llamada, para que los tres enseñen y borren lo mismo. Sin SDK para poder probarlo en la JVM.
 */
object HistorialCuentas {
    /**
     * ¿Se enseñan (y se borran) las llamadas de todas las cuentas? Sí con «Todas las cuentas»; y también con una sola
     * cuenta, que es el apaño de upstream (las llamadas guardadas con otra identidad local no casan con la cuenta y el
     * historial salía vacío), o sin cuenta por defecto, porque no hay «esta cuenta».
     */
    @AnyThread
    fun todas(todasLasCuentas: Boolean, numeroDeCuentas: Int, hayCuentaPorDefecto: Boolean): Boolean {
        return todasLasCuentas || numeroDeCuentas <= 1 || !hayCuentaPorDefecto
    }
}
