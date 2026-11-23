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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.MundoPhoneAprovisionamiento
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class QrCodeViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Qr Code Scanner ViewModel]"

        // MundoPhone: la API del panel, por https. El QR del panel apunta a un enlace corto de urla.ws que redirige ahí. De
        // allí la app descarga ella misma el XML de la cuenta (MundoPhoneAprovisionamiento), sin mandar nada de las demás.
        private const val PROVISIONING_HOST = "api.mundosms.es"
        private const val QR_XML_FILE = MundoPhoneAprovisionamiento.FICHERO_QR
        private const val MAX_REDIRECTS = 5
        private const val HTTP_TIMEOUT_MS = 8000
    }

    val remoteProvisioningSuccessfulEvent = MutableLiveData<Event<Boolean>>()

    val onErrorEvent = MutableLiveData<Event<Boolean>>()

    // MundoPhone: el QR en curso no es de MundoSMS (se le da al SDK, como upstream): si falla, se dice eso y no el error
    // genérico. En iOS, el 21/11/2026, el lector leyó un QR de cartuchos de Canon y salió «error al aplicar la configuración».
    private var foreignQr = false

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onConfiguringStatus(core: Core, status: ConfiguringState, message: String?) {
            Log.i("$TAG Configuring state is [$status]")
            if (status != ConfiguringState.Skipped) {
                // MundoPhone: el XML del QR (lleva la clave de su cuenta) no se queda en disco, y si no se pudo aplicar
                // tampoco su file:// en la configuración, que el SDK volvería a intentar en cada arranque
                if (core.provisioningUri.orEmpty().endsWith("/$QR_XML_FILE")) core.provisioningUri = null
                qrXmlFile().delete()
            }
            if (status == ConfiguringState.Failed) {
                Log.e("$TAG Failure applying remote provisioning: $message")
                val toast = if (foreignQr) {
                    R.string.assistant_qr_code_not_mundosms_toast
                } else {
                    R.string.remote_provisioning_config_failed_toast
                }
                showRedToast(toast, R.drawable.warning_circle)
                onErrorEvent.postValue(Event(true))
            }
            if (status != ConfiguringState.Skipped) foreignQr = false
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

        // MundoPhone: el QR puede traer el XML de configuración entero (antes se le daba al SDK como URI y lo rechazaba)
        if (payload.startsWith("<config") || payload.startsWith("<?xml")) {
            applyXml(core, payload)
            return
        }

        val url = LinphoneUtils.getRemoteProvisioningUrlFromUri(payload)
        if (url == null) {
            Log.e("$TAG The content of the QR Code doesn't seem to be a valid web URL")
            showRedToast(R.string.assistant_qr_code_invalid_toast, R.drawable.warning_circle)
            return
        }

        // MundoPhone: el XML de la cuenta lo descarga la app, sin mandar nada de las que ya tiene (antes iban en la URL
        // el usuario y el MD5 de la clave de todas). Primero se resuelve el enlace corto, también sin datos.
        Thread({
            val finalUrl = resolveRedirects(url)
            if (finalUrl == null) {
                coreContext.postOnCoreThread { coreOnThread ->
                    val nuestro = MundoPhoneAprovisionamiento.esEnlaceDeMundoSms(url)
                    if (coreOnThread.accountList.isEmpty()) {
                        // Un QR que no es del panel (otro proveedor): sin cuentas que pisar, lo aplica el SDK, como upstream
                        Log.w(
                            "$TAG QR link didn't lead to https://$PROVISIONING_HOST, letting the SDK apply it (MundoSMS link [$nuestro])"
                        )
                        foreignQr = !nuestro
                        applyProvisioningPayload(coreOnThread, url)
                    } else {
                        Log.e(
                            "$TAG QR link didn't lead to https://$PROVISIONING_HOST, existing accounts could be overwritten " +
                                "(MundoSMS link [$nuestro])"
                        )
                        // Uno del panel que no se pudo seguir (sin red…) se puede reintentar; uno ajeno, no
                        val toast = if (nuestro) {
                            R.string.assistant_qr_code_link_not_verified_toast
                        } else {
                            R.string.assistant_qr_code_not_mundosms_toast
                        }
                        showRedToast(toast, R.drawable.warning_circle)
                        onErrorEvent.postValue(Event(true))
                    }
                }
                return@Thread
            }
            val xml = downloadXml(finalUrl)
            if (xml != null) {
                coreContext.postOnCoreThread { coreOnThread -> applyXml(coreOnThread, xml) }
            } else {
                onErrorEvent.postValue(Event(true))
            }
        }, "QR link resolver").start()
    }

    private class Respuesta(val code: Int, val bytes: Int?, val xml: String?, val message: String)

    /**
     * El XML de la cuenta del QR, o null (ya avisado). Los campos del enlace (con la firma) van en el cuerpo de un POST, que
     * no queda en ningún registro de URLs; en la URL, para el registro del panel (nginx), que no los usa ni los firma:
     * source, la app, su versión y la extensión (x). Nada de las cuentas que ya tiene la app. El panel contesta con un JSON
     * de error si el enlace ha caducado, no es válido o la extensión no existe: su «message» va al registro.
     */
    @WorkerThread
    private fun downloadXml(url: String): String? {
        val extra = listOf(
            "source" to "linphonexml",
            "app" to "MundoPhone-Android",
            "ver" to "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"
        )
        val post = MundoPhoneAprovisionamiento.peticionPost(url, extra)
        var answer = request(post)
        // Transición: el panel acepta el POST desde una entrega reciente. Hasta que esté subida
        // solo lee el enlace de la URL y contesta que le faltan campos o que no es válido: entonces, una vez, como antes.
        // Quitar cuando la entrega lleve tiempo en producción.
        if (post.cuerpo != null && answer?.xml == null &&
            (answer?.message.orEmpty().contains("not valid", true) || answer?.message.orEmpty().contains("missing", true))
        ) {
            Log.w("$TAG Panel didn't take the link by POST [${answer?.message}], trying again by GET")
            answer = request(MundoPhoneAprovisionamiento.peticionGet(url, extra))
        }
        if (answer?.xml != null) {
            Log.i("$TAG QR code configuration downloaded from [${hostOf(url)}], [${answer.bytes}] bytes")
            return answer.xml
        }
        if (answer != null) {
            Log.e("$TAG QR code configuration not downloaded: HTTP [${answer.code}], [${answer.bytes}] bytes, message [${answer.message}]")
        }
        val message = answer?.message.orEmpty()
        if (message.contains("expired", ignoreCase = true)) {
            showRedToast(R.string.assistant_qr_code_expired_toast, R.drawable.warning_circle)
        } else if (message.contains("already used", ignoreCase = true)) {
            // El panel gasta el enlace al entregar la configuración (un solo uso, con unos minutos de margen)
            showRedToast(R.string.assistant_qr_code_used_toast, R.drawable.warning_circle)
        } else {
            showRedToast(R.string.remote_provisioning_config_failed_toast, R.drawable.warning_circle)
        }
        return null
    }

    /** Una petición al panel; null si ni siquiera se pudo hacer (ya registrado). */
    @WorkerThread
    private fun request(peticion: MundoPhoneAprovisionamiento.Peticion): Respuesta? {
        val connection = try {
            URL(peticion.url).openConnection() as? HttpURLConnection
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            if (peticion.cuerpo != null) {
                val bytes = peticion.cuerpo.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { readAtMost(it) }
            val text = body?.toString(Charsets.UTF_8)?.trim()?.removePrefix("\uFEFF")?.trim().orEmpty()
            if (code in 200..299 && body != null && body.size <= MundoPhoneAprovisionamiento.MAX_BYTES &&
                (text.startsWith("<config") || text.startsWith("<?xml"))
            ) {
                return Respuesta(code, body.size, text, "")
            }
            val message = try {
                JSONObject(text).optString("message").take(80)
            } catch (_: Exception) {
                ""
            }
            return Respuesta(code, body?.size, null, message)
        } catch (e: Exception) {
            Log.e("$TAG Failed to download QR code configuration from [${hostOf(peticion.url)}]: ${e.javaClass.simpleName}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** Lee como mucho MAX_BYTES + 1: con uno de más ya se sabe que se pasa. */
    @WorkerThread
    private fun readAtMost(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= MundoPhoneAprovisionamiento.MAX_BYTES) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun qrXmlFile(): File = File(coreContext.context.noBackupFilesDir, QR_XML_FILE)

    /**
     * Añade la cuenta del XML sin tocar las demás: con el Core parado (al parar, el SDK reescribe sus cuentas en la
     * configuración), se renumera contra lo que hay y se le da al SDK como file://, que lo aplica al arrancar y antes de
     * leer las cuentas.
     */
    @WorkerThread
    private fun applyXml(core: Core, xml: String) {
        core.nativePreviewWindowId = null
        core.isVideoPreviewEnabled = false
        core.isQrcodeVideoPreviewEnabled = false

        // MundoPhone: el mismo QR otra vez (la misma cuenta, con la misma contraseña) no reinicia el Core: no hay nada que
        // aplicar, y cada reinicio seguido arriesga un cierre dentro del SDK (A52s, 21/11/2026, ver sinCambios)
        val sinCambios = try {
            MundoPhoneAprovisionamiento.sinCambios(xml, MundoPhoneAprovisionamiento.leerExistentes(core.config))
        } catch (e: Exception) {
            Log.w("$TAG Couldn't compare the QR code configuration with the current one: ${e.javaClass.simpleName}")
            false
        }
        if (sinCambios) {
            Log.i("$TAG QR code account is already configured with the same password, nothing to apply")
            // Por la pantalla principal: la del QR se cierra en ese mismo momento y su aviso no se llegaría a ver
            coreContext.showGreenToastEvent.postValue(
                Event(Pair(R.string.assistant_qr_code_already_configured_toast, R.drawable.check))
            )
            remoteProvisioningSuccessfulEvent.postValue(Event(true))
            return
        }

        Log.i("$TAG Adding the QR code account, restarting the Core outside of iterate() loop to apply it")

        coreContext.postOnCoreThread { coreOnThread ->
            Log.i("$TAG Stopping Core")
            coreOnThread.stop()
            try {
                val existing = MundoPhoneAprovisionamiento.leerExistentes(coreOnThread.config)
                val result = MundoPhoneAprovisionamiento.reescribir(xml, existing)
                qrXmlFile().writeText(result.xml)
                coreOnThread.provisioningUri = "file://${qrXmlFile().absolutePath}"
                Log.i(
                    "$TAG QR code configuration ready: [${result.nuevas}] new account(s), [${result.actualizadas}] updated, " +
                        "[${existing.cuentas.size}] already there"
                )
            } catch (e: Exception) {
                Log.e("$TAG Invalid QR code configuration: ${e.message}")
                showRedToast(R.string.remote_provisioning_config_failed_toast, R.drawable.warning_circle)
                onErrorEvent.postValue(Event(true))
            }
            Log.i("$TAG Core has been stopped, restarting it")
            coreOnThread.start()
            Log.i("$TAG Core has been restarted")
        }
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
