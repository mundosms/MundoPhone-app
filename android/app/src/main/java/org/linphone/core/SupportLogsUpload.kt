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

import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log

/**
 * MundoPhone (F2): la subida del registro a soporte, sin que lo que escribe el usuario (ni el número de la llamada de la
 * que informa) se quede en el móvil.
 *
 * El texto viaja en la URL de subida (?d=…&c=…&a=…&m=…: el servidor no se puede cambiar) y eso lo dejaba en dos sitios:
 * - la configuración: el SDK guarda la URL en [misc] log_collection_upload_server_url y nadie la devolvía a su valor (se
 *   veía en «Ver configuración» y en los ajustes de desarrollador hasta el siguiente envío). Ahora se restaura al acabar,
 *   y MundoPhoneMigrations limpia la que dejaron las versiones anteriores;
 * - el registro: belle-sip escribe a nivel «message» cada petición HTTP que manda, con su primera línea
 *   («POST /upload?d=… HTTP/1.1»: send_buffer y make_logbuf, channel.c), y eso acababa en el registro del SIGUIENTE
 *   envío y en logcat. Como en iOS, mientras dura la subida solo se registran errores y fatales; se vuelve al nivel de
 *   antes con el estado final (Delivered o NotDelivered) o, de respaldo, a los 180 s. Antes del estado final no hay un
 *   momento seguro: el SDK avisa InProgress al recibir el 204 de la primera petición, ANTES de mandar la segunda, y el
 *   progreso del cuerpo puede llegar antes de que salga la cabecera (cuerpo pequeño: van juntos en el mismo envío).
 *   Queda un caso: si la subida FALLA, el SDK escribe la URL en su línea de error («I/O Error during log collection
 *   upload to …», «Unexpected HTTP response code … during log collection upload to …», linphonecore.c). Los errores no
 *   se tapan, para no quedarse ciego justo cuando algo va mal.
 * Y antes de subir, el registro recibe la configuración de la app sin secretos (SupportLogsConfig): líneas
 * «[Configuración] …» al final del fichero, justo antes de «Uploading logs», y el estado de segundo plano
 * (MundoPhoneSegundoPlano.registrar).
 */
object SupportLogsUpload {
    private const val TAG = "[Support Logs Upload]"

    // Respaldo si nunca llega el estado final (el mismo que iOS). La URL también espera: el SDK la vuelve a leer al
    // recibir el 204 para mandar la segunda petición
    private const val SAFETY_RESTORE_MS = 180_000L

    private var inProgress = false
    private var generation = 0
    private var savedUrl: String? = null
    private var savedLogMask: Int? = null

    private val listener = object : CoreListenerStub() {
        @WorkerThread
        override fun onLogCollectionUploadStateChanged(
            core: Core,
            state: Core.LogCollectionUploadState,
            info: String
        ) {
            if (state == Core.LogCollectionUploadState.Delivered || state == Core.LogCollectionUploadState.NotDelivered) {
                finish(core)
            }
        }
    }

    /**
     * Sube el registro a [url] (la de soporte con sus parámetros). Devuelve false si ya hay una subida en marcha: el SDK
     * no la haría y escribiría la URL en un warning («Could not upload log collection…»).
     */
    @WorkerThread
    fun upload(core: Core, url: String): Boolean {
        if (inProgress) {
            Log.w("$TAG A logs upload is already in progress, not starting another one")
            return false
        }
        inProgress = true
        val currentGeneration = ++generation

        val previous = core.logCollectionUploadServerUrl.orEmpty()
        // La de las versiones anteriores se quedaba con el último texto enviado: esa no se restaura
        savedUrl = if (previous.contains('?')) MundoPhoneMigrations.LOGS_UPLOAD_URL else previous
        core.addListener(listener)

        // La configuración de la app sin secretos (SupportLogsConfig), ANTES de bajar el nivel: con solo errores no se
        // escribiría. Cada línea llega al fichero de la colección en el momento (fprintf + fflush, linphonecore.c) y
        // uploadLogCollection comprime ese fichero en esta misma llamada, antes de mandar nada: va en lo que se sube
        for (line in SupportLogsConfig.logLines(core.config.dump())) Log.i(line)
        // Y lo que Android deja hacer a la app en segundo plano, en cualquier marca: el del arranque puede haber salido ya
        // de los ficheros si la app lleva días abierta
        MundoPhoneSegundoPlano.registrar(coreContext.context)

        val loggingService = Factory.instance().loggingService
        savedLogMask = loggingService.logLevelMask
        Log.i("$TAG Uploading logs; only errors are logged until the upload ends")
        loggingService.setLogLevelMask(LogLevel.Error.toInt() or LogLevel.Fatal.toInt())

        core.logCollectionUploadServerUrl = url
        core.uploadLogCollection()

        coreContext.postOnCoreThreadDelayed({
            if (inProgress && generation == currentGeneration) {
                Log.w("$TAG No final upload state after [${SAFETY_RESTORE_MS / 1000}] s, restoring log level and upload URL")
                finish(it)
            }
        }, SAFETY_RESTORE_MS)
        return true
    }

    @WorkerThread
    private fun finish(core: Core) {
        if (!inProgress) return
        savedLogMask?.let { Factory.instance().loggingService.setLogLevelMask(it) }
        savedLogMask = null
        core.removeListener(listener)
        savedUrl?.let { core.logCollectionUploadServerUrl = it }
        savedUrl = null
        inProgress = false
        Log.i("$TAG Logs upload finished, log level and upload server URL restored")
    }
}
