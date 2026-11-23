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
package org.linphone.ui.main.help.viewmodel

import android.os.Build
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.launch
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.CallProblemReport
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MundoPhonePushToken
import org.linphone.core.SupportLogsUpload
import org.linphone.core.VersionUpdateCheckResult
import org.linphone.core.MundoPhoneSegundoPlano
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import java.net.URLEncoder

class HelpViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Help ViewModel]"

        private const val NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE = 6

        // MundoPhone: el SDK descodifica la URL de subida y la vuelve a codificar (belle-sip, generic-uri): el «+» de
        // URLEncoder llegaba como «+» y no como espacio, y un «&» del usuario se quedaba sin codificar y partía el campo.
        // Con %20 y el «&» de ancho completo los dos viajes dejan el texto igual.
        private fun queryValue(value: String): String =
            URLEncoder.encode(value.replace('&', '＆'), "UTF-8").replace("+", "%20")
    }

    val version = MutableLiveData<String>()

    val appVersion = MutableLiveData<String>()

    val sdkVersion = MutableLiveData<String>()

    val firebaseProjectId = MutableLiveData<String>()

    val checkUpdateAvailable = MutableLiveData<Boolean>()

    val uploadLogsAvailable = MutableLiveData<Boolean>()

    val logsUploadInProgress = MutableLiveData<Boolean>()

    val printLogsInLogcat = MutableLiveData<Boolean>()

    val developerSettingsEnabled = MutableLiveData<Boolean>()

    val canConfigFileBeViewed = MutableLiveData<Boolean>()

    val newVersionAvailableEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData()
    }

    // MundoPhone: «Buscar actualizaciones» va a Google Play (HelpFragment → MainActivity), no al servidor de Linphone
    val checkPlayUpdateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val versionUpToDateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val errorEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val debugLogsCleanedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val uploadDebugLogsFinishedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val uploadDebugLogsErrorEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val showConfigFileEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    private var versionClickCount: Int = 0

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onVersionUpdateCheckResultReceived(
            core: Core,
            result: VersionUpdateCheckResult,
            version: String?,
            url: String?
        ) {
            when (result) {
                VersionUpdateCheckResult.NewVersionAvailable -> {
                    Log.i("$TAG Update available, version [$version], url [$url]")
                    if (!version.isNullOrEmpty() && !url.isNullOrEmpty()) {
                        newVersionAvailableEvent.postValue(Event(Pair(version, url)))
                    }
                }
                VersionUpdateCheckResult.UpToDate -> {
                    Log.i("$TAG This version is up-to-date")
                    versionUpToDateEvent.postValue(Event(true))
                }
                else -> {
                    Log.e("$TAG Can't check for update, an error happened [$result]")
                    errorEvent.postValue(Event(true))
                }
            }
        }

        @WorkerThread
        override fun onLogCollectionUploadStateChanged(
            core: Core,
            state: Core.LogCollectionUploadState,
            info: String
        ) {
            Log.i("$TAG Logs upload state changed [$state]")
            if (state == Core.LogCollectionUploadState.Delivered) {
                logsUploadInProgress.postValue(false)
                uploadDebugLogsFinishedEvent.postValue(Event(info))
            } else if (state == Core.LogCollectionUploadState.NotDelivered) {
                logsUploadInProgress.postValue(false)
                uploadDebugLogsErrorEvent.postValue(Event(true))
            }
        }
    }

    init {
        val currentVersion = BuildConfig.VERSION_NAME
        version.value = currentVersion

        val versionCode = BuildConfig.VERSION_CODE
        val appGitDescribe = AppUtils.getString(R.string.linphone_app_version)
        val appBranch = AppUtils.getString(R.string.linphone_app_branch)
        appVersion.value = "$versionCode - $appGitDescribe ($appBranch)"

        sdkVersion.value = coreContext.sdkVersion
        logsUploadInProgress.value = false

        try {
            firebaseProjectId.value = FirebaseApp.getInstance().options.projectId
        } catch (e: Exception) {
            Log.e("$TAG Failed to get FirebaseApp instance: $e")
            firebaseProjectId.value = "unknown"
        }

        versionClickCount = if (corePreferences.showDeveloperSettings) {
            Log.i("$TAG Developer settings are already enabled")
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE
        } else {
            0
        }

        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            printLogsInLogcat.postValue(corePreferences.printLogsInLogcat)
            developerSettingsEnabled.postValue(corePreferences.showDeveloperSettings)
            // MundoPhone: siempre, la búsqueda es la de Google Play (el comprobador de Linphone no tiene servidor)
            checkUpdateAvailable.postValue(true)
            uploadLogsAvailable.postValue(corePreferences.logsUploadUrl.isNotEmpty())
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
    }

    @UiThread
    fun versionClicked() {
        versionClickCount += 1
        Log.i("$TAG Version was clicked [$versionClickCount] times")
        when (versionClickCount) {
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE - 2 -> {
                showGreenToast(R.string.settings_developer_two_more_clicks_required_toast, R.drawable.gear)
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE - 1 -> {
                showGreenToast(R.string.settings_developer_one_more_click_required_toast, R.drawable.gear)
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE -> {
                showGreenToast(R.string.settings_developer_enabled_toast, R.drawable.gear)
                coreContext.postOnCoreThread {
                    Log.w("$TAG Enabling developer settings")
                    corePreferences.showDeveloperSettings = true
                    developerSettingsEnabled.postValue(true)
                }
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE + 1 -> {
                showGreenToast(R.string.settings_developer_already_enabled_toast, R.drawable.gear)
            }
            else -> { }
        }
    }

    /**
     * MundoPhone: token de Firebase nuevo, para cuando el móvil ha dejado de recibir llamadas porque Google dio por
     * muerto el suyo (MundoPhonePushToken). Soporte se lo puede pedir al usuario sin reinstalar.
     */
    @UiThread
    fun renewPushToken() {
        MundoPhonePushToken.renovar("asked from Help > Troubleshooting") { ok ->
            if (ok) {
                showGreenToast(R.string.help_troubleshooting_renew_push_done, R.drawable.bell)
            } else {
                showRedToast(R.string.help_troubleshooting_renew_push_error, R.drawable.warning_circle)
            }
        }
    }

    @UiThread
    fun cleanLogs() {
        coreContext.postOnCoreThread { core ->
            core.resetLogCollection()
            Log.i("$TAG Debug logs have been cleaned")
            debugLogsCleanedEvent.postValue(Event(true))
        }
    }

    val showShareLogsFormEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    // MundoPhone (F2): el formulario con una llamada concreta (historial o aviso tras colgar): su cabecera
    val showCallReportFormEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    @UiThread
    fun shareLogs() {
        // MundoPhone: antes de enviar se pide qué ha pasado (DebugFragment), para que a soporte no le llegue a ciegas
        showShareLogsFormEvent.value = Event(true)
    }

    /** MundoPhone (F2): abre el formulario con la llamada [callId] y, si viene del aviso, por qué se avisó. */
    @UiThread
    fun reportCallProblem(callId: String, reason: String?) {
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Opening logs form for call [$callId]")
            showCallReportFormEvent.postValue(Event(CallProblemReport.header(core, callId, reason)))
        }
    }

    /**
     * Sube el registro al servidor de soporte (mundophonedebug). Lo que escribe el usuario, sus extensiones, la versión de
     * la app y el móvil van en la URL de subida: el SDK solo manda el fichero, sin User-Agent. Con [callHeader] (F2), el
     * texto va detrás de la cabecera de la llamada. Nada de eso se escribe en el registro (SupportLogsUpload).
     */
    @UiThread
    fun uploadLogsToSupport(description: String, contact: String, callHeader: String? = null) {
        coreContext.postOnCoreThread { core ->
            val accounts = core.accountList.mapNotNull { it.params.identityAddress?.username }.joinToString(",")
            // En Xiaomi, además, la capa (HyperOS/MIUI) y la lectura de «Inicio automático», para saber con el tiempo en
            // qué modelos y versiones se puede leer (MundoPhoneSegundoPlano.resumenParaSoporte)
            val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}" +
                MundoPhoneSegundoPlano.resumenParaSoporte(coreContext.context)
            val text = listOfNotNull(callHeader, description.ifEmpty { null }).joinToString("\n")
            val url = corePreferences.logsUploadUrl +
                "?d=" + queryValue(text) +
                "&c=" + queryValue(contact) +
                "&a=" + queryValue(accounts) +
                "&v=" + queryValue(BuildConfig.VERSION_NAME) +
                "&m=" + queryValue(device)
            Log.i("$TAG Uploading debug logs to support server${if (callHeader != null) " (about a call)" else ""}")
            if (SupportLogsUpload.upload(core, url)) {
                logsUploadInProgress.postValue(true)
            } else {
                uploadDebugLogsErrorEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun checkForUpdate() {
        // MundoPhone: Google Play (actualización integrada); ofrece la versión nueva aunque se haya ignorado hoy
        Log.i("$TAG Checking Google Play for an update (requested by the user)")
        checkPlayUpdateEvent.value = Event(true)
    }

    @UiThread
    fun toggleLogcat() {
        val newValue = printLogsInLogcat.value == false
        coreContext.postOnCoreThread {
            corePreferences.printLogsInLogcat = newValue
            coreContext.updateLogcatEnabledSetting(newValue)
            Factory.instance().enableLogcatLogs(newValue)
            printLogsInLogcat.postValue(newValue)
        }
    }

    @UiThread
    fun showConfigFile() {
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Dumping & displaying Core's config")
            val config = core.config.dump()
            val file = FileUtils.getFileStorageCacheDir(
                "linphonerc.txt",
                overrideExisting = true
            )
            viewModelScope.launch {
                if (FileUtils.dumpStringToFile(config, file)) {
                    Log.i("$TAG .linphonerc string saved as file in cache folder")
                    showConfigFileEvent.postValue(Event(file.absolutePath))
                } else {
                    Log.e("$TAG Failed to save .linphonerc string as file in cache folder")
                }
            }
        }
    }
}
