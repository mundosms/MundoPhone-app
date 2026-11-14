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
package org.linphone.ui.call.model

import android.view.View
import org.linphone.contacts.AbstractAvatarModel
import org.linphone.core.Account
import org.linphone.core.tools.Log
import org.linphone.utils.DireccionVisible
import org.linphone.utils.LinphoneUtils

class AccountSelectionItemModel(
    val account: Account,
    private val clickListener: (Account) -> Unit
) : AbstractAvatarModel() {
    companion object {
        private const val TAG = "[Account Selection Item Model]"
    }

    val displayName: String = account.params.identityAddress
        ?.displayName
        ?.takeIf { it.isNotEmpty() }
        ?: account.params.identityAddress?.username ?: ""
    val sipAddress: String = account.params.identityAddress?.asStringUriOnly() ?: ""

    // MundoPhone: bajo el nombre, la extensión; nada si el nombre ya lo es (DireccionVisible)
    val displayedAddress: String = DireccionVisible.bajoNombre(
        displayName,
        LinphoneUtils.getDisplayedAddress(account.params.identityAddress),
        account.params.internationalPrefix
    )

    init {
        // Set initials for avatar
        initials.value = displayName.takeIf { it.isNotEmpty() }?.let {
            it
                .split(" ")
                .mapNotNull { name -> name.firstOrNull()?.toString() }
                .take(2)
                .joinToString("")
        } ?: ""
    }

    val clickableBackgroundClickListener = View.OnClickListener {
        Log.i("$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] clicked")
        clickListener(account)
    }
}
