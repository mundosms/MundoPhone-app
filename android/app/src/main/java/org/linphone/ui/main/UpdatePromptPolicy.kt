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
package org.linphone.ui.main

/** MundoPhone: cuándo se ofrece una actualización flexible de Play (AppUpdateChecker). Sin Android, para probarlo en la JVM. */
object UpdatePromptPolicy {
    /**
     * Se ofrece [available] salvo que el usuario haya ignorado esa misma versión hoy ([declinedCode] el [declinedDay]).
     * Aceptar no cuenta, y una versión distinta de la ignorada se ofrece al momento.
     */
    fun shouldOffer(available: Int, today: Long, declinedCode: Int, declinedDay: Long): Boolean =
        !(available == declinedCode && today == declinedDay)

    /**
     * Play devuelve lo mismo («cancelado») por «No, gracias», por atrás y por tocar fuera, y su diálogo no se puede hacer
     * obligatorio. Un cierre en los primeros [ACCIDENTAL_MS] con el diálogo a la vista (desde que oculta la app) no da
     * tiempo a leerlo: se toma por un toque sin querer y no cuenta como ignorada (visto en un
     * móvil: 0,6 s a la vista al estar arrastrando, y ya no se volvía a ofrecer en todo el día).
     */
    fun countsAsDeclined(shownForMs: Long): Boolean = shownForMs >= ACCIDENTAL_MS

    const val ACCIDENTAL_MS = 2000L
}
