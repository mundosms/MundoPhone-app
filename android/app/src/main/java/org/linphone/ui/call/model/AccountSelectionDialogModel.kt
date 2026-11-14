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

import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.tools.Log
import org.linphone.utils.Event

class AccountSelectionDialogModel(
    val accounts: List<Account>,
    private val callAddress: Address,
    private val isVideoCall: Boolean
) {
    companion object {
        private const val TAG = "[Account Selection Dialog Model]"
    }

    val items = MutableLiveData<ArrayList<AccountSelectionItemModel>>()

    val dismissEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    init {
        Log.i("$TAG Creating dialog model with ${accounts.size} accounts")
        val list = arrayListOf<AccountSelectionItemModel>()

        for (account in accounts) {
            val model = AccountSelectionItemModel(account) { selectedAccount ->
                Log.i("$TAG User selected account: ${selectedAccount.params.identityAddress?.asStringUriOnly()}")
                coreContext.postOnCoreThread {
                    if (isVideoCall) {
                        coreContext.startVideoCall(
                            callAddress,
                            false,
                            selectedAccount.params.identityAddress
                        )
                    } else {
                        coreContext.startAudioCall(
                            callAddress,
                            false,
                            selectedAccount.params.identityAddress
                        )
                    }
                }
                dismiss()
            }
            list.add(model)
        }

        items.value = list
    }

    fun onCancelClicked() {
        Log.i("$TAG User cancelled account selection")
        dismiss()
    }

    private fun dismiss() {
        dismissEvent.value = Event(true)
    }
}
