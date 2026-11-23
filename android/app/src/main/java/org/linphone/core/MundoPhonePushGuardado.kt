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

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import org.linphone.core.tools.Log

/**
 * MundoPhone: el push que llega con el Core sin arrancar, guardado al momento desde el hilo de FCM.
 *
 * El SDK lo guarda en las preferencias «push_notification_storage» («call-id» y «payload») y lo procesa al arrancar el
 * Core (AndroidPlatformHelper.onLinphoneCoreStart), que después lo borra. Pero si el push llega con el Core ya creado y
 * todavía sin arrancar, el SDK lo guarda pasando por el hilo principal: si esa escritura llega después de que el Core lo
 * busque, este arranque no lo procesa (ni arranca el servicio en primer plano del push) y lo procesa el siguiente,
 * viejo. Hasta ahora lo tapaban los 50 ms que upstream esperaba antes de arrancar el Core. Aquí se escribe lo mismo, en el
 * hilo de FCM y antes que el SDK, con una marca de tiempo propia para descartarlo si se queda sin procesar.
 */
object MundoPhonePushGuardado {
    private const val TAG = "[MundoPhone Push Guardado]"
    private const val PREFS = "push_notification_storage" // las del SDK (FirebaseMessaging.storePushRemoteMessage)
    private const val CALL_ID = "call-id"
    private const val PAYLOAD = "payload"
    private const val GUARDADO_EN = "mundophone_guardado_en" // System.currentTimeMillis() al guardarlo (sobrevive a un reinicio)

    // Un push guardado hace más de esto ya no sirve: la llamada que avisaba hace rato que terminó (el borde reintenta
    // con otro push), y procesarlo solo arrancaría el servicio del push para nada
    private const val CADUCA_MS = 60_000L

    @AnyThread
    fun guardar(context: Context, remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val callId = data[CALL_ID].orEmpty()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CALL_ID, callId)
            .putString(PAYLOAD, JSONObject(data).toString())
            .putLong(GUARDADO_EN, System.currentTimeMillis())
            .commit()
        Log.i("$TAG Push for Call-ID [$callId] stored before the Core is On")
    }

    /** En startCore, antes de core.start(): si lo que hay guardado lo guardó la app hace más de CADUCA_MS, fuera. */
    @WorkerThread
    fun descartarViejo(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardadoEn = prefs.getLong(GUARDADO_EN, -1L)
        if (guardadoEn < 0L) return
        val edad = System.currentTimeMillis() - guardadoEn
        // Una marca «del futuro» (la hora del móvil se atrasó) también se da por vieja
        if (edad in 0..CADUCA_MS) return
        val callId = prefs.getString(CALL_ID, "").orEmpty()
        val editor = prefs.edit().remove(GUARDADO_EN)
        if (callId.isNotEmpty()) {
            editor.putString(CALL_ID, "").putString(PAYLOAD, "")
            Log.w("$TAG Dropping push for Call-ID [$callId] stored [$edad] ms ago, it's too old to be processed")
        }
        editor.apply()
    }
}
