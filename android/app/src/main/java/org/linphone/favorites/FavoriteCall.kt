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
package org.linphone.favorites

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.ui.main.MainActivity
import org.linphone.utils.LinphoneUtils

/**
 * MundoPhone (E4): las llamadas que salen de un acceso directo del icono o del widget de favoritos.
 *
 * Entran por MainActivity, como un «tel:» de otra app: arranca el Core si la app estaba cerrada (MainActivity espera a
 * coreContext.isReady() antes de seguir), abre el asistente si no hay cuenta y enseña el diálogo de «¿desde qué
 * extensión?» si hay varias registradas y ninguna favorita (CoreContext.accountForCall). La llamada es la misma de la
 * ficha del contacto: CoreContext.startAudioCall.
 *
 * Lo que añade sobre el «tel:» es esperar a que el Core esté encendido (GlobalState.On): con la app cerrada, isReady()
 * solo dice que el Core existe, y CoreContext lo arranca 50 ms después (startCore).
 */
object FavoriteCall {
    private const val TAG = "[Favorite Call]"

    /** Llamar a lo que lleva el intent en data («sip:…» o «tel:…»). */
    const val ACTION_CALL = "es.mundosms.action.LLAMAR_FAVORITO"

    /** Llamar al buzón de voz de la cuenta por defecto (el que esté configurado en ese momento). */
    const val ACTION_CALL_VOICEMAIL = "es.mundosms.action.LLAMAR_BUZON"

    /** Id del acceso directo que lanzó la llamada, para avisar al sistema de que se usó (orden de sugerencias). */
    private const val EXTRA_SHORTCUT_ID = "es.mundosms.extra.ACCESO_DIRECTO"

    @AnyThread
    fun callIntent(context: Context, target: String, shortcutId: String? = null): Intent {
        val data = if (target.startsWith("sip:", true) || target.startsWith("sips:", true)) {
            target.toUri()
        } else {
            Uri.fromParts("tel", target, null)
        }
        return Intent(ACTION_CALL, data, context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (shortcutId != null) putExtra(EXTRA_SHORTCUT_ID, shortcutId)
        }
    }

    @AnyThread
    fun voicemailIntent(context: Context, shortcutId: String): Intent {
        return Intent(ACTION_CALL_VOICEMAIL).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_SHORTCUT_ID, shortcutId)
        }
    }

    /** Lo llama MainActivity.handleIntent con las dos acciones de arriba. */
    @UiThread
    fun handleIntent(context: Context, intent: Intent) {
        // Al volver a la app desde Recientes, Android repite el intent que abrió la tarea: no volver a llamar
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            Log.w("$TAG Call intent re-delivered from recent apps, ignoring it")
            return
        }

        val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)
        if (shortcutId != null) {
            try {
                ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
            } catch (e: Exception) {
                Log.w("$TAG Can't report shortcut [$shortcutId] as used: $e")
            }
        }

        val voicemail = intent.action == ACTION_CALL_VOICEMAIL
        val data = intent.data
        val target = when {
            voicemail -> null
            data == null -> {
                Log.e("$TAG Call intent without data, nothing to call")
                return
            }
            data.scheme.equals("tel", true) -> data.schemeSpecificPart
            else -> data.toString()
        }
        Log.i("$TAG Call requested from ${if (shortcutId != null) "shortcut [$shortcutId]" else "widget"}")

        coreContext.postOnCoreThread { core ->
            whenCoreIsOn(core) {
                if (voicemail) callVoicemail(it) else call(it, target.orEmpty())
            }
        }
    }

    @WorkerThread
    private fun whenCoreIsOn(core: Core, action: (Core) -> Unit) {
        if (core.globalState == GlobalState.On) {
            action(core)
            return
        }

        Log.i("$TAG Core is [${core.globalState}], waiting for it to be On before calling")
        core.addListener(object : CoreListenerStub() {
            @WorkerThread
            override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
                if (state == GlobalState.On) {
                    core.removeListener(this)
                    action(core)
                } else if (state == GlobalState.Shutdown || state == GlobalState.Off) {
                    core.removeListener(this)
                }
            }
        })
    }

    @WorkerThread
    private fun call(core: Core, target: String) {
        // Sin cuenta, MainActivity ya ha abierto el asistente
        if (core.accountList.isEmpty()) {
            Log.w("$TAG No account configured, not calling")
            return
        }
        val address = core.interpretUrl(target, LinphoneUtils.applyInternationalPrefix())
        if (address == null) {
            Log.e("$TAG Can't interpret [$target] as an address")
            return
        }
        Log.i("$TAG Calling favourite [${address.asStringUriOnly()}]")
        // Como el «tel:» de MainActivity: recién arrancada, la app aún no sabe si hay red
        coreContext.startAudioCall(address, skipNetworkReachabilityTest = true)
    }

    @WorkerThread
    private fun callVoicemail(core: Core) {
        val account = core.defaultAccount
        val voicemail = account?.params?.voicemailAddress
        if (account == null || voicemail == null) {
            Log.w("$TAG No voicemail address configured for the default account, not calling")
            return
        }
        Log.i("$TAG Calling voicemail [${voicemail.asStringUriOnly()}]")
        // El buzón es el de la cuenta por defecto: se llama desde ella, sin preguntar por la extensión
        coreContext.startAudioCall(
            voicemail,
            localAddress = account.params.identityAddress,
            skipNetworkReachabilityTest = true
        )
    }
}
