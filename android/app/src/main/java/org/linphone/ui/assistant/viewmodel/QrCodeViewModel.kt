/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
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
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class QrCodeViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Qr Code Scanner ViewModel]"

        // MundoPhone: único destino al que se mandan las cuentas que ya hay (login + MD5 de la clave): la API del panel,
        // por https. El QR del panel apunta a un enlace corto de urla.ws que redirige ahí.
        private const val PROVISIONING_HOST = "api.mundosms.es"
        private const val MAX_REDIRECTS = 5
        private const val HTTP_TIMEOUT_MS = 8000
    }

    val remoteProvisioningSuccessfulEvent = MutableLiveData<Event<Boolean>>()

    val onErrorEvent = MutableLiveData<Event<Boolean>>()

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onConfiguringStatus(core: Core, status: ConfiguringState, message: String?) {
            Log.i("$TAG Configuring state is [$status]")
            if (status == ConfiguringState.Failed) {
                Log.e("$TAG Failure applying remote provisioning: $message")
                showRedToast(R.string.remote_provisioning_config_failed_toast, R.drawable.warning_circle)
                onErrorEvent.postValue(Event(true))
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) {
                if (core.accountList.isEmpty()) {
                    Log.w("$TAG Provisioning was successful but no account has been configured yet, staying in assistant")
                    // Remote provisioning didn't contain any account
                    // and there wasn't at least one configured before either
                    remoteProvisioningSuccessfulEvent.postValue(Event(false))
                } else {
                    Log.i("$TAG At least an account exists in Core, leaving assistant")
                    remoteProvisioningSuccessfulEvent.postValue(Event(true))
                }
            }
        }

        @WorkerThread
        override fun onQrcodeFound(core: Core, result: String?) {
            // MundoPhone: sin la URL, que lleva la firma del panel (válida un día)
            Log.i("$TAG QR Code found, host [${hostOf(result.orEmpty())}]")
            processScannedQrPayloadOnCore(core, result)
        }
    }

    init {
        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            val coreGlobalState = core.globalState
            if (coreGlobalState != GlobalState.On) {
                Log.e("$TAG Core isn't ON (current state is [$coreGlobalState]), video preview won't work!")
            }
        }
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
        super.onCleared()
    }

    /** El QR leído desde una imagen de la galería (QrCodeScannerFragment). */
    @UiThread
    fun processScannedQrPayload(result: String?) {
        coreContext.postOnCoreThread { core ->
            processScannedQrPayloadOnCore(core, result)
        }
    }

    @WorkerThread
    private fun processScannedQrPayloadOnCore(core: Core, result: String?) {
        val payload = result?.trim().orEmpty()
        if (payload.isEmpty()) {
            showRedToast(R.string.assistant_qr_code_invalid_toast, R.drawable.warning_circle)
            return
        }

        // MundoPhone: el QR puede traer el XML de configuración entero
        if (payload.startsWith("<config")) {
            applyProvisioningPayload(core, payload)
            return
        }

        val url = LinphoneUtils.getRemoteProvisioningUrlFromUri(payload)
        if (url == null) {
            Log.e("$TAG The content of the QR Code doesn't seem to be a valid web URL")
            showRedToast(R.string.assistant_qr_code_invalid_toast, R.drawable.warning_circle)
            return
        }

        // Sin cuentas no hay nada que proteger: la URL se usa tal cual.
        val accountsJson = buildAccountsJson(core)
        if (core.accountList.isEmpty() || isProvisioningUrl(url)) {
            applyProvisioningPayload(core, appendAccounts(url, accountsJson))
            return
        }

        // Enlace corto: antes se le añadían los login + MD5 de TODAS las cuentas, que el acortador guardaba en su
        // registro. Se resuelve primero SIN datos y solo se añaden a la URL final si es la API del panel por https. Si no
        // se llega ahí, error: aprovisionar sin las cuentas que ya hay podría pisarlas (el XML del panel las conserva
        // gracias a jsonaddaccounts).
        Thread({
            val finalUrl = resolveRedirects(url)
            coreContext.postOnCoreThread { coreOnThread ->
                if (finalUrl != null) {
                    applyProvisioningPayload(coreOnThread, appendAccounts(finalUrl, accountsJson))
                } else {
                    Log.e("$TAG QR link didn't lead to https://$PROVISIONING_HOST, existing accounts haven't been sent")
                    showRedToast(R.string.assistant_qr_code_link_not_verified_toast, R.drawable.warning_circle)
                    onErrorEvent.postValue(Event(true))
                }
            }
        }, "QR link resolver").start()
    }

    @AnyThread
    private fun appendAccounts(url: String, accountsJson: String): String {
        val encodedAccounts = URLEncoder.encode(accountsJson, "UTF-8")
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}source=linphonexml&jsonaddaccounts=$encodedAccounts"
    }

    @AnyThread
    private fun hostOf(url: String): String {
        return try {
            URI(url).host.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    @AnyThread
    private fun isProvisioningUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(PROVISIONING_HOST, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sigue las redirecciones del enlace (Location de un 3xx o la cabecera Refresh) sin mandar ningún dato, hasta llegar a
     * https://api.mundosms.es. Un enlace http pasa a https: Android no permite tráfico sin cifrar y urla.ws sirve los dos.
     * Devuelve null si no se llega.
     */
    @WorkerThread
    private fun resolveRedirects(start: String): String? {
        var current = start.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        repeat(MAX_REDIRECTS) {
            if (isProvisioningUrl(current)) return current

            val connection = try {
                URL(current).openConnection() as? HttpURLConnection
            } catch (_: Exception) {
                null
            } ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                val code = connection.responseCode
                val next = if (code in 300..399) {
                    connection.getHeaderField("Location")
                } else {
                    connection.getHeaderField("Refresh")?.let {
                        Regex("url=(.+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
                    }
                }
                if (next.isNullOrBlank()) {
                    Log.e("$TAG QR link [${hostOf(current)}] answered [$code] without a redirection")
                    return null
                }
                current = URL(URL(current), next.trim()).toString()
            } catch (e: Exception) {
                Log.e("$TAG Failed to follow QR link [${hostOf(current)}]: ${e.javaClass.simpleName}")
                return null
            } finally {
                connection.disconnect()
            }
        }
        return if (isProvisioningUrl(current)) current else null
    }

    /** Las cuentas que ya hay (login + MD5 de la clave), para que el XML del panel las conserve. */
    @WorkerThread
    private fun buildAccountsJson(core: Core): String {
        val accountsArray = JSONArray()
        for (account in core.accountList) {
            val params = account.params
            val identity = params.identityAddress?.username ?: continue
            val password = account.findAuthInfo()?.password.orEmpty()

            val accountJson = JSONObject()
            accountJson.put("login", identity)
            accountJson.put("md5pass", md5(password))
            accountsArray.put(accountJson)
        }
        return JSONObject().put("accounts", accountsArray).toString()
    }

    @AnyThread
    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @WorkerThread
    private fun applyProvisioningPayload(core: Core, payload: String) {
        Log.i("$TAG Setting QR code provisioning URI, restarting the Core outside of iterate() loop to apply configuration changes")
        core.nativePreviewWindowId = null
        core.isVideoPreviewEnabled = false
        core.isQrcodeVideoPreviewEnabled = false
        core.provisioningUri = payload

        coreContext.postOnCoreThread { coreOnThread ->
            Log.i("$TAG Stopping Core")
            coreOnThread.stop()
            Log.i("$TAG Core has been stopped, restarting it")
            coreOnThread.start()
            Log.i("$TAG Core has been restarted")
        }
    }

    @UiThread
    fun setBackCamera() {
        coreContext.postOnCoreThread { core ->
            // Just in case, on some devices such as Xiaomi Redmi Note 5
            // this is required right after granting the CAMERA permission
            Log.i("$TAG Reloading video devices, might be needed if CAMERA permission was granted very recently")
            core.reloadVideoDevices()

            if (!coreContext.setBackCamera()) {
                for (camera in core.videoDevicesList) {
                    if (camera != "StaticImage: Static picture") {
                        Log.w("$TAG No back facing camera found, using first one available [$camera]")
                        coreContext.core.videoDevice = camera
                        return@postOnCoreThread
                    }
                }

                Log.e("$TAG No camera device found!")
            }
        }
    }
}
