/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import android.text.InputFilter
import android.text.Spanned
import android.widget.EditText
import androidx.databinding.BindingAdapter

/*
 * MundoPhone: el texto que se pega desde otras apps trae caracteres invisibles que el SDK no reconoce, y con ellos la
 * búsqueda no encuentra el contacto y la sugerencia sale rota («%e2%80%aa+34…»), aunque en pantalla se vea igual:
 * WhatsApp e iOS rodean los teléfonos copiados con marcas de dirección (U+202A … U+202C) y muchas webs y apps separan
 * las cifras con espacios duros (U+00A0, U+202F). Los caracteres de formato (categoría Cf) se quitan y cualquier
 * espacio raro (categoría Zs) pasa a espacio normal. Igual que String.sinInvisibles de iOS.
 */

private fun Char.esInvisible(): Boolean = Character.getType(this) == Character.FORMAT.toInt()

private fun Char.esEspacioRaro(): Boolean =
    this != ' ' && Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

fun CharSequence.sinInvisibles(): String {
    val limpio = StringBuilder(length)
    for (c in this) {
        when {
            c.esInvisible() -> Unit
            c.esEspacioRaro() -> limpio.append(' ')
            else -> limpio.append(c)
        }
    }
    return limpio.toString()
}

/** Limpia lo que entra en el campo, se pegue o se escriba, sin mover el cursor (a diferencia de reescribir el texto) */
object FiltroSinInvisibles : InputFilter {
    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        val trozo = source.subSequence(start, end)
        if (trozo.none { it.esInvisible() || it.esEspacioRaro() }) return null
        return trozo.sinInvisibles()
    }
}

/** En los buscadores: app:sinInvisibles="@{true}" */
@BindingAdapter("sinInvisibles")
fun EditText.setSinInvisibles(activo: Boolean) {
    if (activo && filters.none { it === FiltroSinInvisibles }) {
        filters = filters + FiltroSinInvisibles
    }
}
