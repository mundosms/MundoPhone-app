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
package org.linphone.ui.main.settings.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.UiThread
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.linphone.compatibility.Compatibility

/**
 * MundoPhone: el estado de Ajustes › «Permisos de MundoPhone». Todo son consultas a Android,
 * sin el Core: se rehace al volver a la pantalla y tras cada petición, porque se cambia desde los ajustes del sistema.
 */
class PermissionsManagementViewModel
    @UiThread
    constructor() : ViewModel() {
    val notificationsGranted = MutableLiveData<Boolean>()

    val microphoneGranted = MutableLiveData<Boolean>()

    val cameraGranted = MutableLiveData<Boolean>()

    val contactsGranted = MutableLiveData<Boolean>()

    val batteryOptimizationIgnored = MutableLiveData<Boolean>()

    // La hibernación de apps (quitar permisos si no se usa) existe desde Android 11
    val showAppHibernation = MutableLiveData<Boolean>()

    val appHibernationDisabled = MutableLiveData<Boolean>()

    @UiThread
    fun refresh(context: Context) {
        // Antes de Android 13 no hay permiso que pedir, pero el usuario puede apagarlas igual
        notificationsGranted.value = Compatibility.isPostNotificationsPermissionGranted(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        microphoneGranted.value = isGranted(context, Manifest.permission.RECORD_AUDIO)
        cameraGranted.value = isGranted(context, Manifest.permission.CAMERA)
        contactsGranted.value = isGranted(context, Manifest.permission.READ_CONTACTS)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryOptimizationIgnored.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showAppHibernation.value = true
            appHibernationDisabled.value = context.packageManager.isAutoRevokeWhitelisted
        } else {
            showAppHibernation.value = false
            appHibernationDisabled.value = true
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
