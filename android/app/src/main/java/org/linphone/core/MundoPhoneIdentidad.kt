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

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.annotation.WorkerThread
import org.linphone.core.tools.Log

/**
 * MundoPhone: cada aparato con su propia identidad, también si la configuración viene copiada de otro (móvil nuevo
 * clonado del viejo, restauración de una copia).
 *
 * El caso: el móvil nuevo era un clon del viejo, con la app y sus datos copiados. Al volver a
 * encender el viejo, su MundoPhone se registró en el borde con el mismo identificador de aparato (+sip.instance) y en
 * Firebase con la misma instalación; Google invalidó el token que seguía usando el nuevo (NotRegistered) y ese móvil
 * dejó de recibir llamadas sin que la app pudiera enterarse.
 *
 * El SDK ya regenera el +sip.instance si el `ANDROID_ID` guardado en [misc] physical_device_identifier no coincide con el
 * del aparato, pero (1) no renueva el token de Firebase y (2) no puede detectar los clones hechos con versiones que no
 * guardaban esa huella. Aquí: una huella propia en [app] (y, sin ella, la del SDK); si no es la de este
 * aparato, fuera el [misc] uuid (el SDK crea uno nuevo al arrancar) y token nuevo de Firebase en cuanto arranque el Core.
 * `ANDROID_ID` es distinto en cada aparato (y en cada usuario o segundo espacio) y no se copia con los datos.
 */
object MundoPhoneIdentidad {
    private const val TAG = "[MundoPhone Identidad]"

    const val KEY_APARATO = "mundosms_device_id"

    @Volatile
    var renovarTokenAlArrancar = false
        private set

    /**
     * ¿La configuración se creó en otro aparato? Manda la huella propia; sin ella, la del SDK. Sin ninguna (instalación
     * nueva, o una configuración anterior a las dos) no se puede saber: no.
     */
    fun esOtroAparato(huellaApp: String?, huellaSdk: String?, actual: String?): Boolean {
        if (actual.isNullOrEmpty()) return false
        val guardada = huellaApp?.takeIf { it.isNotEmpty() } ?: huellaSdk?.takeIf { it.isNotEmpty() } ?: return false
        return guardada != actual
    }

    /** Con el Core CREADO y SIN arrancar: el SDK lee el [misc] uuid y su huella dentro de core.start(). */
    @WorkerThread
    fun comprobar(context: Context, config: Config) {
        val actual = androidId(context) ?: return
        val huellaApp = config.getString("app", KEY_APARATO, null)
        val huellaSdk = config.getString("misc", "physical_device_identifier", null)
        if (esOtroAparato(huellaApp, huellaSdk, actual)) {
            Log.w("$TAG Configuration comes from another device (clone or restore): new SIP instance and new push token")
            config.cleanEntry("misc", "uuid")
            renovarTokenAlArrancar = true
        }
        if (huellaApp != actual) config.setString("app", KEY_APARATO, actual)
    }

    fun tokenRenovado() {
        renovarTokenAlArrancar = false
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotEmpty() }
}
