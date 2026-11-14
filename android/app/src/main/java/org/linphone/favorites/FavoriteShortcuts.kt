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
import android.os.PersistableBundle
import java.security.MessageDigest
import androidx.annotation.WorkerThread
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.linphone.R
import org.linphone.core.tools.Log

/**
 * MundoPhone (E4): los accesos directos dinámicos del icono (mantener pulsado): hasta 4 favoritos que llaman al tocarlos
 * y el buzón de voz si la cuenta por defecto tiene uno configurado (3 favoritos entonces).
 *
 * Convive con los de upstream: los de conversaciones (ShortcutUtils, con el id de la conversación) se publican con
 * pushDynamicShortcut, que respeta el límite del sistema. Aquí se tocan solo los ids con [ID_PREFIX], también con
 * pushDynamicShortcut (nunca setDynamicShortcuts, que borraría los de las conversaciones).
 *
 * El buzón no es estático (shortcuts.xml): un estático sale siempre, y la mayoría de las cuentas no tienen buzón
 * configurado («URI del buzón de voz» en los ajustes de la cuenta).
 */
object FavoriteShortcuts {
    private const val TAG = "[Favorite Shortcuts]"

    private const val ID_PREFIX = "mundophone_"
    private const val FAVORITE_ID_PREFIX = "${ID_PREFIX}favorito:"
    private const val VOICEMAIL_ID = "${ID_PREFIX}buzon"

    private const val MAX_FAVORITES = 4
    private const val VOICEMAIL_RANK = 10 // detrás de los favoritos; el sistema deja los rangos consecutivos

    // 108 dp de un icono adaptable a ~2x: el sistema lo guarda reducido a su tamaño de icono
    private const val ICON_SIZE_PX = 216

    /**
     * Firma de lo publicado, con el rango que se pidió (en los extras del acceso directo): solo se vuelve a publicar lo
     * que cambia. El rango real no sirve para comparar: el sistema lo renumera, y los de conversaciones entran delante.
     */
    private const val EXTRA_SIGNATURE = "firma"

    private class Spec(
        val id: String,
        val rank: Int,
        /** Lo que, si cambia, obliga a volver a publicarlo */
        val signature: List<String>,
        val build: () -> ShortcutInfoCompat.Builder
    )

    /**
     * Deja publicados los que tocan, quitando los nuestros que sobran. [favorites] null = todavía no se sabe cuáles son
     * (la agenda no se ha cargado nunca): no se tocan los de favoritos, solo el del buzón.
     */
    @WorkerThread
    fun publish(context: Context, favorites: List<FavoriteContact>?, voicemail: Boolean) {
        val wanted = arrayListOf<Spec>()
        if (favorites != null) {
            val count = if (voicemail) MAX_FAVORITES - 1 else MAX_FAVORITES
            favorites.take(count).forEachIndexed { rank, favorite ->
                wanted.add(favoriteSpec(context, favorite, rank))
            }
        }
        if (voicemail) wanted.add(voicemailSpec(context))

        try {
            val existing = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id.startsWith(ID_PREFIX) }
            val stale = existing.map { it.id }.filter { id ->
                wanted.none { it.id == id } && (favorites != null || !id.startsWith(FAVORITE_ID_PREFIX))
            }
            if (stale.isNotEmpty()) {
                Log.i("$TAG Removing [${stale.size}] shortcut(s) no longer needed")
                ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            }

            for (spec in wanted) {
                val current = existing.find { it.id == spec.id }
                val signature = digest(listOf(spec.rank.toString()) + spec.signature)
                if (current?.extras?.getString(EXTRA_SIGNATURE) == signature) continue

                val extras = PersistableBundle().apply { putString(EXTRA_SIGNATURE, signature) }
                val shortcut = spec.build().setRank(spec.rank).setExtras(extras).build()
                Log.i("$TAG Publishing shortcut [${spec.id}] with rank [${spec.rank}]")
                if (!ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)) {
                    // Android 9-10 limita las publicaciones en segundo plano; se reintenta en el próximo cambio
                    Log.w("$TAG Shortcut [${spec.id}] not published (rate limited?)")
                }
            }
        } catch (e: Exception) {
            // IllegalStateException si el usuario está bloqueado, IllegalArgumentException si el sistema rechaza algo
            Log.e("$TAG Can't publish shortcuts: $e")
        }
    }

    private fun favoriteSpec(context: Context, favorite: FavoriteContact, rank: Int): Spec {
        // El id, resumido: la clave puede llevar el nombre del contacto (FavoriteContact.key) y el sistema lo guarda en XML
        val id = FAVORITE_ID_PREFIX + digest(listOf(favorite.key)).take(24)
        return Spec(id, rank, listOf(favorite.name, favorite.target, favorite.photo)) {
            val photo = FavoriteAvatars.photo(context, favorite.photo, ICON_SIZE_PX)
            val bitmap = photo ?: FavoriteAvatars.adaptiveInitials(context, favorite.name, ICON_SIZE_PX)
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(favorite.name)
                .setLongLabel(favorite.name)
                .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                .setIntent(FavoriteCall.callIntent(context, favorite.target, id))
        }
    }

    /**
     * Resumen en hexadecimal: los extras los guarda el sistema en un XML (en texto hasta Android 11), donde no caben
     * caracteres de control como separador.
     */
    private fun digest(parts: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun voicemailSpec(context: Context): Spec {
        val shortLabel = context.getString(R.string.voicemail_shortcut_short_label)
        val longLabel = context.getString(R.string.voicemail_shortcut_long_label)
        // Con las etiquetas en la firma, un cambio de idioma de la app se ve en el siguiente arranque
        return Spec(VOICEMAIL_ID, VOICEMAIL_RANK, listOf(shortLabel, longLabel)) {
            ShortcutInfoCompat.Builder(context, VOICEMAIL_ID)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(IconCompat.createWithResource(context, R.drawable.voicemail_shortcut))
                .setIntent(FavoriteCall.voicemailIntent(context, VOICEMAIL_ID))
        }
    }
}
