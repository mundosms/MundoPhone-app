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
package org.linphone.utils

import android.content.res.Resources
import android.provider.ContactsContract
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import org.linphone.core.DialPlan
import org.linphone.core.Factory
import org.linphone.core.tools.Log

class PhoneNumberUtils {
    companion object {
        private const val TAG = "[Phone Number Utils]"

        // MundoPhone: de 7 a 15 cifras es un número de teléfono; menos, una extensión de la centralita
        private const val MIN_PHONE_DIGITS = 7
        private const val MAX_PHONE_DIGITS = 15

        // MundoPhone (D3): emparejado flexible de números («600 12 31 23» = «+34600123123»)
        private const val DEFAULT_NATIONAL_NUMBER_LENGTH = 9
        private const val SPAIN_COUNTRY_CODE = "34"
        private const val SPAIN_INTERNATIONAL_LENGTH = 11

        @AnyThread
        fun normalizePhoneNumber(raw: String?): String {
            if (raw.isNullOrBlank()) return ""

            val trimmed = raw.trim()
            val hasLeadingPlus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }

            if (digits.isEmpty()) return ""
            return if (hasLeadingPlus) "+$digits" else digits
        }

        @AnyThread
        fun isPhoneNumberLike(raw: String?): Boolean {
            val normalized = normalizePhoneNumber(raw)
            if (normalized.isEmpty()) return false

            val digitsOnly = normalized.removePrefix("+")
            return digitsOnly.length in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS
        }

        /**
         * MundoPhone (D3): las formas comparables de un número, sin «+»: tal cual, sin el prefijo internacional de la
         * cuenta, sin «00» y con sus últimas 9 cifras. Dos números son el mismo si comparten alguna.
         */
        @AnyThread
        fun getComparablePhoneCandidates(
            raw: String?,
            internationalPrefix: String? = null
        ): Set<String> {
            val normalized = normalizePhoneNumber(raw)
            if (normalized.isEmpty()) return emptySet()

            val digitsOnly = normalized.removePrefix("+")
            if (digitsOnly.isEmpty()) return emptySet()

            val candidates = linkedSetOf<String>()
            candidates.add(digitsOnly)

            val prefix = normalizePhoneNumber(internationalPrefix).removePrefix("+")
            if (prefix.isNotEmpty() && digitsOnly.startsWith(prefix) && digitsOnly.length > prefix.length) {
                candidates.add(digitsOnly.substring(prefix.length))
            }

            if (digitsOnly.startsWith("00") && digitsOnly.length > 2) {
                candidates.add(digitsOnly.substring(2))
            }

            if (digitsOnly.length > DEFAULT_NATIONAL_NUMBER_LENGTH) {
                candidates.add(digitsOnly.takeLast(DEFAULT_NATIONAL_NUMBER_LENGTH))
            }

            return candidates
        }

        /**
         * MundoPhone (D3): ¿es [storedPhoneNumber] (el de un contacto) el número que dio [incomingCandidates]
         * ([getComparablePhoneCandidates] del que se busca)? Sí si comparten alguna forma comparable.
         */
        @AnyThread
        fun matchesComparableCandidates(
            storedPhoneNumber: String?,
            incomingCandidates: Set<String>,
            internationalPrefix: String? = null
        ): Boolean {
            val storedCandidates = getComparablePhoneCandidates(storedPhoneNumber, internationalPrefix)
            return storedCandidates.any { it in incomingCandidates }
        }

        /** MundoPhone (D3): el número para mostrar, sin el prefijo de España ni el de la cuenta. */
        @AnyThread
        fun getNationalNumberForDisplay(raw: String?, internationalPrefix: String? = null): String {
            val normalized = normalizePhoneNumber(raw)
            if (normalized.isEmpty()) return ""

            val digitsOnly = normalized.removePrefix("+")
            if (digitsOnly.isEmpty()) return ""

            if (digitsOnly.length == SPAIN_INTERNATIONAL_LENGTH && digitsOnly.startsWith(SPAIN_COUNTRY_CODE)) {
                return digitsOnly.takeLast(DEFAULT_NATIONAL_NUMBER_LENGTH)
            }

            val prefix = normalizePhoneNumber(internationalPrefix).removePrefix("+")
            if (prefix.isNotEmpty() && digitsOnly.startsWith(prefix) && digitsOnly.length > prefix.length) {
                return digitsOnly.substring(prefix.length)
            }

            if (digitsOnly.length > DEFAULT_NATIONAL_NUMBER_LENGTH) {
                return digitsOnly.takeLast(DEFAULT_NATIONAL_NUMBER_LENGTH)
            }

            return digitsOnly
        }

        @WorkerThread
        fun getDeviceDialPlan(countryIso: String): DialPlan? {
            for (dp in Factory.instance().dialPlans) {
                if (dp.isoCountryCode.equals(countryIso, true)) {
                    val prefix = dp.countryCallingCode
                    Log.i(
                        "$TAG Found matching entry [$prefix] in dialplan for network country iso [$countryIso]"
                    )
                    return dp
                }
            }
            return null
        }

        @AnyThread
        fun addressBookLabelTypeToVcardParamString(type: Int, default: String?): String {
            return when (type) {
                ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT -> "assistant"
                ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK -> "callback"
                ContactsContract.CommonDataKinds.Phone.TYPE_CAR -> "car"
                ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> "work,main"
                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "home,fax"
                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "work,fax"
                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                ContactsContract.CommonDataKinds.Phone.TYPE_ISDN -> "isdn"
                ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
                ContactsContract.CommonDataKinds.Phone.TYPE_MMS -> "text"
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "cell"
                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX -> "fax"
                ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "pager"
                ContactsContract.CommonDataKinds.Phone.TYPE_RADIO -> "radio"
                ContactsContract.CommonDataKinds.Phone.TYPE_TELEX -> "telex"
                ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD -> "textphone"
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "work,cell"
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER -> "work,pager"
                ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM -> {
                    Log.d(
                        "$TAG Found custom phone label type using default value [$default] or will use 'custom' if null"
                    )
                    default ?: "custom"
                }
                else -> {
                    Log.w(
                        "$TAG Can't translate phone label type [$type], using default value [$default]"
                    )
                    default ?: type.toString()
                }
            }
        }

        @AnyThread
        fun vcardParamStringToAddressBookLabel(resources: Resources, label: String): String {
            if (label.isEmpty()) return label
            val type = labelToType(label)
            return ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, label).toString()
        }

        @AnyThread
        private fun labelToType(label: String): Int {
            return when (label) {
                "assistant" -> ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT
                "callback" -> ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK
                "car" -> ContactsContract.CommonDataKinds.Phone.TYPE_CAR
                "work,main" -> ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN
                "home,fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME
                "work,fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
                "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                "isdn" -> ContactsContract.CommonDataKinds.Phone.TYPE_ISDN
                "main" -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
                "text" -> ContactsContract.CommonDataKinds.Phone.TYPE_MMS
                "cell" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                "other" -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                "fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX
                "pager" -> ContactsContract.CommonDataKinds.Phone.TYPE_PAGER
                "radio" -> ContactsContract.CommonDataKinds.Phone.TYPE_RADIO
                "telex" -> ContactsContract.CommonDataKinds.Phone.TYPE_TELEX
                "textphone" -> ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD
                "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                "work,cell" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE
                "work,pager" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER
                "custom" -> ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM
                else -> ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM
            }
        }
    }
}
