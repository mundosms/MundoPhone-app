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

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.ContactsManager
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Friend
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log

/**
 * MundoPhone (E4): mantiene al día los accesos directos del icono y el widget de favoritos.
 *
 * - Favoritos: se recalculan cuando ContactsManager avisa de que han cambiado los contactos (también al marcar o
 *   desmarcar uno: ContactsListViewModel, TeamListViewModel y ContactViewModel llaman a notifyContactsListChanged), con
 *   un margen para juntar las ráfagas de presencia.
 * - La agenda del móvil solo la carga MainActivity: con la app arrancada por un push no está, y sus favoritos saldrían
 *   como «ya no favoritos». Mientras no esté, se recalculan las demás listas y los de la agenda se quedan como estaban
 *   en la copia en disco. Una agenda vacía no avisa nunca (ContactLoader no llega a onNativeContactsLoaded): por eso,
 *   un rato después de pedir la carga, se calcula igualmente aunque no haya copia. Sin permiso de contactos no hay
 *   agenda.
 * - Buzón de voz: cuando cambia la cuenta por defecto o se registra una cuenta (guardar sus ajustes la vuelve a
 *   registrar).
 *
 * Lo del Core, en su hilo; los accesos directos, el widget y el fichero, en un hilo aparte (fotos, disco y llamadas al
 * sistema), siempre en orden.
 */
object FavoritesPublisher {
    private const val TAG = "[Favorites Publisher]"

    private const val DELAY_MS = 1500L

    // Tras pedir la carga de la agenda: lo que se le deja para terminar antes de calcular sin ella
    private const val ADDRESS_BOOK_LOAD_TIMEOUT_MS = 10000L

    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "FavoritesPublisher") }

    // Estado del hilo del Core
    private var started = false
    private var generation = 0
    private var favorites: List<FavoriteContact>? = null
    private var completeInThisProcess = false
    private var voicemail: Boolean? = null

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            scheduleRefresh()
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            checkVoicemail(core)
            // La dirección «principal» de un favorito con varias depende del dominio de la cuenta
            scheduleRefresh()
        }

        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (state == RegistrationState.Ok || state == RegistrationState.Cleared) {
                checkVoicemail(core)
            }
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            checkVoicemail(core)
        }
    }

    /** Lo llama ContactsManager.onCoreStarted. */
    @WorkerThread
    fun onCoreStarted(core: Core) {
        if (started) return
        started = true

        coreContext.contactsManager.addListener(contactsListener)
        core.addListener(coreListener)

        favorites = FavoriteContacts.load(coreContext.context)
        checkVoicemail(core)
        if (!hasContactsPermission()) {
            // Nadie va a cargar la agenda: los favoritos son los de las demás listas, ya cargadas
            scheduleRefresh()
        }
    }

    /** Lo llama ContactsManager.loadContacts (MainActivity pide cargar la agenda). */
    @AnyThread
    fun onAddressBookLoadRequested() {
        coreContext.postOnCoreThreadDelayed({ core ->
            if (started) refreshFavorites(core, evenWithoutAddressBook = true)
        }, ADDRESS_BOOK_LOAD_TIMEOUT_MS)
    }

    /** Lo llama ContactsManager.onCoreStopped. */
    @WorkerThread
    fun onCoreStopped(core: Core) {
        if (!started) return
        started = false
        core.removeListener(coreListener)
        coreContext.contactsManager.removeListener(contactsListener)
    }

    @WorkerThread
    private fun scheduleRefresh() {
        val scheduled = ++generation
        coreContext.postOnCoreThreadDelayed({ core ->
            if (scheduled == generation) refreshFavorites(core)
        }, DELAY_MS)
    }

    @WorkerThread
    private fun refreshFavorites(core: Core, evenWithoutAddressBook: Boolean = false) {
        val previous = favorites
        val complete = !hasContactsPermission() || coreContext.contactsManager.areNativeContactsLoaded()
        val computed = if (complete) {
            FavoriteContacts.compute(core, includeAddressBook = true)
        } else if (previous != null || evenWithoutAddressBook) {
            // Los de la agenda, como la última vez que se cargó; el resto, como están ahora
            val addressBook = previous.orEmpty().filter { it.fromAddressBook }
            FavoriteContacts.sorted(FavoriteContacts.compute(core, includeAddressBook = false) + addressBook)
        } else {
            Log.i("$TAG Native address book not loaded yet and no favourites saved, waiting for it")
            return
        }

        // La primera vez que se calculan con la agenda en cada proceso se publica aunque no haya cambios: repara lo
        // que haya fallado antes (los accesos directos solo se tocan si su firma cambia, ver FavoriteShortcuts)
        val firstComplete = complete && !completeInThisProcess
        if (computed == previous && !firstComplete) return
        if (complete) completeInThisProcess = true
        favorites = computed
        Log.i("$TAG Publishing [${computed.size}] favourite(s), address book ${if (complete) "loaded" else "not loaded"}")

        val context = coreContext.context
        val withVoicemail = voicemail == true
        executor.execute {
            FavoriteContacts.save(context, computed)
            FavoriteShortcuts.publish(context, computed, withVoicemail)
            FavoritesWidgetProvider.updateAll(context, computed)
        }
    }

    @WorkerThread
    private fun checkVoicemail(core: Core) {
        val hasVoicemail = core.defaultAccount?.params?.voicemailAddress != null
        if (hasVoicemail == voicemail) return
        voicemail = hasVoicemail
        Log.i("$TAG Default account ${if (hasVoicemail) "has" else "has no"} voicemail address")

        // Con el buzón caben 3 favoritos y sin él 4: se publican juntos
        val context = coreContext.context
        val current = favorites
        executor.execute {
            FavoriteShortcuts.publish(context, current, hasVoicemail)
        }
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            coreContext.context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
