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

import androidx.annotation.AnyThread
import java.util.Locale

/**
 * MundoPhone: lo que se ENSEÑA de una dirección SIP. Todas las cuentas son extensiones de una
 * centralita de MundoSMS, así que se ve la extensión (la parte de usuario: «200», «recepcion», «+34600123123»), nunca
 * «sip:», «@dominio», «;transport=» ni el puerto; y bajo un nombre, nada si ese nombre ya es la extensión.
 *
 * Copiar copia lo que se ve; llamar, enviar o guardar un contacto siguen usando la dirección completa.
 * Sin Android ni SDK para poder probarlo en la JVM; LinphoneUtils.getDisplayedAddress lo aplica a un Address con la
 * preferencia [ui] only_display_sip_uri_username.
 */
object DireccionVisible {
    private val ESQUEMAS = setOf("sip", "sips", "tel")

    /**
     * La extensión de una dirección escrita: «"Juan" <sips:200@push.mundosms.es:5061;transport=tls>» → «200». Sin parte
     * de usuario, el dominio sin puerto ni parámetros. Vacía si no hay nada.
     */
    @AnyThread
    fun extension(uri: String?): String {
        if (uri.isNullOrBlank()) return ""
        var resto = uri.trim()

        // Con nombre delante, lo que va entre los ángulos
        val abre = resto.indexOf('<')
        if (abre >= 0) {
            val cierra = resto.indexOf('>', abre + 1)
            resto = if (cierra > abre) resto.substring(abre + 1, cierra) else resto.substring(abre + 1)
        }

        val dosPuntos = resto.indexOf(':')
        if (dosPuntos > 0 && resto.substring(0, dosPuntos).lowercase(Locale.ROOT) in ESQUEMAS) {
            resto = resto.substring(dosPuntos + 1)
        }
        resto = resto.substringBefore('?')

        val arroba = resto.indexOf('@')
        if (arroba > 0) {
            // La parte de usuario, sin sus parámetros (;phone-context=…)
            return resto.substring(0, arroba).substringBefore(';').trim()
        }

        // Sin usuario: un número (tel:) o solo el dominio, sin parámetros ni puerto
        resto = resto.removePrefix("@").substringBefore(';').trim()
        if (resto.startsWith("[")) return resto.substringBefore(']') + "]" // IPv6
        return resto.substringBefore(':')
    }

    /**
     * Lo que se enseña de una dirección: con [soloExtension] su [usuario] (o, si no tiene, el dominio de [uriLimpia]);
     * si no, [uriLimpia] entera, como upstream.
     */
    @AnyThread
    fun paraMostrar(usuario: String?, uriLimpia: String, soloExtension: Boolean): String {
        if (!soloExtension) return uriLimpia
        return usuario?.trim()?.takeIf { it.isNotEmpty() } ?: extension(uriLimpia)
    }

    /**
     * ¿La dirección [mostrada] repite el [nombre] que hay encima? Sí si son iguales sin contar mayúsculas ni espacios, o
     * si los dos son el mismo número escrito de otra forma («600 12 31 23» bajo «+34600123123», que es como
     * getDisplayName enseña un número sin contacto). Con el nombre vacío, no: la extensión es lo único que queda.
     */
    @AnyThread
    fun repiteNombre(nombre: String?, mostrada: String?, prefijoInternacional: String? = null): Boolean {
        if (nombre.isNullOrBlank() || mostrada.isNullOrBlank()) return false
        if (normaliza(nombre) == normaliza(mostrada)) return true

        if (soloNumero(nombre) && soloNumero(mostrada) &&
            PhoneNumberUtils.isPhoneNumberLike(nombre) && PhoneNumberUtils.isPhoneNumberLike(mostrada)
        ) {
            val candidatos = PhoneNumberUtils.getComparablePhoneCandidates(mostrada, prefijoInternacional)
            return PhoneNumberUtils.matchesComparableCandidates(nombre, candidatos, prefijoInternacional)
        }
        return false
    }

    /** La línea de la dirección bajo [nombre]: [mostrada], o vacía si no hay o repite el nombre. */
    @AnyThread
    fun bajoNombre(nombre: String?, mostrada: String?, prefijoInternacional: String? = null): String {
        if (mostrada.isNullOrBlank() || repiteNombre(nombre, mostrada, prefijoInternacional)) return ""
        return mostrada
    }

    /**
     * ¿La extensión es un número de teléfono («600123123», «+34600123123», «200»)? Así se rellena
     * el teléfono del contacto nuevo, también con el «+» delante.
     */
    @AnyThread
    fun esNumero(extension: String?): Boolean {
        val cifras = extension?.trim()?.removePrefix("+") ?: return false
        return cifras.isNotEmpty() && cifras.all { it in '0'..'9' }
    }

    private fun normaliza(texto: String): String = texto.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

    // Un nombre como «Juan 600123123» no es un número aunque lleve uno
    private fun soloNumero(texto: String): Boolean = texto.all { it.isDigit() || it.isWhitespace() || it in "+-()./" }
}
