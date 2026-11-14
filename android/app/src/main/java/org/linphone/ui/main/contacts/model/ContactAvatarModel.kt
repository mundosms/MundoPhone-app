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
package org.linphone.ui.main.contacts.model

import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.contacts.AbstractAvatarModel
import org.linphone.contacts.TeamRls
import org.linphone.contacts.getNativeContactPictureUri
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Friend
import org.linphone.core.FriendListenerStub
import org.linphone.core.PresenceActivity
import org.linphone.core.SecurityLevel
import org.linphone.core.tools.Log
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils
import androidx.core.net.toUri
import org.linphone.LinphoneApplication.Companion.corePreferences

class ContactAvatarModel
    @WorkerThread
    constructor(val friend: Friend, val address: Address? = null) : AbstractAvatarModel() {
    companion object {
        private const val TAG = "[Contact Avatar Model]"
    }

    val id = friend.refKey ?: friend.name ?: ""

    val contactName = friend.name

    val isStored = friend.inList()

    val isReadOnly = friend.isReadOnly

    val isNative = !friend.nativeUri.isNullOrEmpty()

    val isFavourite = MutableLiveData<Boolean>()

    val lastPresenceInfo = MutableLiveData<String>()

    val name = MutableLiveData<String>()

    // MundoPhone: el valor de [name] para leerlo en el hilo del Core sin esperar al postValue (la línea de la
    // extensión bajo el nombre se oculta si lo repite, DireccionVisible)
    var displayedName: String = ""
        private set

    // MundoPhone: la línea de estado de Equipo («En llamada con Ana · 3:12», vacía si no hay que enseñarla) y el color de
    // esa línea y de la de la ficha
    val teamStatus = MutableLiveData("")

    val presenceTextColor = MutableLiveData(R.color.danger_500)

    @Volatile
    private var teamLinea: TeamRls.Linea? = null

    @Volatile
    private var teamCon: String? = null

    var sortingName: String? = null

    var firstLetter: String? = null

    private val friendListener = object : FriendListenerStub() {
        @WorkerThread
        override fun onPresenceReceived(fr: Friend) {
            Log.d(
                "$TAG Presence received for friend [${fr.name}]: [${fr.consolidatedPresence}]"
            )
            computePresence()
        }
    }

    init {
        presenceStatus.postValue(ConsolidatedPresence.Offline)

        if (friend.addresses.isNotEmpty()) {
            friend.addListener(friendListener)
        }

        update(address)
        refreshSortingName()
    }

    @AnyThread
    fun compare(other: ContactAvatarModel?): Boolean {
        if (other == null) return false

        val picture = picturePath.value
        val otherPicture = other.picturePath.value
        if (picture != null && otherPicture != null && picture != otherPicture) {
            return false
        }

        if (contactName != other.contactName) {
            return false
        }

        if (id != other.id) {
            return false
        }

        if (isFavourite.value != other.isFavourite.value) {
            return false
        }

        return true
    }

    @WorkerThread
    fun destroy() {
        if (friend.addresses.isNotEmpty()) {
            friend.removeListener(friendListener)
        }
    }

    @WorkerThread
    fun refreshSortingName() {
        sortingName = getNameToUseForSorting()
        firstLetter = AppUtils.getFirstLetter(getNameToUseForSorting())
    }

    @WorkerThread
    fun update(address: Address?) {
        updateSecurityLevel(address)

        // MundoPhone (A11): con una dirección concreta (llamada, historial) y «Prioridad remitente texto», el nombre de
        // la centralita; sin dirección (listas de contactos y de Equipo) siempre el de la agenda
        val displayName = if (address != null && corePreferences.prioridadRemitenteTexto) {
            LinphoneUtils.getCallerDisplayName(address, friend)
        } else {
            friend.name
        }

        isFavourite.postValue(friend.starred)
        initials.postValue(AppUtils.getInitials(sinFavoritoSiEsDelBorde(displayName.orEmpty())))
        showTrust.postValue(true)
        picturePath.postValue(getAvatarUri(friend).toString())

        this.displayedName = displayName.orEmpty()
        name.postValue(displayName)
        computePresence(address)
    }

    @WorkerThread
    fun updateSecurityLevelUsingConversation(chatRoom: ChatRoom) {
        /*
        // Don't do that, as chatRoom securityLevel is taking into account the security level
        // between the device and the other devices that shares the same SIP identity
        val securityLevel = when (chatRoom.securityLevel) {
            ChatRoom.SecurityLevel.Unsafe -> {
                SecurityLevel.Unsafe
            }
            ChatRoom.SecurityLevel.Encrypted -> {
                SecurityLevel.EndToEndEncrypted
            }
            ChatRoom.SecurityLevel.Safe -> {
                SecurityLevel.EndToEndEncryptedAndVerified
            }
            else -> SecurityLevel.None
        }*/

        val participants = chatRoom.participants
        var lowestSecurityLevel = if (participants.isEmpty()) {
            SecurityLevel.None
        } else {
            SecurityLevel.EndToEndEncryptedAndVerified
        }
        for (participant in participants) {
            val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(
                participant.address
            )
            val level = avatar.trust.value ?: SecurityLevel.None
            if (level == SecurityLevel.None) {
                lowestSecurityLevel = SecurityLevel.None
            } else if (level == SecurityLevel.Unsafe) {
                lowestSecurityLevel = SecurityLevel.Unsafe
                break
            } else if (lowestSecurityLevel != SecurityLevel.None && level != SecurityLevel.EndToEndEncryptedAndVerified) {
                lowestSecurityLevel = SecurityLevel.EndToEndEncrypted
            }
        }
        trust.postValue(lowestSecurityLevel)
    }

    @WorkerThread
    fun updateSecurityLevel(address: Address?) {
        if (address == null) {
            trust.postValue(friend.securityLevel)
        } else {
            trust.postValue(friend.getSecurityLevelForAddress(address))
        }
    }

    @WorkerThread
    fun getNameToUseForSorting(): String {
        val sortByFirstName = corePreferences.sortContactsByFirstName
        val firstOrLastName = if (sortByFirstName) friend.firstName else friend.lastName
        return sinFavoritoSiEsDelBorde(firstOrLastName ?: friend.name ?: friend.organization ?: friend.vcard?.fullName ?: "")
    }

    /**
     * MundoPhone: los compañeros de la lista del borde se llaman «(111) - Ana»; se ordenan y llevan iniciales por «Ana».
     * Los contactos de la agenda no se tocan: si alguien se pone un nombre para ordenar, se respeta.
     */
    @WorkerThread
    private fun sinFavoritoSiEsDelBorde(nombre: String): String =
        if (coreContext.contactsManager.teamRls.isOwnFriend(friend)) TeamRls.sinFavorito(nombre) else nombre

    @WorkerThread
    private fun getAvatarUri(friend: Friend): Uri? {
        val picturePath = friend.photo
        if (!picturePath.isNullOrEmpty()) {
            return picturePath.toUri()
        }

        val refKey = friend.refKey
        if (refKey != null) {
            try {
                return friend.getNativeContactPictureUri()
            } catch (numberFormatException: NumberFormatException) {
                // Expected for contacts created by Linphone
            }
        }

        return null
    }

    @WorkerThread
    private fun computePresence(address: Address? = null) {
        val modelDeLaDireccion = address?.let { friend.getPresenceModelForUriOrTel(it.asStringUriOnly()) }
        var presence = modelDeLaDireccion?.consolidatedPresence ?: friend.consolidatedPresence
        val actividad = (modelDeLaDireccion ?: friend.presenceModel)?.activity?.type
        // MundoPhone: el «no molestar» del borde (*78) llega abierto + busy y el SDK lo da por Busy (rojo, «En llamada»)
        if (presence == ConsolidatedPresence.Busy && actividad == PresenceActivity.Type.Busy) {
            presence = ConsolidatedPresence.DoNotDisturb
        }
        Log.d("$TAG Friend [${friend.name}] presence status is [$presence]")
        presenceStatus.postValue(presence)

        // MundoPhone: con la lista del borde, lo que manda el borde: situación, con quién y desde cuándo
        val linea = coreContext.contactsManager.teamRls.miembroDe(friend)?.let { TeamRls.linea(it) }
        teamCon = linea?.con?.let { coreContext.contactsManager.nombreParaRemoto(it) }
        teamLinea = linea
        if (linea != null) {
            refreshTeamStatus()
            return
        }
        teamStatus.postValue("")
        presenceTextColor.postValue(
            when (presence) {
                ConsolidatedPresence.Online -> R.color.success_500
                ConsolidatedPresence.DoNotDisturb -> R.color.warning_600
                else -> R.color.danger_500
            }
        )
        // MundoPhone: Busy es «al teléfono» en nuestro borde, no el «visto a las…» de upstream (la hora del modelo es la
        // de cuando se creó, y salía «En línea a las 04:57» a quien estaba en una llamada)
        val presenceString = when (presence) {
            ConsolidatedPresence.Online -> AppUtils.getString(R.string.contact_presence_status_online)
            ConsolidatedPresence.Busy -> if (actividad == PresenceActivity.Type.Away) {
                AppUtils.getString(R.string.team_status_away)
            } else {
                AppUtils.getString(R.string.contact_presence_status_away)
            }
            ConsolidatedPresence.DoNotDisturb -> if (actividad == PresenceActivity.Type.Busy) {
                AppUtils.getString(R.string.team_status_dnd)
            } else {
                AppUtils.getString(R.string.contact_presence_status_do_not_disturb)
            }
            else -> ""
        }
        lastPresenceInfo.postValue(presenceString)
    }

    /** MundoPhone: rehace la línea de Equipo; el contador de la llamada avanza solo, así que la vista lo llama cada segundo. */
    @AnyThread
    fun refreshTeamStatus() {
        val linea = teamLinea ?: return
        val con = teamCon
        val base = when (linea.situacion) {
            TeamRls.Situacion.DISPONIBLE -> AppUtils.getString(R.string.team_status_available)
            TeamRls.Situacion.SONANDO -> conQuien(R.string.team_status_ringing, R.string.team_status_ringing_with, con)
            TeamRls.Situacion.MARCANDO -> conQuien(R.string.team_status_dialing, R.string.team_status_dialing_with, con)
            TeamRls.Situacion.EN_LLAMADA -> conQuien(R.string.contact_presence_status_away, R.string.team_status_in_call_with, con)
            TeamRls.Situacion.EN_ESPERA -> conQuien(R.string.team_status_on_hold, R.string.team_status_on_hold_with, con)
            TeamRls.Situacion.NO_MOLESTAR -> AppUtils.getString(R.string.team_status_dnd)
            TeamRls.Situacion.AUSENTE -> AppUtils.getString(R.string.team_status_away)
            TeamRls.Situacion.DESCONECTADO -> AppUtils.getString(R.string.contact_presence_status_offline)
        }
        val texto = linea.desde?.let { "$base · ${TeamRls.duracion(System.currentTimeMillis() / 1000 - it)}" } ?: base
        teamStatus.postValue(texto)
        lastPresenceInfo.postValue(texto)
        presenceTextColor.postValue(
            when (linea.situacion) {
                TeamRls.Situacion.DISPONIBLE -> R.color.success_500
                TeamRls.Situacion.EN_ESPERA, TeamRls.Situacion.NO_MOLESTAR, TeamRls.Situacion.AUSENTE -> R.color.warning_600
                else -> R.color.danger_500
            }
        )
    }

    /** MundoPhone: ¿lleva contador la línea de Equipo? (una llamada contestada) */
    @AnyThread
    fun hasTeamTimer(): Boolean = teamLinea?.desde != null

    private fun conQuien(sin: Int, con: Int, quien: String?): String =
        if (quien.isNullOrBlank()) AppUtils.getString(sin) else AppUtils.getFormattedString(con, quien)
}
