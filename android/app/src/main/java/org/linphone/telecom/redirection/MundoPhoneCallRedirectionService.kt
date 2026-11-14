/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone, a fork of linphone-android.
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
package org.linphone.telecom.redirection

import android.app.ActivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import org.linphone.core.tools.Log
import org.linphone.ui.redirection.CallRedirectionActivity

/**
 * MundoPhone (E1): Telecom nos pasa cada llamada que se marca en el teléfono nativo o en Contactos (solo con el rol
 * ROLE_CALL_REDIRECTION, que se pide en Ajustes > Llamadas) y espera unos 5 s a que contestemos. Las emergencias no
 * pasan por aquí: las excluye Android.
 *
 * Por la SIM sin preguntar (placeCallUnmodified):
 * - la que relanzamos nosotros tras elegir «SIM» (marca de un solo uso, ver [CallRedirection.consumeSimRelaunch]);
 * - ajuste «nunca»; códigos que empiezan por `*` o `#`; lo que no es un número normal (pausas, letras);
 * - sin red, o sin cuenta por defecto registrada ([CallRedirection.checkAccount]);
 * - cuando no se puede enseñar nada (allowInteractiveResponse=false, p. ej. en el coche). También con «siempre»:
 *   MundoPhone no es app de Android Auto y su llamada iría sin pantalla.
 * Si no, se abre la pantalla ([CallRedirectionActivity]): con «preguntar» pregunta, con «siempre» llama por MundoPhone.
 * La llamada se cancela en Telecom (cancelCall) en cuanto Android acepta abrir esa pantalla (existe su tarea), aunque
 * aún no se vea: la pantalla de llamada del teléfono la tapa mientras la llamada siga viva. Si se abre pero no sale a la
 * vista, la notificación de respaldo de [CallRedirection]. Si Android no la deja abrir (Xiaomi sin «ventanas emergentes
 * en segundo plano»), esa misma notificación al momento (con «siempre», la llamada por MundoPhone sin pantalla); solo
 * si la notificación tampoco puede salir, por la SIM.
 *
 * Todo pasa en el hilo principal. Cada decisión queda en el log con el número enmascarado. Hereda de
 * [NullableCallRedirectionService] (Java) para recibir la cuenta de la SIM como anulable (ver allí por qué).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MundoPhoneCallRedirectionService : NullableCallRedirectionService() {
    companion object {
        private const val TAG = "[Call Redirection Service]"

        // Telecom espera 5 s desde que enlaza el servicio, y con la app cerrada el arranque del proceso ya se come una
        // parte: comprobación de la cuenta (≤ 1,2 s + 0,3 s si el hilo del Core no contesta); la pantalla, al momento
        private const val ACCOUNT_CHECK_TIMEOUT_MS = 1200L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var destroyed = false

    private var current: Decision? = null

    private var createdAt = 0L

    private var placeCallReceived = false

    /** Una llamada: se contesta a Telecom una sola vez. */
    private inner class Decision(val masked: String, val startedAt: Long) {
        private var answered = false

        fun passThrough(reason: String) {
            if (!take()) return
            Log.i("$TAG [$masked] por la SIM sin preguntar: $reason (${elapsed()} ms)")
            try {
                placeCallUnmodified()
            } catch (e: Exception) {
                Log.e("$TAG [$masked] placeCallUnmodified ha fallado: $e")
            }
        }

        /** true si la llamada queda cancelada en Telecom y la decisión pasa a ser nuestra. */
        fun cancel(reason: String): Boolean {
            if (!take()) {
                Log.w("$TAG [$masked] ya no se puede cancelar en Telecom (ya contestada o servicio cerrado): $reason")
                return false
            }
            Log.i("$TAG [$masked] cancelada en Telecom: $reason (${elapsed()} ms)")
            return try {
                cancelCall()
                true
            } catch (e: Exception) {
                Log.e("$TAG [$masked] cancelCall ha fallado: $e")
                false
            }
        }

        private fun take(): Boolean {
            if (answered || destroyed || current !== this) return false
            answered = true
            return true
        }

        private fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt
    }

    override fun onCreate() {
        super.onCreate()
        createdAt = SystemClock.elapsedRealtime()
        // Con la app cerrada, lo que tardó Android en arrancarla cuenta dentro de los 5 s de Telecom
        val startup = CallRedirection.recentStartup(this)
        Log.i("$TAG Creado${if (startup != null) " ($startup)" else ""}; ${CallRedirection.powerState(this)}")
    }

    @MainThread
    override fun onPlaceCallNullable(handle: Uri?, initialPhoneAccount: PhoneAccountHandle?, allowInteractiveResponse: Boolean) {
        val number = handle?.schemeSpecificPart.orEmpty()
        val decision = Decision(CallRedirection.mask(number), SystemClock.elapsedRealtime())
        current = decision
        placeCallReceived = true
        Log.i(
            "$TAG Llamada del teléfono a [${decision.masked}]: se puede preguntar [$allowInteractiveResponse], cuenta de SIM [${initialPhoneAccount != null}]"
        )

        if (handle?.scheme != PhoneAccount.SCHEME_TEL) {
            decision.passThrough("no es un número (esquema [${handle?.scheme}])")
            return
        }
        if (CallRedirection.consumeSimRelaunch(number)) {
            decision.passThrough("es la que relanzamos tras elegir «SIM»")
            return
        }
        // Telecom solo nos llama con el rol concedido: la elección guardada para el número y, si no hay, el ajuste general
        // (sin elegir todavía vale «preguntar»)
        val rule = CallRedirection.ruleForNumber(number)
        val mode = CallRedirection.modeForNumber(number)
        if (mode == CallRedirection.MODE_NEVER) {
            decision.passThrough(if (rule != null) "elección guardada para este número: SIM" else "ajuste «nunca»")
            return
        }
        if (CallRedirection.isOperatorCode(number)) {
            decision.passThrough("código que empieza por * o #")
            return
        }
        if (CallRedirection.dialableNumber(number) == null) {
            decision.passThrough("no es un número normal (pausas, letras…)")
            return
        }
        val net = CallRedirection.networkState(this)
        if (!net.available) {
            decision.passThrough("sin red con Internet")
            return
        }
        if (!allowInteractiveResponse) {
            decision.passThrough("no se puede enseñar nada (allowInteractiveResponse=false), ajuste [$mode]")
            return
        }

        CallRedirection.checkAccount(net, ACCOUNT_CHECK_TIMEOUT_MS) { check ->
            if (!check.ok) {
                decision.passThrough(check.reason)
            } else {
                Log.i("$TAG [${decision.masked}] MundoPhone puede llamar: ${check.reason}")
                if (rule != null) Log.i("$TAG [${decision.masked}] Elección guardada para este número: MundoPhone")
                showPrompt(decision, number, initialPhoneAccount, mode == CallRedirection.MODE_ALWAYS)
            }
        }
    }

    @MainThread
    private fun showPrompt(decision: Decision, number: String, phoneAccount: PhoneAccountHandle?, auto: Boolean) {
        if (destroyed || current !== decision) return
        val id = CallRedirection.newPromptId()
        try {
            // Mientras Telecom esté enlazado a este servicio, Android deja abrir una pantalla desde segundo plano
            startActivity(CallRedirectionActivity.createIntent(this, number, phoneAccount, id, auto))
        } catch (e: Exception) {
            decision.passThrough("no se ha podido abrir la pantalla: $e")
            return
        }
        // startActivity es síncrono: si Android no deja abrirla, no hay tarea. Si la hay, se cancela YA en Telecom aunque
        // aún no se vea: la pantalla de llamada del teléfono la tapa mientras la llamada siga viva (ver CallRedirection)
        val task = promptTask()
        Log.i("$TAG [${decision.masked}] Pantalla pedida: ${task ?: "sin tarea"}")
        if (task == null) {
            // Si la pantalla llega más tarde con este id, CallRedirection.promptShown decide si aún vale
            if (auto) {
                if (decision.cancel("ajuste «siempre» y Android no deja abrir la pantalla: llama MundoPhone sin ella")) {
                    CallRedirection.callWithoutPrompt(this, number, phoneAccount)
                }
            } else if (CallRedirection.canAskByNotification(this)) {
                if (decision.cancel("Android no deja abrir la pantalla: la pregunta va en la notificación")) {
                    CallRedirection.askByNotification(this, id, number, phoneAccount)
                }
            } else {
                decision.passThrough("Android no ha dejado abrir la pantalla y la notificación no saldría")
            }
            return
        }
        val reason = if (auto) "ajuste «siempre», llama MundoPhone" else "ajuste «preguntar», la pregunta sale al irse la pantalla de llamada"
        if (decision.cancel(reason)) {
            CallRedirection.takeOverPrompt(this, id, number, phoneAccount, auto)
        }
        // Si ya no se pudo cancelar, Telecom decidió: la pantalla, si sale, se cierra sola (promptShown da false)
    }

    /** La tarea de la pantalla de la pregunta (singleInstance, tarea propia), o null si no existe. */
    private fun promptTask(): String? {
        val activityManager = getSystemService(ActivityManager::class.java) ?: return "sin ActivityManager"
        return try {
            val name = CallRedirectionActivity::class.java.name
            val info = activityManager.appTasks.mapNotNull { it.taskInfo }.firstOrNull {
                it.baseActivity?.className == name || it.topActivity?.className == name
            } ?: return null
            val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " visible [${info.isVisible}]" else ""
            "tarea [${info.taskId}] actividades [${info.numActivities}]$visible"
        } catch (e: Exception) {
            // Sin poder mirarlo, se sigue como si estuviera: si no sale, la notificación de respaldo
            "no se ha podido mirar la tarea ($e)"
        }
    }

    override fun onDestroy() {
        if (placeCallReceived) {
            Log.i("$TAG Destruido")
        } else {
            // Telecom enlazó y soltó sin pasar ninguna llamada: si además el proceso acababa de arrancar, lo normal es
            // que se le hubieran pasado los 5 s y el usuario haya visto «MundoPhone no puede realizar la llamada»
            val startup = CallRedirection.recentStartup(this)
            Log.w(
                "$TAG Destruido sin recibir ninguna llamada de Telecom, a los [${SystemClock.elapsedRealtime() - createdAt}] ms de crearse${if (startup != null) " ($startup)" else ""}"
            )
        }
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
