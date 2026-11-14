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
package org.linphone.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.car.app.connection.CarConnection
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import kotlin.math.max
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.databinding.MainActivityBinding
import org.linphone.favorites.FavoriteCall
import org.linphone.core.CallProblemReport
import org.linphone.ui.main.help.fragment.DebugFragment
import org.linphone.ui.GenericActivity
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.main.chat.fragment.ConversationsListFragmentDirections
import org.linphone.utils.PasswordDialogModel
import org.linphone.ui.main.viewmodel.MainViewModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel
import org.linphone.ui.welcome.WelcomeActivity
import org.linphone.utils.AppUtils
import org.linphone.utils.CallPermissions
import org.linphone.utils.DialogUtils
import org.linphone.utils.DireccionVisible
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.telecom.redirection.CallRedirection
import org.linphone.ui.call.model.AccountSelectionDialogModel
import org.linphone.core.Address
import org.linphone.core.Account
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils
import androidx.core.content.edit
import org.linphone.ui.sso.SingleSignOnActivity

@UiThread
class MainActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Main Activity]"

        private const val DEFAULT_FRAGMENT_KEY = "default_fragment"
        private const val CONTACTS_FRAGMENT_ID = 1
        private const val HISTORY_FRAGMENT_ID = 2
        private const val CHAT_FRAGMENT_ID = 3
        private const val MEETINGS_FRAGMENT_ID = 4
        private const val TEAM_FRAGMENT_ID = 5 // MundoPhone

        const val ARGUMENTS_CHAT = "Chat"
        const val ARGUMENTS_CONVERSATION_ID = "ConversationId"
    }

    private lateinit var binding: MainActivityBinding

    private lateinit var viewModel: MainViewModel

    private lateinit var sharedViewModel: SharedMainViewModel

    // MundoPhone: actualización integrada de Google Play (se registra antes de onCreate, como pide la API)
    private val appUpdateChecker = AppUpdateChecker(this)

    private var currentlyDisplayedAuthDialog: Dialog? = null

    private var currentlyDisplayedAccountSelectionDialog: Dialog? = null

    private var navigatedToDefaultFragment = false

    // MundoPhone: con el menú lateral abierto, «atrás» lo cierra en vez de salir. El drawerlayout 1.1.1
    // solo lo hace con la tecla, que con el «atrás» predictivo (enableOnBackInvokedCallback, Android 13+) ya no le llega.
    // Se añade al abrirse el menú, para ir por delante de los de los fragmentos, y se quita al cerrarse.
    private val closeDrawerOnBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Log.i("$TAG Back pressed with the drawer menu open, closing it")
            closeDrawerMenu()
        }
    }

    private val destinationListener = object : NavController.OnDestinationChangedListener {
        override fun onDestinationChanged(
            controller: NavController,
            destination: NavDestination,
            arguments: Bundle?
        ) {
            Log.i("$TAG Latest visited fragment was restored")
            navigatedToDefaultFragment = true
            controller.removeOnDestinationChangedListener(this)
        }
    }

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG POST_NOTIFICATIONS permission has been granted")
            viewModel.updateMissingPermissionAlert()
        } else {
            Log.w("$TAG POST_NOTIFICATIONS permission has been denied!")
        }
    }

    private val fullScreenIntentPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG USE_FULL_SCREEN_INTENT permission has been granted")
            viewModel.updateMissingPermissionAlert()
        } else {
            Log.w("$TAG USE_FULL_SCREEN_INTENT permission has been denied!")
        }
    }

    // MundoPhone: la ventana del sistema que concede el rol de redirección, desde el ofrecimiento de una vez
    private val callRedirectionRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onCallRedirectionRoleResult()
    }

    // MundoPhone: permisos de una llamada que CoreContext ha dejado esperando (waitForCallPermissions), con lo que
    // decía shouldShowRequestPermissionRationale antes de pedirlos, para saber después si Android ya ni pregunta
    private var callPermissionsRationaleBefore = mapOf<String, Boolean>()

    private val callPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onCallPermissionsResult(results)
    }

    private val accessLocalNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG ACCESS_LOCAL_NETWORK permission has been granted")
            viewModel.updateMissingPermissionAlert()
        } else {
            Log.w("$TAG ACCESS_LOCAL_NETWORK permission has been denied!")
        }
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be done before the setContentView
        installSplashScreen()

        val sw600dpLand = resources.configuration.smallestScreenWidthDp >= 600 &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (sw600dpLand) {
            // Do not force white icons in status bar as it will use the app's background color depending on device light/dark theme
            Log.i("$TAG Device is in sw600dp-land configuration")
            enableEdgeToEdge()
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                    true // Force dark mode to always have white icons in status bar, black over orange isn't looking great
                }
            )
        }

        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)
        binding.lifecycleOwner = this
        setUpToastsArea(binding.toastsArea)

        // Will give the device's status bar background color
        ViewCompat.setOnApplyWindowInsetsListener(binding.notificationsArea) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(0, insets.top, 0, 0)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainNavContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(insets.left, 0, insets.right, max(insets.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerMenuContent) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // MundoPhone: «atrás» cierra el menú lateral (closeDrawerOnBackCallback)
        binding.drawerMenu.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                enableCloseDrawerOnBack(true)
            }

            override fun onDrawerClosed(drawerView: View) {
                enableCloseDrawerOnBack(false)
            }
        })

        while (!coreContext.isReady()) {
            Thread.sleep(50)
        }

        viewModel = run {
            ViewModelProvider(this)[MainViewModel::class.java]
        }
        binding.viewModel = viewModel

        sharedViewModel = run {
            ViewModelProvider(this)[SharedMainViewModel::class.java]
        }

        viewModel.goBackToCallEvent.observe(this) {
            it.consume {
                coreContext.showCallActivity()
            }
        }

        viewModel.openDrawerEvent.observe(this) {
            it.consume {
                openDrawerMenu()
            }
        }

        viewModel.askPostNotificationsPermissionEvent.observe(this) {
            it.consume {
                // MundoPhone: por debajo de Android 13 no hay permiso que pedir (o ya está y lo apagado son las
                // notificaciones): a los ajustes de notificaciones de la app, que es donde se encienden
                if (!Compatibility.isPostNotificationsPermissionGranted(this) &&
                    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    Log.w("$TAG Asking for POST_NOTIFICATIONS permission")
                    postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Log.e("$TAG Notifications can't be enabled from here, going to the app notification settings")
                    goToNotificationSettings()
                }
            }
        }

        viewModel.askFullScreenIntentPermissionEvent.observe(this) {
            it.consume {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.USE_FULL_SCREEN_INTENT)) {
                    Log.w("$TAG Asking for USE_FULL_SCREEN_INTENT permission")
                    fullScreenIntentPermissionLauncher.launch(Manifest.permission.USE_FULL_SCREEN_INTENT)
                } else {
                    Log.e("$TAG Permission request for USE_FULL_SCREEN_INTENT will be automatically denied, go to manage app full screen intent android settings instead")
                    Compatibility.requestFullScreenIntentPermission(this)
                }
            }
        }

        viewModel.offerCallRedirectionEvent.observe(this) {
            it.consume {
                showOfferCallRedirectionDialog()
            }
        }

        viewModel.showAppHibernationWarningEvent.observe(this) {
            it.consume {
                showAppHibernationWarningDialog()
            }
        }

        viewModel.askAccessLocalNetworkPermissionEvent.observe(this) {
            it.consume {
                Log.w("$TAG Asking for ACCESS_LOCAL_NETWORK permission")
                accessLocalNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }

        viewModel.showNewAccountToastEvent.observe(this) {
            it.consume {
                val message = getString(R.string.new_account_configured_toast)
                showGreenToast(message, R.drawable.user_circle)
            }
        }

        viewModel.startLoadingContactsEvent.observe(this) {
            it.consume {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    loadContacts()
                }
            }
        }

        viewModel.lastAccountRemovedEvent.observe(this) {
            it.consume {
                startActivity(Intent(this, AssistantActivity::class.java))
            }
        }

        viewModel.clearFilesOrTextPendingSharingEvent.observe(this) {
            it.consume {
                sharedViewModel.filesToShareFromIntent.value = arrayListOf()
                sharedViewModel.textToShareFromIntent.value = ""
            }
        }

        sharedViewModel.filesToShareFromIntent.observe(this) { list ->
            if (list.isNotEmpty()) {
                viewModel.addFilesPendingSharing(list)
            } else {
                viewModel.filesOrTextPendingSharingListCleared()
            }
        }

        sharedViewModel.textToShareFromIntent.observe(this) { text ->
            if (!text.isEmpty()) {
                viewModel.addTextPendingSharing()
            } else {
                viewModel.filesOrTextPendingSharingListCleared()
            }
        }

        // Wait for latest visited fragment to be displayed before hiding the splashscreen
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                return if (navigatedToDefaultFragment) {
                    Log.i("$TAG Report UI has been fully drawn (TTFD)")
                    try {
                        reportFullyDrawn()
                    } catch (se: SecurityException) {
                        Log.e("$TAG Security exception when doing reportFullyDrawn(): $se")
                    }
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    true
                } else {
                    false
                }
            }
        })
        coreContext.bearerAuthenticationRequestedEvent.observe(this) {
            it.consume { pair ->
                val serverUrl = pair.first
                val username = pair.second

                Log.i(
                    "$TAG Bearer auth request, navigating to Single Sign On Fragment with server URL [$serverUrl] and username [$username]"
                )
                val intent = Intent(this, SingleSignOnActivity::class.java)
                intent.putExtra(SingleSignOnActivity.INTENT_EXTRA_USERNAME, username)
                intent.putExtra(SingleSignOnActivity.INTENT_EXTRA_SERVER_URL, serverUrl)
                startActivity(intent)
            }
        }

        coreContext.digestAuthenticationRequestedEvent.observe(this) {
            it.consume { identity ->
                try {
                    if (coreContext.digestAuthInfoPendingPasswordUpdate != null) {
                        showAuthenticationRequestedDialog(identity)
                    }
                } catch (e: WindowManager.BadTokenException) {
                    Log.e("$TAG Failed to show authentication dialog: $e")
                }
            }
        }

        coreContext.clearAuthenticationRequestDialogEvent.observe(this) {
            it.consume {
                currentlyDisplayedAuthDialog?.dismiss()
            }
        }

        coreContext.showGreenToastEvent.observe(this) {
            it.consume { pair ->
                val message = getString(pair.first)
                val icon = pair.second
                showGreenToast(message, icon)
            }
        }

        // MundoPhone: la llamada espera a que se conceda el micrófono (y la cámara, si es vídeo)
        coreContext.callPermissionsRequiredEvent.observe(this) {
            it.consume { request ->
                askCallPermissions(request)
            }
        }

        // MundoPhone (A8): varias extensiones registradas y ninguna favorita: preguntar desde cuál llamar
        coreContext.accountSelectionForCallEvent.observe(this) {
            it.consume { (accounts, address, isVideoCall) ->
                showAccountSelectionDialog(accounts, address, isVideoCall)
            }
        }

        coreContext.showRedToastEvent.observe(this) {
            it.consume { pair ->
                val message = getString(pair.first)
                val icon = pair.second
                showRedToast(message, icon)
            }
        }

        coreContext.showFormattedRedToastEvent.observe(this) {
            it.consume { pair ->
                val message = pair.first
                val icon = pair.second
                showRedToast(message, icon)
            }
        }

        coreContext.provisioningAppliedEvent.observe(this) {
            it.consume {
                Log.i("$TAG Remote provisioning was applied, checking if theme has changed")
                checkMainColorTheme()
            }
        }

        coreContext.filesToExportToNativeMediaGalleryEvent.observe(this) {
            it.consume { files ->
                Log.i("$TAG Found [${files.size}] files to export to native media gallery")
                for (file in files) {
                    exportFileToNativeMediaGallery(file)
                }

                coreContext.postOnCoreThread {
                    coreContext.clearFilesToExportToNativeGallery()
                }
            }
        }

        CarConnection(this).type.observe(this) {
            val asString = when (it) {
                CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "NOT CONNECTED"
                CarConnection.CONNECTION_TYPE_PROJECTION -> "PROJECTION"
                CarConnection.CONNECTION_TYPE_NATIVE -> "NATIVE"
                else -> "UNEXPECTED ($it)"
            }
            Log.i("$TAG Car connection is [$asString]")
            val projection = it == CarConnection.CONNECTION_TYPE_PROJECTION
            coreContext.isConnectedToAndroidAuto = projection
        }

        coreContext.postOnCoreThread { core ->
            if (corePreferences.firstLaunch) {
                Log.i("$TAG First time Linphone 6.0 has been started, showing Welcome activity")
                corePreferences.firstLaunch = false
                coreContext.postOnMainThread {
                    try {
                        startActivity(Intent(this, WelcomeActivity::class.java))
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't start activity: $ise")
                    }
                }
            } else if (core.accountList.isEmpty()) {
                Log.w("$TAG No account found, showing Assistant activity")
                coreContext.postOnMainThread {
                    try {
                        startActivity(Intent(this, AssistantActivity::class.java))
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't start activity: $ise")
                    }
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // MundoPhone: al recrear la actividad (p. ej. al girar) el menú vuelve abierto sin avisar a onDrawerOpened
        if (binding.drawerMenu.isDrawerOpen(binding.drawerMenuContent)) {
            enableCloseDrawerOnBack(true)
        }

        goToLatestVisitedFragment()

        // We don't want that intent to be handled upon rotation
        if (savedInstanceState == null && intent != null) {
            Log.d("$TAG savedInstanceState is null but intent isn't, handling it")
            handleIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        // MundoPhone: desde aquí se piden los permisos de una llamada (CoreContext.waitForCallPermissions)
        coreContext.callPermissionsRequesterVisible = true
    }

    override fun onStop() {
        // MundoPhone: la app ya no se ve; si hay un diálogo de Play pedido, desde aquí está a la vista
        appUpdateChecker.onStop()
        coreContext.callPermissionsRequesterVisible = false
        super.onStop()
    }

    override fun onPause() {
        // MundoPhone: si es el diálogo de Play el que empieza a tapar la app (para medir cuánto estuvo a la vista)
        appUpdateChecker.onPause()
        viewModel.enableAccountMonitoring(false)

        currentlyDisplayedAuthDialog?.dismiss()
        currentlyDisplayedAuthDialog = null

        val defaultFragmentId = when (sharedViewModel.currentlyDisplayedFragment.value) {
            R.id.teamListFragment -> {
                TEAM_FRAGMENT_ID
            }
            R.id.contactsListFragment -> {
                CONTACTS_FRAGMENT_ID
            }
            R.id.historyListFragment -> {
                HISTORY_FRAGMENT_ID
            }
            R.id.conversationsListFragment -> {
                CHAT_FRAGMENT_ID
            }
            R.id.meetingsListFragment -> {
                MEETINGS_FRAGMENT_ID
            }
            else -> { // Default
                HISTORY_FRAGMENT_ID
            }
        }
        getPreferences(MODE_PRIVATE).edit {
            putInt(DEFAULT_FRAGMENT_KEY, defaultFragmentId)
        }
        Log.i("$TAG Stored [$defaultFragmentId] as default page")

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        viewModel.enableAccountMonitoring(true)
        viewModel.updateMissingPermissionAlert()
        viewModel.updateAccountsAndNetworkReachability()

        appUpdateChecker.onResume()
    }

    /** MundoPhone: Ayuda > «Buscar actualizaciones» (actualización integrada de Play). */
    @UiThread
    fun checkForAppUpdateNow(onResult: (AppUpdateChecker.ManualResult) -> Unit) = appUpdateChecker.checkNow(onResult)

    override fun onDestroy() {
        appUpdateChecker.onDestroy()
        super.onDestroy()
    }

    private fun showAccountSelectionDialog(accounts: List<Account>, callAddress: Address, isVideoCall: Boolean) {
        if (currentlyDisplayedAccountSelectionDialog?.isShowing == true) {
            Log.w("$TAG Account selection dialog already showing, skipping")
            return
        }

        val model = AccountSelectionDialogModel(accounts, callAddress, isVideoCall)
        val dialog = DialogUtils.getAccountSelectionDialog(this, model)
        model.dismissEvent.observe(this) {
            it.consume {
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            currentlyDisplayedAccountSelectionDialog = null
        }
        dialog.show()
        currentlyDisplayedAccountSelectionDialog = dialog
    }

    /**
     * MundoPhone: «Llamar con MundoPhone desde el teléfono», una vez por instalación (MainViewModel decide cuándo). Se
     * explica antes de la ventana del sistema, que no dice para qué es; y «Ahora no» no gasta la negativa de Android.
     */
    private fun showOfferCallRedirectionDialog() {
        val roleIntent = CallRedirection.createRoleRequestIntent(this) ?: return
        // Una sola vez, se acepte o no: después queda en Configuración > Llamadas
        coreContext.postOnCoreThread {
            corePreferences.mundosmsCallRedirectionOffered = true
        }

        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getOfferCallRedirectionDialog(this, model)
        model.dismissEvent.observe(this) {
            it.consume {
                Log.i("$TAG Redirección de llamadas: «Ahora no»")
                dialog.dismiss()
                showCallRedirectionSettingsHint()
            }
        }
        model.confirmEvent.observe(this) {
            it.consume {
                dialog.dismiss()
                Log.i("$TAG Redirección de llamadas: «Activar», se pide el rol")
                try {
                    callRedirectionRoleLauncher.launch(roleIntent)
                } catch (anfe: ActivityNotFoundException) {
                    Log.e("$TAG No se puede abrir la ventana del rol: $anfe")
                    showCallRedirectionSettingsHint()
                }
            }
        }
        dialog.show()
    }

    private fun onCallRedirectionRoleResult() {
        if (CallRedirection.isRoleHeld(this)) {
            // Con el rol y sin elegir ya vale «preguntar»; se guarda para que Configuración lo enseñe igual
            Log.i("$TAG Redirección de llamadas: rol concedido, queda en «preguntar»")
            CallRedirection.saveMode(CallRedirection.MODE_ASK)
            showGreenToast(getString(R.string.call_redirection_offer_granted_toast), R.drawable.check, 5000)
        } else {
            Log.w("$TAG Redirección de llamadas: rol no concedido")
            showCallRedirectionSettingsHint()
        }
    }

    private fun showCallRedirectionSettingsHint() {
        showGreenToast(getString(R.string.call_redirection_offer_settings_hint), R.drawable.info, 5000)
    }

    /**
     * MundoPhone: el aviso de hibernación también al abrir la app (MainViewModel decide cuándo), con el
     * diálogo del asistente y «No mostrar este mensaje nuevamente».
     */
    private fun showAppHibernationWarningDialog() {
        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getUnusedAppRestrictionsDialog(this, model, atAppStart = true)
        model.dismissEvent.observe(this) {
            it.consume {
                dialog.dismiss()
                if (model.doNotShowAnymore.value == true) viewModel.doNotShowAppHibernationWarningAgain()
            }
        }
        model.confirmEvent.observe(this) {
            it.consume {
                dialog.dismiss()
                if (model.doNotShowAnymore.value == true) viewModel.doNotShowAppHibernationWarningAgain()
                openAppHibernationSettings()
            }
        }
        dialog.show()
    }

    /** MundoPhone: como en el asistente (PermissionsFragment): la pantalla del permiso o, si no la hay, la ficha de la app. */
    @SuppressLint("InlinedApi") // El aviso solo se da desde Android 11 (MainViewModel)
    private fun openAppHibernationSettings() {
        val uri = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, uri))
        } catch (anfe: ActivityNotFoundException) {
            Log.w("$TAG No auto-revoke settings screen, opening app details: $anfe")
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            } catch (e: ActivityNotFoundException) {
                Log.e("$TAG Can't open app details either: $e")
                return
            }
        }
        Toast.makeText(this, R.string.unused_app_restrictions_hint, Toast.LENGTH_LONG).show()
    }

    /** MundoPhone: pide lo que le falta a la llamada que espera; si se concede, CoreContext la relanza. */
    private fun askCallPermissions(request: CallPermissions.Request) {
        if (SystemClock.elapsedRealtime() - request.requestedAt > CallPermissions.REQUEST_MAX_AGE_MS) {
            Log.w("$TAG Call permissions request is too old, the pending call won't start")
            coreContext.postOnCoreThread {
                coreContext.onCallPermissionsResult(false)
            }
            return
        }
        Log.i("$TAG Asking for permissions ${request.permissions} before starting the call")
        callPermissionsRationaleBefore = request.permissions.associateWith {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        callPermissionsLauncher.launch(request.permissions.toTypedArray())
    }

    private fun onCallPermissionsResult(results: Map<String, Boolean>) {
        val granted = results.isNotEmpty() && results.values.all { it }
        coreContext.postOnCoreThread {
            coreContext.onCallPermissionsResult(granted)
        }
        val message = CallPermissions.deniedMessage(results) ?: return
        showRedToast(getString(message), R.drawable.warning_circle)

        val deniedForever = results.filterValues { !it }.keys.any {
            CallPermissions.deniedForever(
                callPermissionsRationaleBefore[it],
                ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            )
        }
        if (deniedForever) {
            Log.w("$TAG Call permissions denied for good, Android won't ask again: going to the app settings")
            goToAndroidPermissionSettings()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MundoPhone: un tel: o un favorito con la app parada llega antes de onStart y la llamada ya puede pedir permisos
        coreContext.callPermissionsRequesterVisible = true
        Log.d("$TAG Handling new intent")
        handleIntent(intent)
    }

    @SuppressLint("RtlHardcoded")
    fun toggleDrawerMenu() {
        if (binding.drawerMenu.isDrawerOpen(Gravity.LEFT)) {
            closeDrawerMenu()
        } else {
            openDrawerMenu()
        }
    }

    fun closeDrawerMenu() {
        binding.drawerMenu.closeDrawer(binding.drawerMenuContent, true)
    }

    private fun openDrawerMenu() {
        binding.drawerMenu.openDrawer(binding.drawerMenuContent, true)
    }

    /** MundoPhone: los ajustes de notificaciones de la app; si no los hay, los de la app. */
    private fun goToNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (anfe: ActivityNotFoundException) {
            Log.e("$TAG Can't open the app notification settings: $anfe")
            goToAndroidPermissionSettings()
        }
    }

    private fun enableCloseDrawerOnBack(enable: Boolean) {
        // Quitar y volver a añadir lo deja el último, que es el primero al que llama el despachador de «atrás»
        closeDrawerOnBackCallback.remove()
        if (enable) {
            onBackPressedDispatcher.addCallback(closeDrawerOnBackCallback)
        }
    }

    fun findNavController(): NavController {
        return findNavController(R.id.main_nav_container)
    }

    fun loadContacts() {
        coreContext.contactsManager.loadContacts(this)
    }

    private fun goToLatestVisitedFragment() {
        try {
            // Prevent navigating to default fragment upon rotation (we only want to do it on first start)
            if (intent.action == Intent.ACTION_MAIN && intent.type == null && intent.data == null) {
                if (viewModel.mainIntentHandled) {
                    Log.d(
                        "$TAG Main intent without type nor data was already handled, do nothing"
                    )
                    navigatedToDefaultFragment = true
                    return
                } else {
                    viewModel.mainIntentHandled = true
                }
            }

            val defaultFragmentId = getPreferences(MODE_PRIVATE).getInt(
                DEFAULT_FRAGMENT_KEY,
                HISTORY_FRAGMENT_ID
            )
            Log.i(
                "$TAG Trying to navigate to set default destination [$defaultFragmentId]"
            )
            try {
                val navOptionsBuilder = NavOptions.Builder()
                navOptionsBuilder.setPopUpTo(R.id.historyListFragment, true)
                navOptionsBuilder.setLaunchSingleTop(true)
                val navOptions = navOptionsBuilder.build()
                val args = bundleOf()
                when (defaultFragmentId) {
                    TEAM_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.teamListFragment,
                            args,
                            navOptions
                        )
                    }
                    CONTACTS_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.contactsListFragment,
                            args,
                            navOptions
                        )
                    }
                    CHAT_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.conversationsListFragment,
                            args,
                            navOptions
                        )
                    }
                    MEETINGS_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.meetingsListFragment,
                            args,
                            navOptions
                        )
                    }
                    else -> {
                        Log.i("$TAG Default fragment is the same as the latest visited one")
                        navigatedToDefaultFragment = true
                    }
                }
            } catch (ise: IllegalStateException) {
                Log.e("$TAG Can't navigate to Conversations fragment: $ise")
            }
        } catch (ise: IllegalStateException) {
            Log.i("$TAG Failed to handle intent: $ise")
        }
    }

    private fun handleIntent(intent: Intent) {
        val extras = intent.extras
        val hasExtra = extras != null && !extras.isEmpty
        Log.i(
            "$TAG Handling intent action [${intent.action}], type [${intent.type}], data [${intent.data}] and has ${if (hasExtra) "extras" else "no extra"}"
        )

        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                handleSendIntent(intent, false)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                handleSendIntent(intent, true)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: ""
                if (uri.startsWith("linphone-config:")) {
                    handleConfigIntent(uri)
                } else {
                    handleCallIntent(intent)
                }
            }
            Intent.ACTION_DIAL, Intent.ACTION_CALL -> {
                handleCallIntent(intent)
            }
            // MundoPhone (E4): accesos directos del icono y widget de favoritos
            FavoriteCall.ACTION_CALL, FavoriteCall.ACTION_CALL_VOICEMAIL -> {
                FavoriteCall.handleIntent(this, intent)
            }
            // MundoPhone (F2): la notificación de «la última llamada parece que ha ido mal»
            CallProblemReport.ACTION_REPORT -> {
                handleReportCallProblemIntent(intent)
            }
            Intent.ACTION_VIEW_LOCUS -> {
                val locus = Compatibility.extractLocusIdFromIntent(intent)
                if (locus != null) {
                    Log.i("$TAG Found chat room locus intent extra: $locus")
                    handleLocusOrShortcut(locus)
                }
            }
            else -> {
                handleMainIntent(intent)
            }
        }
    }

    /** MundoPhone (F2): el formulario de registros con la llamada de la notificación (DebugFragment). */
    private fun handleReportCallProblemIntent(intent: Intent) {
        // Al volver desde Recientes, Android repite el intent que abrió la tarea: no volver a abrir el formulario
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val callId = intent.getStringExtra(CallProblemReport.EXTRA_CALL_ID)
        if (callId.isNullOrEmpty()) {
            Log.e("$TAG Report call problem intent without call ID")
            return
        }
        Log.i("$TAG Opening logs form for call [$callId] from the notification")
        val args = Bundle()
        args.putString(DebugFragment.ARGUMENT_REPORT_CALL_ID, callId)
        args.putString(DebugFragment.ARGUMENT_REPORT_REASON, intent.getStringExtra(CallProblemReport.EXTRA_REASON))
        try {
            findNavController().navigate(R.id.action_global_debugFragment, args)
        } catch (e: RuntimeException) {
            Log.e("$TAG Can't navigate to the logs form: $e")
        }
    }

    private fun handleLocusOrShortcut(id: String) {
        Log.i("$TAG Found locus ID [$id]")
        if (id.isNotEmpty()) {
            Log.i("$TAG Navigating to conversation with ID [$id], computed from shortcut ID")
            sharedViewModel.showConversationEvent.value = Event(id)
        }
    }

    private fun handleMainIntent(intent: Intent) {
        coreContext.postOnCoreThread { core ->
            if (intent.hasExtra(ARGUMENTS_CHAT)) {
                Log.i("$TAG Intent has [Chat] extra")
                coreContext.postOnMainThread {
                    try {
                        Log.i("$TAG Trying to go to Conversations fragment")
                        val args = intent.extras
                        val conversationId = args?.getString(ARGUMENTS_CONVERSATION_ID, "")
                        if (conversationId.isNullOrEmpty()) {
                            Log.w("$TAG Found [Chat] extra but no conversation ID!")
                        } else {
                            Log.i("$TAG Found [Chat] extra with conversation ID [$conversationId]")
                            sharedViewModel.showConversationEvent.value = Event(conversationId)
                        }
                        args?.clear()

                        if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                            Log.w(
                                "$TAG Current destination is already conversations list, skipping navigation"
                            )
                        } else {
                            val navOptionsBuilder = NavOptions.Builder()
                            navOptionsBuilder.setPopUpTo(
                                findNavController().currentDestination?.id ?: R.id.historyListFragment,
                                true
                            )
                            navOptionsBuilder.setLaunchSingleTop(true)
                            val navOptions = navOptionsBuilder.build()
                            findNavController().navigate(
                                R.id.conversationsListFragment,
                                args,
                                navOptions
                            )
                        }
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't navigate to Conversations fragment: $ise")
                    }
                }
            }
        }
    }

    private fun handleSendIntent(intent: Intent, multiple: Boolean) {
        val parcelablesUri = arrayListOf<Uri>()
        if (multiple) {
            val parcelables =
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
            for (parcelable in parcelables.orEmpty()) {
                val uri = parcelable as? Uri
                if (uri != null) {
                    Log.i("$TAG Found URI [$uri] in parcelable extra list")
                    parcelablesUri.add(uri)
                }
            }
        } else {
            val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (uri != null) {
                Log.i("$TAG Found URI [$uri] in parcelable extra")
                parcelablesUri.add(uri)
            }
        }

        val list = arrayListOf<String>()
        lifecycleScope.launch {
            val deferred = arrayListOf<Deferred<String?>>()
            for (uri in parcelablesUri) {
                Log.i("$TAG Deferring copy from file [${uri.path}] to local storage")
                deferred.add(async { FileUtils.getFilePath(this@MainActivity, uri, false) })
            }

            if (binding.drawerMenu.isOpen) {
                Log.i("$TAG Drawer menu is opened, closing it")
                closeDrawerMenu()
            }
            if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                if (sharedViewModel.displayedChatRoom != null) {
                    Log.w(
                        "$TAG Closing already opened conversation to prevent attaching file in it directly"
                    )
                    sharedViewModel.hideConversationEvent.value = Event(true)
                } else {
                    Log.i("$TAG No chat room currently displayed, nothing to close")
                }
            }

            val paths = deferred.awaitAll()
            for (path in paths) {
                Log.i("$TAG Found file to share [$path] in intent")
                if (path != null) list.add(path)
            }

            var textToShare = ""
            if (intent.type == "text/plain") {
                Log.i("$TAG Intent type is [${intent.type}], expecting text in Intent.EXTRA_TEXT")
                textToShare = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (textToShare.isEmpty()) {
                    Log.e("$TAG Intent.EXTRA_TEXT not found in intent!")
                } else {
                    Log.i("$TAG Found extra text in intent, long of [${textToShare.length}]")
                }
            }

            if (list.isNotEmpty()) {
                sharedViewModel.filesToShareFromIntent.postValue(list)
            } else {
                if (textToShare.isNotEmpty()) {
                    Log.i("$TAG Found plain text to share")
                    sharedViewModel.textToShareFromIntent.postValue(textToShare)
                } else {
                    Log.w("$TAG Failed to find at least one file or text to share!")
                }
            }

            if (findNavController().currentDestination?.id == R.id.debugFragment) {
                Log.i(
                    "$TAG App is already started and in debug fragment, navigating to conversations list"
                )
                val conversationId = parseShortcutIfAny(intent)
                if (conversationId != null) {
                    Log.i(
                        "$TAG Navigating from debug to conversation with ID [$conversationId], computed from shortcut ID"
                    )
                    sharedViewModel.showConversationEvent.postValue(Event(conversationId))
                }

                val action = ConversationsListFragmentDirections.actionGlobalConversationsListFragment()
                val options = NavOptions.Builder()
                options.apply {
                    setPopUpTo(R.id.helpFragment, true)
                    setLaunchSingleTop(true)
                }
                findNavController().navigate(action, options.build())
            } else {
                val conversationId = parseShortcutIfAny(intent)
                if (conversationId != null) {
                    Log.i(
                        "$TAG Navigating to conversation with conversation ID [$conversationId] addresses, computed from shortcut ID"
                    )
                    sharedViewModel.showConversationEvent.postValue(Event(conversationId))
                }

                if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                    Log.w(
                        "$TAG Current destination is already conversations list, skipping navigation"
                    )
                } else {
                    val action = ConversationsListFragmentDirections.actionGlobalConversationsListFragment()
                    findNavController().navigate(action)
                }
            }
        }
    }

    private fun parseShortcutIfAny(intent: Intent): String? {
        val shortcutId = intent.getStringExtra("android.intent.extra.shortcut.ID") // Intent.EXTRA_SHORTCUT_ID
        if (shortcutId != null) {
            Log.i("$TAG Found shortcut ID [$shortcutId]")
            return shortcutId
        } else {
            Log.i("$TAG No shortcut ID was found")
        }
        return null
    }

    private fun handleCallIntent(intent: Intent) {
        val uri = intent.data?.toString()
        if (uri.isNullOrEmpty()) {
            Log.e("$TAG Intent data is null or empty, can't process [${intent.action}] intent")
            return
        }

        Log.i("$TAG Found URI [$uri] as data for intent [${intent.action}]")
        val sipUriToCall = when {
            uri.startsWith("tel:") -> uri.substring("tel:".length)
            uri.startsWith("callto:") -> uri.substring("callto:".length)
            uri.startsWith("sip-linphone:") -> uri.replace("sip-linphone:", "sip:")
            uri.startsWith("linphone-sip:") -> uri.replace("linphone-sip:", "sip:")
            uri.startsWith("sips-linphone:") -> uri.replace("sips-linphone:", "sips:")
            uri.startsWith("linphone-sips:") -> uri.replace("linphone-sips:", "sips:")
            else -> uri.replace("%40", "@") // Unescape @ character if needed
        }

        coreContext.postOnCoreThread {
            val address = coreContext.core.interpretUrl(
                sipUriToCall,
                LinphoneUtils.applyInternationalPrefix()
            )
            Log.i("$TAG Interpreted SIP URI is [${address?.asStringUriOnly()}]")
            if (address != null) {
                // Skip network reachability test, this code will be called too soon
                coreContext.startAudioCall(address, skipNetworkReachabilityTest = true)
            }
        }
    }

    private fun handleConfigIntent(uri: String) {
        Log.i("$TAG Trying to parse config intent [$uri] as remote provisioning URL")
        val url = LinphoneUtils.getRemoteProvisioningUrlFromUri(uri)
        if (url == null) {
            Log.e("$TAG Couldn't parse URI [$uri] into a valid remote provisioning URL, aborting")
            return
        }

        coreContext.postOnCoreThread { core ->
            core.provisioningUri = url
            Log.w("$TAG Remote provisioning URL set to [$url], restarting Core now")
            core.stop()
            Log.i("$TAG Core has been stopped, let's restart it")
            core.start()
            Log.i("$TAG Core has been restarted")
        }
    }

    private fun showAuthenticationRequestedDialog(identity: String) {
        currentlyDisplayedAuthDialog?.dismiss()

        // MundoPhone: de «usuario@dominio», la extensión (DireccionVisible)
        val shownIdentity = if (corePreferences.onlyDisplaySipUriUsername) DireccionVisible.extension(identity) else identity
        val label = AppUtils.getFormattedString(
            R.string.account_settings_dialog_invalid_password_message,
            shownIdentity
        )
        val model = PasswordDialogModel(label)
        val dialog = DialogUtils.getAuthRequestedDialog(this, model)

        model.dismissEvent.observe(this) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(this) {
            it.consume { password ->
                coreContext.postOnCoreThread {
                    coreContext.updateAuthInfo(password)
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        currentlyDisplayedAuthDialog = dialog
    }

    private fun exportFileToNativeMediaGallery(filePath: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Log.i("$TAG Export file [$filePath] to Android's MediaStore")
                val mediaStorePath = FileUtils.addContentToMediaStore(filePath)
                if (mediaStorePath.isNotEmpty()) {
                    Log.i("$TAG File [$filePath] has been successfully exported to MediaStore")
                } else {
                    Log.e("$TAG Failed to export file [$filePath] to MediaStore!")
                }
            }
        }
    }
}
