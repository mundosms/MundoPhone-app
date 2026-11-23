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
package org.linphone.contacts

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.loader.app.LoaderManager
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.ConferenceInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.FriendListListenerStub
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SecurityLevel
import org.linphone.core.tools.Log
import org.linphone.favorites.FavoritesPublisher
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressClickListener
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressModel
import org.linphone.ui.main.model.isEndToEndEncryptionMandatory
import org.linphone.utils.AppUtils
import org.linphone.utils.ImageUtils
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.PhoneNumberUtils
import org.linphone.utils.ShortcutUtils
import java.io.FileNotFoundException

class ContactsManager
    @UiThread
    constructor() {
    companion object {
        private const val TAG = "[Contacts Manager]"

        // MundoPhone: tope de la consulta a la agenda de Android durante la llamada entrante (la 1.ª en frío, 77-97 ms en un POCO)
        private const val NATIVE_LOOKUP_TIMEOUT_MS = 300L

        private const val DELAY_BEFORE_RELOADING_CONTACTS_AFTER_PRESENCE_RECEIVED = 1000L // 1 second
        private const val DELAY_BEFORE_RELOADING_CONTACTS_AFTER_MAGIC_SEARCH_RESULT = 1000L // 1 second
        private const val FRIEND_LIST_TEMPORARY_STORED_REMOTE_DIRECTORY = "TempRemoteDirectoryContacts"
    }

    private var nativeContactsLoaded = false

    // MundoPhone: Equipo desde la lista del borde (RLS), o como hasta ahora si no la hay
    val teamRls = TeamRlsManager()

    private val listeners = arrayListOf<ContactsListener>()

    private val knownContactsAvatarsMap = hashMapOf<String, ContactAvatarModel>()
    private val unknownContactsAvatarsMap = hashMapOf<String, ContactAvatarModel>()
    private val conferenceAvatarMap = hashMapOf<String, ContactAvatarModel>()
    private val magicSearchMap = hashMapOf<String, MagicSearch>()

    private val unknownRemoteContactDirectoriesContactsMap = arrayListOf<String>()

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reloadPresenceContactsJob: Job? = null
    private var reloadRemoteContactsJob: Job? = null

    // MundoPhone: consultas puntuales a la agenda de Android mientras no se ha cargado entera (ver
    // lookupNativeContactOnDemand)
    private val nativeOnDemandCache = hashMapOf<String, Friend?>()

    // MundoPhone: la consulta a la agenda de Android de una llamada entrante (queryNativeContact), fuera del hilo del Core
    private val nativeLookupExecutor = Executors.newSingleThreadExecutor { Thread(it, "MundoPhone contact lookup") }

    // MundoPhone (D3): resultado de findContactByPhoneNumber() por número, «no encontrado» incluido. Recorrer todos los
    // contactos por cada número desconocido, en cada búsqueda y cada refresco de lista, es lo que colgaba el buscador
    // con muchos contactos. Se vacía cuando cambian los contactos (notifyContactsListChanged y
    // onContactCreated/Deleted/Updated).
    private val phoneNumberLookupCache = hashMapOf<String, Friend?>()

    private var loadContactsOnlyFromDefaultDirectory = true

    private val magicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            var queriedSipUri = ""
            for ((key, value) in magicSearchMap.entries) {
                if (value == magicSearch) {
                    queriedSipUri = key
                }
            }

            val results = magicSearch.lastSearch
            Log.i(
                "$TAG [${results.size}] magic search results available for query upon SIP URI [$queriedSipUri]"
            )

            var found = false
            if (results.isNotEmpty()) {
                val result = results.first { it.friend != null }
                if (result != null) {
                    val friend = result.friend!!
                    Log.i("$TAG Found matching friend in source [${result.sourceFlags}]")
                    val address = result.address?.asStringUriOnly().orEmpty()
                    if (address.isEmpty() || (queriedSipUri.isNotEmpty() && queriedSipUri != address)) {
                        Log.w("$TAG Received friend [${friend.name}] with SIP URI [$address] doesn't match queried SIP URI [$queriedSipUri]")
                    } else {
                        found = true
                        reloadRemoteContactsJob?.cancel()

                        // Store friend in app's cache to be re-used in call history, conversations, etc...
                        val temporaryFriendList = getRemoteContactDirectoriesCacheFriendList()
                        temporaryFriendList.addFriend(friend)
                        newContactAdded(friend)
                        Log.i(
                            "$TAG Stored discovered friend [${friend.name}] in temporary friend list, for later use"
                        )

                        for (listener in listeners) {
                            listener.onContactFoundInRemoteDirectory(friend)
                        }

                        reloadRemoteContactsJob = coroutineScope.launch {
                            delay(DELAY_BEFORE_RELOADING_CONTACTS_AFTER_MAGIC_SEARCH_RESULT)
                            coreContext.postOnCoreThread {
                                Log.i("$TAG At least a new SIP address was discovered, reloading contacts")
                                conferenceAvatarMap.values.forEach(ContactAvatarModel::destroy)
                                conferenceAvatarMap.clear()

                                notifyContactsListChanged()
                            }
                        }
                    }
                }
            }

            if (queriedSipUri.isNotEmpty()) {
                magicSearchMap.remove(queriedSipUri)
                if (!found) {
                    Log.i(
                        "$TAG SIP URI [$queriedSipUri] wasn't found in remote directories, adding it to unknown list to prevent further queries"
                    )
                    unknownRemoteContactDirectoriesContactsMap.add(queriedSipUri)
                }
            }
            magicSearch.removeListener(this)
        }
    }

    private val friendListListener: FriendListListenerStub = object : FriendListListenerStub() {
        @WorkerThread
        override fun onPresenceReceived(friendList: FriendList, friends: Array<out Friend?>) {
            if (friendList.isSubscriptionBodyless) {
                Log.i("$TAG Bodyless friendlist [${friendList.displayName}] presence received")

                var atLeastOneFriendAdded = false
                for (friend in friends) {
                    if (friend != null) {
                        val address = friend.address
                        if (address != null) {
                            Log.i(
                                "$TAG Newly discovered SIP Address [${address.asStringUriOnly()}] for friend [${friend.name}] in bodyless list [${friendList.displayName}]"
                            )
                            newContactAddedWithSipUri(friend, address)
                            atLeastOneFriendAdded = true
                        }
                    }
                }

                if (atLeastOneFriendAdded) {
                    notifyContactsListChanged()
                } else {
                    Log.w("$TAG No new friend detected in the received bodyless friendlist, not refreshing contacts in app")
                }
            }
        }

        @WorkerThread
        override fun onNewSipAddressDiscovered(
            friendList: FriendList,
            friend: Friend,
            sipUri: String
        ) {
            reloadPresenceContactsJob?.cancel()
            Log.d(
                "$TAG Newly discovered SIP Address [$sipUri] for friend [${friend.name}] in list [${friendList.displayName}]"
            )
            val address = Factory.instance().createAddress(sipUri)
            if (address != null) {
                Log.i("$TAG Storing discovered SIP URI inside Friend")
                friend.edit()
                friend.addAddress(address)
                friend.done()

                newContactAddedWithSipUri(friend, address)
            } else {
                Log.e("$TAG Failed to parse SIP URI [$sipUri] as Address!")
            }

            reloadPresenceContactsJob = coroutineScope.launch {
                delay(DELAY_BEFORE_RELOADING_CONTACTS_AFTER_PRESENCE_RECEIVED)
                coreContext.postOnCoreThread {
                    Log.i("$TAG At least a new SIP address was discovered, reloading contacts")
                    conferenceAvatarMap.values.forEach(ContactAvatarModel::destroy)
                    conferenceAvatarMap.clear()

                    notifyContactsListChanged()
                }
            }
        }

        @WorkerThread
        override fun onContactCreated(friendList: FriendList, linphoneFriend: Friend) {
            phoneNumberLookupCache.clear()
            for (address in linphoneFriend.addresses) {
                removeUnknownAddressFromMap(address)
            }
        }

        @WorkerThread
        override fun onContactDeleted(friendList: FriendList, linphoneFriend: Friend) {
            phoneNumberLookupCache.clear()
            for (address in linphoneFriend.addresses) {
                removeKnownAddressFromMap(address)
            }
        }

        @WorkerThread
        override fun onContactUpdated(
            friendList: FriendList,
            newFriend: Friend,
            oldFriend: Friend
        ) {
            phoneNumberLookupCache.clear()
            for (address in oldFriend.addresses) {
                removeKnownAddressFromMap(address)
            }
            for (address in newFriend.addresses) {
                removeUnknownAddressFromMap(address)
            }
        }

        @WorkerThread
        override fun onSyncStatusChanged(
            friendList: FriendList,
            status: FriendList.SyncStatus?,
            message: String?
        ) {
            Log.i("$TAG Friend list [${friendList.displayName}] sync status changed to [$status]")
            when (status) {
                FriendList.SyncStatus.Successful -> {
                    notifyContactsListChanged()
                }
                FriendList.SyncStatus.Failure -> {
                    Log.e("$TAG Friend list [${friendList.displayName}] sync failed: $message")
                }
                else -> {}
            }
        }
    }

    private val coreListener: CoreListenerStub = object : CoreListenerStub() {
        @WorkerThread
        override fun onFriendListCreated(core: Core, friendList: FriendList) {
            Log.i("$TAG Friend list [${friendList.displayName}] created")
            friendList.addListener(friendListListener)
        }

        @WorkerThread
        override fun onFriendListRemoved(core: Core, friendList: FriendList) {
            Log.i("$TAG Friend list [${friendList.displayName}] removed")
            friendList.removeListener(friendListListener)
        }

        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            Log.i("$TAG Default account changed, update all contacts' model showTrust value")
            updateContactsModelDependingOnDefaultAccountMode()
        }
    }

    @MainThread
    fun loadContacts(activity: MainActivity) {
        Log.i("$TAG Starting contacts loader")
        val manager = LoaderManager.getInstance(activity)
        val args = Bundle()
        args.putBoolean("defaultDirectory", loadContactsOnlyFromDefaultDirectory)
        manager.restartLoader(0, args, ContactLoader())
        FavoritesPublisher.onAddressBookLoadRequested() // MundoPhone (E4)
    }

    @WorkerThread
    fun addListener(listener: ContactsListener) {
        // Post again to prevent ConcurrentModificationException
        coreContext.postOnCoreThread {
            try {
                listeners.add(listener)
            } catch (cme: ConcurrentModificationException) {
                Log.e("$TAG Can't add listener: $cme")
            }
        }
    }

    @WorkerThread
    fun removeListener(listener: ContactsListener) {
        if (coreContext.isReady()) {
            // Post again to prevent ConcurrentModificationException
            coreContext.postOnCoreThread {
                try {
                    listeners.remove(listener)
                } catch (cme: ConcurrentModificationException) {
                    Log.e("$TAG Can't remove listener: $cme")
                }
            }
        }
    }

    @WorkerThread
    fun removeKnownAddressFromMap(address: Address) {
        val key = address.asStringUriOnly()
        val wasKnown = knownContactsAvatarsMap.remove(key)
        if (wasKnown != null) {
            Log.d("$TAG Removed address [$key] from knownContactsAvatarsMap")
        }
    }

    @WorkerThread
    fun removeUnknownAddressFromMap(address: Address) {
        val key = address.asStringUriOnly()
        val wasUnknown = unknownContactsAvatarsMap.remove(key)
        if (wasUnknown != null) {
            Log.d("$TAG Removed address [$key] from unknownContactsAvatarsMap")
        }
    }

    @WorkerThread
    private fun newContactAddedWithSipUri(friend: Friend, address: Address) {
        val sipUri = address.asStringUriOnly()
        if (unknownContactsAvatarsMap.keys.contains(sipUri)) {
            Log.d("$TAG Found SIP URI [$sipUri] in unknownContactsAvatarsMap, removing it")
            val oldModel = unknownContactsAvatarsMap[sipUri]
            oldModel?.destroy()
            unknownContactsAvatarsMap.remove(sipUri)
        } else if (knownContactsAvatarsMap.keys.contains(sipUri)) {
            Log.d(
                "$TAG Found SIP URI [$sipUri] in knownContactsAvatarsMap, forcing presence update"
            )
            val oldModel = knownContactsAvatarsMap[sipUri]
            oldModel?.update(address)
        } else {
            Log.i(
                "$TAG New contact added with SIP URI [$sipUri] but no avatar yet, let's create it"
            )
            val model = ContactAvatarModel(friend)
            knownContactsAvatarsMap[sipUri] = model
        }
    }

    @WorkerThread
    fun newContactAdded(friend: Friend) {
        for (sipAddress in friend.addresses) {
            newContactAddedWithSipUri(friend, sipAddress)
        }
    }

    @WorkerThread
    fun contactRemoved(friend: Friend) {
        val refKey = friend.refKey.orEmpty()
        if (refKey.isNotEmpty() && knownContactsAvatarsMap.keys.contains(refKey)) {
            Log.d("$TAG Found RefKey [$refKey] in knownContactsAvatarsMap, removing it")
            val oldModel = knownContactsAvatarsMap[refKey]
            oldModel?.destroy()
            knownContactsAvatarsMap.remove(refKey)
        }

        for (sipAddress in friend.addresses) {
            val sipUri = sipAddress.asStringUriOnly()
            if (knownContactsAvatarsMap.keys.contains(sipUri)) {
                Log.d("$TAG Found SIP URI [$sipUri] in knownContactsAvatarsMap, removing it")
                val oldModel = knownContactsAvatarsMap[sipUri]
                oldModel?.destroy()
                knownContactsAvatarsMap.remove(sipUri)
            }
        }

        conferenceAvatarMap.values.forEach(ContactAvatarModel::destroy)
        conferenceAvatarMap.clear()
        notifyContactsListChanged()
    }

    @WorkerThread
    fun onNativeContactsLoaded() {
        nativeContactsLoaded = true
        nativeOnDemandCache.clear()
        Log.i("$TAG Native contacts have been loaded, cleaning avatars maps")

        knownContactsAvatarsMap.values.forEach(ContactAvatarModel::destroy)
        knownContactsAvatarsMap.clear()
        unknownContactsAvatarsMap.values.forEach(ContactAvatarModel::destroy)
        unknownContactsAvatarsMap.clear()
        conferenceAvatarMap.values.forEach(ContactAvatarModel::destroy)
        conferenceAvatarMap.clear()
        unknownRemoteContactDirectoriesContactsMap.clear()

        notifyContactsListChanged()

        Log.i("$TAG Native contacts have been loaded")
        // No longer create chat room shortcuts depending on most recents ones, create it when a message is sent or received instead
        // ShortcutUtils.createShortcutsToChatRooms(coreContext.context)
    }

    /** MundoPhone (E4): si la agenda del móvil ya está en el Core (FavoritesPublisher no se fía de los favoritos antes). */
    @WorkerThread
    fun areNativeContactsLoaded(): Boolean = nativeContactsLoaded

    @WorkerThread
    fun notifyContactsListChanged() {
        phoneNumberLookupCache.clear()
        for (listener in listeners) {
            listener.onContactsLoaded()
        }
    }

    @WorkerThread
    fun findContactById(id: String): Friend? {
        Log.d("$TAG Looking for a friend with ref key [$id]")
        for (friendList in coreContext.core.friendsLists) {
            val found = friendList.findFriendByRefKey(id)
            if (found != null) {
                Log.d("$TAG Found friend [${found.name}] matching ref key [$id]")
                return found
            }
        }
        Log.w("$TAG No friend matching ref key [$id] has been found")
        return null
    }

    @WorkerThread
    fun findContactByAddress(originalAddress: Address): Friend? {
        // MundoPhone: la centralita llama desde @voip y los contactos están en @push (ver MundoSmsDomain)
        val address = MundoSmsDomain.forLookup(originalAddress)
        Log.i("$TAG Looking for friend matching SIP address [${address.asStringUriOnly()}]")
        val found = coreContext.core.findFriend(address)
        if (found != null) {
            Log.i("$TAG Found friend [${found.name}] matching SIP address [${address.asStringUriOnly()}]")
            return found
        }

        val username = address.username
        val sipUri = LinphoneUtils.getAddressAsCleanStringUriOnly(address)
        // Start an async query in Magic Search in case LDAP or remote CardDAV is configured
        val remoteContactDirectories = coreContext.core.remoteContactDirectories
        if (remoteContactDirectories.isNotEmpty() && !magicSearchMap.keys.contains(sipUri) && !unknownRemoteContactDirectoriesContactsMap.contains(
                sipUri
            )
        ) {
            Log.i(
                "$TAG SIP URI [$sipUri] not found in locally stored Friends, trying LDAP/CardDAV remote directory"
            )

            val magicSearch = coreContext.core.createMagicSearch()
            magicSearch.addListener(magicSearchListener)
            magicSearchMap[sipUri] = magicSearch

            magicSearch.getContactsListAsync(
                username,
                address.domain,
                MagicSearch.Source.LdapServers.toInt() or MagicSearch.Source.RemoteCardDAV.toInt(),
                MagicSearch.Aggregation.Friend
            )
        }

        // MundoPhone (D3): con las variantes del número («600 12 31 23», «0034…», «+34…»)
        return findContactByPhoneNumber(username) ?: lookupNativeContactOnDemand(address)
    }

    @WorkerThread
    private fun findContactByPhoneNumber(rawPhoneNumber: String?): Friend? {
        if (!PhoneNumberUtils.isPhoneNumberLike(rawPhoneNumber)) {
            return null
        }

        val defaultAccountPrefix = coreContext.core.defaultAccount?.params?.internationalPrefix
        val key = "$defaultAccountPrefix|$rawPhoneNumber"
        if (phoneNumberLookupCache.containsKey(key)) {
            return phoneNumberLookupCache[key]
        }
        val found = lookupContactByPhoneNumber(rawPhoneNumber, defaultAccountPrefix)
        if (found != null) {
            Log.i("$TAG Found friend [${found.name}] matching phone number [$rawPhoneNumber]")
        }
        phoneNumberLookupCache[key] = found
        return found
    }

    @WorkerThread
    private fun lookupContactByPhoneNumber(rawPhoneNumber: String?, defaultAccountPrefix: String?): Friend? {
        val incomingCandidates =
            PhoneNumberUtils.getComparablePhoneCandidates(rawPhoneNumber, defaultAccountPrefix)
        if (incomingCandidates.isEmpty()) {
            return null
        }

        for (candidate in incomingCandidates) {
            Log.d("$TAG Looking for friend with phone number candidate [$candidate]")

            coreContext.core.findFriendByPhoneNumber(candidate)?.let { return it }
            coreContext.core.findFriendByPhoneNumber("+$candidate")?.let { return it }
        }

        // Si el SDK no lo encuentra, se comparan las variantes con los números guardados de cada contacto
        for (friendList in coreContext.core.friendsLists) {
            for (friend in friendList.friends) {
                for (storedPhoneNumber in friend.phoneNumbers) {
                    if (PhoneNumberUtils.matchesComparableCandidates(storedPhoneNumber, incomingCandidates, defaultAccountPrefix)) {
                        Log.d(
                            "$TAG Found friend [${friend.name}] by normalized phone number match [$rawPhoneNumber]"
                        )
                        return friend
                    }
                }
            }
        }

        return null
    }

    /**
     * MundoPhone: mientras no se ha cargado la agenda, pregunta a Android SOLO por esta dirección: un número con
     * PhoneLookup, o la dirección SIP para los compañeros de la centralita. Son consultas indexadas de milisegundos,
     * no la carga completa.
     *
     * Hace falta porque la lista «Native address-book» no se guarda en la base del SDK (ver
     * stopStoringNativeFriendList): en un arranque en frío por push la app no carga la agenda hasta que se abre su
     * pantalla principal, y sin esto la llamada entrante mostraría solo el nombre que manda la centralita o el número.
     */
    @WorkerThread
    private fun lookupNativeContactOnDemand(address: Address): Friend? {
        if (nativeContactsLoaded) return null
        val username = address.username.orEmpty()
        if (username.isEmpty()) return null
        val context = coreContext.context
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val key = LinphoneUtils.getAddressAsCleanStringUriOnly(address)
        if (nativeOnDemandCache.containsKey(key)) return nativeOnDemandCache[key]
        // Solo con una llamada en curso (el caso del arranque por push): el historial también pasa por aquí, fila a
        // fila, y al abrir la app la carga completa llega enseguida.
        if (coreContext.core.callsNb == 0) return null

        val found = try {
            if (PhoneNumberUtils.isPhoneNumberLike(username)) {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(username)
                )
                queryNativeContact(
                    uri,
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    null,
                    null,
                    address
                )
            } else {
                // Las direcciones SIP de la agenda pueden llevar «sip:» y cualquier dominio (voip./push.)
                val escaped = username.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                val sipColumn = ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS
                queryNativeContact(
                    ContactsContract.Data.CONTENT_URI,
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                    "${ContactsContract.Data.MIMETYPE} = ? AND ($sipColumn = ? OR $sipColumn LIKE ? ESCAPE '\\' OR $sipColumn LIKE ? ESCAPE '\\')",
                    arrayOf(
                        ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
                        username,
                        "$escaped@%",
                        "sip:$escaped@%"
                    ),
                    address
                )
            }
        } catch (timeout: NativeLookupTimeout) {
            Log.w("$TAG On-demand native contact lookup for [$key] took more than [$NATIVE_LOOKUP_TIMEOUT_MS] ms, going on without it")
            val lateAddress = address.clone()
            nativeLookupExecutor.execute {
                val row = try {
                    timeout.pending.get()
                } catch (e: Exception) {
                    null
                }
                coreContext.postOnCoreThread {
                    if (!nativeOnDemandCache.containsKey(key)) {
                        nativeOnDemandCache[key] = row?.let { friendFromNativeRow(it, lateAddress) }
                        Log.i("$TAG Late on-demand native contact lookup for [$key]: ${if (row != null) "found" else "not found"}")
                    }
                }
            }
            return null
        } catch (e: Exception) {
            Log.e("$TAG On-demand native contact lookup failed: $e")
            null
        }
        Log.i("$TAG On-demand native contact lookup for [$key]: ${if (found != null) "found" else "not found"}")
        nativeOnDemandCache[key] = found
        return found
    }

    /** MundoPhone: una fila de la agenda de Android, leída fuera del hilo del Core (ver queryNativeContact). */
    private class NativeRow(val id: String?, val name: String, val lookupKey: String?, val starred: Boolean)

    /** MundoPhone: la consulta a Android pasó del tope: la llamada sigue sin el nombre (ver lookupNativeContactOnDemand). */
    private class NativeLookupTimeout(val pending: Future<NativeRow?>) : Exception()

    /**
     * MundoPhone: la consulta a la agenda de Android va en su propio hilo y se espera como mucho NATIVE_LOOKUP_TIMEOUT_MS.
     * Corre en el hilo del Core en plena llamada entrante: medido, la primera en frío tarda 77-97 ms en un POCO, y si el
     * proveedor de contactos se atasca, sin tope se paraba todo el SIP (el 180, un CANCEL, el iterate). El Friend se crea
     * aquí, en el hilo del Core, como exige el SDK.
     */
    @WorkerThread
    private fun queryNativeContact(
        uri: Uri,
        idColumn: String,
        nameColumn: String,
        selection: String?,
        selectionArgs: Array<String>?,
        address: Address
    ): Friend? {
        val projection = arrayOf(
            idColumn,
            nameColumn,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.STARRED
        )
        val pending = nativeLookupExecutor.submit<NativeRow?> {
            coreContext.context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(nameColumn))
                    if (name.isNullOrEmpty()) return@use null
                    NativeRow(
                        cursor.getString(cursor.getColumnIndexOrThrow(idColumn)),
                        name,
                        cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)) == 1
                    )
                }
        }
        val row = try {
            pending.get(NATIVE_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw NativeLookupTimeout(pending)
        }
        return row?.let { friendFromNativeRow(it, address) }
    }

    @WorkerThread
    private fun friendFromNativeRow(row: NativeRow, address: Address): Friend {
        val friend = coreContext.core.createFriend()
        friend.refKey = row.id
        friend.name = row.name
        friend.starred = row.starred
        friend.nativeUri = "${ContactsContract.Contacts.CONTENT_LOOKUP_URI}/${row.lookupKey}"
        friend.getNativeContactPictureUri()?.let { friend.photo = it.toString() }
        friend.address = address.clone()
        applyNativeFavoriteOverride(friend)
        return friend
    }

    /**
     * MundoPhone: aplica el favorito marcado o desmarcado en la app (corePreferences) sobre el STARRED de Android.
     */
    @WorkerThread
    fun applyNativeFavoriteOverride(friend: Friend) {
        val refKey = friend.refKey.orEmpty()
        if (refKey.isEmpty()) return
        when (refKey) {
            in corePreferences.nativeFavoritesOn -> friend.starred = true
            in corePreferences.nativeFavoritesOff -> friend.starred = false
        }
    }

    /**
     * MundoPhone: guarda el favorito de un contacto del móvil tras cambiarlo en la app. La lista nativa no se guarda en
     * la base, así que sin esto se perdería al reiniciar.
     */
    @WorkerThread
    fun rememberNativeFavorite(friend: Friend) {
        val refKey = friend.refKey.orEmpty()
        if (refKey.isEmpty() || friend.friendList?.displayName != ContactLoader.NATIVE_ADDRESS_BOOK_FRIEND_LIST) {
            return
        }
        if (friend.starred) {
            corePreferences.nativeFavoritesOn = corePreferences.nativeFavoritesOn + refKey
            corePreferences.nativeFavoritesOff = corePreferences.nativeFavoritesOff - refKey
        } else {
            corePreferences.nativeFavoritesOn = corePreferences.nativeFavoritesOn - refKey
            corePreferences.nativeFavoritesOff = corePreferences.nativeFavoritesOff + refKey
        }
    }

    /**
     * MundoPhone: deja de guardar la lista «Native address-book» en la base del SDK (una vez; luego ContactLoader la
     * crea sin guardar). El SDK carga de la base todas las listas guardadas al arrancar el Core, ANTES del REGISTER
     * (liblinphone 5.5.35 core.cpp:212): con una agenda grande eran 1,35 s de cada arranque en frío por push (Samsung
     * A52s), y el borde manda un 2.º push a los 3 s. Antes de dejar de guardarla se apuntan sus favoritos, que
     * hasta entonces solo vivían ahí. Upstream la guarda para tener nombres y favoritos antes de cargar la agenda: los
     * nombres los da lookupNativeContactOnDemand y los favoritos, corePreferences. Solo actúa
     * en instalaciones antiguas que aún la tengan guardada.
     */
    @WorkerThread
    private fun stopStoringNativeFriendList(core: Core) {
        val nativeList = core.getFriendListByName(ContactLoader.NATIVE_ADDRESS_BOOK_FRIEND_LIST) ?: return
        if (!nativeList.isDatabaseStorageEnabled) return

        val starred = nativeList.friends.filter { it.starred }.mapNotNull { it.refKey }.filter { it.isNotEmpty() }
        corePreferences.nativeFavoritesOn = corePreferences.nativeFavoritesOn + starred
        nativeList.isDatabaseStorageEnabled = false
        Log.w(
            "$TAG Friend list [${ContactLoader.NATIVE_ADDRESS_BOOK_FRIEND_LIST}] no longer stored in database, [${starred.size}] favourite(s) kept in preferences"
        )
    }

    @WorkerThread
    fun findDisplayName(address: Address): String {
        return getContactAvatarModelForAddress(address).friend.name ?: LinphoneUtils.getDisplayName(
            address
        )
    }

    @WorkerThread
    fun getContactAvatarModelForAddress(address: Address?): ContactAvatarModel {
        if (address == null) {
            Log.w("$TAG Address is null, generic model will be used")
            val fakeFriend = coreContext.core.createFriend()
            return ContactAvatarModel(fakeFriend)
        }

        val clone = address.clone()
        clone.clean()
        val key = clone.asStringUriOnly()

        val foundInMap = getAvatarModelFromCache(key)
        if (foundInMap != null) {
            Log.d("$TAG Avatar model found in map for SIP URI [$key]")
            return foundInMap
        }

        val localAccount = coreContext.core.accountList.find {
            it.params.identityAddress?.weakEqual(clone) == true
        }
        val avatar = if (localAccount != null) {
            Log.d("$TAG [$key] SIP URI matches one of the local account")
            val fakeFriend = coreContext.core.createFriend()
            fakeFriend.address = clone
            fakeFriend.name = LinphoneUtils.getDisplayName(localAccount.params.identityAddress)
            fakeFriend.photo = localAccount.params.pictureUri
            val model = ContactAvatarModel(fakeFriend)
            model.trust.postValue(SecurityLevel.EndToEndEncryptedAndVerified)
            unknownContactsAvatarsMap[key] = model
            model
        } else {
            Log.d("$TAG Looking for friend matching SIP URI [$key]")
            val friend = findContactByAddress(clone)
            if (friend != null) {
                Log.d("$TAG Matching friend [${friend.name}] found for SIP URI [$key]")
                val model = ContactAvatarModel(friend, address)
                knownContactsAvatarsMap[key] = model
                model
            } else {
                Log.d("$TAG No matching friend found for SIP URI [$key]...")
                val fakeFriend = coreContext.core.createFriend()
                fakeFriend.name = LinphoneUtils.getDisplayName(address)
                fakeFriend.address = clone
                val model = ContactAvatarModel(fakeFriend)
                unknownContactsAvatarsMap[key] = model
                model
            }
        }

        return avatar
    }

    @WorkerThread
    fun getContactAvatarModelForFriend(friend: Friend?): ContactAvatarModel {
        if (friend == null) {
            Log.w("$TAG Friend is null, using generic avatar model")
            val fakeFriend = coreContext.core.createFriend()
            return ContactAvatarModel(fakeFriend)
        }

        val avatar = ContactAvatarModel(friend)
        return avatar
    }

    @WorkerThread
    fun getContactAvatarModelForConferenceInfo(conferenceInfo: ConferenceInfo): ContactAvatarModel {
        // Do not clean parameters!
        val key = conferenceInfo.uri?.asStringUriOnly()
        if (key == null) {
            val fakeFriend = coreContext.core.createFriend()
            fakeFriend.name = conferenceInfo.subject
            val model = ContactAvatarModel(fakeFriend)
            model.showTrust.postValue(false)
            return model
        }

        val foundInMap = conferenceAvatarMap[key] ?: conferenceAvatarMap[key]
        if (foundInMap != null) return foundInMap

        val avatar = LinphoneUtils.getAvatarModelForConferenceInfo(conferenceInfo)
        conferenceAvatarMap[key] = avatar

        return avatar
    }

    @WorkerThread
    fun isContactAvailable(friend: Friend): Boolean {
        return !friend.refKey.isNullOrEmpty() && !isContactTemporary(friend)
    }

    @WorkerThread
    fun isContactTemporary(friend: Friend, allowNullFriendList: Boolean = false): Boolean {
        val friendList = friend.friendList
        if (friendList == null && !allowNullFriendList) return true
        return friendList?.type == FriendList.Type.ApplicationCache
    }

    @WorkerThread
    fun onCoreStarted(core: Core) {
        Log.i("$TAG Core has been started")
        loadContactsOnlyFromDefaultDirectory = corePreferences.fetchContactsFromDefaultDirectory

        core.addListener(coreListener)
        for (list in core.friendsLists) {
            Log.i("$TAG Found existing friend list [${list.displayName}]")
            list.addListener(friendListListener)
        }
        stopStoringNativeFriendList(core)
        // MundoPhone (A6): presencia del equipo con las listas que ya hay en la base (la agenda llega después). Antes la
        // lista del borde: con ella (o con la de la última vez) no se suscribe a nadie suelto
        enableFriendListsSubscriptionOnce(core)
        teamRls.onCoreStarted(core)
        updateTeamPresenceSubscriptions(core)

        val context = coreContext.context
        ShortcutUtils.removeLegacyShortcuts(context)
        // MundoPhone (E4): accesos directos del icono y widget de favoritos
        FavoritesPublisher.onCoreStarted(core)
        // No longer create chat room shortcuts depending on most recents ones, create it when a message is sent or received instead
        /*if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("$TAG READ_CONTACTS permission was denied, creating chat rooms shortcuts now")
            ShortcutUtils.createShortcutsToChatRooms(context)
        }*/

        for (list in core.friendsLists) {
            if (list.type == FriendList.Type.CardDAV && !list.uri.isNullOrEmpty()) {
                Log.i(
                    "$TAG Found a CardDAV friend list with name [${list.displayName}] and URI [${list.uri}], synchronizing it"
                )
                list.synchronizeFriendsFromServer()
            }
        }
    }

    /**
     * MundoPhone (A6): se vigila la presencia (y el BLF) de un compañero de la centralita ([Team]) en uno de nuestros
     * dominios; un número sería externo.
     */
    @WorkerThread
    fun isTeamPresenceAddress(address: Address): Boolean {
        val domain = address.domain
        return Team.isTeamAddress(address) &&
            (domain in corePreferences.pushNotificationCompatibleDomains || domain == corePreferences.mundosmsDomain)
    }

    @WorkerThread
    fun isTeamPresenceFriend(friend: Friend): Boolean = friend.addresses.any { isTeamPresenceAddress(it) }

    /**
     * MundoPhone: ¿se le hace un SUBSCRIBE suelto? Solo sin la lista del borde (con ella llega todo en una suscripción) y
     * si el usuario no lo ha quitado de Equipo (menos SUBSCRIBE y menos tráfico).
     */
    @WorkerThread
    fun shouldWatchIndividually(address: Address): Boolean =
        isTeamPresenceAddress(address) && !teamRls.isActive() && !teamRls.isHidden(address.username)

    @WorkerThread
    fun shouldWatchFriendIndividually(friend: Friend): Boolean =
        !teamRls.isOwnFriend(friend) && friend.addresses.any { shouldWatchIndividually(it) }

    /**
     * MundoPhone: ¿se enseña el estado en la ficha? Si se vigila, también «Desconectado»: por SUBSCRIBE
     * suelto o por la lista del borde (que quita los sueltos), salvo que el borde aún no lo sepa.
     */
    @WorkerThread
    fun showsPresenceStatus(friend: Friend): Boolean {
        if (friend.addresses.isEmpty()) return false
        if (friend.isSubscribesEnabled) return true
        val estado = teamRls.estadoDe(friend)
        return estado != null && estado != TeamRls.Estado.SIN_DATO
    }

    /**
     * MundoPhone: con quién habla un compañero (el `remote` de ms:call: extensión o número): el nombre de la agenda si lo
     * tenemos guardado; si no, el de la centralita si es un compañero; si no, tal cual.
     */
    @WorkerThread
    fun nombreParaRemoto(remoto: String): String {
        val address = coreContext.core.interpretUrl(remoto, false)
        val friend = address?.let { findContactByAddress(it) }
        if (friend != null && !teamRls.isOwnFriend(friend)) {
            friend.name?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return teamRls.miembroPorUsuario(remoto)?.nombre ?: remoto
    }

    /** MundoPhone: el estado fijado a mano (lista del borde) no avisa: se refrescan los modelos que hay en caché. */
    @WorkerThread
    fun refreshAvatarPresence(friend: Friend) {
        for (address in friend.addresses) {
            knownContactsAvatarsMap[address.asStringUriOnly()]?.update(address)
        }
        val refKey = friend.refKey.orEmpty()
        if (refKey.isNotEmpty()) knownContactsAvatarsMap[refKey]?.update(null)
    }

    /**
     * MundoPhone (A6): suscribe o deja de suscribir a los compañeros, en todas las listas (la agenda del móvil y los contactos
     * creados en la app), según [shouldWatchFriendIndividually]: sin la lista del borde y sin los quitados de Equipo.
     * Solo se toca a quien cambia, y la suscripción la manda la
     * lista, que respeta el interruptor de Ajustes y no repite SUBSCRIBE a quien ya está suscrito.
     */
    @WorkerThread
    fun updateTeamPresenceSubscriptions(core: Core) {
        if (!core.isFriendListSubscriptionEnabled) {
            Log.w("$TAG Friend lists subscriptions are disabled in settings, team presence won't be available")
            return
        }

        var enabled = 0
        var disabled = 0
        var unchanged = 0
        for (list in core.friendsLists) {
            if (list.type == FriendList.Type.ApplicationCache || list.isSubscriptionBodyless) continue
            if (list.displayName == TeamRlsManager.LIST_NAME) continue

            var changed = false
            for (friend in list.friends) {
                if (!isTeamPresenceFriend(friend)) continue
                val wanted = shouldWatchFriendIndividually(friend)
                if (friend.isSubscribesEnabled == wanted) {
                    unchanged += 1
                    continue
                }
                Log.i("$TAG ${if (wanted) "Enabling" else "Disabling"} presence subscription for team contact [${friend.name}] in list [${list.displayName}]")
                friend.isSubscribesEnabled = wanted
                changed = true
                if (wanted) enabled += 1 else disabled += 1
            }
            if (changed) list.updateSubscriptions()
        }
        Log.i("$TAG Team presence: [$enabled] enabled, [$disabled] disabled, [$unchanged] unchanged (team list from the edge [${teamRls.isActive()}])")
    }

    /**
     * MundoPhone (A6): las versiones de 2025 llamaban en cada arranque a updateFriendListsSubscriptionDependingOnDefaultAccount
     * con un defaultDomain que no era el de las cuentas (voip.mundosms.es frente a @push), y dejaron guardado
     * [net] friendlist_subscription_enabled=0. Suscribir contacto a contacto con edit()/done() no mira ese
     * interruptor; la 6.2 suscribe por las listas, que sí lo miran, y se quedaba sin
     * presencia. Se enciende una vez por instalación; después manda el interruptor de Ajustes.
     */
    @WorkerThread
    private fun enableFriendListsSubscriptionOnce(core: Core) {
        if (core.config.getInt("app", "mundosms_presence_lists_once", 0) >= 1) return

        if (!core.isFriendListSubscriptionEnabled) {
            core.isFriendListSubscriptionEnabled = true
            Log.w("$TAG Friend lists subscriptions were disabled (left by an old version), enabled once")
        } else {
            Log.i("$TAG Friend lists subscriptions already enabled")
        }
        core.config.setInt("app", "mundosms_presence_lists_once", 1)
    }

    @WorkerThread
    fun onCoreStopped(core: Core) {
        Log.w("$TAG Core has been stopped")
        coroutineScope.cancel()
        teamRls.onCoreStopped(core)
        FavoritesPublisher.onCoreStopped(core) // MundoPhone (E4)

        knownContactsAvatarsMap.clear()
        unknownContactsAvatarsMap.clear()
        conferenceAvatarMap.clear()
        magicSearchMap.clear()

        core.removeListener(coreListener)

        for (list in core.friendsLists) {
            list.removeListener(friendListListener)
        }
    }

    @WorkerThread
    fun getRemoteContactDirectoriesCacheFriendList(): FriendList {
        val core = coreContext.core
        val name = FRIEND_LIST_TEMPORARY_STORED_REMOTE_DIRECTORY
        val temporaryFriendList = core.getFriendListByName(name) ?: core.createFriendList()
        if (temporaryFriendList.displayName.isNullOrEmpty()) {
            temporaryFriendList.isDatabaseStorageEnabled = false
            temporaryFriendList.displayName = name
            temporaryFriendList.type = FriendList.Type.ApplicationCache
            core.addFriendList(temporaryFriendList)
            Log.i(
                "$TAG Created temporary friend list with name [$name]"
            )
        }
        return temporaryFriendList
    }

    @WorkerThread
    fun getMePerson(localAddress: Address): Person {
        val account = coreContext.core.accountList.find {
            it.params.identityAddress?.weakEqual(localAddress) == true
        }
        val name = account?.params?.identityAddress?.displayName ?: LinphoneUtils.getDisplayName(
            localAddress
        )
        val personBuilder = Person.Builder().setName(name.ifEmpty { "Unknown" })

        val photo = account?.params?.pictureUri.orEmpty()
        val bm = ImageUtils.getBitmap(coreContext.context, photo)
        personBuilder.setIcon(
            if (bm == null) {
                AvatarGenerator(coreContext.context).setInitials(AppUtils.getInitials(name)).buildIcon()
            } else {
                IconCompat.createWithAdaptiveBitmap(bm)
            }
        )

        val identity = account?.params?.identityAddress?.asStringUriOnly() ?: localAddress.asStringUriOnly()
        personBuilder.setKey(identity)
        personBuilder.setImportant(true)
        return personBuilder.build()
    }

    @WorkerThread
    fun updateContactsModelDependingOnDefaultAccountMode() {
        val showTrust = true
        Log.i(
            "$TAG Default account mode is [${if (showTrust) "end-to-end encryption mandatory" else "interoperable"}], update all contact models showTrust value"
        )
        knownContactsAvatarsMap.forEach { (_, contactAvatarModel) ->
            contactAvatarModel.showTrust.postValue(showTrust)
        }
        unknownContactsAvatarsMap.forEach { (_, contactAvatarModel) ->
            contactAvatarModel.showTrust.postValue(showTrust)
        }
        conferenceAvatarMap.forEach { (_, contactAvatarModel) ->
            contactAvatarModel.showTrust.postValue(showTrust)
        }
    }

    @WorkerThread
    private fun getAvatarModelFromCache(key: String): ContactAvatarModel? {
        return knownContactsAvatarsMap[key] ?: unknownContactsAvatarsMap[key]
    }

    interface ContactsListener {
        fun onContactsLoaded()

        fun onContactFoundInRemoteDirectory(friend: Friend)
    }
}

@WorkerThread
fun Friend.getAvatarBitmap(round: Boolean = false): Bitmap? {
    try {
        return ImageUtils.getBitmap(
            coreContext.context,
            photo ?: getNativeContactPictureUri()?.toString(),
            round
        )
    } catch (_: NumberFormatException) {
        // Expected for contacts created by Linphone
    }
    return null
}

/** MundoPhone: la dirección de la miniatura del contacto nativo, sin comprobar nada (el respaldo de la de abajo). */
@AnyThread
fun Friend.getNativeContactThumbnailUri(): Uri? {
    val contactId = refKey?.toLongOrNull() ?: return null
    return Uri.withAppendedPath(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
        ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
    )
}

@WorkerThread
fun Friend.getNativeContactPictureUri(): Uri? {
    val contactId = refKey
    if (contactId != null) {
        try {
            val lookupUri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contactId.toLong()
            )

            val pictureUri = Uri.withAppendedPath(
                lookupUri,
                ContactsContract.Contacts.Photo.DISPLAY_PHOTO
            )
            // Check that the URI points to a real file
            val contentResolver = coreContext.context.contentResolver
            try {
                val fd = contentResolver.openAssetFileDescriptor(pictureUri, "r")
                if (fd != null) {
                    fd.close()
                    return pictureUri
                }
            } catch (fnfe: FileNotFoundException) {
                // MundoPhone: es lo normal (el contacto no tiene foto grande); sin línea en el registro, que ponía el
                // nombre de cada contacto de la agenda en el registro de soporte
            } catch (e: Exception) {
                Log.e("[Contacts Manager] Can't open [$pictureUri] for contact id [$contactId]: $e")
            }

            // Fallback to thumbnail
            return Uri.withAppendedPath(
                lookupUri,
                ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
            )
        } catch (_: NumberFormatException) {
            // Expected for contacts created by Linphone
        }
    }
    return null
}

@WorkerThread
fun Friend.getPerson(): Person {
    val personBuilder = Person.Builder()
    val personName = if (name.orEmpty().isNotEmpty()) {
        name
    } else {
        if (!lastName.isNullOrEmpty() || !firstName.isNullOrEmpty()) {
            Log.w("[Friend] Name is null or empty, using first and last name")
            "$firstName $lastName".trim()
        } else if (!organization.isNullOrEmpty()) {
            Log.w("[Friend] Name, first name & last name are null or empty, using organization instead")
            organization
        } else if (!jobTitle.isNullOrEmpty()) {
            Log.w("[Friend] Name, first and last names & organization are null or empty, using job title instead")
            jobTitle
        } else {
            Log.e("[Friend] No identification field filled for this friend!")
            "Unknown"
        }
    }
    personBuilder.setName(personName.orEmpty().ifEmpty { "Unknown" })

    val bm: Bitmap? = getAvatarBitmap()
    personBuilder.setIcon(
        if (bm == null) {
            Log.i(
                "[Friend] Can't use friend [$name] picture path, generating avatar based on initials"
            )
            AvatarGenerator(coreContext.context).setInitials(AppUtils.getInitials(personName.orEmpty())).buildIcon()
        } else {
            IconCompat.createWithAdaptiveBitmap(bm)
        }
    )

    personBuilder.setKey(refKey)
    personBuilder.setUri(nativeUri)
    personBuilder.setImportant(true)
    return personBuilder.build()
}

@WorkerThread
fun Friend.getListOfSipAddresses(): ArrayList<Address> {
    val addressesList = arrayListOf<Address>()
    if (corePreferences.hideSipAddresses) return addressesList

    for (address in addresses) {
        if (addressesList.find { it.weakEqual(address) } == null) {
            addressesList.add(address)
        }
    }

    return addressesList
}

@WorkerThread
fun Friend.getListOfSipAddressesAndPhoneNumbers(
    listener: ContactNumberOrAddressClickListener,
    removeDefaultAccountAddress: Boolean = false
): ArrayList<ContactNumberOrAddressModel> {
    val addressesAndNumbers = arrayListOf<ContactNumberOrAddressModel>()
    val defaultAccountAddress = coreContext.core.defaultAccount?.params?.identityAddress

    // Will return an empty list if corePreferences.hideSipAddresses == true
    for (address in getListOfSipAddresses()) {
        if (LinphoneUtils.isSipAddressLinkedToPhoneNumberByPresence(this, address.asStringUriOnly())) {
            continue
        }
        if (removeDefaultAccountAddress && defaultAccountAddress != null && address.weakEqual(defaultAccountAddress)) {
            Log.i("[Contacts Manager] Removing from addresses current default account address")
            continue
        }

        val data = ContactNumberOrAddressModel(
            this,
            address,
            address.asStringUriOnly(),
            true, // SIP addresses are always enabled
            listener,
            true
        )
        addressesAndNumbers.add(data)
    }

    if (corePreferences.hidePhoneNumbers) {
        return addressesAndNumbers
    }

    for (number in phoneNumbersWithLabel) {
        val phoneNumber = number.phoneNumber
        val presenceModel = getPresenceModelForUriOrTel(phoneNumber)
        val hasPresenceInfo = !presenceModel?.contact.isNullOrEmpty()
        var presenceAddress: Address? = null

        if (presenceModel != null && hasPresenceInfo) {
            val contact = presenceModel.contact
            if (!contact.isNullOrEmpty()) {
                val address = core.interpretUrl(contact, false)
                if (address != null) {
                    address.clean() // To remove ;user=phone
                    presenceAddress = address
                } else {
                    Log.e("[Contacts Manager] Failed to parse phone number [$phoneNumber] contact address [$contact] from presence model!")
                }
            }
        }

        // phone numbers are disabled is secure mode unless linked to a SIP address
        val defaultAccount = LinphoneUtils.getDefaultAccount()
        val enablePhoneNumbers = hasPresenceInfo || !isEndToEndEncryptionMandatory()
        val address = presenceAddress ?: core.interpretUrl(
            phoneNumber,
            LinphoneUtils.applyInternationalPrefix(defaultAccount)
        )
        address ?: continue
        if (removeDefaultAccountAddress && defaultAccountAddress != null && address.weakEqual(defaultAccountAddress)) {
            Log.i("[Contacts Manager] Removing from phone number presence extracted addresses current default account address")
            continue
        }

        val label = PhoneNumberUtils.vcardParamStringToAddressBookLabel(
            coreContext.context.resources,
            number.label ?: ""
        )
        Log.d("[Contacts Manager] Parsed phone number [$phoneNumber] with label [$label] into address [${address.asStringUriOnly()}], presence address is [${presenceAddress?.asStringUriOnly()}]")
        val data = ContactNumberOrAddressModel(
            this,
            address,
            phoneNumber,
            enablePhoneNumbers,
            listener,
            false,
            label,
            presenceAddress != null
        )
        addressesAndNumbers.add(data)
    }

    return addressesAndNumbers
}
