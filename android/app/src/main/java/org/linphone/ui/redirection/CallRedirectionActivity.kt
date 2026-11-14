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
package org.linphone.ui.redirection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.telecom.redirection.CallRedirection
import org.linphone.telecom.redirection.RedirectionNumbers
import org.linphone.ui.GenericActivity

/**
 * MundoPhone (E1): la pantalla de la redirección de llamadas, encima del marcador del teléfono. La abre
 * [org.linphone.telecom.redirection.MundoPhoneCallRedirectionService], que cancela la llamada en Telecom en cuanto la
 * pide (la pantalla de llamada del teléfono la tapa hasta entonces); al salir a la vista (onResume) pregunta o llama si
 * la petición sigue pendiente. También la abre la notificación de respaldo, con la elección ya hecha. Dos modos:
 * - «preguntar»: «¿Llamar a <número> con MundoPhone o con la SIM?», con «Recordar mi elección» (cambia el ajuste a
 *   siempre o nunca). MundoPhone llama por la app; SIM la relanza por la SIM, que necesita CALL_PHONE: si no está, se
 *   pide, y si se niega se abre el marcador con el número para pulsar llamar.
 * - «siempre» (auto): llama por MundoPhone sin preguntar. Solo enseña «Llamando con MundoPhone…» si tarda (app cerrada,
 *   el Core arrancando); también sirve para que Android deje abrir la pantalla de llamada, porque la app está delante.
 * Tocar fuera o atrás cierra sin llamar; en «Llamando…» (la app arrancando), anula la llamada si aún no ha salido.
 */
@MainThread
class CallRedirectionActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Call Redirection Activity]"

        private const val EXTRA_NUMBER = "es.mundosms.redirection.NUMBER"
        private const val EXTRA_PHONE_ACCOUNT = "es.mundosms.redirection.PHONE_ACCOUNT"
        private const val EXTRA_REQUEST_ID = "es.mundosms.redirection.REQUEST_ID"
        private const val EXTRA_AUTO = "es.mundosms.redirection.AUTO"
        private const val EXTRA_CHOICE = "es.mundosms.redirection.CHOICE"

        // La elección ya hecha en la notificación de respaldo (CallRedirection)
        const val CHOICE_NONE = ""
        const val CHOICE_APP = "app"
        const val CHOICE_SIM = "sim"

        private const val STATE_SHOWN_ID = "shown_request_id"
        private const val STATE_CALL_IN_PROGRESS = "call_in_progress"

        // «siempre»: si la llamada tarda más que esto en salir, se enseña «Llamando con MundoPhone…»
        private const val SHOW_CALLING_DELAY_MS = 500L

        fun createIntent(
            context: Context,
            number: String,
            phoneAccount: PhoneAccountHandle?,
            requestId: Long,
            auto: Boolean,
            choice: String = CHOICE_NONE
        ): Intent {
            return Intent(context, CallRedirectionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_PHONE_ACCOUNT, phoneAccount)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_AUTO, auto)
                putExtra(EXTRA_CHOICE, choice)
            }
        }
    }

    private lateinit var root: View
    private lateinit var card: View
    private lateinit var title: TextView
    private lateinit var message: View
    private lateinit var remember: CheckBox
    private lateinit var rememberNumber: CheckBox
    private lateinit var buttons: View
    private lateinit var calling: View
    private lateinit var callingLabel: TextView

    private var number = ""
    private var phoneAccount: PhoneAccountHandle? = null
    private var requestId = 0L
    private var auto = false
    private var choice = CHOICE_NONE

    // La petición de la que ya se avisó al servicio (sobrevive a recrear la actividad)
    private var shownRequestId = 0L

    private var waitingForPermission = false
    private var callInProgress = false
    private var appCallRequest: CallRedirection.AppCallRequest? = null

    // Cambia con cada llamada que recibe la pantalla: el aviso de una llamada anterior no cierra la de la siguiente
    private var generation = 0

    private val callPhonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        waitingForPermission = false
        Log.i("$TAG Permiso CALL_PHONE concedido [$granted]")
        if (!granted || !CallRedirection.placeCallWithSim(this, number, phoneAccount)) {
            openDialer()
        }
        finish()
    }

    private val showCallingRunnable = Runnable {
        if (!isFinishing) showCallingCard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // GenericActivity aplica el tema de color de la app, que trae fondo opaco: aquí se tiene que ver el marcador detrás
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setContentView(R.layout.call_redirection_activity)

        root = findViewById(R.id.root)
        card = findViewById(R.id.card)
        title = findViewById(R.id.title)
        message = findViewById(R.id.message)
        remember = findViewById(R.id.remember)
        rememberNumber = findViewById(R.id.remember_number)
        // «Para este número» o «para todos»: una deshabilita la otra
        remember.setOnCheckedChangeListener { _, checked ->
            if (checked) rememberNumber.isChecked = false
            rememberNumber.isEnabled = !checked
        }
        rememberNumber.setOnCheckedChangeListener { _, checked ->
            if (checked) remember.isChecked = false
            remember.isEnabled = !checked
        }
        buttons = findViewById(R.id.buttons)
        calling = findViewById(R.id.calling)
        callingLabel = findViewById(R.id.calling_label)

        root.setOnClickListener {
            // En «siempre», mientras no se enseña nada, un toque es del marcador de debajo, no una respuesta
            if (card.isVisible) closeWithoutCalling("toca fuera")
        }
        findViewById<View>(R.id.call_with_app).setOnClickListener { onAppChosen() }
        findViewById<View>(R.id.call_with_sim).setOnClickListener { onSimChosen() }
        onBackPressedDispatcher.addCallback(this) { closeWithoutCalling("atrás") }

        shownRequestId = savedInstanceState?.getLong(STATE_SHOWN_ID, 0L) ?: 0L
        readIntent(intent)
        // Recreada con la llamada ya pedida por la anterior (o en «siempre», que no tiene nada que enseñar): esa sigue
        // su curso; esta sobra y, si se quedara, sería una ventana invisible que se traga los toques
        if (savedInstanceState != null && (auto || savedInstanceState.getBoolean(STATE_CALL_IN_PROGRESS, false))) {
            Log.i("$TAG Recreada con la llamada ya decidida: se cierra")
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Otra llamada mientras la pregunta seguía abierta: la anterior ya está cancelada en Telecom, manda la nueva
        Log.i("$TAG Nueva llamada con la pantalla abierta")
        setIntent(intent)
        readIntent(intent)
        signalShown()
    }

    override fun onResume() {
        super.onResume()
        signalShown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SHOWN_ID, shownRequestId)
        outState.putBoolean(STATE_CALL_IN_PROGRESS, callInProgress)
    }

    override fun onStop() {
        super.onStop()
        // Si el usuario se va (inicio, otra app) sin elegir, la pregunta no se queda escondida: se cierra sin llamar.
        // Con la llamada en marcha, lo normal es que la tape la pantalla de llamada.
        if (!isChangingConfigurations && !waitingForPermission && !isFinishing) {
            if (!callInProgress) Log.i("$TAG Pantalla tapada sin elegir: se cierra sin llamar")
            finish()
        }
    }

    override fun onDestroy() {
        root.removeCallbacks(showCallingRunnable)
        super.onDestroy()
    }

    private fun readIntent(intent: Intent) {
        number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        phoneAccount = IntentCompat.getParcelableExtra(intent, EXTRA_PHONE_ACCOUNT, PhoneAccountHandle::class.java)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        choice = intent.getStringExtra(EXTRA_CHOICE).orEmpty()
        generation++
        callInProgress = false
        appCallRequest = null
        root.removeCallbacks(showCallingRunnable)

        title.text = getString(R.string.call_redirection_dialog_title, number)
        callingLabel.text = getString(R.string.call_redirection_calling_label, number)
        // «Recordar para este número» marcada por defecto; con ella, «para todos» deshabilitada
        remember.isChecked = false
        rememberNumber.isChecked = true
        rememberNumber.isEnabled = true
        remember.isEnabled = false
        if (auto) {
            // Hasta que tarde, nada a la vista: con la app abierta la llamada sale enseguida y taparía la pantalla
            root.background = Color.TRANSPARENT.toDrawable()
            card.visibility = View.INVISIBLE
        } else {
            showQuestionCard()
        }
    }

    /** Ya está a la vista: si la petición sigue pendiente (cancelada en Telecom y sin caducar), la decisión es nuestra. */
    private fun signalShown() {
        if (requestId == 0L || shownRequestId == requestId) return
        shownRequestId = requestId
        val masked = CallRedirection.mask(number)
        if (!CallRedirection.promptShown(this, requestId)) {
            // Telecom ya la decidió, ya se eligió en otra parte o ha pasado demasiado: nada que hacer
            Log.w("$TAG [$masked] La petición ya no está pendiente (decidida o caducada): se cierra")
            finish()
            return
        }
        if (choice == CHOICE_APP) {
            Log.i("$TAG [$masked] Elegido en la notificación: MundoPhone")
            showCallingCard()
            callWithApp()
            return
        }
        if (choice == CHOICE_SIM) {
            Log.i("$TAG [$masked] Elegido en la notificación: SIM")
            // En la notificación no se ve ninguna casilla: no se recuerda nada (la de este número sale marcada por defecto)
            rememberNumber.isChecked = false
            remember.isChecked = false
            onSimChosen()
            return
        }
        if (auto) {
            Log.i("$TAG [$masked] Ajuste «siempre»: llamando con MundoPhone")
            root.postDelayed(showCallingRunnable, SHOW_CALLING_DELAY_MS)
            callWithApp()
        } else {
            Log.i("$TAG [$masked] Preguntando: ¿MundoPhone o SIM?")
        }
    }

    private fun onAppChosen() {
        if (callInProgress) return
        Log.i(
            "$TAG [${CallRedirection.mask(number)}] Elige MundoPhone (recordar para todos [${remember.isChecked}], para este número [${rememberNumber.isChecked}])"
        )
        if (rememberNumber.isChecked) {
            CallRedirection.saveNumberRule(number, RedirectionNumbers.RULE_APP)
        } else if (remember.isChecked) {
            CallRedirection.saveMode(CallRedirection.MODE_ALWAYS)
        }
        showCallingCard()
        callWithApp()
    }

    private fun onSimChosen() {
        if (callInProgress) return
        Log.i(
            "$TAG [${CallRedirection.mask(number)}] Elige la SIM (recordar para todos [${remember.isChecked}], para este número [${rememberNumber.isChecked}])"
        )
        if (rememberNumber.isChecked) {
            CallRedirection.saveNumberRule(number, RedirectionNumbers.RULE_SIM)
        } else if (remember.isChecked) {
            CallRedirection.saveMode(CallRedirection.MODE_NEVER)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            if (!CallRedirection.placeCallWithSim(this, number, phoneAccount)) openDialer()
            finish()
        } else {
            // La ventana del permiso tapa esta pantalla: que onStop no la cierre mientras tanto
            Log.i("$TAG Pidiendo CALL_PHONE para relanzar por la SIM")
            waitingForPermission = true
            callPhonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun callWithApp() {
        callInProgress = true
        val myGeneration = generation
        var request: CallRedirection.AppCallRequest? = null
        request = CallRedirection.startAppCall(number) { started ->
            if (!started && request?.cancelled != true) {
                Toast.makeText(applicationContext, R.string.call_redirection_failed_toast, Toast.LENGTH_LONG).show()
            }
            // La pantalla de llamada ya está pedida (CoreContext la abre en OutgoingInit): esta sobra
            if (myGeneration == generation && !isDestroyed) finish()
        }
        appCallRequest = request
    }

    /** Sin CALL_PHONE: el marcador del teléfono con el número, y se explica qué hacer. */
    private fun openDialer() {
        Toast.makeText(applicationContext, R.string.call_redirection_dialer_toast, Toast.LENGTH_LONG).show()
        CallRedirection.openDialerWithNumber(this, number)
    }

    private fun closeWithoutCalling(how: String) {
        val masked = CallRedirection.mask(number)
        if (callInProgress) {
            // Si la llamada aún espera al Core o al registro, no sale; si ya salió, la pantalla de llamada está delante
            Log.i("$TAG [$masked] Cerrada en «Llamando…» ($how): se anula si aún no ha salido")
            appCallRequest?.cancel()
        } else {
            Log.i("$TAG [$masked] Cerrada sin elegir ($how): no se llama")
        }
        finish()
    }

    private fun showQuestionCard() {
        setDimmedBackground()
        card.visibility = View.VISIBLE
        title.visibility = View.VISIBLE
        message.visibility = View.VISIBLE
        remember.visibility = View.VISIBLE
        rememberNumber.visibility = View.VISIBLE
        buttons.visibility = View.VISIBLE
        calling.visibility = View.GONE
    }

    private fun showCallingCard() {
        setDimmedBackground()
        card.visibility = View.VISIBLE
        title.visibility = View.GONE
        message.visibility = View.GONE
        remember.visibility = View.GONE
        rememberNumber.visibility = View.GONE
        buttons.visibility = View.GONE
        calling.visibility = View.VISIBLE
    }

    private fun setDimmedBackground() {
        // Como los diálogos de la app (DialogUtils): negro al 60 %
        root.background = ColorUtils.setAlphaComponent(getColor(R.color.bc_black), 153).toDrawable()
    }
}
