/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone, a fork of linphone-android.
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
package org.linphone.telecom.redirection;

import android.net.Uri;
import android.os.Build;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * MundoPhone (E1): la única pieza en Java, a propósito. El SDK declara los parámetros de onPlaceCall como no nulos y
 * Kotlin solo deja sobrescribirlo así, añadiendo al entrar una comprobación que lanza una excepción si llega null. Pero
 * Telecom pasa la cuenta de la SIM sin decidir todavía (null) cuando el teléfono tiene dos SIM y «preguntar siempre»: la
 * cuenta se elige DESPUÉS de la redirección. Esa excepción tumbaría el proceso (y una llamada entrante en curso). Aquí
 * no hay comprobación: los dos llegan a Kotlin como anulables.
 */
@RequiresApi(Build.VERSION_CODES.Q)
public abstract class NullableCallRedirectionService extends CallRedirectionService {
    @Override
    public final void onPlaceCall(Uri handle, PhoneAccountHandle initialPhoneAccount, boolean allowInteractiveResponse) {
        onPlaceCallNullable(handle, initialPhoneAccount, allowInteractiveResponse);
    }

    protected abstract void onPlaceCallNullable(
            @Nullable Uri handle,
            @Nullable PhoneAccountHandle initialPhoneAccount,
            boolean allowInteractiveResponse);
}
