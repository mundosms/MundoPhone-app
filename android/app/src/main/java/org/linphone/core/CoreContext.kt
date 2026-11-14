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
package org.linphone.core

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.MutableLiveData
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.net.InetAddress
import kotlin.system.exitProcess
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.compatibility.Compatibility
import org.linphone.contacts.ContactsManager
import org.linphone.core.tools.Log
import org.linphone.notifications.NotificationsManager
import org.linphone.telecom.TelecomManager
import org.linphone.utils.ActivityMonitor
import org.linphone.utils.AppUtils
import org.linphone.utils.AudioUtils
import org.linphone.utils.CallPermissions
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils

class CoreContext
    @UiThread
    constructor(val context: Context) : HandlerThread("Core Thread") {
    companion object {
        private const val TAG = "[Core Context]"
    }

    lateinit var core: Core

    fun isCoreAvailable(): Boolean {
        return ::core.isInitialized
    }

    val contactsManager = ContactsManager()

    val notificationsManager = NotificationsManager(context)

    val telecomManager = TelecomManager(context)

    @get:AnyThread
    val sdkVersion: String by lazy {
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkBuildType = org.linphone.core.BuildConfig.BUILD_TYPE
        "$sdkVersion ($sdkBranch, $sdkBuildType)"
    }

    private val activityMonitor = ActivityMonitor()

    private val mainThread = Handler(Looper.getMainLooper())

    var defaultAccountHasVideoConferenceFactoryUri: Boolean = false

    var bearerAuthInfoPendingPasswordUpdate: AuthInfo? = null
    var digestAuthInfoPendingPasswordUpdate: AuthInfo? = null

    var isConnectedToAndroidAuto: Boolean = false

    // MundoPhone (A8): con varias extensiones registradas y sin favorita, la UI pregunta desde cuál llamar
    // (cuentas, a quién, si es vídeo)
    val accountSelectionForCallEvent: MutableLiveData<Event<Triple<List<Account>, Address, Boolean>>> by lazy {
        MutableLiveData<Event<Triple<List<Account>, Address, Boolean>>>()
    }

    // MundoPhone: sin micrófono (o sin cámara, en vídeo) la llamada espera a que MainActivity los pida; si se
    // conceden, sale (waitForCallPermissions)
    val callPermissionsRequiredEvent: MutableLiveData<Event<CallPermissions.Request>> by lazy {
        MutableLiveData()
    }

    /** MundoPhone: MainActivity a la vista (entre onStart y onStop), que es quien pide los permisos de una llamada. */
    @Volatile
    var callPermissionsRequesterVisible = false

    private var callWaitingForPermissions: (() -> Unit)? = null

    val bearerAuthenticationRequestedEvent: MutableLiveData<Event<Pair<String, String?>>> by lazy {
        MutableLiveData()
    }

    val digestAuthenticationRequestedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val clearAuthenticationRequestDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val refreshMicrophoneMuteStateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val showGreenToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData()
    }

    val showRedToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData()
    }

    val showFormattedRedToastEvent: MutableLiveData<Event<Pair<String, Int>>> by lazy {
        MutableLiveData()
    }

    val provisioningAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private var filesToExportToNativeMediaGallery = arrayListOf<String>()
    val filesToExportToNativeMediaGalleryEvent: MutableLiveData<Event<List<String>>> by lazy {
        MutableLiveData()
    }

    private var keepAliveServiceStarted = false

    private lateinit var proximityWakeLock: PowerManager.WakeLock

    @SuppressLint("HandlerLeak")
    private lateinit var coreThread: Handler

    // MundoPhone: al registrar el callback, Android avisa enseguida con TODOS los dispositivos que ya hay, y recargarlos 500 ms
    // después bloqueaba el hilo del Core 40-45 ms en plena ventana del REGISTER/INVITE de un arranque por push (registros
    // del A52s, 21-22/10/2026). El Core ya los cargó al iniciarse, así que ese primer aviso se ignora.
    private var audioDevicesCallbackPrimed = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @WorkerThread
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!audioDevicesCallbackPrimed) {
                audioDevicesCallbackPrimed = true
                Log.i("$TAG Initial list of [${addedDevices?.size ?: 0}] audio device(s) ignored, the Core already has them")
                return
            }
            if (!addedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${addedDevices.size}] new device(s) have been added:")
                var atLeastOneNewDeviceIsBluetooth = false
                var atLeastOneNewDeviceIsHeadset = false
                for (device in addedDevices) {
                    Log.i(
                        "$TAG Added device [${device.productName}] with ID [${device.id}] and type [${device.type}]"
                    )

                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEARING_AID -> {
                            atLeastOneNewDeviceIsBluetooth = true
                        }

                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                            atLeastOneNewDeviceIsHeadset = true
                        }
                        else -> {}
                    }
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()

                    if (atLeastOneNewDeviceIsBluetooth && core.callsNb > 0 && corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG It seems a bluetooth device is now available, trying to route audio to it")
                        AudioUtils.routeAudioBluetoothOrHearingAid()
                    } else if (atLeastOneNewDeviceIsHeadset && core.callsNb > 0) {
                        Log.i("$TAG It seems a headset or headphones device is now available, trying to route audio to it")
                        AudioUtils.routeAudioToHeadset()
                    }
                }, 500)
            }
        }

        @WorkerThread
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!removedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${removedDevices.size}] existing device(s) have been removed")
                for (device in removedDevices) {
                    Log.i(
                        "$TAG Removed device [${device.id}][${device.productName}][${device.type}]"
                    )
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()
                }, 500)
            }
        }
    }

    private var previousCallState = Call.State.Idle

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            defaultAccountHasVideoConferenceFactoryUri = account?.params?.audioVideoConferenceFactoryAddress != null

            val defaultDomain = corePreferences.defaultDomain
            val isAccountOnDefaultDomain = account?.params?.domain == defaultDomain
            val domainFilter = corePreferences.contactsFilter
            Log.i("$TAG Currently selected filter is [$domainFilter]")

            if (!isAccountOnDefaultDomain && domainFilter == defaultDomain) {
                corePreferences.contactsFilter = "*"
                Log.i(
                    "$TAG New default account isn't on default domain, changing filter to any SIP contacts instead"
                )
            } else if (isAccountOnDefaultDomain && domainFilter != "") {
                corePreferences.contactsFilter = defaultDomain
                Log.i("$TAG New default account is on default domain, using that domain as filter instead of wildcard")
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage?>
        ) {
            if (corePreferences.makePublicMediaFilesDownloaded && core.maxSizeForAutoDownloadIncomingFiles >= 0) {
                for (message in messages) {
                    // Never do auto media export for ephemeral messages!
                    if (message?.isEphemeral == true) continue

                    for (content in message?.contents.orEmpty()) {
                        if (content.isFile) {
                            val path = content.filePath
                            if (path.isNullOrEmpty()) continue

                            val mime = "${content.type}/${content.subtype}"
                            val mimeType = FileUtils.getMimeType(mime)
                            when (mimeType) {
                                FileUtils.MimeType.Image, FileUtils.MimeType.Video, FileUtils.MimeType.Audio -> {
                                    Log.i("$TAG Added file path [$path] to the list of media to export to native media gallery")
                                    filesToExportToNativeMediaGallery.add(path)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            if (filesToExportToNativeMediaGallery.isNotEmpty()) {
                Log.i("$TAG Creating event with [${filesToExportToNativeMediaGallery.size}] files to export to native media gallery")
                filesToExportToNativeMediaGalleryEvent.postValue(Event(filesToExportToNativeMediaGallery))
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Log.i("$TAG Global state changed [$state]")

            if (state == GlobalState.On) {
                // Wait for GlobalState.ON as some settings modification won't be saved
                // in RC file if Core isn't ON
                onCoreStarted()
            } else if (state == GlobalState.Shutdown) {
                onCoreStopped()
            }
        }

        @WorkerThread
        override fun onConfiguringStatus(
            core: Core,
            status: ConfiguringState?,
            message: String?
        ) {
            Log.i("$TAG Configuring state changed [$status], message is [$message]")
            if (status == ConfiguringState.Successful) {
                val accounts = core.accountList
                if (core.defaultAccount == null && accounts.isNotEmpty()) {
                    val firstAccount = accounts.firstOrNull()
                    if (firstAccount != null) {
                        val sipUri = firstAccount.params.identityAddress?.asStringUriOnly()
                        Log.w(
                            "$TAG Default account is null but account list isn't empty, using account [$sipUri] as default"
                        )
                        core.defaultAccount = firstAccount
                    }
                }

                // MundoPhone: TCP y push en las cuentas que acaba de crear el QR (el XML del panel es común con iOS)
                MundoPhoneMigrations.afterProvisioning(core)

                provisioningAppliedEvent.postValue(Event(true))
                corePreferences.firstLaunch = false
                showGreenToastEvent.postValue(
                    Event(
                        Pair(
                            org.linphone.R.string.remote_provisioning_config_applied_toast,
                            org.linphone.R.drawable.smiley
                        )
                    )
                )
            } else if (status == ConfiguringState.Failed) {
                showRedToastEvent.postValue(
                    Event(
                        Pair(
                            org.linphone.R.string.remote_provisioning_config_failed_toast,
                            org.linphone.R.drawable.warning_circle
                        )
                    )
                )
            }
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            val currentState = call.state
            Log.i(
                "$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$currentState]"
            )
            when (currentState) {
                Call.State.IncomingReceived -> {
                    if (corePreferences.autoAnswerEnabled) {
                        val autoAnswerDelay = corePreferences.autoAnswerDelay
                        if (autoAnswerDelay == 0) {
                            Log.w("$TAG Auto answering call immediately")
                            MundoPhoneTiempos.answerRequested("respuesta automática")
                            answerCall(call, true)
                        } else {
                            Log.i("$TAG Scheduling auto answering in $autoAnswerDelay milliseconds")
                            postOnCoreThreadDelayed({
                                Log.w("$TAG Auto answering call")
                                MundoPhoneTiempos.answerRequested("respuesta automática")
                                answerCall(call, true)
                            }, autoAnswerDelay.toLong())
                        }
                    }
                }
                Call.State.IncomingEarlyMedia -> {
                    if (core.ringDuringIncomingEarlyMedia) {
                        val speaker = core.audioDevices.find {
                            it.type == AudioDevice.Type.Speaker
                        }
                        if (speaker != null) {
                            Log.i("$TAG Ringing during incoming early media enabled, make sure speaker audio device [${speaker.id}] is used")
                            call.outputAudioDevice = speaker
                        } else {
                            Log.w("$TAG No speaker device found, incoming call early media ringing will be played on default device")
                        }
                    }
                }
                Call.State.OutgoingInit -> {
                    val conferenceInfo = core.findConferenceInformationFromUri(call.remoteAddress)
                    // Do not show outgoing call view for conference calls, wait for connected state
                    if (conferenceInfo == null) {
                        postOnMainThread {
                            showCallActivity()
                        }
                    } else {
                        Log.i(
                            "$TAG Call peer address matches known conference, delaying in-call UI until Connected state"
                        )
                    }
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Trying to route audio to either bluetooth, hearing aid, headphones or headset if available")
                        AudioUtils.routeAudioToAnyConnectedAudioDeviceOtherThanEarpieceAndSpeaker(call)
                    }
                }
                Call.State.Connected -> {
                    postOnMainThread {
                        showCallActivity()
                    }

                    if (previousCallState == Call.State.IncomingEarlyMedia && core.ringDuringIncomingEarlyMedia && !LinphoneUtils.isVideoEnabled(call)) {
                        Log.i("$TAG Audio call is in Connected state and was in IncomingEarlyMedia before with ring during early media enabled")
                        val earpiece = core.audioDevices.find {
                            it.type == AudioDevice.Type.Earpiece
                        }
                        if (earpiece != null) {
                            Log.i("$TAG Switching audio back to earpiece ([${earpiece.id}])")
                            call.outputAudioDevice = earpiece
                        } else {
                            Log.w("$TAG No earpiece device found")
                        }
                    }

                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Call is connected, trying to route audio to either bluetooth, hearing aid, headphones or headset if available")
                        AudioUtils.routeAudioToAnyConnectedAudioDeviceOtherThanEarpieceAndSpeaker(call)
                    }
                }
                Call.State.StreamsRunning -> {
                    if (previousCallState == Call.State.Connected) {
                        if (corePreferences.automaticallyStartCallRecording && !call.params.isRecording) {
                            if (call.conference == null) { // TODO: FIXME: Conference recordings are currently disabled
                                Log.i("$TAG Auto record calls is enabled, starting it now")
                                call.startRecording()
                            }
                        }

                        if (core.isInBackground) {
                            // App is in background which means user likely answered the call from the notification
                            // In this case start proximity sensor, otherwise CallActivity will handle it
                            postOnMainThread {
                                Log.i("$TAG App is in background, start proximity sensor")
                                enableProximitySensor(true)
                            }
                        }
                    }
                }
                Call.State.Error -> {
                    val errorInfo = call.errorInfo
                    Log.w(
                        "$TAG Call error reason is [${errorInfo.reason}](${errorInfo.protocolCode}): ${errorInfo.phrase}"
                    )
                    val text = LinphoneUtils.getCallErrorInfoToast(call)
                    showFormattedRedToastEvent.postValue(
                        Event(Pair(text, org.linphone.R.drawable.warning_circle))
                    )
                }
                else -> {
                }
            }

            previousCallState = currentState
        }

        @WorkerThread
        override fun onTransferStateChanged(core: Core, transfered: Call, state: Call.State) {
            Log.i(
                "$TAG Transferred call [${transfered.remoteAddress.asStringUriOnly()}] state changed [$state]"
            )
            if (state == Call.State.Connected) {
                val icon = org.linphone.R.drawable.phone_transfer
                showGreenToastEvent.postValue(
                    Event(Pair(org.linphone.R.string.call_transfer_successful_toast, icon))
                )
            }
        }

        @WorkerThread
        override fun onAudioDevicesListUpdated(core: Core) {
            Log.i("$TAG Available audio devices list was updated")
        }

        @WorkerThread
        override fun onFirstCallStarted(core: Core) {
            Log.i("$TAG First call started")
        }

        @WorkerThread
        override fun onLastCallEnded(core: Core) {
            Log.i("$TAG Last call ended")
            val currentCamera = core.videoDevice
            if (currentCamera != "FrontFacingCamera") {
                val frontFacing = core.videoDevicesList.find { it == "FrontFacingCamera" }
                if (frontFacing == null) {
                    Log.w("$TAG Failed to find [FrontFacingCamera] camera, doing nothing...")
                } else {
                    Log.i("$TAG Last call ended, setting [$frontFacing] as the default one")
                    core.videoDevice = frontFacing
                }
            }

            postOnMainThread {
                Log.i("$TAG Releasing proximity sensor if it was enabled")
                enableProximitySensor(false)
            }
        }

        @WorkerThread
        override fun onAuthenticationRequested(core: Core, authInfo: AuthInfo, method: AuthMethod) {
            when (method) {
                AuthMethod.Bearer -> {
                    if (authInfo.authorizationServer == null) {
                        Log.e(
                            "$TAG Authentication request using Bearer method but authorization server is null!"
                        )
                        core.abortAuthentication(null)
                        return
                    }

                    val serverUrl = authInfo.authorizationServer
                    val username = authInfo.username
                    if (!serverUrl.isNullOrEmpty()) {
                        Log.i(
                            "$TAG Authentication requested method is Bearer, starting Single Sign On activity with server URL [$serverUrl] and username [$username]"
                        )
                        bearerAuthInfoPendingPasswordUpdate = authInfo
                        bearerAuthenticationRequestedEvent.postValue(
                            Event(Pair(serverUrl, username))
                        )
                    } else {
                        Log.e(
                            "$TAG Authentication requested method is Bearer but no authorization server was found in auth info!"
                        )
                        core.abortAuthentication(null)
                    }
                }
                AuthMethod.HttpDigest -> {
                    if (authInfo.username == null || authInfo.domain == null || authInfo.realm == null) {
                        Log.e(
                            "$TAG Authentication request using Digest method but either username [${authInfo.username}], domain [${authInfo.domain}] or realm [${authInfo.realm}] is null!"
                        )
                        return
                    }

                    val accountFound = core.accountList.find {
                        it.params.identityAddress?.username == authInfo.username && it.params.identityAddress?.domain == authInfo.domain
                    }
                    if (accountFound == null) {
                        Log.w(
                            "$TAG Failed to find account matching auth info, aborting auth dialog"
                        )
                        return
                    }

                    val identity = "${authInfo.username}@${authInfo.domain}"
                    Log.i(
                        "$TAG Authentication requested method is HttpDigest, showing dialog asking user for password for identity [$identity]"
                    )
                    digestAuthInfoPendingPasswordUpdate = authInfo
                    digestAuthenticationRequestedEvent.postValue(Event(identity))
                }
                AuthMethod.Tls -> {
                    Log.w("$TAG Authentication requested method is TLS, not doing anything...")
                }
                else -> {
                    Log.w("$TAG Unexpected authentication request method [$method]")
                }
            }
        }

        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            // Prevent this trigger when core is stopped/start in remote prov
            if (core.globalState == GlobalState.Off) return

            Log.i(
                "$TAG New account configured: [${account.params.identityAddress?.asStringUriOnly()}]"
            )
            if (!account.params.isPushNotificationAvailable) {
                if (!corePreferences.keepServiceAlive) {
                    Log.w(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications, enabling keep-alive foreground service..."
                    )
                    corePreferences.keepServiceAlive = true
                    startKeepAliveService()
                } else {
                    Log.i(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications but keep-alive foreground service is already enabled, nothing to do"
                    )
                }
            }
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            Log.i("$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] removed, clearing auth request dialog if needed")
            if (account.findAuthInfo() == digestAuthInfoPendingPasswordUpdate) {
                Log.i("$TAG Removed account matches auth info pending password update, removing dialog")
                clearAuthenticationRequestDialogEvent.postValue(Event(true))
                digestAuthInfoPendingPasswordUpdate = null
            }

            if (core.defaultAccount == null || core.defaultAccount == account) {
                Log.w("$TAG Removed account was the default one, choosing another as default if possible")
                val newDefaultAccount = core.accountList.find {
                    it.params.isRegisterEnabled
                } ?: core.accountList.firstOrNull()
                if (newDefaultAccount == null) {
                    Log.e("$TAG Failed to find a new default account!")
                } else {
                    Log.i("$TAG New default account will be [${newDefaultAccount.params.identityAddress?.asStringUriOnly()}]")
                    // Delay changing default account to allow for other onAccountRemoved listeners to trigger first
                    postOnCoreThread {
                        core.defaultAccount = newDefaultAccount
                    }
                }
            }
        }
    }

    private var logcatEnabled: Boolean = corePreferences.printLogsInLogcat

    private var crashlyticsEnabled: Boolean = corePreferences.sendLogsToCrashlytics
    private var crashlyticsAvailable = BuildConfig.CRASHLYTICS_ENABLED

    // MundoPhone: las líneas del plugin AAudio, para las marcas del cambio de salida (MundoPhoneAltavoz). Va aparte de la
    // de Crashlytics, que solo se registra si Crashlytics está disponible; sin medida abierta sale al momento.
    private val altavozLoggingListener = object : LoggingServiceListenerStub() {
        @AnyThread
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            MundoPhoneAltavoz.onNativeLog(message)
        }
    }

    private val loggingServiceListener = object : LoggingServiceListenerStub() {
        @WorkerThread
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            if (logcatEnabled) {
                when (level) {
                    LogLevel.Error -> android.util.Log.e(domain, message)
                    LogLevel.Warning -> android.util.Log.w(domain, message)
                    LogLevel.Message -> android.util.Log.i(domain, message)
                    LogLevel.Fatal -> android.util.Log.wtf(domain, message)
                    else -> android.util.Log.d(domain, message)
                }
            }
            if (crashlyticsAvailable && crashlyticsEnabled) {
                FirebaseCrashlytics.getInstance().log("[$domain] [${level.name}] $message")
            }
        }
    }

    private val proximitySensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int
        ) {
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
                if (event.values[0] == 0f) {
                    Log.i("$TAG Proximity sensor triggered, screen will turn off")
                } else {
                    Log.i("$TAG Proximity sensor released, screen will turn back on")
                }
            }
        }
    }

    init {
        (context as Application).registerActivityLifecycleCallbacks(activityMonitor)
    }

    @WorkerThread
    override fun run() {
        Log.i("$TAG Creating Core")
        Looper.prepare()

        if (BuildConfig.CRASHLYTICS_ENABLED) {
            Log.i("$TAG Crashlytics is enabled, registering logging service listener")
            try {
                FirebaseCrashlytics.getInstance()
                Factory.instance().loggingService.addListener(loggingServiceListener)
            } catch (e: Exception) {
                Log.e("$TAG Failed to instantiate Crashlytics: $e")
                crashlyticsEnabled = false
                crashlyticsAvailable = false
            }
        } else {
            Log.i("$TAG Crashlytics is disabled")
            crashlyticsAvailable = false
        }
        // MundoPhone: una sola vez y para siempre (añadir y quitar escuchas con el SDK escribiendo en el registro desde
        // otros hilos no es seguro)
        Factory.instance().loggingService.addListener(altavozLoggingListener)
        Log.i("=========================================")
        Log.i("==== Linphone-android information dump ====")
        val gitVersion = AppUtils.getString(org.linphone.R.string.linphone_app_version)
        val gitBranch = AppUtils.getString(org.linphone.R.string.linphone_app_branch)
        Log.i("VERSION=${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} ($gitVersion from $gitBranch branch)")
        Log.i("PACKAGE=${BuildConfig.APPLICATION_ID}")
        Log.i("BUILD TYPE=${BuildConfig.BUILD_TYPE}")
        Log.i("=========================================")

        val looper = Looper.myLooper() ?: return
        coreThread = Handler(looper)

        // MundoPhone: las versiones de la serie 22 no enseñaban la bienvenida y solo marcaban first_6.0_launch al
        // aprovisionar por QR: quien actualiza desde ellas no la ve. Aquí y no en onCoreStarted, junto a las
        // migraciones: MainActivity mira firstLaunch en cuanto isReady() y su tarea en este hilo puede correr antes que
        // startCore (50 ms después).
        if (MundoPhoneMigrations.isVersion22(corePreferences.linphoneConfigurationVersion) && corePreferences.firstLaunch) {
            Log.i("$TAG Updating from a configuration without welcome screens: they won't be shown")
            corePreferences.firstLaunch = false
        }

        core = Factory.instance().createCoreWithConfig(corePreferences.config, context)
        MundoPhoneTiempos.coreCreated()
        // MundoPhone: ¿configuración copiada de otro aparato (clon, restauración)? Antes de core.start(), que es cuando el
        // SDK lee el [misc] uuid
        MundoPhoneIdentidad.comprobar(context, corePreferences.config)
        core.isAutoIterateEnabled = true
        core.addListener(coreListener)
        // MundoPhone (F4): marcas de tiempo del arranque por push y del descolgar; solo escucha
        core.addListener(MundoPhoneTiempos.coreListener)
        // MundoPhone (F2): aviso tras una llamada con señales objetivas de que fue mal (fallo, calidad, pérdidas)
        core.addListener(CallProblemReport.coreListener)

        defaultAccountHasVideoConferenceFactoryUri = core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != null

        coreThread.postDelayed({ startCore() }, 50)

        Looper.loop()
    }

    override fun quit(): Boolean {
        destroyCore()
        return super.quit()
    }

    override fun quitSafely(): Boolean {
        destroyCore()
        return super.quitSafely()
    }

    @WorkerThread
    fun startCore() {
        Log.i("$TAG Starting Core")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, coreThread)

        val accounts = core.accountList
        if (core.defaultAccount == null && accounts.isNotEmpty()) {
            Log.e("$TAG No default account set but accounts list not empty!")
            val firstAccount = accounts.first()
            core.defaultAccount = firstAccount
            Log.w("$TAG Set account [${firstAccount?.params?.identityAddress?.asStringUriOnly()}] as default")
        }

        computeUserAgent()
        Log.i("$TAG Core has been configured with user-agent [${core.userAgent}], starting it")
        core.start()
    }

    @WorkerThread
    fun onCoreStarted() {
        Log.i("$TAG Core started, updating configuration if required")
        core.videoCodecPriorityPolicy = CodecPriorityPolicy.Auto

        // Set in the Core the list of directories from which it is allowed to delete a file related to a chat message
        val paths = arrayListOf<String>()
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            paths.add(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath.orEmpty())
            paths.add(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath.orEmpty())
            paths.add(context.getExternalFilesDir(Compatibility.getRecordingsDirectory())?.absolutePath.orEmpty())
        }
        paths.add(context.filesDir?.absolutePath.orEmpty())
        val pathsArray = arrayOfNulls<String>(paths.size)
        paths.toArray(pathsArray)
        core.setChatMessageFilesDirectories(pathsArray)
        for (path in paths) {
            Log.i("$TAG Adding path [$path] to list of directories from which Core is allowed to delete files from")
        }

        // MundoPhone: la numeración de upstream, no nuestro versionCode (app/build.gradle.kts)
        val currentVersion = BuildConfig.LINPHONE_CONFIG_VERSION
        // MundoPhone: la serie 22 guardaba su versionCode; se presenta como 6.0.9 (ver MundoPhoneMigrations)
        val storedVersion = corePreferences.linphoneConfigurationVersion
        val oldVersion = MundoPhoneMigrations.upstreamConfigVersion(storedVersion)
        Log.w("$TAG Current configuration version is [$oldVersion]")

        if (oldVersion < currentVersion) {
            Log.w("$TAG Migrating configuration to [$currentVersion]")

            if (oldVersion < 600000) { // 6.0.0 initial release
                configurationMigration5To6()
            } else if (oldVersion < 600004) { // 6.0.4
                disablePushNotificationsFromThirdPartySipAccounts()
            } else if (oldVersion < 600009) { // 6.0.9
                removePortFromSipIdentity()
            } else if (oldVersion < 602000) { // 6.2.0
                if (!core.isChatMessageFilesDeletionEnabled) {
                    core.isChatMessageFilesDeletionEnabled = true
                    Log.i("$TAG Core is allowed to automatically delete files from previously logged directories when a chat message is deleted")
                }

                val rlsUri = core.config.getString("sip", "rls_uri", "").orEmpty()
                if (rlsUri.isNotEmpty()) {
                    var accountOnDefaultDomainFound = false
                    for (account in core.accountList) {
                        if (account.params.identityAddress?.domain == corePreferences.defaultDomain) {
                            accountOnDefaultDomainFound = true
                            break
                        }
                    }
                    if (!accountOnDefaultDomainFound) {
                        Log.w("$TAG Removing rls_uri from [sip] section & all friend lists to prevent sending SUBSCRIBE")
                        core.config.setString("sip", "rls_uri", "")
                        for (friendList in core.friendsLists) {
                            if (friendList.rlsAddress != null) {
                                Log.i("$TAG Removing RLS URI from friend list [${friendList.displayName}]")
                                friendList.rlsAddress = null
                            }
                        }
                    } else {
                        Log.i("$TAG Keeping RLS URI as an account on the default domain has been found")
                    }
                }
            }

            if (core.logCollectionUploadServerUrl.isNullOrEmpty()) {
                Log.w("$TAG Logs sharing server URL not set, fixing that")
                core.logCollectionUploadServerUrl = "https://files.linphone.org/http-file-transfer-server/hft.php"
            }

            corePreferences.linphoneConfigurationVersion = currentVersion
            Log.w(
                "$TAG Core configuration updated to version [${corePreferences.linphoneConfigurationVersion}]"
            )
        } else {
            Log.i("$TAG No configuration migration required")
        }
        // MundoPhone: después de las de upstream, porque su 5→6 (instalación nueva) acepta el vídeo solo
        MundoPhoneMigrations.afterCoreStart(core)
        // MundoPhone: configuración copiada de otro aparato (MundoPhoneIdentidad): token de Firebase propio. Unos segundos
        // después de arrancar, para llegar después del getToken del SDK al iniciar, que devolvería el token copiado
        if (MundoPhoneIdentidad.renovarTokenAlArrancar) {
            MundoPhoneIdentidad.tokenRenovado()
            postOnCoreThreadDelayed({
                MundoPhonePushToken.renovar("configuration copied from another device")
            }, 3000)
        }

        contactsManager.onCoreStarted(core)
        telecomManager.onCoreStarted(core)
        // Re-create channels when migrating from a non 6.0 version (MundoPhone: también una vez al venir de la serie
        // 22)
        notificationsManager.onCoreStarted(core, oldVersion < 600000 || MundoPhoneMigrations.isVersion22(storedVersion))
        Log.i("$TAG Started contacts, telecom & notifications managers")

        if (corePreferences.keepServiceAlive) {
            if (activityMonitor.isInForeground() || corePreferences.autoStart) {
                Log.i("$TAG Keep alive service is enabled and either app is in foreground or auto start is enabled, starting it")
                startKeepAliveService()
            } else {
                Log.w("$TAG Keep alive service is enabled but auto start isn't and app is not in foreground, not starting it")
            }
        }

        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            Log.w("$TAG PROXIMITY_SCREEN_OFF_WAKE_LOCK isn't supported on this device!")
        } else {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "${context.packageName};proximity_sensor"
            )
        }
    }

    @WorkerThread
    private fun onCoreStopped() {
        Log.w("$TAG Core is being shut down, notifying managers so they can remove their listeners and do some cleanup if needed")
        contactsManager.onCoreStopped(core)
        telecomManager.onCoreStopped(core)
        notificationsManager.onCoreStopped(core)
    }

    @WorkerThread
    private fun destroyCore() {
        if (!::core.isInitialized) {
            return
        }

        val state = core.globalState
        if (state != GlobalState.On) {
            Log.w("$TAG Core is in state [$state], do not continue destroy process")
            return
        }
        Log.w("$TAG Stopping Core and destroying context related objects")

        postOnMainThread {
            (context as Application).unregisterActivityLifecycleCallbacks(activityMonitor)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioDevicesCallbackPrimed = false

        core.stop()

        // It's very unlikely the process will survive until the Core reaches GlobalStateOff sadly
        Log.w("$TAG Core has been shut down")
        exitProcess(0)
    }

    @AnyThread
    fun isReady(): Boolean {
        return ::core.isInitialized
    }

    @AnyThread
    fun postOnCoreThread(
        @WorkerThread lambda: (core: Core) -> Unit
    ) {
        if (::coreThread.isInitialized) {
            coreThread.post {
                lambda.invoke(core)
            }
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadDelayed(
        @WorkerThread lambda: (core: Core) -> Unit,
        delay: Long
    ) {
        if (::coreThread.isInitialized) {
            coreThread.postDelayed({
                lambda.invoke(core)
            }, delay)
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadWhenAvailableForHeavyTask(@WorkerThread lambda: (core: Core) -> Unit, name: String) {
        postOnCoreThread {
            if (core.callsNb >= 1) {
                Log.i("$TAG At least one call is active, wait until there is no more call before executing lambda [$name] (checking again in 1 sec)")
                coreContext.postOnCoreThreadDelayed({
                    postOnCoreThreadWhenAvailableForHeavyTask(lambda, name)
                }, 1000)
            } else {
                Log.i("$TAG No active call at the moment, executing lambda [$name] right now")
                lambda.invoke(core)
            }
        }
    }

    @AnyThread
    fun postOnMainThread(
        @UiThread lambda: () -> Unit
    ) {
        mainThread.post {
            lambda.invoke()
        }
    }

    @AnyThread
    fun postOnMainThreadDelayed(
        @WorkerThread lambda: () -> Unit,
        delay: Long
    ) {
        mainThread.postDelayed({
            lambda.invoke()
        }, delay)
    }

    @UiThread
    fun onForeground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in foreground, PUBLISHING presence as Online")
                core.consolidatedPresence = ConsolidatedPresence.Online
            }

            if (corePreferences.keepServiceAlive && !keepAliveServiceStarted) {
                startKeepAliveService()
            }
        }
    }

    @UiThread
    fun onBackground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in background, un-PUBLISHING presence info")
                // We don't use ConsolidatedPresence.Busy but Offline to do an unsubscribe,
                // Flexisip will handle the Busy status depending on other devices
                core.consolidatedPresence = ConsolidatedPresence.Offline
            }
        }
    }

    @WorkerThread
    fun updateAuthInfo(password: String) {
        val authInfo = digestAuthInfoPendingPasswordUpdate
        if (authInfo != null) {
            Log.i(
                "$TAG Updating password for username [${authInfo.username}] using auth info [$authInfo]"
            )
            authInfo.password = password
            core.addAuthInfo(authInfo)
            digestAuthInfoPendingPasswordUpdate = null
            core.refreshRegisters()
        } else {
            Log.e("$TAG No pending auth info for digest authentication!")
        }
    }

    @AnyThread
    fun abortBearerAuthIfAny() {
        coreContext.postOnCoreThread { core ->
            if (bearerAuthInfoPendingPasswordUpdate != null) {
                Log.e("$TAG Aborting bearer authentication")
                core.abortAuthentication(bearerAuthInfoPendingPasswordUpdate)
            }
        }
    }

    @WorkerThread
    fun isAddressMyself(address: Address): Boolean {
        val found = core.accountList.find {
            it.params.identityAddress?.weakEqual(address) == true
        }
        return found != null
    }

    @WorkerThread
    fun clearFilesToExportToNativeGallery() {
        filesToExportToNativeMediaGallery.clear()
    }

    @WorkerThread
    fun startAudioCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false
    ) {
        // MundoPhone: sin micrófono se pide antes, en vez de llamar con el micrófono en silencio
        val retry = { startAudioCall(address, forceZRTP, localAddress, skipNetworkReachabilityTest) }
        if (waitForCallPermissions(false, retry)) return

        val from = localAddress ?: when (val choice = accountForCall(address, false)) {
            is AccountChoice.Ask -> return
            is AccountChoice.Use -> choice.address
        }
        val params = core.createCallParams(null)
        params?.isVideoEnabled = false
        startCall(address, params, forceZRTP, from, skipNetworkReachabilityTest)
    }

    @WorkerThread
    fun startVideoCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false
    ) {
        // MundoPhone: sin micrófono o sin cámara se piden antes de llamar
        val retry = { startVideoCall(address, forceZRTP, localAddress, skipNetworkReachabilityTest) }
        if (waitForCallPermissions(true, retry)) return

        val from = localAddress ?: when (val choice = accountForCall(address, true)) {
            is AccountChoice.Ask -> return
            is AccountChoice.Use -> choice.address
        }
        val params = core.createCallParams(null)
        params?.isVideoEnabled = true
        params?.videoDirection = MediaDirection.SendRecv
        startCall(address, params, forceZRTP, from, skipNetworkReachabilityTest)
    }

    /**
     * MundoPhone: sin micrófono, y en vídeo sin cámara, la llamada no sale con el micrófono
     * en silencio. Si MainActivity está a la vista, los pide (callPermissionsRequiredEvent) y [retry] relanza la llamada
     * al concederlos; devuelve true si la llamada queda esperando. Sin MainActivity delante (Android Auto, la redirección
     * desde la app Teléfono, una llamada nueva desde la pantalla de llamada) sale como en upstream: la pantalla de
     * llamada avisa y deja pedir el permiso desde el botón del micrófono.
     */
    @WorkerThread
    private fun waitForCallPermissions(isVideoCall: Boolean, retry: () -> Unit): Boolean {
        val missing = CallPermissions.missing(isVideoCall) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return false
        if (!callPermissionsRequesterVisible || isConnectedToAndroidAuto) {
            Log.w("$TAG Missing permissions $missing to call and the main screen isn't shown, calling anyway")
            return false
        }
        Log.w("$TAG Missing permissions $missing to call, asking for them before starting it")
        callWaitingForPermissions = retry
        callPermissionsRequiredEvent.postValue(Event(CallPermissions.Request(missing, SystemClock.elapsedRealtime())))
        return true
    }

    /** MundoPhone: lo que contestó el usuario a callPermissionsRequiredEvent; con todo concedido, la llamada sale. */
    @WorkerThread
    fun onCallPermissionsResult(granted: Boolean) {
        val retry = callWaitingForPermissions ?: return
        callWaitingForPermissions = null
        if (granted) {
            Log.i("$TAG Permissions to call granted, starting the pending call")
            retry()
        } else {
            Log.w("$TAG Permissions to call not granted, the pending call won't start")
        }
    }

    /**
     * MundoPhone (A8): desde qué extensión sale una llamada para la que no se ha dicho (el QR hace normal tener varias).
     * - Una sola registrada (o ninguna): la de siempre del SDK (AccountChoice.Use(null)).
     * - Varias, y la cuenta por defecto es la favorita que marcó el usuario: esa.
     * - Varias y sin favorita: devuelve null y pide a la UI que pregunte (accountSelectionForCallEvent); la llamada la
     *   relanza el diálogo con la cuenta elegida.
     */
    private sealed interface AccountChoice {
        /** Llamar desde esta identidad (null = la que elija el SDK, la cuenta por defecto). */
        class Use(val address: Address?) : AccountChoice

        /** Se ha preguntado al usuario: la llamada la relanza el diálogo. */
        object Ask : AccountChoice
    }

    @WorkerThread
    private fun accountForCall(address: Address, isVideoCall: Boolean): AccountChoice {
        val registered = core.accountList.filter { it.state == RegistrationState.Ok }
        if (registered.size <= 1) return AccountChoice.Use(null)

        val favorite = userFavoriteAccount()
        if (favorite != null && favorite.state == RegistrationState.Ok) {
            Log.i("$TAG Several registered accounts, using the user's favorite [${favorite.params.identityAddress?.asStringUriOnly()}]")
            return AccountChoice.Use(favorite.params.identityAddress)
        }
        Log.i("$TAG Several registered accounts and no favorite, asking the user which one to call from")
        accountSelectionForCallEvent.postValue(Event(Triple(registered, address, isVideoCall)))
        return AccountChoice.Ask
    }

    @WorkerThread
    private fun userFavoriteAccount(): Account? {
        val favorite = corePreferences.config.getString("app", "user_favorite_account", null)
        if (favorite.isNullOrEmpty()) return null
        return core.accountList.firstOrNull { it.params.identityAddress?.asStringUriOnly() == favorite }
    }

    @WorkerThread
    fun isUserFavoriteAccount(account: Account): Boolean {
        val favorite = corePreferences.config.getString("app", "user_favorite_account", null)
        return !favorite.isNullOrEmpty() && account.params.identityAddress?.asStringUriOnly() == favorite
    }

    /** La favorita es también la cuenta por defecto; null la quita (vuelve a preguntar si hay varias). */
    @WorkerThread
    fun setUserFavoriteAccount(account: Account?) {
        if (account != null) {
            core.defaultAccount = account
            corePreferences.config.setString("app", "user_favorite_account", account.params.identityAddress?.asStringUriOnly())
        } else {
            corePreferences.config.setString("app", "user_favorite_account", null)
        }
        Log.i("$TAG User favorite account set to [${account?.params?.identityAddress?.asStringUriOnly()}]")
    }

    @WorkerThread
    fun startCall(
        address: Address,
        callParams: CallParams? = null,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false
    ) {
        if (!skipNetworkReachabilityTest && !core.isNetworkReachable) {
            Log.e("$TAG Network unreachable, abort outgoing call")
            return
        }

        val currentCall = core.currentCall
        if (currentCall != null) {
            Log.w(
                "$TAG Found current call [${currentCall.remoteAddress.asStringUriOnly()}], pausing it first"
            )
            currentCall.pause()
        }

        val params = callParams ?: core.createCallParams(null)
        if (params == null) {
            val call = core.inviteAddress(address)
            Log.w("$TAG Starting call $call without params")
            return
        }

        if (forceZRTP) {
            params.mediaEncryption = MediaEncryption.ZRTP
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(address)

        if (localAddress != null) {
            val account = core.accountList.find { account ->
                account.params.identityAddress?.weakEqual(localAddress) == true
            }
            if (account != null) {
                params.account = account
                Log.i(
                    "$TAG Using account matching address ${localAddress.asStringUriOnly()} as From"
                )
            } else {
                val defaultAccount = core.defaultAccount
                params.account = defaultAccount
                Log.e(
                    "$TAG Failed to find account matching address ${localAddress.asStringUriOnly()}, using default one [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]"
                )
            }
        } else {
            val defaultAccount = core.defaultAccount
            params.account = defaultAccount
            Log.i("$TAG No local address given, using default account [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]")
        }

        val username = address.username.orEmpty()
        val domain = address.domain.orEmpty()
        val account = params.account ?: core.defaultAccount
        if (account != null && Compatibility.isIpAddress(domain)) {
            Log.i("$TAG SIP URI [${address.asStringUriOnly()}] seems to have an IP address as domain")
            val isPhoneNumber = username.isNotEmpty() && (username.startsWith("+") || username.isDigitsOnly())
            // MundoPhone: las llamadas internas llegan de la centralita con From en @127.0.0.1, y así quedan en el
            // historial. Upstream solo cambia el dominio IP cuando el usuario es un número, así que al devolver la
            // llamada a una extensión con nombre («ana_wrtc_mundosms») el INVITE salía a 127.0.0.1:5060, o sea
            // al propio móvil, y la llamada no llegaba a ningún sitio. Una IP local o
            // privada nunca es alcanzable desde el móvil: se llama siempre por el dominio de la cuenta.
            val isLocalIp = isLocalOrPrivateIp(domain)
            if (username.isNotEmpty() && (isPhoneNumber || isLocalIp)) {
                val identityDomain = account.params.identityAddress?.domain
                Log.w(
                    "$TAG Username [$username] is a phone number [$isPhoneNumber] or domain [$domain] is a local IP [$isLocalIp], replacing domain by the local account one [$identityDomain]"
                )
                if (identityDomain != null) {
                    val newAddress = address.clone()
                    newAddress.domain = identityDomain
                    newAddress.port = 0 // el puerto de la IP no vale para el dominio de la cuenta

                    core.inviteAddressWithParams(newAddress, params)
                    Log.i("$TAG Starting call to [${newAddress.asStringUriOnly()}]")
                    return
                }
            }
        }

        core.inviteAddressWithParams(address, params)
        Log.i("$TAG Starting call to [${address.asStringUriOnly()}]")
    }

    /** MundoPhone: IP que el móvil no puede usar para llegar a la centralita (loopback, privada, de enlace local). */
    private fun isLocalOrPrivateIp(domain: String): Boolean {
        return try {
            // Solo se llama con un literal de IP (isIpAddress), así que no hay consulta de DNS
            val ip = InetAddress.getByName(domain.removePrefix("[").removeSuffix("]"))
            val bytes = ip.address
            ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) // IPv6 única local (fc00::/7)
        } catch (e: Exception) {
            Log.w("$TAG Can't parse [$domain] as an IP address: $e")
            false
        }
    }

    @WorkerThread
    fun switchCamera() {
        val currentDevice = core.videoDevice
        Log.i("$TAG Current camera device is $currentDevice")

        for (camera in core.videoDevicesList) {
            if (camera != currentDevice && camera != "StaticImage: Static picture") {
                Log.i("$TAG New camera device will be $camera")
                core.videoDevice = camera
                break
            }
        }

        val call = core.currentCall
        if (call == null) {
            Log.w("$TAG Switching camera while not in call")
            return
        }
        call.update(null)
    }

    @WorkerThread
    fun showSwitchCameraButton(): Boolean {
        return core.isVideoCaptureEnabled && core.videoDevicesList.size > 2 // Count StaticImage camera
    }

    @WorkerThread
    fun answerCall(call: Call, autoAnswer: Boolean = false) {
        Log.i(
            "$TAG Answering call with remote address [${call.remoteAddress.asStringUriOnly()}] and to address [${call.toAddress.asStringUriOnly()}]"
        )
        val params = core.createCallParams(call)
        if (params == null) {
            Log.w("$TAG Answering call without params!")
            MundoPhoneTiempos.accepting(call)
            call.accept()
            MundoPhoneTiempos.accepted()
            return
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(call.remoteAddress)

        /*if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Log.w("$TAG Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }*/

        if (call.callLog.wasConference()) {
            // Prevent incoming group call to start in audio only layout
            // Do the same as the conference waiting room
            params.isVideoEnabled = true
            params.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
            Log.i(
                "$TAG Enabling video on call params to prevent audio-only layout when answering"
            )
        } else if (autoAnswer) {
            val videoBothWays = corePreferences.autoAnswerVideoCallsWithVideoDirectionSendReceive
            if (videoBothWays) {
                Log.i("$TAG Call is being auto-answered, requesting video in both ways according to user setting")
                params.videoDirection = MediaDirection.SendRecv
            }
        }

        // MundoPhone (F4): dentro del accept van el 200 OK (Connected) y casi siempre StreamsRunning
        MundoPhoneTiempos.accepting(call)
        call.acceptWithParams(params)
        MundoPhoneTiempos.accepted()
    }

    @WorkerThread
    fun terminateCall(call: Call) {
        val conference = call.conference
        if (conference != null) {
            Log.i("$TAG Terminating conference [${call.remoteAddress.asStringUriOnly()}]")
            conference.terminate()
        } else {
            if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
                val reason = if (call.core.callsNb > 1) Reason.Busy else Reason.Declined
                Log.i(
                    "$TAG Declining call [${call.remoteAddress.asStringUriOnly()}] with reason [$reason]"
                )
                call.decline(reason)
            } else {
                Log.i("$TAG Terminating call [${call.remoteAddress.asStringUriOnly()}]")
                call.terminate()
            }
        }
    }

    @UiThread
    fun showCallActivity() {
        Log.i("$TAG Starting Call activity")
        val intent = LinphoneUtils.getCallActivityIntent(context)
        val options = Compatibility.getPendingIntentActivityOptions(true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle()
        )

        val senderOptions = Compatibility.getPendingIntentActivityOptions(false)
        Compatibility.sendPendingIntent(pendingIntent, senderOptions.toBundle())
    }

    @WorkerThread
    fun startKeepAliveService() {
        if (keepAliveServiceStarted) {
            Log.w("$TAG Keep alive service already started, skipping")
        }

        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i("$TAG Starting Keep alive for third party accounts Service")
        try {
            context.startService(serviceIntent)
            keepAliveServiceStarted = true
        } catch (e: Exception) {
            Log.e("$TAG Failed to start keep alive service: $e")
        }
    }

    @WorkerThread
    fun stopKeepAliveService() {
        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i(
            "$TAG Stopping Keep alive for third party accounts Service"
        )
        context.stopService(serviceIntent)
        keepAliveServiceStarted = false
    }

    @WorkerThread
    fun computeUserAgent() {
        val savedDeviceName = corePreferences.deviceName
        val deviceName = if (savedDeviceName.isEmpty()) {
            Log.i("$TAG Device name not fetched yet, doing it now")
            AppUtils.getDeviceName(context)
        } else if (savedDeviceName.contains("'")) {
            // Some VoIP providers such as voip.ms seem to not like apostrophe in user-agent
            // https://github.com/BelledonneCommunications/linphone-android/issues/2287
            Log.i("$TAG Found an apostrophe in device name, removing it")
            savedDeviceName.replace("'", "")
        } else {
            savedDeviceName
        }
        if (savedDeviceName != deviceName) {
            corePreferences.deviceName = deviceName
        }
        Log.i("$TAG Device name for user-agent is [$deviceName]")

        val appName = context.getString(org.linphone.R.string.app_name)
        val androidVersion = BuildConfig.VERSION_NAME
        // MundoPhone: «MundoPhone Android/<versión>», con espacio: el borde decide por ahí (Opus según la versión de la
        // app). Upstream lo junta («MundoPhoneAndroid/…») y la app se quedaba fuera
        val userAgent = "$appName Android/$androidVersion ($deviceName) LinphoneSDK"
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkUserAgent = "$sdkVersion ($sdkBranch)"
        core.setUserAgent(userAgent, sdkUserAgent)
    }

    @WorkerThread
    private fun removePortFromSipIdentity() {
        for (account in core.accountList) {
            val params = account.params
            val identity = params.identityAddress
            if (identity != null && identity.port != 0) {
                val clone = params.clone()
                val newIdentity = identity.clone()
                newIdentity.port = 0
                clone.identityAddress = newIdentity
                Log.w("$TAG Found account with identity address [${identity.asStringUriOnly()}] that contains port information in domain, removing port information in new identity [${newIdentity.asStringUriOnly()}]")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun disablePushNotificationsFromThirdPartySipAccounts() {
        for (account in core.accountList) {
            val params = account.params
            val pushAvailableForDomain = params.identityAddress?.domain in corePreferences.pushNotificationCompatibleDomains
            if (!pushAvailableForDomain && params.pushNotificationAllowed) {
                val clone = params.clone()
                clone.pushNotificationAllowed = false
                Log.w("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to disable push notifications, they won't work and may cause issues when used with UDP transport protocol")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun configurationMigration5To6() {
        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = true
        policy.automaticallyAcceptDirection = MediaDirection.RecvOnly
        core.videoActivationPolicy = policy
        Log.i(
            "$TAG Updated video activation policy to disable auto initiate, enable auto accept with media direction RecvOnly"
        )

        core.isFecEnabled = true
        Log.i("$TAG Video FEC has been enabled")

        core.config.setBool("magic_search", "return_empty_friends", true)
        Log.i("$TAG Showing 'empty' friends enabled")

        if (LinphoneUtils.getDefaultAccount()?.params?.domain == corePreferences.defaultDomain) {
            corePreferences.contactsFilter = corePreferences.defaultDomain
            Log.i(
                "$TAG Setting default contacts list filter to [${corePreferences.contactsFilter}]"
            )
        }

        for (account in core.accountList) {
            val params = account.params
            if (params.identityAddress?.domain == corePreferences.defaultDomain && params.limeAlgo.isNullOrEmpty()) {
                val clone = params.clone()
                clone.limeAlgo = "c25519"
                Log.i("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to use LIME algo c25519")
                account.params = clone
            }
        }

        Log.i("$TAG Making sure both RFC2833 & SIP INFO are enabled for DTMFs")
        core.useRfc2833ForDtmf = true
        core.useInfoForDtmf = true

        // Add that flag back, was disabled for a time during dev process
        Log.i("$TAG Enabling hiding empty chat rooms")
        core.config.setBool("misc", "hide_empty_chat_rooms", true)

        // Replace old URLs by new ones
        if (corePreferences.checkForUpdateServerUrl == "https://www.linphone.org/releases") {
            corePreferences.checkForUpdateServerUrl = "https://download.linphone.org/releases"
        }
        if (core.fileTransferServer == "https://www.linphone.org:444/lft.php") {
            core.fileTransferServer = "https://files.linphone.org/http-file-transfer-server/hft.php"
        }
        if (core.logCollectionUploadServerUrl == "https://www.linphone.org:444/lft.php") {
            core.logCollectionUploadServerUrl = "https://files.linphone.org/http-file-transfer-server/hft.php"
        }

        Log.i("$TAG IMDN threshold set to 1 (meaning only sender will receive delivery & read notifications)")
        core.imdnToEverybodyThreshold = 1

        Log.i("$TAG Removing previous grammar files (without .belr extension)")
        corePreferences.clearPreviousGrammars()
    }

    @WorkerThread
    fun isCrashlyticsAvailable(): Boolean {
        return crashlyticsAvailable
    }

    @WorkerThread
    fun updateLogcatEnabledSetting(enabled: Boolean) {
        logcatEnabled = enabled
    }

    @WorkerThread
    fun updateCrashlyticsEnabledSetting(enabled: Boolean) {
        crashlyticsEnabled = enabled
    }

    @UiThread
    fun enableProximitySensor(enable: Boolean) {
        if (enable && !corePreferences.useProximitySensor) {
            Log.w("$TAG App tried to enable proximity sensor but it's been disabled in settings, doing nothing")
            return
        }

        if (::proximityWakeLock.isInitialized) {
            if (enable && !proximityWakeLock.isHeld) {
                Log.i("$TAG Acquiring proximity sensor wake lock for 2 hours")
                proximityWakeLock.acquire(7200 * 1000L) // 2 hours

                val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
                val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                val added = sensorManager.registerListener(proximitySensorListener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
                if (!added) {
                    Log.e("$TAG Failed to add proximity sensor listener!")
                }
            } else if (!enable && proximityWakeLock.isHeld) {
                Log.i("$TAG Releasing proximity sensor wake lock")
                val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
                sensorManager.unregisterListener(proximitySensorListener)

                proximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
    }

    fun setBackCamera(): Boolean {
        val list = core.videoDevicesList
        for (camera in list) {
            if (camera.contains("Back")) {
                Log.i("$TAG Found back facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        Log.i("$TAG Back camera wasn't found in [${list.size}] detected video devices")
        return false
    }

    fun setFrontCamera(): Boolean {
        val list = core.videoDevicesList
        for (camera in list) {
            if (camera.contains("Front")) {
                Log.i("$TAG Found front facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        Log.i("$TAG Front camera wasn't found in [${list.size}] detected video devices")
        return false
    }
}
