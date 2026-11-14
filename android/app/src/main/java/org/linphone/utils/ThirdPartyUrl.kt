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

import android.content.Intent
import androidx.annotation.AnyThread
import androidx.core.net.toUri

/**
 * MundoPhone (A10): la URL de la ficha del cliente que manda la centralita en el INVITE (X-3partyurl).
 *
 * Aquí está la parte que solo mira el texto, para probarla en la JVM (app/src/test); la comprobación final (que tenga
 * host, con android.net.Uri) la hace CurrentCallViewModel.safeThirdPartyUrl.
 */
object ThirdPartyUrl {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d)")

    /**
     * http:// y https:// tal cual (el que abre es el navegador, y un CRM interno puede no tener https),
     * «http//» y «https//» sin los dos puntos se corrigen, y sin esquema se le pone https:// (también con puerto:
     * «crm.empresa.com:8080/ficha»). Lo único nuevo: cualquier otro esquema (javascript:, intent:, file:…) se descarta,
     * porque la URL viene de la cabecera de quien llama y se abre con un toque. null = sin botón.
     */
    @AnyThread
    fun candidate(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        // Un prefijo «algo:» que no va seguido de una cifra es un esquema; «host:8080» es un puerto
        val tieneEsquema = schemePrefix.containsMatchIn(value)
        return when {
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("https//", ignoreCase = true) -> "https://" + value.substring("https//".length)
            value.startsWith("http//", ignoreCase = true) -> "http://" + value.substring("http//".length)
            !tieneEsquema -> "https://$value"
            else -> null
        }
    }

    /**
     * La URL lista para abrir: la de [candidate] si es http(s) y tiene host; si no, null (sin botón). Usa
     * android.net.Uri, así que no va en las pruebas de JVM. La usan la pantalla de llamada (entrante y activa) y la
     * notificación.
     */
    @AnyThread
    fun paraAbrir(raw: String?): String? {
        val candidate = candidate(raw) ?: return null
        val uri = candidate.toUri()
        val scheme = uri.scheme?.lowercase()
        return if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()) candidate else null
    }

    /** El Intent que abre la ficha en el navegador por defecto del usuario. */
    @AnyThread
    fun intentParaAbrir(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
