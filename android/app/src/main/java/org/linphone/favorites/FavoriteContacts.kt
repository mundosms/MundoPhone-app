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

import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.io.File
import java.text.Collator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.contacts.ContactLoader
import org.linphone.contacts.Team
import org.linphone.contacts.getListOfSipAddresses
import org.linphone.core.Core
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.MagicSearch
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils

/**
 * MundoPhone (E4): un contacto favorito tal como lo usan los accesos directos del icono y el widget.
 *
 * Se guarda lo justo para pintarlo y llamarle sin el Core ni la agenda: el widget lo lee en un proceso que puede no
 * tener nada cargado.
 */
data class FavoriteContact(
    /** Estable entre arranques: lista del contacto + su refKey (o lo que se marca, si no tiene) */
    val key: String,
    val name: String,
    /**
     * Lo que se marca: una dirección SIP («sip:ana@…») o un número tal como está en el contacto. El número se
     * convierte en dirección al llamar (Core.interpretUrl), con la cuenta que haya entonces, como MainActivity con
     * un «tel:».
     */
    val target: String,
    /** Foto (content: o file:) o vacío: entonces, iniciales */
    val photo: String,
    /** De la agenda del móvil (lista «Native address-book»), que solo se carga con la app abierta */
    val fromAddressBook: Boolean
)

/**
 * MundoPhone (E4): la lista de favoritos que se enseña fuera de la app, y su copia en disco.
 *
 * Son los mismos que la sección «Favoritos» de Contactos y de Equipo: los Friend con estrella que da MagicSearch
 * (Source.FavoriteFriends), en orden alfabético. La copia va a noBackupFilesDir porque se rehace sola.
 */
object FavoriteContacts {
    private const val TAG = "[Favorite Contacts]"

    private const val FILE_NAME = "mundophone_favoritos.json"
    private const val VERSION = 1

    /**
     * Calcula los favoritos con lo que el Core tiene cargado ahora. Sin [includeAddressBook], deja fuera los de la agenda
     * del móvil (FavoritesPublisher, cuando la agenda aún no se ha cargado y no se puede saber cuáles son).
     */
    @WorkerThread
    fun compute(core: Core, includeAddressBook: Boolean): List<FavoriteContact> {
        val magicSearch = core.createMagicSearch()
        magicSearch.limitedSearch = false
        val results = magicSearch.getContactsList(
            "",
            "",
            MagicSearch.Source.FavoriteFriends.toInt(),
            MagicSearch.Aggregation.Friend
        )

        val list = arrayListOf<FavoriteContact>()
        val keys = hashSetOf<String>()
        for (result in results) {
            val friend = result.friend ?: continue
            if (!friend.starred) continue
            // La lista temporal de los directorios remotos no es de nadie (ContactsManager.isContactTemporary)
            if (friend.friendList?.type == FriendList.Type.ApplicationCache) continue
            val fromAddressBook = friend.friendList?.displayName == ContactLoader.NATIVE_ADDRESS_BOOK_FRIEND_LIST
            if (fromAddressBook && !includeAddressBook) continue

            val target = callTarget(core, friend)
            if (target == null) {
                Log.w("$TAG Favourite [${friend.name}] has nothing to call, skipping it")
                continue
            }
            val key = "${friend.friendList?.displayName.orEmpty()}|${friend.refKey?.ifEmpty { null } ?: target}"
            if (!keys.add(key)) continue

            list.add(
                FavoriteContact(key, displayName(friend, target), target, photoUri(friend, fromAddressBook), fromAddressBook)
            )
        }

        Log.i("$TAG [${list.size}] favourite(s) found out of [${results.size}] result(s)")
        return sorted(list)
    }

    /** En orden alfabético, como la sección «Favoritos» de la app. */
    @AnyThread
    fun sorted(favorites: List<FavoriteContact>): List<FavoriteContact> {
        val collator = Collator.getInstance(Locale.getDefault())
        return favorites.sortedWith { first, second -> collator.compare(first.name, second.name) }
    }

    /**
     * Lo que se marca al tocar el favorito. Con una sola opción, la misma que la ficha del contacto
     * (LinphoneUtils.getSingleAvailableAddressForFriend). Con varias la ficha pregunta, y aquí no se puede: la
     * principal es la dirección SIP del equipo (la extensión de la centralita), luego la del dominio de la cuenta, luego
     * cualquier dirección SIP y, si no hay ninguna, el primer número.
     */
    @WorkerThread
    private fun callTarget(core: Core, friend: Friend): String? {
        val addresses = friend.getListOfSipAddresses().map { // vacía si [app] hide_sip_addresses
            CallableAddress(it.asStringUriOnly(), it.scheme, it.username, it.domain)
        }
        val phoneNumbers = if (corePreferences.hidePhoneNumbers) emptyList() else friend.phoneNumbers.toList()
        return chooseCallTarget(addresses, phoneNumbers, core.defaultAccount?.params?.identityAddress?.domain)
    }

    /** Una dirección SIP del contacto, con lo que mira [chooseCallTarget]. */
    data class CallableAddress(val uri: String, val scheme: String?, val username: String?, val domain: String?)

    /** La elección de [callTarget] sin el SDK, para probarla en la JVM (app/src/test). */
    @AnyThread
    fun chooseCallTarget(addresses: List<CallableAddress>, phoneNumbers: List<String>, accountDomain: String?): String? {
        val numbers = phoneNumbers.filter { it.isNotBlank() }

        if (addresses.size == 1 && numbers.isEmpty()) return addresses.first().uri
        if (addresses.isEmpty() && numbers.size == 1) return numbers.first()

        val address = addresses.firstOrNull { Team.isTeamAddress(it.scheme, it.username) }
            ?: addresses.firstOrNull { accountDomain != null && it.domain == accountDomain }
            ?: addresses.firstOrNull()
        return address?.uri ?: numbers.firstOrNull()
    }

    @WorkerThread
    private fun displayName(friend: Friend, target: String): String {
        val name = friend.name.orEmpty().trim()
        if (name.isNotEmpty()) return name
        val fullName = "${friend.firstName.orEmpty()} ${friend.lastName.orEmpty()}".trim()
        if (fullName.isNotEmpty()) return fullName
        if (!friend.organization.isNullOrBlank()) return friend.organization.orEmpty().trim()
        val address = coreContext.core.interpretUrl(target, false)
        return if (address != null) LinphoneUtils.getDisplayName(address) else target
    }

    /**
     * Foto del contacto. Los de la agenda: la de Android (PHOTO_URI, vacía si no tiene). Los demás: la suya si es un
     * fichero o un content://; una URL de un directorio remoto se queda en iniciales (habría que bajarla).
     */
    @WorkerThread
    private fun photoUri(friend: Friend, fromAddressBook: Boolean): String {
        val refKey = friend.refKey.orEmpty()
        val contactId = refKey.toLongOrNull()
        if (fromAddressBook && contactId != null) {
            return try {
                val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
                coreContext.context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.Contacts.PHOTO_URI),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                }.orEmpty()
            } catch (e: Exception) {
                Log.w("$TAG Can't read photo of native contact [$refKey]: $e")
                ""
            }
        }

        // Las de los contactos creados en la app son «file:/…» (FileUtils.getProperFilePath)
        val photo = friend.photo.orEmpty()
        return when {
            photo.startsWith("content:") || photo.startsWith("file:") -> photo
            photo.startsWith("/") -> "file:$photo"
            else -> ""
        }
    }

    /** La copia en disco: null si todavía no se ha calculado nunca (el widget lo dice), vacía si no hay favoritos. */
    @AnyThread
    fun load(context: Context): List<FavoriteContact>? {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("favoritos")
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                FavoriteContact(
                    item.getString("clave"),
                    item.getString("nombre"),
                    item.getString("marcar"),
                    item.optString("foto"),
                    item.optBoolean("agenda")
                )
            }
        } catch (e: Exception) {
            Log.e("$TAG Can't read [$FILE_NAME]: $e")
            null
        }
    }

    @AnyThread
    fun save(context: Context, favorites: List<FavoriteContact>) {
        val array = JSONArray()
        for (favorite in favorites) {
            array.put(
                JSONObject()
                    .put("clave", favorite.key)
                    .put("nombre", favorite.name)
                    .put("marcar", favorite.target)
                    .put("foto", favorite.photo)
                    .put("agenda", favorite.fromAddressBook)
            )
        }
        val json = JSONObject().put("version", VERSION).put("favoritos", array)

        // Escribir aparte y renombrar: el widget la lee desde otro hilo y nunca debe ver un fichero a medias
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val temporary = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        try {
            temporary.writeText(json.toString())
            if (!temporary.renameTo(file)) {
                Log.e("$TAG Can't rename [${temporary.name}] to [$FILE_NAME]")
            }
        } catch (e: Exception) {
            Log.e("$TAG Can't write [$FILE_NAME]: $e")
        }
    }
}
