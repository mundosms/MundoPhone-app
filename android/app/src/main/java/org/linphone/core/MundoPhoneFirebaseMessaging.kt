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

import com.google.firebase.messaging.RemoteMessage
import org.linphone.core.tools.firebase.FirebaseMessaging

/**
 * MundoPhone (F4): el servicio de FCM del SDK, con una marca de tiempo antes de hacer lo de siempre.
 *
 * El SDK escribe su «[Push Notification] Received, sent [x] ms ago» con android.util.Log, que va a logcat y NO al registro
 * que se sube; en un arranque en frío por push, el registro no decía cuándo llegó el push. Todo lo demás (también el
 * «hold» de nuestro parche, que hace esperar a onMessageReceived) sigue en la clase del SDK: aquí solo se mira la hora.
 */
class MundoPhoneFirebaseMessaging : FirebaseMessaging() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        MundoPhoneTiempos.pushReceived(
            System.currentTimeMillis() - remoteMessage.sentTime,
            "${remoteMessage.priority}/${remoteMessage.originalPriority}"
        )
        super.onMessageReceived(remoteMessage)
    }
}
