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
package org.linphone.ui.assistant.fragment

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantPermissionsFragmentBinding
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.assistant.viewmodel.PermissionsViewModel
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils
import kotlin.getValue

@UiThread
class PermissionsFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Permissions Fragment]"

        private const val BATTERY_SETTINGS_NONE = 0
        private const val BATTERY_SETTINGS_OPENED = 1
        private const val BATTERY_SETTINGS_SHOWN = 2
    }

    private lateinit var binding: AssistantPermissionsFragmentBinding

    private val viewModel: PermissionsViewModel by navGraphViewModels(
        R.id.assistant_nav_graph
    )

    private var leaving = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            val permissionName = it.key
            val isGranted = it.value
            if (isGranted) {
                Log.i("Permission [$permissionName] is now granted")
            } else {
                Log.i("Permission [$permissionName] has been denied")
                allGranted = false
            }
        }

        if (!allGranted) {
            Log.w(
                "$TAG Not all permissions were granted, leaving anyway, they will be asked again later..."
            )
        }
        afterRuntimePermissions()
    }

    // MundoPhone (A13): después de los permisos de Android, la batería y la hibernación de la app. Estado de los ajustes
    // de batería: la respuesta de los permisos llega antes del onResume, así que entre abrirlos y que el usuario esté
    // en ellos hay un onResume que no debe hacer nada.
    private var batterySettings = BATTERY_SETTINGS_NONE

    private val telecomManagerPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG MANAGE_OWN_CALLS permission has been granted")
        } else {
            Log.w("$TAG MANAGE_OWN_CALLS permission has been denied, leaving this fragment")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AssistantPermissionsFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        binding.setBackClickListener {
            findNavController().popBackStack()
        }

        binding.setSkipClickListener {
            Log.i("$TAG User clicked skip...")
            leave()
        }

        binding.setGrantAllClickListener {
            Log.i("$TAG Requesting all permissions")
            requestPermissionLauncher.launch(
                Compatibility.getAllRequiredPermissionsArray()
            )
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.MANAGE_OWN_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("$TAG Request MANAGE_OWN_CALLS permission")
            telecomManagerPermissionLauncher.launch(Manifest.permission.MANAGE_OWN_CALLS)
        }

        if (!Compatibility.hasFullScreenIntentPermission(requireContext())) {
            Log.w(
                "$TAG Android 14 or newer detected & full screen intent permission hasn't been granted!"
            )
            Compatibility.requestFullScreenIntentPermission(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()

        if (batterySettings == BATTERY_SETTINGS_OPENED) {
            batterySettings = BATTERY_SETTINGS_SHOWN
        }
    }

    override fun onResume() {
        super.onResume()

        viewModel.batteryOptimizationIgnored.value = isIgnoringBatteryOptimizations()
        when (batterySettings) {
            BATTERY_SETTINGS_OPENED -> return // Todavía no se han abierto
            BATTERY_SETTINGS_SHOWN -> {
                Log.i("$TAG Back from battery optimization settings, ignored is [${isIgnoringBatteryOptimizations()}]")
                batterySettings = BATTERY_SETTINGS_NONE
                askToDisableAppHibernation()
                return
            }
        }

        if (!leaving && areAllPermissionsGranted()) {
            Log.i("$TAG All permissions have been granted, skipping")
            leave()
        }
    }

    private fun afterRuntimePermissions() {
        if (!isIgnoringBatteryOptimizations() && openBatteryOptimizationSettings()) {
            batterySettings = BATTERY_SETTINGS_OPENED
            return
        }
        askToDisableAppHibernation()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    // La lista general, no el diálogo directo (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS): ese exige declarar
    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, un permiso que Google Play restringe a las apps que no pueden usar push de
    // alta prioridad, y nosotros lo usamos; al usuario se le dice qué tocar.
    private fun openBatteryOptimizationSettings(): Boolean {
        return try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Toast.makeText(
                requireContext(),
                R.string.permission_battery_optimization_guide_toast,
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (anfe: ActivityNotFoundException) {
            Log.w("$TAG No battery optimization settings screen on this device: $anfe")
            false
        }
    }

    // Desde Android 11, si la app no se usa en unos meses el sistema le quita los permisos, y con ellos las llamadas
    private fun askToDisableAppHibernation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            requireContext().packageManager.isAutoRevokeWhitelisted
        ) {
            leave()
            return
        }

        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getUnusedAppRestrictionsDialog(requireActivity(), model)
        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
                leave()
            }
        }
        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
                openAppHibernationSettings()
                leave()
            }
        }
        dialog.show()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun openAppHibernationSettings() {
        val uri = Uri.fromParts("package", requireContext().packageName, null)
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
        Toast.makeText(requireContext(), R.string.unused_app_restrictions_hint, Toast.LENGTH_LONG).show()
    }

    private fun leave() {
        if (leaving) return
        leaving = true

        if (requireActivity().intent.getBooleanExtra(AssistantActivity.SKIP_LANDING_EXTRA, false)) {
            Log.w(
                "$TAG We were asked to leave assistant if at least an account is already configured"
            )
            coreContext.postOnCoreThread { core ->
                if (core.accountList.isNotEmpty()) {
                    coreContext.postOnMainThread {
                        Log.w("$TAG At least one account was found, leaving assistant")
                        try {
                            requireActivity().finish()
                        } catch (ise: IllegalStateException) {
                            Log.e("$TAG Failed to finish activity: $ise")
                        }
                    }
                } else {
                    coreContext.postOnMainThread {
                        Log.w("$TAG No account was found, going to landing fragment")
                        try {
                            goToLoginFragment()
                        } catch (ise: IllegalStateException) {
                            Log.e("$TAG Failed to navigate to login fragment: $ise")
                        }
                    }
                }
            }
        } else {
            goToLoginFragment()
        }
    }

    private fun goToLoginFragment() {
        if (findNavController().currentDestination?.id == R.id.permissionsFragment) {
            val action =
                PermissionsFragmentDirections.actionPermissionsFragmentToLandingFragment()
            leaving = false
            findNavController().navigate(action)
        }
    }

    // MundoPhone: el estado de TODOS (antes paraba en el primero que faltaba y los de detrás salían como pendientes);
    // los encabezados de esenciales y opcionales dependen de él
    private fun areAllPermissionsGranted(): Boolean {
        var allGranted = true
        for (permission in Compatibility.getAllRequiredPermissionsArray()) {
            val granted = ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
            viewModel.setPermissionGranted(permission, granted)
            if (!granted) {
                Log.w("$TAG Permission [$permission] hasn't been granted yet!")
                allGranted = false
            }
        }
        return allGranted && Compatibility.hasFullScreenIntentPermission(requireContext())
    }
}
