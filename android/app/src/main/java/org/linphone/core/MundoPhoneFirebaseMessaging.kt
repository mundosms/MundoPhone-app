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

import android.os.SystemClock
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import org.linphone.core.tools.firebase.FirebaseMessaging

/**
 * MundoPhone (F4): el servicio de FCM del SDK, con lo que el SDK no hace bien en un arranque en frío por push.
 *
 * - Marca de tiempo al llegar: el SDK escribe su «[Push Notification] Received, sent [x] ms ago» con android.util.Log, que
 *   va a logcat y NO al registro que se sube.
 * - Guarda el push al momento si el Core no está en On (MundoPhonePushGuardado): el SDK, con el Core creado y sin
 *   arrancar, lo guarda pasando por el hilo principal y puede llegar tarde a su propio arranque.
 * - Retiene el push hasta que entra la LLAMADA: el «hold» de nuestro parche del SDK suelta en cuanto el REGISTER queda
 *   registrado, y el INVITE puede llegar después (medido: 200 del REGISTER 24-214 ms después del INVITE, pero también
 *   antes). Mientras onMessageReceived no vuelve, el proceso no es «cached» y Android no lo congela aunque el servicio en
 *   primer plano del push no haya arrancado. Con el mismo tope total que el SDK.
 */
class MundoPhoneFirebaseMessaging : FirebaseMessaging() {
    companion object {
        private const val TAG = "[MundoPhone FCM]"

        // El PUSH_HOLD_MS del parche del SDK (FirebaseMessaging.java): FCM da hasta ~20 s
        private const val RETENER_MS = 8500L
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val llegada = SystemClock.elapsedRealtime()
        MundoPhoneTiempos.pushReceived(
            System.currentTimeMillis() - remoteMessage.sentTime,
            "${remoteMessage.priority}/${remoteMessage.originalPriority}"
        )
        if (!coreContext.isCoreOn) {
            MundoPhonePushGuardado.guardar(applicationContext, remoteMessage)
        }

        super.onMessageReceived(remoteMessage)

        retenerHastaLaLlamada(llegada)
    }

    private fun retenerHastaLaLlamada(llegada: Long) {
        val restante = RETENER_MS - (SystemClock.elapsedRealtime() - llegada)
        if (restante <= 0L) return
        val entra = CountDownLatch(1)
        val listener = object : CoreListenerStub() {
            override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
                // También si la llamada se va antes de sonar: ya no hay nada que esperar
                if (state != Call.State.Idle && state != Call.State.PushIncomingReceived) entra.countDown()
            }

            override fun onMessagesReceived(core: Core, chatRoom: ChatRoom, messages: Array<ChatMessage>) {
                entra.countDown()
            }
        }
        // En el hilo del Core: o ya hay llamada (entró mientras retenía el SDK) o se escucha a partir de ahora
        coreContext.postOnCoreThread { core ->
            if (core.callsNb > 0) entra.countDown() else core.addListener(listener)
        }
        try {
            val entro = entra.await(restante, TimeUnit.MILLISECONDS)
            Log.i("$TAG Push held [${SystemClock.elapsedRealtime() - llegada}] ms in total, ${if (entro) "the call or message came in" else "nothing came in"}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            coreContext.postOnCoreThread { core -> core.removeListener(listener) }
        }
    }
}
