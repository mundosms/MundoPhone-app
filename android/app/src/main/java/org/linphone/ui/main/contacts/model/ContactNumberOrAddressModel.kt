/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
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

import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Address
import org.linphone.core.Friend
import org.linphone.telecom.redirection.CallRedirection
import org.linphone.telecom.redirection.RedirectionNumbers
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

class ContactNumberOrAddressModel
    @WorkerThread
    constructor(
    val friend: Friend,
    val address: Address?,
    val displayedValue: String,
    val isEnabled: Boolean,
    private val listener: ContactNumberOrAddressClickListener,
    val isSip: Boolean = true,
    val label: String = "",
    val hasPresence: Boolean = true
) {
    val selected = MutableLiveData<Boolean>()

    val name: String = friend.name.orEmpty()

    // MundoPhone: lo que se enseña de una dirección SIP, la extensión (DireccionVisible), y lo que se copia;
    // displayedValue sigue siendo lo que se manda por SMS
    val valueToDisplay: String = if (isSip && address != null) LinphoneUtils.getDisplayedAddress(address) else displayedValue

    // MundoPhone: «Desde el teléfono: siempre por la SIM / por MundoPhone» si este número tiene elección guardada en la
    // pregunta de la redirección; vacía si no la tiene o sin el rol
    val redirectionLabel =
        MutableLiveData(if (isSip) "" else redirectionLabelFor(CallRedirection.ruleForNumber(displayedValue)))

    private var actionDoneCallback: (() -> Unit)? = null

    @UiThread
    fun refreshRedirection() {
        redirectionLabel.value = redirectionLabelFor(CallRedirection.ruleForNumber(displayedValue))
    }

    @AnyThread
    private fun redirectionLabelFor(rule: String?): String {
        if (rule == null || !CallRedirection.isRoleHeld(coreContext.context)) return ""
        return AppUtils.getString(
            if (rule == RedirectionNumbers.RULE_SIM) R.string.contact_redirection_always_sim else R.string.contact_redirection_always_app
        )
    }

    @UiThread
    fun setActionDoneCallback(lambda: () -> Unit) {
        actionDoneCallback = lambda
    }

    @UiThread
    fun onClicked() {
        listener.onClicked(this)
        actionDoneCallback?.invoke()
    }

    @UiThread
    fun onLongPress(): Boolean {
        selected.value = true
        listener.onLongPress(this)
        actionDoneCallback?.invoke()
        return true
    }
}

interface ContactNumberOrAddressClickListener {
    fun onClicked(model: ContactNumberOrAddressModel)

    fun onLongPress(model: ContactNumberOrAddressModel)
}
