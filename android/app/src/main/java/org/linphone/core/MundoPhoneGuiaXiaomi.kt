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

/**
 * MundoPhone: el orden de la guía de Xiaomi (MainActivity.showBackgroundGuide), sin nada de Android para poder probarlo.
 * La guía enseña todos los ajustes con su estado y un solo botón, el del siguiente que falta; al volver de los ajustes se
 * vuelve a abrir con el estado nuevo y el botón del siguiente.
 */
object MundoPhoneGuiaXiaomi {
    /** Los ajustes a los que lleva la guía, en el orden en que se enseñan. */
    enum class Ajuste { INICIO_AUTOMATICO, BATERIA, PANTALLA_COMPLETA, OTROS_PERMISOS }

    /**
     * El siguiente ajuste al que llevar al usuario, o null si no queda ninguno. Cada uno llega con su lectura: true (bien),
     * false (mal) o null (no se puede saber). Primero los que se leen mal, en su orden, y después los que no se pueden leer.
     * Nunca uno ya abierto en esta pasada de la guía: una lectura que no cambia (en algunos Xiaomi «Inicio automático» no se
     * puede leer nunca) no puede dejar al usuario dando vueltas en el mismo paso.
     */
    fun siguiente(lecturas: List<Pair<Ajuste, Boolean?>>, abiertos: Set<Ajuste>): Ajuste? {
        val pendientes = lecturas.filter { (ajuste, bien) -> bien != true && ajuste !in abiertos }
        return (pendientes.firstOrNull { it.second == false } ?: pendientes.firstOrNull())?.first
    }

    /**
     * «Otros permisos» de Xiaomi junta dos lecturas («Mostrar en pantalla de bloqueo» y «Mostrar ventanas emergentes
     * mientras se ejecuta en segundo plano»): mal si alguna se lee mal, bien si las dos se leen bien; si no, no se sabe.
     */
    fun juntar(a: Boolean?, b: Boolean?): Boolean? = when {
        a == false || b == false -> false
        a == true && b == true -> true
        else -> null
    }
}
