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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import kotlin.math.max
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.utils.AppUtils

/**
 * MundoPhone (E4): las caras de los favoritos fuera de la app (accesos directos y widget). La foto del contacto si la
 * tiene; si no, sus iniciales con los colores de la marca (celeste #A3E0EF y azul petróleo #00647C).
 */
object FavoriteAvatars {
    private const val TAG = "[Favorite Avatars]"

    /**
     * La foto recortada en cuadrado y a [size] px, o null si no tiene o no se puede leer. ImageDecoder la decodifica ya
     * reducida: la de la agenda puede ser de 720 px y el widget la pinta a 40 dp.
     */
    @WorkerThread
    fun photo(context: Context, uri: String, size: Int): Bitmap? {
        if (uri.isEmpty()) return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri.toUri())
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = size.toFloat() / minOf(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).toInt()),
                        max(1, (info.size.height * scale).toInt())
                    )
                }
                // Por software: los iconos de los accesos directos no admiten bitmaps de hardware
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            centerCrop(decoded, size, round = false)
        } catch (e: Exception) {
            Log.w("$TAG Can't read photo [$uri]: $e")
            null
        }
    }

    /** Foto redonda, para el widget. */
    @WorkerThread
    fun roundPhoto(context: Context, uri: String, size: Int): Bitmap? {
        val square = photo(context, uri, size) ?: return null
        return centerCrop(square, size, round = true)
    }

    /**
     * Iniciales para un icono adaptable (IconCompat.createWithAdaptiveBitmap): fondo a sangre, porque el lanzador
     * recorta con su máscara, y las letras dentro de la zona segura (los 72 dp centrales de 108).
     */
    @WorkerThread
    fun adaptiveInitials(context: Context, name: String, size: Int): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.mundosms_main_100))

        val initials = AppUtils.getInitials(name).ifEmpty { "?" }
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = ContextCompat.getColor(context, R.color.mundosms_main_500)
            textSize = size * 0.26f
            typeface = ResourcesCompat.getFont(context, R.font.noto_sans_800)
            textAlign = Paint.Align.CENTER
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, baseline, paint)
        return bitmap
    }

    private fun centerCrop(source: Bitmap, size: Int, round: Boolean): Bitmap {
        if (!round && source.width == size && source.height == size) return source

        val output = createBitmap(size, size)
        val canvas = Canvas(output)
        val scale = size.toFloat() / minOf(source.width, source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (round) {
            paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        } else {
            canvas.drawBitmap(source, matrix, paint)
        }
        return output
    }
}
