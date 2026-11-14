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
package org.linphone.favorites

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.WorkerThread
import kotlin.concurrent.thread
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.ui.main.MainActivity
import org.linphone.utils.AppUtils

/**
 * MundoPhone (E4): widget de favoritos para la pantalla de inicio. Una rejilla de hasta 2 filas de 4 con la foto o las
 * iniciales y el nombre; al tocar uno, llama (FavoriteCall); la cabecera abre la app.
 *
 * Lo pinta de la copia en disco de FavoriteContacts, sin el Core ni la agenda. RemoteViews clásico, sin dependencias, y
 * con las celdas metidas en el propio RemoteViews en vez de una colección con RemoteViewsService: así el sistema guarda
 * el widget entero y el lanzador no despierta a la app (ni arranca el Core) cada vez que lo vuelve a pintar.
 *
 * No se actualiza solo (updatePeriodMillis=0): lo actualiza FavoritesPublisher cuando cambian los favoritos. El sistema
 * llama a onUpdate al añadirlo, al reiniciar el móvil y al actualizar la app.
 */
class FavoritesWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "[Favorites Widget]"

        private const val MAX_COLUMNS = 4
        private const val MAX_ROWS = 2

        // Lo que ocupa cada parte, en dp, para decidir cuántas columnas y filas caben (ver gridSize)
        private const val CELL_WIDTH_DP = 64
        private const val CELL_HEIGHT_DP = 64
        private const val CHROME_HEIGHT_DP = 48 // cabecera (32) y márgenes (16)
        private const val CHROME_WIDTH_DP = 16

        private const val PHOTO_SIZE_DP = 40f

        /** Vuelve a pintar todos los widgets que haya; si no hay ninguno, no hace nada. */
        @WorkerThread
        fun updateAll(context: Context, favorites: List<FavoriteContact>?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, FavoritesWidgetProvider::class.java))
            if (ids.isEmpty()) return
            Log.i("$TAG Updating [${ids.size}] widget(s) with [${favorites?.size}] favourite(s)")
            update(context, manager, ids, favorites)
        }

        @WorkerThread
        private fun update(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            favorites: List<FavoriteContact>?
        ) {
            for (id in ids) {
                try {
                    manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id), favorites))
                } catch (e: Exception) {
                    Log.e("$TAG Can't update widget [$id]: $e")
                }
            }
        }

        @WorkerThread
        private fun build(context: Context, options: Bundle?, favorites: List<FavoriteContact>?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.favorites_widget)
            val openApp = openAppPendingIntent(context)
            views.setOnClickPendingIntent(R.id.favorites_widget_header, openApp)
            views.setOnClickPendingIntent(R.id.favorites_widget_empty, openApp)
            views.setOnClickPendingIntent(R.id.favorites_widget_not_loaded, openApp)

            val rows = intArrayOf(R.id.favorites_widget_row_1, R.id.favorites_widget_row_2)
            for (row in rows) {
                views.removeAllViews(row)
                views.setViewVisibility(row, View.GONE)
            }
            views.setViewVisibility(
                R.id.favorites_widget_not_loaded,
                if (favorites == null) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.favorites_widget_empty,
                if (favorites?.isEmpty() == true) View.VISIBLE else View.GONE
            )
            if (favorites.isNullOrEmpty()) return views

            val (columns, maxRows) = gridSize(options)
            val shown = favorites.take(columns * maxRows)
            val photoSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                PHOTO_SIZE_DP,
                context.resources.displayMetrics
            ).toInt()
            for ((rowIndex, row) in rows.withIndex()) {
                val first = rowIndex * columns
                if (rowIndex >= maxRows || first >= shown.size) break
                views.setViewVisibility(row, View.VISIBLE)
                for (column in 0 until columns) {
                    // Las celdas que faltan, invisibles: así la última fila no se estira y quedan en columna
                    val favorite = shown.getOrNull(first + column)
                    views.addView(row, cell(context, favorite, photoSize))
                }
            }
            return views
        }

        @WorkerThread
        private fun cell(context: Context, favorite: FavoriteContact?, photoSize: Int): RemoteViews {
            val cell = RemoteViews(context.packageName, R.layout.favorites_widget_cell)
            if (favorite == null) {
                cell.setViewVisibility(R.id.favorites_widget_cell, View.INVISIBLE)
                return cell
            }

            cell.setTextViewText(R.id.favorites_widget_cell_name, favorite.name)
            cell.setTextViewText(R.id.favorites_widget_cell_initials, AppUtils.getInitials(favorite.name))
            val photo = FavoriteAvatars.roundPhoto(context, favorite.photo, photoSize)
            if (photo != null) {
                cell.setImageViewBitmap(R.id.favorites_widget_cell_photo, photo)
                cell.setViewVisibility(R.id.favorites_widget_cell_photo, View.VISIBLE)
            }
            cell.setContentDescription(
                R.id.favorites_widget_cell,
                context.getString(R.string.favorites_call_contact_description, favorite.name)
            )

            val intent = FavoriteCall.callIntent(context, favorite.target)
            // Cada favorito con su PendingIntent: el data (lo que se marca) ya los distingue; el código, por si acaso
            val pendingIntent = PendingIntent.getActivity(
                context,
                favorite.key.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            cell.setOnClickPendingIntent(R.id.favorites_widget_cell, pendingIntent)
            return cell
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClass(context, MainActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /**
         * Columnas y filas que caben con el tamaño que da el lanzador (en vertical: ancho mínimo y alto máximo). Sin ese
         * dato (al añadirlo, antes de saber su tamaño), las del tamaño por defecto: 4 x 2.
         */
        private fun gridSize(options: Bundle?): Pair<Int, Int> {
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val columns = if (width > 0) ((width - CHROME_WIDTH_DP) / CELL_WIDTH_DP).coerceIn(1, MAX_COLUMNS) else MAX_COLUMNS
            val rows = if (height > 0) ((height - CHROME_HEIGHT_DP) / CELL_HEIGHT_DP).coerceIn(1, MAX_ROWS) else MAX_ROWS
            return Pair(columns, rows)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.i("$TAG onUpdate for [${appWidgetIds.size}] widget(s)")
        updateInBackground(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        // Al cambiarle el tamaño: caben más o menos columnas y filas
        updateInBackground(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun updateInBackground(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Leer el fichero y las fotos fuera del hilo principal; goAsync mantiene vivo el receptor mientras tanto
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread(name = "FavoritesWidget") {
            try {
                update(appContext, manager, ids, FavoriteContacts.load(appContext))
            } finally {
                pendingResult.finish()
            }
        }
    }
}
