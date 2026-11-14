/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
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
package org.linphone.compatibility

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import org.linphone.core.tools.Log

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class Api35Compatibility {
    companion object {
        private const val TAG = "[API 35 Compatibility]"

        fun setupAppStartupListener(context: Context) {
            try {
                val activityManager = context.getSystemService(ActivityManager::class.java)
                activityManager.addApplicationStartInfoCompletionListener(
                    Executors.newSingleThreadExecutor()
                ) { info ->
                    Log.i("==== Current startup information dump ====")
                    logAppStartupInfo(info)
                }

                Log.i("==== Fetching last three startup reasons if available ====")
                val lastStartupInfo = activityManager.getHistoricalProcessStartReasons(3)
                for (info in lastStartupInfo) {
                    Log.i("==== Previous startup information dump ====")
                    logAppStartupInfo(info)
                }
            } catch (iae: IllegalArgumentException) {
                Log.e("$TAG Can't add application start info completion listener: $iae")
            }
        }

        private fun logAppStartupInfo(info: ApplicationStartInfo) {
            Log.i("TYPE = ${startupTypeToString(info.startType)}")
            Log.i("STATE = ${startupStateToString(info.startupState)}")
            Log.i("REASON = ${startupReasonToString(info.reason)}")
            Log.i("START COMPONENT = ${startComponentToString(info.launchMode)}")
            Log.i("INTENT = ${info.intent}")
            Log.i("TIMESTAMPS = ${startupTimestampsToString(info)}")
            Log.i("FORCE STOPPED = ${if (info.wasForceStopped()) "yes" else "no"}")
            Log.i("PROCESS NAME = ${info.processName}")
            Log.i("=========================================")
        }

        // MundoPhone: tiempos de Android desde que decidió lanzar el proceso. Con la app cerrada, la redirección de
        // llamadas tiene 5 s de Telecom que empiezan ahí, y lo que va antes de nuestro onCreate no se ve de otra forma
        private fun startupTimestampsToString(info: ApplicationStartInfo): String {
            val timestamps = info.startupTimestamps
            val launch = timestamps[ApplicationStartInfo.START_TIMESTAMP_LAUNCH] ?: return "sin lanzamiento $timestamps"

            fun since(key: Int, name: String): String? = timestamps[key]?.let { "$name +${(it - launch) / 1_000_000} ms" }
            val stages = listOfNotNull(
                since(ApplicationStartInfo.START_TIMESTAMP_FORK, "fork"),
                since(ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION, "bindApplication"),
                since(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE, "onCreate"),
                since(ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME, "primer frame")
            )
            val ago = (SystemClock.elapsedRealtimeNanos() - launch) / 1_000_000
            return "lanzado hace [$ago] ms; ${stages.joinToString(", ").ifEmpty { "sin más etapas" }}"
        }

        private fun startComponentToString(component: Int): String {
            return when (component) {
                ApplicationStartInfo.START_COMPONENT_ACTIVITY -> "Activity"
                ApplicationStartInfo.START_COMPONENT_BROADCAST -> "Broadcast"
                ApplicationStartInfo.START_COMPONENT_CONTENT_PROVIDER -> "Content Provider"
                ApplicationStartInfo.START_COMPONENT_SERVICE -> "Service"
                ApplicationStartInfo.START_COMPONENT_OTHER -> "Other"
                else -> "Unexpected ($component)"
            }
        }

        private fun startupTypeToString(type: Int): String {
            return when (type) {
                ApplicationStartInfo.START_TYPE_COLD -> "Cold"
                ApplicationStartInfo.START_TYPE_HOT -> "Hot"
                ApplicationStartInfo.START_TYPE_UNSET -> "Unset"
                ApplicationStartInfo.START_TYPE_WARM -> "Warm"
                else -> "Unexpected ($type)"
            }
        }

        private fun startupStateToString(state: Int): String {
            return when (state) {
                ApplicationStartInfo.STARTUP_STATE_STARTED -> "Started"
                ApplicationStartInfo.STARTUP_STATE_ERROR -> "Error"
                ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN -> "First frame drawn"
                else -> "Unexpected ($state)"
            }
        }

        private fun startupReasonToString(reason: Int): String {
            return when (reason) {
                ApplicationStartInfo.START_REASON_ALARM -> "Alarm"
                ApplicationStartInfo.START_REASON_BACKUP -> "Backup"
                ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "Boot complete"
                ApplicationStartInfo.START_REASON_BROADCAST -> "Broadcast"
                ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "Content provider"
                ApplicationStartInfo.START_REASON_JOB -> "Job"
                ApplicationStartInfo.START_REASON_LAUNCHER -> "Launcher"
                ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "Launcher (recents)"
                ApplicationStartInfo.START_REASON_OTHER -> "Other"
                ApplicationStartInfo.START_REASON_PUSH -> "Push"
                ApplicationStartInfo.START_REASON_SERVICE -> "Service"
                ApplicationStartInfo.START_REASON_START_ACTIVITY -> "Start Activity"
                else -> "Unexpected ($reason)"
            }
        }
    }
}
