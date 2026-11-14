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

import androidx.annotation.AnyThread
import com.google.firebase.messaging.FirebaseMessaging
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.AndroidPlatformHelper
import org.linphone.core.tools.Log

/**
 * MundoPhone: token de Firebase nuevo. Google puede dar por muerto el token que guarda la app sin que el móvil reciba
 * otro (clon del móvil al reaparecer el viejo, MundoPhoneIdentidad): el borde manda los avisos de llamada con él, FCM
 * contesta NotRegistered y la app no se entera. Se borra el token, se pide otro y se le pasa al SDK, que lo pone en las
 * cuentas (pn-prid) y vuelve a registrarse. Lo usan la detección de clones al arrancar y Ayuda > Solución de problemas.
 * El token no se escribe en el registro (solo su longitud).
 */
object MundoPhonePushToken {
    private const val TAG = "[MundoPhone Push Token]"

    @Volatile
    private var enCurso = false

    @AnyThread
    fun renovar(motivo: String, alTerminar: ((Boolean) -> Unit)? = null) {
        if (enCurso) {
            Log.w("$TAG Already renewing the push token, ignoring [$motivo]")
            return
        }
        enCurso = true
        Log.w("$TAG Renewing the push token: $motivo")
        val fin = { ok: Boolean ->
            enCurso = false
            alTerminar?.invoke(ok)
        }
        try {
            val firebase = FirebaseMessaging.getInstance()
            firebase.deleteToken().addOnCompleteListener { borrado ->
                if (!borrado.isSuccessful) {
                    Log.w("$TAG Couldn't delete the old push token: ${borrado.exception}")
                }
                firebase.token.addOnCompleteListener { nuevo ->
                    val token = if (nuevo.isSuccessful) nuevo.result else null
                    if (token.isNullOrEmpty()) {
                        Log.e("$TAG Couldn't get a new push token: ${nuevo.exception}")
                        fin(false)
                        return@addOnCompleteListener
                    }
                    Log.i("$TAG New push token received ([${token.length}] chars)")
                    coreContext.postOnCoreThread { core ->
                        if (AndroidPlatformHelper.isReady()) {
                            AndroidPlatformHelper.instance().setPushToken(token)
                        }
                        core.refreshRegisters()
                        fin(true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("$TAG Firebase not available: $e")
            fin(false)
        }
    }
}
