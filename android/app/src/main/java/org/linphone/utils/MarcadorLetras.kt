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
package org.linphone.utils

import java.text.Normalizer
import java.util.Locale

/**
 * MundoPhone: buscar contactos por las letras del teclado, como el marcador del móvil. «7272» casa con
 * «Papa» y con «Carlos SARAworld» (PAPA y SARA se marcan igual). MagicSearch del SDK solo busca el texto tal cual, así que
 * con cifras solo encontraba números.
 *
 * Casa si alguna palabra del nombre EMPIEZA por las cifras, sin acentos ni mayúsculas (la ñ va al 6, como la n) y con
 * las cifras del nombre como tales («Oficina 2»). Solo a partir de 2 cifras: con una sola casa media agenda.
 */
object MarcadorLetras {
    private val diacriticos = Regex("\\p{Mn}+")
    private val separadores = Regex("[^\\p{L}\\p{Nd}]+")

    /** La cifra del teclado de una letra o cifra (ya sin acentos y en minúscula), o null si no tiene. */
    fun cifraDe(c: Char): Char? = when (c) {
        in '0'..'9' -> c
        'a', 'b', 'c' -> '2'
        'd', 'e', 'f' -> '3'
        'g', 'h', 'i' -> '4'
        'j', 'k', 'l' -> '5'
        'm', 'n', 'o' -> '6'
        'p', 'q', 'r', 's' -> '7'
        't', 'u', 'v' -> '8'
        'w', 'x', 'y', 'z' -> '9'
        else -> null
    }

    /** Las palabras del nombre en cifras del teclado: «Carlos SARAworld» → [227567, 72729673]. */
    fun palabras(nombre: String): List<String> {
        val sinAcentos = Normalizer.normalize(nombre.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(diacriticos, "")
        return sinAcentos.split(separadores)
            .map { palabra -> palabra.mapNotNull { cifraDe(it) }.joinToString("") }
            .filter { it.isNotEmpty() }
    }

    /** ¿El filtro es de los que también buscan por letras? Solo cifras, 2 o más. */
    fun esBusquedaPorLetras(filtro: String): Boolean = filtro.length >= 2 && filtro.all { it in '0'..'9' }

    fun casa(filtro: String, nombre: String?): Boolean {
        if (nombre.isNullOrBlank() || !esBusquedaPorLetras(filtro)) return false
        return palabras(nombre).any { it.startsWith(filtro) }
    }
}
