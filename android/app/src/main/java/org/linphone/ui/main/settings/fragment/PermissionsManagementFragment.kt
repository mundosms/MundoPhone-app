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
package org.linphone.ui.main.settings.fragment

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.PermissionsManagementFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.ui.main.settings.viewmodel.PermissionsManagementViewModel

/**
 * MundoPhone: Ajustes › «Permisos de MundoPhone», la pantalla para revisar después del alta los permisos,
 * la batería y la hibernación, con el estado de cada uno. Al tocar uno que falta se pide; si Android ya no deja
 * preguntar, o si ya está concedido, se abren sus ajustes del sistema, y al volver se avisa si ha quedado concedido.
 */
@UiThread
class PermissionsManagementFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Permissions Management Fragment]"
    }

    private lateinit var binding: PermissionsManagementFragmentBinding

    private lateinit var viewModel: PermissionsManagementViewModel

    // Al volver de los ajustes del sistema: si ha quedado concedido, y el aviso que toca
    private var pendingCheck: Pair<() -> Boolean, Int>? = null

    private val notifications = RuntimePermission(
        Manifest.permission.POST_NOTIFICATIONS,
        R.string.permission_notifications_granted_toast,
        { viewModel.notificationsGranted.value == true },
        ::openNotificationSettings
    )

    private val microphone = RuntimePermission(
        Manifest.permission.RECORD_AUDIO,
        R.string.permission_microphone_granted_toast,
        { viewModel.microphoneGranted.value == true },
        ::openAppSettings
    )

    private val camera = RuntimePermission(
        Manifest.permission.CAMERA,
        R.string.permission_camera_granted_toast,
        { viewModel.cameraGranted.value == true },
        ::openAppSettings
    )

    private val contacts = RuntimePermission(
        Manifest.permission.READ_CONTACTS,
        R.string.permission_contacts_granted_toast,
        { viewModel.contactsGranted.value == true },
        ::openAppSettings
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = PermissionsManagementFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[PermissionsManagementViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        binding.setBackClickListener {
            goBack()
        }

        binding.setNotificationsClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifications.requestOrOpenSettings()
            } else {
                // Antes de Android 13 no hay permiso que pedir: solo sus ajustes
                notifications.goToSettings()
            }
        }

        binding.setMicrophoneClickListener {
            microphone.requestOrOpenSettings()
        }

        binding.setCameraClickListener {
            camera.requestOrOpenSettings()
        }

        binding.setContactsClickListener {
            contacts.requestOrOpenSettings()
        }

        binding.setBatteryOptimizationClickListener {
            openSettingsAndCheck(
                ::openBatteryOptimizationSettings,
                { viewModel.batteryOptimizationIgnored.value == true },
                R.string.permission_battery_optimization_granted_toast
            )
        }

        binding.setAppHibernationClickListener {
            openSettingsAndCheck(
                ::openAppHibernationSettings,
                { viewModel.appHibernationDisabled.value == true },
                R.string.permission_app_hibernation_disabled_toast
            )
        }
    }

    override fun onResume() {
        super.onResume()

        // Se cambian desde los ajustes del sistema: el estado se rehace cada vez que se vuelve
        viewModel.refresh(requireContext())
        pendingCheck?.let { (isGrantedNow, grantedToast) ->
            pendingCheck = null
            if (isGrantedNow()) {
                showGrantedToast(grantedToast)
            }
        }
    }

    // Un permiso de Android de la pantalla: cómo se mira, dónde están sus ajustes y el aviso al concederlo
    private inner class RuntimePermission(
        private val permission: String,
        private val grantedToast: Int,
        private val isGranted: () -> Boolean,
        private val openSettings: () -> Boolean
    ) {
        private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.refresh(requireContext())
            if (granted) {
                showGrantedToast(grantedToast)
            } else {
                // Denegado, o Android ya no pregunta: a sus ajustes
                Log.w("$TAG Permission [$permission] denied, opening its settings")
                goToSettings()
            }
        }

        fun requestOrOpenSettings() {
            if (isGranted() || shouldShowRequestPermissionRationale(permission)) {
                // Ya concedido (se quita desde los ajustes) o ya denegado una vez: a sus ajustes
                goToSettings()
            } else {
                Log.i("$TAG Requesting permission [$permission]")
                launcher.launch(permission)
            }
        }

        fun goToSettings() {
            openSettingsAndCheck(openSettings, isGranted, grantedToast)
        }
    }

    private fun openSettingsAndCheck(openSettings: () -> Boolean, isGrantedNow: () -> Boolean, @StringRes grantedToast: Int) {
        val wasGranted = isGrantedNow()
        if (openSettings() && !wasGranted) {
            pendingCheck = Pair(isGrantedNow, grantedToast)
        }
    }

    private fun showGrantedToast(@StringRes message: Int) {
        (requireActivity() as GenericActivity).showGreenToast(getString(message), R.drawable.check)
    }

    private fun openNotificationSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        }
        return startSettings(intent) || openAppSettings()
    }

    private fun openAppSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
        if (startSettings(intent)) return true

        (requireActivity() as GenericActivity).showRedToast(
            getString(R.string.error_opening_settings),
            R.drawable.warning_circle
        )
        return false
    }

    // La lista general, como el asistente: el diálogo directo exige un permiso que Google Play restringe
    private fun openBatteryOptimizationSettings(): Boolean {
        if (!startSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return openAppSettings()
        }
        if (viewModel.batteryOptimizationIgnored.value != true) {
            Toast.makeText(requireContext(), R.string.permission_battery_optimization_guide_toast, Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun openAppHibernationSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return openAppSettings()

        val opened = startSettings(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri())) || openAppSettings()
        if (opened && viewModel.appHibernationDisabled.value != true) {
            Toast.makeText(requireContext(), R.string.unused_app_restrictions_hint, Toast.LENGTH_LONG).show()
        }
        return opened
    }

    private fun packageUri(): Uri = Uri.fromParts("package", requireContext().packageName, null)

    private fun startSettings(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (anfe: ActivityNotFoundException) {
            Log.w("$TAG No activity for [${intent.action}]: $anfe")
            false
        }
    }
}
