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
package org.linphone

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.google.android.material.color.DynamicColors
import org.linphone.compatibility.Compatibility
import org.linphone.core.CoreContext
import org.linphone.core.CorePreferences
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import org.linphone.core.LogLevel
import org.linphone.core.MundoPhoneDns
import org.linphone.core.MundoPhoneMigrations
import org.linphone.core.MundoPhoneTiempos
import org.linphone.core.VFS
import org.linphone.core.tools.Log

@MainThread
class LinphoneApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        private const val TAG = "[Linphone Application]"

        @SuppressLint("StaticFieldLeak")
        lateinit var corePreferences: CorePreferences

        @SuppressLint("StaticFieldLeak")
        lateinit var coreContext: CoreContext
    }

    @SuppressLint("ThreadConstraint")
    override fun onCreate() {
        // MundoPhone: tiempos del arranque, para medir el tramo que el log no veía (del arranque del proceso al primer log,
        // hasta 1,8 s en un arranque en frío por push con la pantalla apagada)
        val onCreateStart = SystemClock.elapsedRealtime()
        super.onCreate()
        val context = applicationContext

        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Linphone:AppCreation"
        )
        wakeLock.acquire(20000L) // 20 seconds

        Factory.instance().setLogCollectionPath(context.filesDir.absolutePath)
        Factory.instance().enableLogCollection(LogCollectionState.Enabled)
        // For VFS
        Factory.instance().setCacheDir(context.cacheDir.absolutePath)

        corePreferences = CorePreferences(context)
        corePreferences.copyAssetsFromPackage()

        val vfsStart = SystemClock.elapsedRealtime()
        if (VFS.isEnabledFast(context)) {
            VFS.setup(context)
        }
        val vfsMs = SystemClock.elapsedRealtime() - vfsStart

        val config = Factory.instance().createConfigWithFactory(
            corePreferences.configPath,
            corePreferences.factoryConfigPath
        )
        corePreferences.config = config
        // MundoPhone: push, TCP y puerto de nuestras cuentas, antes de que el Core lea la configuración
        MundoPhoneMigrations.beforeCoreStart(config)
        // MundoPhone: la IP del borde en un fichero de hosts para belle-sip (sin esperar al DNS en el arranque por push)
        MundoPhoneDns.prepare(context, config)

        val appName = context.getString(R.string.app_name)
        Factory.instance().setLoggerDomain(appName)
        Factory.instance().loggingService.setLogLevel(LogLevel.Message)
        Factory.instance().enableLogcatLogs(corePreferences.printLogsInLogcat)

        Log.i("$TAG Report Core preferences initialized")
        Log.i(
            "$TAG MundoPhone startup: process start to onCreate [${onCreateStart - Process.getStartElapsedRealtime()}] ms, " +
                "onCreate until logs [${SystemClock.elapsedRealtime() - onCreateStart}] ms (VFS check [$vfsMs] ms)"
        )
        // MundoPhone (F4): las mismas dos horas, para el resumen de la fase «entrante» si este arranque es por un push
        MundoPhoneTiempos.processStarted(onCreateStart)
        Compatibility.setupAppStartupListener(context)

        coreContext = CoreContext(context)
        coreContext.start()

        DynamicColors.applyToActivitiesIfAvailable(this)
        Log.i("$TAG MundoPhone startup: onCreate took [${SystemClock.elapsedRealtime() - onCreateStart}] ms")
        wakeLock.release()
    }

    override fun onTrimMemory(level: Int) {
        Log.w("$TAG onTrimMemory called with level [${trimLevelToString(level)}]($level) !")
        when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE -> {
                Log.i("$TAG Memory trim required, clearing imageLoader memory cache")
                imageLoader.memoryCache?.clear()
            }
            else -> {}
        }
        super.onTrimMemory(level)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        // When VFS is enabled, prevent Coil from keeping plain version of files on disk
        val diskCachePolicy = if (VFS.isEnabledFast(applicationContext)) {
            CachePolicy.DISABLED
        } else {
            CachePolicy.ENABLED
        }

        return ImageLoader.Builder(this)
            .crossfade(false)
            .components {
                add(VideoFrameDecoder.Factory())
                // add(GifDecoder.Factory) // Do not add it, GIFs are properly rendered without it and adding it breaks resizing...
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                val cache = cacheDir.resolve("image_cache")
                DiskCache.Builder()
                    .directory(cache)
                    .maxSizePercent(0.02)
                    .build()
            }
            .networkCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(diskCachePolicy)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    private fun trimLevelToString(level: Int): String {
        return when (level) {
            TRIM_MEMORY_UI_HIDDEN -> "Hidden UI"
            TRIM_MEMORY_RUNNING_MODERATE -> "Moderate (Running)"
            TRIM_MEMORY_RUNNING_LOW -> "Low"
            TRIM_MEMORY_RUNNING_CRITICAL -> "Critical"
            TRIM_MEMORY_BACKGROUND -> "Background"
            TRIM_MEMORY_MODERATE -> "Moderate"
            TRIM_MEMORY_COMPLETE -> "Complete"
            else -> level.toString()
        }
    }
}
