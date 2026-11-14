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
package org.linphone.ui.main.team.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.Collator
import java.util.ArrayList
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager.ContactsListener
import org.linphone.contacts.Team
import org.linphone.contacts.TeamRlsManager
import org.linphone.core.Friend
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.core.tools.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.Event
import org.linphone.utils.FileUtils

/**
 * MundoPhone: la pestaña Equipo (los compañeros de la centralita, ver [Team]). Es la lista de contactos de la 6.2
 * recortada: solo contactos locales con dirección SIP (dominio «*»), sin filtro ni LDAP/CardDAV remotos.
 */
class TeamListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[Team List ViewModel]"

        // Solo direcciones SIP, de cualquier dominio
        private const val SIP_ONLY_DOMAIN = "*"
    }

    val contactsList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val favouritesList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val fetchInProgress = MutableLiveData<Boolean>()

    val showFavourites = MutableLiveData<Boolean>()

    val isListFiltered = MutableLiveData<Boolean>()

    val searchInProgress = MutableLiveData<Boolean>()

    val showResultsLimitReached = MutableLiveData<Boolean>()

    val disableAddContact = MutableLiveData<Boolean>()

    val vCardTerminatedEvent: MutableLiveData<Event<Pair<String, File>>> by lazy {
        MutableLiveData()
    }

    // MundoPhone: los compañeros ocultados por el usuario, al final de la lista y plegados («Ver ocultos (N)»)
    val hiddenList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    init {
        // MundoPhone: el contador de la línea de estado («En llamada con Ana · 3:12») avanza cada segundo
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                for (model in contactsList.value.orEmpty() + favouritesList.value.orEmpty()) {
                    if (model.hasTeamTimer()) model.refreshTeamStatus()
                }
            }
        }
    }

    val showHidden = MutableLiveData<Boolean>(false)

    // MundoPhone: Equipo desde la lista del borde (RLS): cada cambio de la lista o de un estado rehace la búsqueda
    private val teamRlsListener = object : TeamRlsManager.Listener {
        @WorkerThread
        override fun onTeamRlsChanged() {
            if (::magicSearch.isInitialized) {
                magicSearch.resetSearchCache()
                favouritesMagicSearch.resetSearchCache()
                searchTeam(currentFilter, filterChanged = true)
            }
        }
    }

    private var previousFilter = "NotSet"

    // MundoPhone: el indicador de carga se enseña solo hasta la primera lista (hilo del Core)
    private var firstLoad = true

    private lateinit var magicSearch: MagicSearch

    private lateinit var favouritesMagicSearch: MagicSearch

    private val magicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            Log.i("$TAG Magic search team contacts available")
            processMagicSearchResults(magicSearch.lastSearch, favourites = false)
        }

        @WorkerThread
        override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
            Log.w("$TAG Results limit reached (configured limit is [${magicSearch.searchLimit}])")
            if (searchFilter.value.orEmpty().isNotEmpty()) {
                showResultsLimitReached.postValue(true)
            }
        }
    }

    private val favouritesMagicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            Log.i("$TAG Magic search favourite team contacts available")
            processMagicSearchResults(magicSearch.lastSearch, favourites = true)
        }
    }

    private val contactsListener = object : ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            Log.i("$TAG Contacts have been (re)loaded, updating team list")
            magicSearch.resetSearchCache()
            favouritesMagicSearch.resetSearchCache()
            searchTeam(currentFilter, filterChanged = true)
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        fetchInProgress.value = true
        showFavourites.value = corePreferences.showFavoriteContacts
        disableAddContact.value = corePreferences.disableAddContact
        isListFiltered.value = false

        coreContext.postOnCoreThread { core ->
            coreContext.contactsManager.addListener(contactsListener)
            coreContext.contactsManager.teamRls.addListener(teamRlsListener)
            magicSearch = core.createMagicSearch()
            magicSearch.limitedSearch = true
            magicSearch.searchLimit = corePreferences.magicSearchResultsLimit
            magicSearch.addListener(magicSearchListener)

            favouritesMagicSearch = core.createMagicSearch()
            favouritesMagicSearch.limitedSearch = false
            favouritesMagicSearch.addListener(favouritesMagicSearchListener)

            searchTeam(currentFilter)
        }
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread {
            magicSearch.removeListener(magicSearchListener)
            favouritesMagicSearch.removeListener(favouritesMagicSearchListener)
            coreContext.contactsManager.removeListener(contactsListener)
            coreContext.contactsManager.teamRls.removeListener(teamRlsListener)
        }
        super.onCleared()
    }

    @UiThread
    override fun filter() {
        isListFiltered.value = currentFilter.isNotEmpty()
        coreContext.postOnCoreThread {
            searchTeam(currentFilter)
        }
    }

    @UiThread
    fun refresh() {
        coreContext.postOnCoreThread {
            searchTeam(currentFilter, filterChanged = true)
        }
    }

    /** MundoPhone: ocultar a un compañero de Equipo (local; sigue en «Ver ocultos» y se puede volver a mostrar). */
    @UiThread
    fun hideFromTeam(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread { core ->
            val usuario = Team.teamUser(contactModel.friend) ?: return@postOnCoreThread
            coreContext.contactsManager.teamRls.hide(core, usuario)
            coreContext.contactsManager.updateTeamPresenceSubscriptions(core)
        }
    }

    /** MundoPhone: volver a mostrar en Equipo a un compañero oculto. */
    @UiThread
    fun showInTeam(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread { core ->
            val usuario = Team.teamUser(contactModel.friend) ?: return@postOnCoreThread
            coreContext.contactsManager.teamRls.unhide(core, usuario)
            coreContext.contactsManager.updateTeamPresenceSubscriptions(core)
        }
    }

    @UiThread
    fun toggleHiddenVisibility() {
        showHidden.value = showHidden.value != true
    }

    /** MundoPhone: tirar hacia abajo: pide la lista otra vez al borde (o vuelve a buscar en la agenda). */
    @UiThread
    fun syncTeam() {
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Sync requested by the user")
            coreContext.contactsManager.teamRls.resync(core)
            coreContext.contactsManager.updateTeamPresenceSubscriptions(core)
            magicSearch.resetSearchCache()
            favouritesMagicSearch.resetSearchCache()
            searchTeam(currentFilter, filterChanged = true)
        }
    }

    @UiThread
    fun toggleFavouritesVisibility() {
        val show = showFavourites.value == false
        showFavourites.value = show

        coreContext.postOnCoreThread {
            corePreferences.showFavoriteContacts = show
        }
    }

    @UiThread
    fun exportContactAsVCard(friend: Friend) {
        coreContext.postOnCoreThread {
            val vCard = friend.dumpVcard()
            if (!vCard.isNullOrEmpty()) {
                val fileName = friend.name.orEmpty().replace(" ", "_").lowercase(Locale.getDefault())
                val file = FileUtils.getFileStorageCacheDir("$fileName.vcf", overrideExisting = true)
                viewModelScope.launch {
                    if (FileUtils.dumpStringToFile(vCard, file)) {
                        vCardTerminatedEvent.postValue(Event(Pair(friend.name.orEmpty(), file)))
                    } else {
                        Log.e("$TAG Failed to save vCard string as file in cache folder")
                    }
                }
            } else {
                Log.e("$TAG Failed to dump contact as vCard string")
            }
        }
    }

    @UiThread
    fun toggleContactFavoriteFlag(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            contactModel.friend.edit()
            val starred = !contactModel.friend.starred
            Log.i(
                "$TAG Friend [${contactModel.name.value}] will be [${if (starred) "added to" else "removed from"}] favourites"
            )
            contactModel.friend.starred = starred
            contactModel.friend.done()
            coreContext.contactsManager.rememberNativeFavorite(contactModel.friend)
            coreContext.contactsManager.notifyContactsListChanged()
        }
    }

    @UiThread
    fun deleteContact(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            Log.w("$TAG Removing friend [${contactModel.contactName}]")
            coreContext.contactsManager.contactRemoved(contactModel.friend)
            contactModel.friend.remove()
            coreContext.contactsManager.notifyContactsListChanged()
            showGreenToast(R.string.contact_deleted_toast, R.drawable.warning_circle)
        }
    }

    /**
     * MundoPhone: no se llama applyFilter. Con ese nombre, la llamada con un solo argumento resolvía a la de
     * AbstractMainViewModel (Kotlin prefiere la sobrecarga que no necesita valores por defecto), que no hace nada si el
     * filtro no cambia: al abrir Equipo no se buscaba nunca y la lista solo salía si se recargaban los contactos con la
     * pantalla abierta («tarda muchísimo, a veces no carga»); el buscador tampoco buscaba.
     */
    @WorkerThread
    private fun searchTeam(filter: String, filterChanged: Boolean = false) {
        // MundoPhone: no se mira contactsList.value desde este hilo (va por detrás de lo publicado). Con el Equipo
        // vacío, cada recarga volvía a poner el indicador y, si dos listas se juntaban en una sola entrega, quitarlo
        // (TeamListFragment) llegaba antes que ese «true» y se quedaba cargando para siempre
        if (firstLoad) {
            fetchInProgress.postValue(true)
        }

        if (previousFilter.isNotEmpty() && (
            previousFilter.length > filter.length ||
                (previousFilter.length == filter.length && previousFilter != filter)
            )
        ) {
            magicSearch.resetSearchCache()
        }
        currentFilter = filter
        previousFilter = filter

        Log.i("$TAG Asking Magic search for team contacts matching filter [$filter]")
        searchInProgress.postValue(filter.isNotEmpty())
        showResultsLimitReached.postValue(false)

        if (filter.isEmpty() && (favouritesList.value.orEmpty().isEmpty() || filterChanged)) {
            favouritesMagicSearch.getContactsListAsync(
                filter,
                SIP_ONLY_DOMAIN,
                MagicSearch.Source.FavoriteFriends.toInt(),
                MagicSearch.Aggregation.Friend
            )
        }

        magicSearch.getContactsListAsync(
            filter,
            SIP_ONLY_DOMAIN,
            MagicSearch.Source.Friends.toInt(),
            MagicSearch.Aggregation.Friend
        )
    }

    @WorkerThread
    private fun processMagicSearchResults(results: Array<SearchResult>, favourites: Boolean) {
        // Do not call destroy() on previous list items as they are cached and will be re-used
        val list = arrayListOf<ContactAvatarModel>()
        val collator = Collator.getInstance(Locale.getDefault())

        // MundoPhone: con la lista del borde manda ella (solo sus miembros); sin ella, la agenda como hasta ahora y sin los
        // compañeros de la lista propia. En los dos casos, sin los quitados de Equipo y una sola vez cada extensión, la de
        // la agenda antes que la de la lista del borde (trae nombre y foto)
        val teamRls = coreContext.contactsManager.teamRls
        val rlsActive = teamRls.isActive()
        val members = teamRls.usuarios()
        val seen = hashSetOf<String>()
        val ordered = results.mapNotNull { it.friend }.sortedBy { if (teamRls.isOwnFriend(it)) 1 else 0 }
        val hidden = arrayListOf<ContactAvatarModel>()

        for (friend in ordered) {
            if (!Team.isTeamFriend(friend)) continue
            val usuario = Team.teamUser(friend) ?: continue
            if (rlsActive) {
                if (usuario !in members) continue
            } else if (teamRls.isOwnFriend(friend)) {
                continue
            }
            if (!seen.add(usuario)) continue
            if (list.any { it.friend == friend }) continue
            val isHidden = teamRls.isHidden(usuario)
            // Los ocultos no van a favoritos ni a la lista: aparte, al final
            if (isHidden && favourites) continue

            if (friend.refKey.orEmpty().isEmpty()) {
                if (friend.vcard != null) {
                    friend.vcard?.generateUniqueId()
                    friend.refKey = friend.vcard?.uid
                } else {
                    friend.refKey = friend.name
                }
            }

            val model = coreContext.contactsManager.getContactAvatarModelForFriend(friend)
            model.refreshSortingName()
            model.isFavourite.postValue(friend.starred)
            if (isHidden) hidden.add(model) else list.add(model)
        }

        list.sortWith { model1, model2 ->
            collator.compare(model1.getNameToUseForSorting(), model2.getNameToUseForSorting())
        }
        hidden.sortWith { model1, model2 ->
            collator.compare(model1.getNameToUseForSorting(), model2.getNameToUseForSorting())
        }

        searchInProgress.postValue(false)
        if (favourites) {
            favouritesList.postValue(list)
        } else {
            hiddenList.postValue(hidden)
            contactsList.postValue(list)
            // MundoPhone: desde el mismo hilo y después de la lista, así que siempre es lo último que se aplica
            firstLoad = false
            fetchInProgress.postValue(false)
        }
        Log.i("$TAG Processed [${results.size}] results into [${list.size}] team contacts, favourites is [$favourites]")
    }
}
