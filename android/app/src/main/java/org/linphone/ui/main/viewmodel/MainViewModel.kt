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
package org.linphone.ui.main.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.MessageWaitingIndication
import org.linphone.core.MundoPhoneSegundoPlano
import org.linphone.core.RegistrationState
import org.linphone.core.VFS
import org.linphone.core.tools.AndroidPlatformHelper
import org.linphone.core.tools.Log
import org.linphone.telecom.redirection.CallRedirection
import org.linphone.telecom.redirection.CallRedirectionOffer
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils

class MainViewModel
    @UiThread
    constructor() : ViewModel() {
    companion object {
        private const val TAG = "[Main ViewModel]"

        const val NONE = 0

        // MundoPhone: la actualización de Play bajándose (AppUpdateChecker); la prioridad más baja, no tapa a ninguna
        const val APP_UPDATE_DOWNLOADING = 3
        const val MWI_MESSAGES_WAITING = 4
        const val NON_DEFAULT_ACCOUNT_NOTIFICATIONS = 5
        const val NON_DEFAULT_ACCOUNT_NOT_CONNECTED = 10
        const val FULL_SCREEN_INTENTS_PERMISSION_NOT_GRANTED = 14
        const val SEND_NOTIFICATIONS_PERMISSION_NOT_GRANTED = 15

        // MundoPhone: Android puede no despertar la app con el push de una llamada (ver MundoPhoneSegundoPlano)
        const val APP_RESTRICTED_IN_BACKGROUND = 16
        const val DEFAULT_ACCOUNT_DISABLED = 18
        const val NETWORK_NOT_REACHABLE = 19

        // MundoPhone: los mismos fichero y clave que la serie 22
        private const val APP_HIBERNATION_PREFS = "permission_tracking_prefs"
        private const val DO_NOT_SHOW_APP_HIBERNATION_WARNING = "dont_show_auto_revoke_warning"
    }

    val showAlert = MutableLiveData<Boolean>()

    val maxAlertLevel = MutableLiveData<Int>()

    val alertLabel = MutableLiveData<String>()

    val alertIcon = MutableLiveData<Int>()

    val atLeastOneCall = MutableLiveData<Boolean>()

    val callLabel = MutableLiveData<String>()

    val callsStatus = MutableLiveData<String>()

    val pendingFilesOrTextSharing = MutableLiveData<Boolean>()

    val filesOrTextPendingSharingLabel = MutableLiveData<String>()

    val goBackToCallEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val openDrawerEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val askPostNotificationsPermissionEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val askFullScreenIntentPermissionEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // MundoPhone: enseñar cómo dejar que Android despierte la app (true = Xiaomi)
    val showBackgroundGuideEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val askAccessLocalNetworkPermissionEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val showNewAccountToastEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val startLoadingContactsEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val lastAccountRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val clearFilesOrTextPendingSharingEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // MundoPhone: ofrecer una vez «Llamar con MundoPhone desde el teléfono» (MainActivity)
    val offerCallRedirectionEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private var callRedirectionOfferPosted = false

    // MundoPhone: aviso de que Android puede quitarle los permisos a la app por no usarla (hibernación)
    val showAppHibernationWarningEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private var appHibernationWarningPosted = false

    // MundoPhone: en cada apertura, un solo aviso de los de al abrir (showOpeningNoticeIfNeeded)
    private var openingNoticeChosen = false

    private var accountsFound = -1

    var mainIntentHandled = false

    private val alertsList = arrayListOf<Pair<Int, String>>()

    // MundoPhone: el usuario cerró la barra con el aviso de descarga a la vista: no vuelve hasta la próxima descarga
    private var appUpdateAlertClosed = false

    private var firstAccountRegistered: Boolean = false

    private var monitorAccount = false

    private var nonDefaultAccountNotificationsCount = 0

    private var mwiNewMessages = false

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            Log.i("$TAG Core's global state is now [${core.globalState}]")
            if (core.globalState == GlobalState.On) {
                computeNonDefaultAccountNotificationsCount()
            }
        }

        @WorkerThread
        override fun onLastCallEnded(core: Core) {
            Log.i("$TAG Last call ended, removing in-call 'alert'")
            atLeastOneCall.postValue(false)
            computeNonDefaultAccountNotificationsCount()
        }

        @WorkerThread
        override fun onFirstCallStarted(core: Core) {
            Log.i("$TAG First call started, adding in-call 'alert'")
            updateCallAlert()
            coreContext.postOnCoreThreadDelayed({
                if (core.callsNb > 0) {
                    Log.i("$TAG At least a call is active, showing 'alert' top bar")
                    atLeastOneCall.postValue(true)
                } else {
                    Log.i("$TAG No call found, do not show 'alert' top bar")
                }
            }, 1000L)
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            Log.i("$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed to [$state], updating 'alerts' if needed")
            if (
                core.callsNb > 1 && (
                    LinphoneUtils.isCallEnding(call.state) ||
                        LinphoneUtils.isCallIncoming(call.state) ||
                        LinphoneUtils.isCallOutgoing(call.state)
                    )
            ) {
                updateCallAlert()
            } else if (core.callsNb == 1) {
                if (callsStatus.value.orEmpty().isEmpty()) {
                    // In case there was more than one call and now there is only one left
                    updateCallAlert()
                } else if (call.state != Call.State.Released) {
                    // When you have two calls, when the one is ended core.callsNb will become 1
                    // but the ended one will still go to Released state after that
                    callsStatus.postValue(LinphoneUtils.callStateToString(call.state))
                }
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            Log.i("$TAG Message(s) received, updating notifications count if needed")
            val account = LinphoneUtils.getAccountForAddress(chatRoom.localAddress)
            if (account != null && account != core.defaultAccount) {
                computeNonDefaultAccountNotificationsCount()
            }
        }

        @WorkerThread
        override fun onMessageRetracted(core: Core, chatRoom: ChatRoom, message: ChatMessage) {
            val account = LinphoneUtils.getAccountForAddress(chatRoom.localAddress)
            if (account != null && account != core.defaultAccount) {
                computeNonDefaultAccountNotificationsCount()
            }
        }

        @WorkerThread
        override fun onNetworkReachable(core: Core, reachable: Boolean) {
            Log.i(
                "$TAG According to SDK, network is ${if (reachable) "reachable" else "not reachable"}"
            )
            checkNetworkReachability()
        }

        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (!monitorAccount) return

            when (state) {
                RegistrationState.Failed -> {
                    if (account == core.defaultAccount) {
                        Log.e("$TAG Default account registration failed!")
                        val label = AppUtils.getString(
                            R.string.connection_error_for_non_default_account
                        )
                        addAlert(DEFAULT_ACCOUNT_DISABLED, label)
                    } else if (core.isNetworkReachable) {
                        Log.e("$TAG Non-default account registration failed!")
                        val label = AppUtils.getString(
                            R.string.connection_error_for_non_default_account
                        )
                        addAlert(NON_DEFAULT_ACCOUNT_NOT_CONNECTED, label)
                    }
                    checkAccessLocalNetworkPermission()
                }
                RegistrationState.Ok -> {
                    removeAlert(NETWORK_NOT_REACHABLE) // Just in case

                    showOpeningNoticeIfNeeded(core)

                    if (!firstAccountRegistered) {
                        triggerNativeAddressBookImport()
                    }

                    if (account == core.defaultAccount) {
                        Log.i("$TAG Default account is now registered")
                        removeAlert(DEFAULT_ACCOUNT_DISABLED)
                    } else {
                        // If no call and no account is in Failed state, hide top bar
                        val found = core.accountList.find {
                            it.state == RegistrationState.Failed
                        }
                        if (found == null) {
                            removeAlert(NON_DEFAULT_ACCOUNT_NOT_CONNECTED)
                        }
                    }
                }
                RegistrationState.Progress, RegistrationState.Refreshing -> {
                    if (account == core.defaultAccount) {
                        Log.i(
                            "$TAG Default account is registering, removing registration failed alert for now"
                        )
                        removeAlert(DEFAULT_ACCOUNT_DISABLED)
                    }
                }
                RegistrationState.Cleared -> {
                    if (account == core.defaultAccount) {
                        Log.w("$TAG Default account is now disabled")
                        val label = AppUtils.getString(
                            R.string.default_account_disabled
                        )
                        addAlert(DEFAULT_ACCOUNT_DISABLED, label)
                    }
                }
                else -> {}
            }
        }

        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            if (!monitorAccount) return
            if (core.globalState != GlobalState.On) return // In case of late remote provisioning

            if (account == null) {
                Log.w("$TAG Default account is now null!")
            } else {
                Log.i(
                    "$TAG Default account changed, now is [${account.params.identityAddress?.asStringUriOnly()}]"
                )

                removeAlert(DEFAULT_ACCOUNT_DISABLED)
                removeAlert(NON_DEFAULT_ACCOUNT_NOT_CONNECTED)
                // Refresh REGISTER to re-compute alerts regarding accounts registration state
                core.refreshRegisters()

                if (!account.params.isRegisterEnabled) {
                    val label = AppUtils.getString(
                        R.string.default_account_disabled
                    )
                    addAlert(DEFAULT_ACCOUNT_DISABLED, label)
                }

                val mwi = account.latestReceivedMessageWaitingIndication
                if (mwi != null) {
                    parseMwiEvent(mwi)
                } else {
                    removeAlert(MWI_MESSAGES_WAITING)
                    mwiNewMessages = false
                }
            }

            computeNonDefaultAccountNotificationsCount()
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            if (!monitorAccount) return
            if (core.globalState != GlobalState.On) return // In case of late remote provisioning

            Log.w(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] has been removed!"
            )
            removeAlert(DEFAULT_ACCOUNT_DISABLED)
            removeAlert(NON_DEFAULT_ACCOUNT_NOT_CONNECTED)
            // Refresh REGISTER to re-compute alerts regarding accounts registration state
            core.refreshRegisters()

            computeNonDefaultAccountNotificationsCount()

            if (core.accountList.isEmpty()) {
                Log.w("$TAG No more account configured, going into assistant")
                lastAccountRemovedEvent.postValue(Event(true))
            }
        }

        @WorkerThread
        override fun onMessageWaitingIndicationChanged(
            core: Core,
            event: org.linphone.core.Event,
            mwi: MessageWaitingIndication
        ) {
            val address = mwi.accountAddress
            val defaultAccountAddress = core.defaultAccount?.params?.identityAddress
            if (defaultAccountAddress != null && address?.weakEqual(defaultAccountAddress) == true) {
                parseMwiEvent(mwi)
            }
        }
    }

    init {
        showAlert.value = false
        atLeastOneCall.value = false
        maxAlertLevel.value = NONE
        nonDefaultAccountNotificationsCount = 0

        pendingFilesOrTextSharing.value = false
        filesOrTextPendingSharingLabel.value = ""

        enableAccountMonitoring(true)

        coreContext.postOnCoreThread { core ->
            accountsFound = core.accountList.size

            core.addListener(coreListener)

            if (!core.isNetworkReachable && core.globalState == GlobalState.On) {
                Log.w("$TAG Core is ON & network is not reachable!")
                val label = AppUtils.getString(R.string.network_not_reachable)
                addAlert(NETWORK_NOT_REACHABLE, label)
            }

            if (core.callsNb > 0) {
                updateCallAlert()
                atLeastOneCall.postValue(true)
            }

            val defaultAccount = core.defaultAccount
            if (defaultAccount != null) {
                if (!defaultAccount.params.isRegisterEnabled) {
                    val label = AppUtils.getString(
                        R.string.default_account_disabled
                    )
                    addAlert(DEFAULT_ACCOUNT_DISABLED, label)
                }

                if (defaultAccount.state == RegistrationState.Ok && !firstAccountRegistered) {
                    triggerNativeAddressBookImport()
                }

                mwiNewMessages = false
                val mwi = defaultAccount.latestReceivedMessageWaitingIndication
                if (mwi != null) {
                    parseMwiEvent(mwi)
                }
            }

            showOpeningNoticeIfNeeded(core)
        }

        updateMissingPermissionAlert()

        if (VFS.isEnabled(coreContext.context)) {
            val cache = corePreferences.vfsCachePath
            viewModelScope.launch {
                val notClearedCount = FileUtils.countFilesInDirectory(cache)
                if (notClearedCount > 0) {
                    Log.w(
                        "$TAG [VFS] There are [$notClearedCount] plain files not cleared from previous app lifetime, removing them now"
                    )
                    FileUtils.clearExistingPlainFiles(cache)
                }
            }
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
    fun updateMissingPermissionAlert() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            coreContext.postOnCoreThread {
                checkFullScreenIntentNotificationPermission()
                checkPostNotificationsPermission()
                checkBackgroundRestrictions()
            }
        } else {
            // MundoPhone: también por debajo de Android 13, donde se pueden apagar en los ajustes sin permiso
            coreContext.postOnCoreThread {
                checkPostNotificationsPermission()
                checkBackgroundRestrictions()
            }
        }
    }

    @UiThread
    fun updateAccountsAndNetworkReachability() {
        coreContext.postOnCoreThread { core ->
            val accounts = core.accountList
            val count = accounts.size
            if (count > accountsFound) {
                Log.i("$TAG Newly added account detected!")
                showNewAccountToastEvent.postValue(Event(true))
            }
            accountsFound = count

            checkNetworkReachability()

            for (account in accounts) {
                if (account.state == RegistrationState.Failed) {
                    val identity = account.params.identityAddress?.asStringUriOnly().orEmpty()
                    Log.i("$TAG Account [$identity] registration state is failed, refreshing REGISTER")
                    account.refreshRegister()
                }
            }
        }
    }

    @UiThread
    fun closeTopBar() {
        showAlert.value = false

        coreContext.postOnCoreThread {
            if (alertsList.any { it.first == APP_UPDATE_DOWNLOADING }) appUpdateAlertClosed = true
            Log.i("$TAG User closed alerts top bar, clearing alerts")
            showAlert.postValue(false)
            alertsList.clear()
            updateDisplayedAlert()
        }
    }

    @UiThread
    fun onTopBarClicked() {
        if (atLeastOneCall.value == true) {
            goBackToCallEvent.value = Event(true)
        } else if (maxAlertLevel.value == APP_RESTRICTED_IN_BACKGROUND) {
            // MundoPhone: antes que los permisos, para que el toque haga lo que dice la barra que se ve
            showBackgroundGuideEvent.value = Event(MundoPhoneSegundoPlano.esXiaomi())
        } else if (!Compatibility.hasFullScreenIntentPermission(coreContext.context)) {
            askFullScreenIntentPermissionEvent.value = Event(true)
        } else if (!Compatibility.areNotificationsEnabled(coreContext.context)) { // MundoPhone: también apagadas (< 13)
            askPostNotificationsPermissionEvent.value = Event(true)
        } else if (mwiNewMessages) {
            coreContext.postOnCoreThread {
                callVoiceMail()
            }
        } else if (maxAlertLevel.value == APP_UPDATE_DOWNLOADING) {
            // MundoPhone: el aviso de descarga solo informa, no hay nada que abrir
            Log.i("$TAG App update download alert clicked, nothing to do")
        } else {
            openDrawerEvent.value = Event(true)
        }
    }

    @UiThread
    fun onCallTopBarClicked() {
        if (atLeastOneCall.value == true) {
            goBackToCallEvent.value = Event(true)
        }
    }

    @UiThread
    fun addFilesPendingSharing(list: ArrayList<String>) {
        val count = list.size
        Log.i("$TAG Adding [$count] files to pending sharing files list")
        if (count > 0) {
            filesOrTextPendingSharingLabel.value = AppUtils.getStringWithPlural(
                R.plurals.conversations_files_waiting_to_be_shared_toast,
                count,
                "$count"
            )
            pendingFilesOrTextSharing.value = true
        }
    }

    @UiThread
    fun addTextPendingSharing() {
        filesOrTextPendingSharingLabel.value = AppUtils.getString(R.string.conversations_text_waiting_to_be_shared_toast)
        pendingFilesOrTextSharing.value = true
    }

    @UiThread
    fun filesOrTextPendingSharingListCleared() {
        pendingFilesOrTextSharing.value = false
        filesOrTextPendingSharingLabel.value = ""
        Log.i("$TAG List of files pending sharing has been cleared")
    }

    @UiThread
    fun cancelFileOrTextSharing() {
        Log.i("$TAG Clearing list of files pending sharing")
        clearFilesOrTextPendingSharingEvent.value = Event(true)
    }

    /**
     * MundoPhone: con una cuenta ya registrada, una vez por instalación, ofrecer «Llamar con MundoPhone desde el teléfono».
     * El rol solo lo puede dar el usuario en una ventana del sistema; las condiciones, en CallRedirectionOffer.
     */
    @WorkerThread
    private fun offerCallRedirectionIfNeeded(core: Core) {
        // MundoPhone: si en esta apertura ya salió el de hibernación, se ofrece en la siguiente
        if (callRedirectionOfferPosted || appHibernationWarningPosted) return
        val context = coreContext.context
        val offer = CallRedirectionOffer.shouldOffer(
            alreadyOffered = corePreferences.mundosmsCallRedirectionOffered,
            storedMode = CallRedirection.storedMode(),
            roleAvailable = CallRedirection.isRoleAvailable(context),
            roleHeld = CallRedirection.isRoleHeld(context),
            accountRegistered = core.accountList.any { it.state == RegistrationState.Ok },
            calls = core.callsNb
        )
        if (!offer) return
        callRedirectionOfferPosted = true
        Log.i("$TAG Redirección de llamadas: se ofrece (una vez por instalación)")
        offerCallRedirectionEvent.postValue(Event(true))
    }

    /**
     * MundoPhone: los dos avisos de una vez de MainActivity, el ofrecimiento de la redirección de llamadas y el de
     * hibernación, no salen en la misma apertura. Se elige con la primera cuenta registrada (el ofrecimiento la
     * necesita): la redirección si toca, que es una vez por instalación; si no, el de hibernación, que se repite en cada
     * apertura hasta que se arregla o se marca «No mostrar». Elegir antes de que registre dejaría la redirección
     * siempre detrás del de hibernación.
     */
    @WorkerThread
    private fun showOpeningNoticeIfNeeded(core: Core) {
        offerCallRedirectionIfNeeded(core)
        if (openingNoticeChosen || core.accountList.none { it.state == RegistrationState.Ok }) return
        openingNoticeChosen = true
        if (!callRedirectionOfferPosted) {
            warnAboutAppHibernationIfNeeded()
        }
    }

    /**
     * MundoPhone: desde Android 11, si la app no se usa en unos meses el sistema le quita los permisos y con ellos las
     * llamadas. Se avisa también al abrir la app, no solo en el asistente, que quien actualiza no recorre.
     */
    @WorkerThread
    private fun warnAboutAppHibernationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val context = coreContext.context
        if (context.packageManager.isAutoRevokeWhitelisted) return
        if (appHibernationPrefs().getBoolean(DO_NOT_SHOW_APP_HIBERNATION_WARNING, false)) {
            Log.i("$TAG App hibernation is enabled but the user chose not to be warned again")
            return
        }
        Log.w("$TAG App hibernation is enabled, Android may revoke our permissions: warning the user")
        appHibernationWarningPosted = true
        showAppHibernationWarningEvent.postValue(Event(true))
    }

    /** MundoPhone: «No mostrar este mensaje nuevamente» en el aviso de hibernación. */
    @UiThread
    fun doNotShowAppHibernationWarningAgain() {
        Log.i("$TAG The user doesn't want the app hibernation warning any more")
        appHibernationPrefs().edit { putBoolean(DO_NOT_SHOW_APP_HIBERNATION_WARNING, true) }
    }

    // El fichero y la clave de la serie 22: quien marcó «No mostrar» allí no lo vuelve a ver al actualizar
    private fun appHibernationPrefs(): SharedPreferences =
        coreContext.context.getSharedPreferences(APP_HIBERNATION_PREFS, Context.MODE_PRIVATE)

    @UiThread
    fun enableAccountMonitoring(enable: Boolean) {
        if (enable != monitorAccount) {
            monitorAccount = enable
            Log.i(
                "$TAG Account monitoring is now [${if (monitorAccount) "enabled" else "disabled"}]"
            )
        } else {
            Log.i(
                "$TAG Account monitoring is already [${if (monitorAccount) "enabled" else "disabled"}], nothing to do"
            )
        }
    }

    @WorkerThread
    private fun computeNonDefaultAccountNotificationsCount() {
        var count = 0
        for (account in coreContext.core.accountList) {
            if (account == coreContext.core.defaultAccount) continue
            count += account.unreadChatMessageCount + account.missedCallsCount
        }
        if (count != nonDefaultAccountNotificationsCount) {
            if (count > 0) {
                val label = AppUtils.getStringWithPlural(
                    R.plurals.pending_notification_for_other_accounts,
                    count,
                    count.toString()
                )
                addAlert(NON_DEFAULT_ACCOUNT_NOTIFICATIONS, label, forceUpdate = true)
                Log.i("$TAG Found [$count] pending notifications for other account(s)")
            } else {
                removeAlert(NON_DEFAULT_ACCOUNT_NOTIFICATIONS)
                Log.i("$TAG No pending notification found for other account(s)")
            }
            nonDefaultAccountNotificationsCount = count
        }
    }

    @WorkerThread
    private fun updateCallAlert() {
        val core = coreContext.core
        val callsNb = core.callsNb
        if (callsNb == 1) {
            val currentCall = core.currentCall ?: core.calls.firstOrNull()
            if (currentCall != null) {
                val address = currentCall.callLog.remoteAddress
                val conferenceInfo = LinphoneUtils.getConferenceInfoIfAny(currentCall)
                val label = if (conferenceInfo != null) {
                    conferenceInfo.subject ?: LinphoneUtils.getDisplayName(address)
                } else {
                    val contact = coreContext.contactsManager.findContactByAddress(address)
                    contact?.name ?: LinphoneUtils.getDisplayName(address)
                }
                Log.i("$TAG Showing single call alert with label [$label]")
                callLabel.postValue(label)
                callsStatus.postValue(LinphoneUtils.callStateToString(currentCall.state))
            }
        } else if (callsNb > 1) {
            callLabel.postValue(AppUtils.getFormattedString(R.string.calls_count_label, callsNb))
            callsStatus.postValue("")
        }
    }

    /**
     * MundoPhone: «Descargando la actualización…» en la barra de avisos mientras Play baja la versión aceptada (antes no se
     * veía nada hasta «Reiniciar»). [step] de 10 en 10, o -1 si Play aún no sabe el tamaño.
     */
    @UiThread
    fun showAppUpdateDownloading(step: Int) {
        coreContext.postOnCoreThread {
            if (appUpdateAlertClosed) return@postOnCoreThread
            val label = if (step < 0) {
                AppUtils.getString(R.string.app_update_downloading_alert)
            } else {
                AppUtils.getFormattedString(R.string.app_update_downloading_alert_percent, step)
            }
            addAlert(APP_UPDATE_DOWNLOADING, label, forceUpdate = true)
        }
    }

    /** MundoPhone: la descarga terminó, falló o se canceló. */
    @UiThread
    fun hideAppUpdateDownloading() {
        coreContext.postOnCoreThread {
            appUpdateAlertClosed = false
            if (alertsList.any { it.first == APP_UPDATE_DOWNLOADING }) {
                removeAlert(APP_UPDATE_DOWNLOADING)
            }
        }
    }

    @WorkerThread
    private fun addAlert(type: Int, label: String, forceUpdate: Boolean = false) {
        val found = alertsList.find {
            it.first == type
        }
        if (found == null || forceUpdate) {
            showAlert.postValue(false)
            if (found != null) {
                alertsList.remove(found)
            }

            val alert = Pair(type, label)
            Log.i("$TAG Adding alert with type [$type]")
            alertsList.add(alert)
            updateDisplayedAlert()
        } else {
            Log.w("$TAG There is already an alert with type [$type], skipping...")
        }
    }

    @WorkerThread
    private fun removeAlert(type: Int) {
        val found = alertsList.find {
            it.first == type
        }
        if (found != null) {
            showAlert.postValue(false)
            Log.i("$TAG Removing alert with type [$type]")
            alertsList.remove(found)
            updateDisplayedAlert()
        } else {
            Log.w("$TAG Failed to remove alert with type [$type], not found in current alerts list")
        }
    }

    @WorkerThread
    private fun updateDisplayedAlert() {
        // Sort alerts by priority
        alertsList.sortByDescending {
            it.first
        }

        val maxedPriorityAlert = alertsList.firstOrNull()
        if (maxedPriorityAlert == null) {
            Log.i("$TAG No alert to display")
            showAlert.postValue(false)
            maxAlertLevel.postValue(NONE)
        } else {
            val type = maxedPriorityAlert.first
            val label = maxedPriorityAlert.second
            Log.i("$TAG Max priority alert right now is [$type]")
            maxAlertLevel.postValue(type)
            val icon = when (type) {
                DEFAULT_ACCOUNT_DISABLED, APP_RESTRICTED_IN_BACKGROUND -> {
                    R.drawable.warning_circle
                }
                NETWORK_NOT_REACHABLE -> {
                    R.drawable.wifi_slash
                }
                APP_UPDATE_DOWNLOADING -> {
                    R.drawable.download_simple
                }
                SEND_NOTIFICATIONS_PERMISSION_NOT_GRANTED, FULL_SCREEN_INTENTS_PERMISSION_NOT_GRANTED -> {
                    R.drawable.bell_slash
                }
                else -> {
                    R.drawable.bell
                }
            }
            alertIcon.postValue(icon)
            alertLabel.postValue(label)

            Log.i("$TAG Alert top-bar is currently invisible, display it now")
            showAlert.postValue(true)
        }
    }

    @WorkerThread
    private fun triggerNativeAddressBookImport() {
        firstAccountRegistered = true
        Log.i("$TAG Trying to fetch & import native contacts")
        startLoadingContactsEvent.postValue(Event(true))
    }

    @WorkerThread
    private fun checkNetworkReachability() {
        val reachable = coreContext.core.isNetworkReachable
        Log.i("$TAG Network is ${if (reachable) "reachable" else "not reachable"}")
        if (!reachable && coreContext.core.globalState == GlobalState.On) {
            val label = if (coreContext.core.isWifiOnlyEnabled) {
                if (AndroidPlatformHelper.isReady() && AndroidPlatformHelper.instance().isActiveNetworkWifiOnlyCompliant) {
                    AppUtils.getString(R.string.network_not_reachable)
                } else {
                    AppUtils.getString(R.string.network_is_not_wifi)
                }
            } else {
                AppUtils.getString(R.string.network_not_reachable)
            }
            addAlert(NETWORK_NOT_REACHABLE, label)
        } else {
            removeAlert(NETWORK_NOT_REACHABLE)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @WorkerThread
    private fun checkFullScreenIntentNotificationPermission() {
        if (!Compatibility.hasFullScreenIntentPermission(coreContext.context)) {
            Log.w("$TAG USE_FULL_SCREEN_INTENT permission seems to be not granted!")
            val label = AppUtils.getString(R.string.full_screen_intent_permission_not_granted)
            coreContext.postOnCoreThread {
                addAlert(FULL_SCREEN_INTENTS_PERMISSION_NOT_GRANTED, label)
            }
        } else {
            removeAlert(FULL_SCREEN_INTENTS_PERMISSION_NOT_GRANTED)
        }
    }

    /**
     * MundoPhone: ¿puede Android no despertar la app con el push de una llamada? Siempre si la tiene restringida en segundo
     * plano o en el grupo «restringido»; en Xiaomi/Redmi/POCO además con la batería optimizada o sin la guía confirmada,
     * porque ahí se pierden las llamadas (K5, 16-19/11/2026: 114 de 146 avisos sin respuesta eran Xiaomi; Samsung, con la
     * batería optimizada por defecto, falla el 1 %).
     */
    @WorkerThread
    private fun checkBackgroundRestrictions() {
        val estado = MundoPhoneSegundoPlano.estado(coreContext.context)
        // En Xiaomi: la batería (API oficial) manda siempre. «Inicio automático» se lee de MIUI sin API oficial: si está
        // encendido, no hace falta «Ya está»; si se lee apagado, avisa, salvo que el usuario dijera «Ya está» leyéndose ya
        // apagado (puede ser una mala lectura: gana el usuario); si dijo «Ya está» con otra lectura y ahora se lee apagado,
        // algo lo ha cambiado y vuelve a avisar. Sin lectura, cuenta el «Ya está».
        val hecho = corePreferences.xiaomiBackgroundGuideDone
        val inicioAutomaticoMal = when (estado.inicioAutomatico) {
            true -> false
            false -> !(hecho && corePreferences.xiaomiGuideDoneAutostart == "false")
            null -> !hecho
        }
        // «Otros permisos» (pantalla de bloqueo y ventanas en segundo plano): solo si se leen denegados, que sin lectura no
        // hay que molestar más (el «Inicio automático» ya pide el «Ya está»); y si el usuario dijo «Ya está» leyéndose ya
        // denegados, gana el usuario, como con «Inicio automático»
        val otrosPermisosMal = estado.otrosPermisos == false &&
            !(hecho && corePreferences.xiaomiGuideDoneOtherPermissions == "false")
        val xiaomiEnRiesgo = estado.xiaomi && (estado.bateriaOptimizada || inicioAutomaticoMal || otrosPermisosMal)
        val enRiesgo = estado.segundoPlanoRestringido || estado.grupoRestringido || xiaomiEnRiesgo
        val mostrado = alertsList.any { it.first == APP_RESTRICTED_IN_BACKGROUND }
        if (enRiesgo && !mostrado) {
            Log.w(
                "$TAG Android may not wake the app up for calls: background restricted [${estado.segundoPlanoRestringido}], " +
                    "restricted bucket [${estado.grupoRestringido}], Xiaomi [${estado.xiaomi}], battery optimized [${estado.bateriaOptimizada}], " +
                    "autostart [${estado.inicioAutomatico ?: "unknown"}], other permissions [${estado.otrosPermisos ?: "unknown"}]"
            )
            addAlert(APP_RESTRICTED_IN_BACKGROUND, AppUtils.getString(R.string.background_restricted_alert))
        } else if (!enRiesgo && mostrado) {
            removeAlert(APP_RESTRICTED_IN_BACKGROUND)
        }
    }

    @WorkerThread
    private fun checkPostNotificationsPermission() {
        // MundoPhone: el permiso o, en cualquier versión, las notificaciones de la app apagadas en los ajustes
        if (!Compatibility.areNotificationsEnabled(coreContext.context)) {
            Log.w("$TAG POST_NOTIFICATIONS permission seems to be not granted or notifications are disabled!")
            val label = AppUtils.getString(R.string.post_notifications_permission_not_granted)
            coreContext.postOnCoreThread {
                addAlert(SEND_NOTIFICATIONS_PERMISSION_NOT_GRANTED, label)
            }
        } else {
            removeAlert(SEND_NOTIFICATIONS_PERMISSION_NOT_GRANTED)
        }
    }

    @WorkerThread
    private fun checkAccessLocalNetworkPermission() {
        if (!Compatibility.isAccessLocalNetworkPermissionGranted(coreContext.context)) {
            Log.w("$TAG ACCESS_LOCAL_NETWORK permission seems to be not granted!")
            askAccessLocalNetworkPermissionEvent.postValue(Event(true))
        }
    }

    @WorkerThread
    private fun parseMwiEvent(mwi: MessageWaitingIndication) {
        if (mwi.hasMessageWaiting()) {
            val summaries = mwi.summaries
            Log.i(
                "$TAG [MWI] Messages are waiting ([${summaries.size}] summaries)"
            )
            if (summaries.isNotEmpty()) {
                val summary = summaries.first()
                val label = AppUtils.getStringWithPlural(
                    R.plurals.mwi_messages_are_waiting,
                    summary.nbNew,
                    summary.nbNew.toString()
                )
                Log.i("$TAG [MWI] Showing alert with [${summary.nbNew}] new message(s)")
                addAlert(MWI_MESSAGES_WAITING, label)
                mwiNewMessages = true
            }
        } else {
            Log.i("$TAG [MWI] No message is waiting")
            removeAlert(MWI_MESSAGES_WAITING)
            mwiNewMessages = false
        }
    }

    @WorkerThread
    private fun callVoiceMail() {
        val defaultAccount = LinphoneUtils.getDefaultAccount()
        if (defaultAccount != null) {
            val voiceMailUri = defaultAccount.params.voicemailAddress
            if (voiceMailUri != null) {
                Log.i("$TAG [MWI] Starting call to voicemail address [${voiceMailUri.asStringUriOnly()}]")
                coreContext.startCall(voiceMailUri)
            } else {
                Log.e("$TAG [MWI] Can't call voicemail, no URI configured in account params!")
            }
        } else {
            Log.e("$TAG [MWI] Can't call voicemail, no default account found!")
        }
    }
}
