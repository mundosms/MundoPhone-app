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
package org.linphone.core

import org.linphone.telecom.redirection.RedirectionNumbers

/**
 * MundoPhone: la configuración de la app (el linphonerc: lo mismo que enseña Ayuda > Solución de problemas > «Mostrar la
 * configuración», core.config.dump()) sin secretos, para escribirla en el registro justo antes de subirlo a soporte
 * (SupportLogsUpload). Hasta ahora el registro no la llevaba y soporte no veía ninguna clave (25/10/2026).
 *
 * Sin nada nativo, para probarlo en la JVM (app/src/test). Lee el formato de linphone_config_dump (lpconfig.c):
 * «[sección]» y debajo «\tclave=valor». Lo que se tapa se cambia por [TAPADO]:
 * - [auth_info_N]: passwd, ha1, algorithm si la sección lleva ha1 (dice con qué se hizo el hash) y cualquier clave con
 *   «token» (access_token, refresh_token, token_endpoint);
 * - en cualquier sección, las claves cuyo nombre contiene pass, secret, token, ha1 o key, salvo [SIN_SECRETO];
 * - el token de push (pn-prid=… en push_parameters, contact_uri_parameters o donde aparezca): solo quedan sus 6 últimos
 *   caracteres, para casarlo con el servidor de push;
 * - [misc] config-uri, el enlace de aprovisionamiento: quien lo tenga baja el XML con las claves de las cuentas, y el del
 *   QR lleva además los usuarios con el MD5 de su clave (jsonaddaccounts, QrCodeViewModel). Solo queda el servidor;
 * - [misc] config-uri-header_N: cabeceras HTTP del aprovisionamiento (pueden llevar una autorización);
 * - en cualquier URL con «esquema://»: el usuario y la clave (…://usuario:clave@servidor) y los parámetros (?…), que es
 *   donde van los tokens. Así [misc] log_collection_upload_server_url queda sin el informe de un envío anterior (?d=…);
 * - [app] mundosms_call_redirection_numbers, los números que el usuario manda siempre por la SIM o por MundoPhone (su
 *   agenda personal): solo las 2 últimas cifras de cada uno, como en el resto del registro (07/11/2026);
 * - las líneas que no son ni sección ni «clave=valor» (un valor de varias líneas, p. ej. un certificado): enteras.
 * Un valor vacío se deja vacío: no hay nada que tapar, y saber que passwd está vacío (va con ha1) también ayuda.
 */
object SupportLogsConfig {
    const val TAPADO = "«tapado»"
    const val PREFIX = "[Configuración]"

    // Una clave cuyo nombre lleva una de estas palabras se tapa entera, en cualquier sección
    private val SECRET_WORDS = listOf("pass", "secret", "token", "ha1", "key")

    // Llevan una de esas palabras en el nombre, pero su valor no es secreto: un interruptor, un número o una lista de
    // algoritmos que soporte sí quiere ver. Fuera de [auth_info_N] (allí manda la lista de arriba)
    private val SIN_SECRETO = setOf(
        "zrtp_key_agreements_suites", // [sip] algoritmos de ZRTP (los fija nuestro linphonerc_factory)
        "keep_srtp_keys", // [sip] interruptor 0/1
        "ice_password_ufrag_in_media_description", // [sip] interruptor 0/1 de dónde va ice-pwd en el SDP
        "store_ha1_passwd", // [sip] interruptor 0/1: guardar el HA1 en vez de la clave (el HA1 en sí se tapa)
        "password_min_length", // [assistant] longitudes que pide el asistente de cuentas
        "password_max_length"
    )

    private const val PUSH_TOKEN_SUFFIX = 6

    // Un token más corto que esto no es de verdad un token de push: se tapa entero en vez de enseñar casi todo
    private const val PUSH_TOKEN_MIN_FOR_SUFFIX = 2 * PUSH_TOKEN_SUFFIX

    private val authInfoSection = Regex("auth_info_\\d+")
    private val sectionLine = Regex("""^\[([^\]]+)]$""")
    private val entryLine = Regex("""^\t([A-Za-z0-9_.\-]+)=(.*)$""")
    private val pushToken = Regex("""(pn-prid=)([^;\s]*)""", RegexOption.IGNORE_CASE)
    // Los parámetros se llevan hasta el primer blanco, fragmento (#…) incluido
    private val url = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)(?:([^\s/?#@]*)@)?([^\s?#]*)(\?\S*)?""")
    private val urlServer = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*://)(?:[^\s/?#@]*@)?([^\s/?#]*)""")

    /** [dump] (core.config.dump()) con los secretos cambiados por [TAPADO], línea a línea y en el mismo orden. */
    fun redact(dump: String): List<String> {
        val lines = dump.split('\n').dropLastWhile { it.isEmpty() }

        // Primera pasada: qué secciones de auth_info llevan ha1 (algorithm puede ir antes o después)
        val sectionsWithHa1 = mutableSetOf<String>()
        var section = ""
        for (line in lines) {
            val header = sectionLine.find(line)
            if (header != null) {
                section = header.groupValues[1]
                continue
            }
            val entry = entryLine.find(line) ?: continue
            if (entry.groupValues[1] == "ha1" && entry.groupValues[2].isNotEmpty()) sectionsWithHa1.add(section)
        }

        section = ""
        return lines.map { line ->
            val header = sectionLine.find(line)
            val entry = entryLine.find(line)
            when {
                header != null -> {
                    section = header.groupValues[1]
                    line
                }
                entry != null -> {
                    val key = entry.groupValues[1]
                    "\t$key=${redactValue(section, key, entry.groupValues[2], section in sectionsWithHa1)}"
                }
                line.isBlank() -> line
                else -> "\t$TAPADO (línea suelta de un valor de varias líneas)"
            }
        }
    }

    /**
     * Las líneas que se escriben en el registro antes de subirlo: [PREFIX] delante de cada una (para encontrarlas y
     * filtrarlas), y una línea de inicio y otra de fin con cuántas hay.
     */
    fun logLines(dump: String): List<String> {
        val body = redact(dump).map { "$PREFIX ${it.replace("\t", "  ")}" }
        return listOf("$PREFIX ===== Configuración de la app (linphonerc; lo secreto pone $TAPADO) =====") +
            body +
            "$PREFIX ===== Fin de la configuración de la app (${body.size} líneas) ====="
    }

    private fun redactValue(section: String, key: String, value: String, sectionHasHa1: Boolean): String {
        if (value.isEmpty()) return value
        val name = key.lowercase()

        if (authInfoSection.matches(section)) {
            if (name == "passwd" || name == "ha1" || name.contains("token")) return TAPADO
            if (name == "algorithm" && sectionHasHa1) return TAPADO
            if (SECRET_WORDS.any { name.contains(it) }) return TAPADO
        } else if (name !in SIN_SECRETO && SECRET_WORDS.any { name.contains(it) }) {
            return TAPADO
        }

        if (section == "app" && name == "mundosms_call_redirection_numbers") {
            return value.split(';').joinToString(";") { entry ->
                val number = entry.substringBefore('=')
                RedirectionNumbers.mask(number) + entry.substring(number.length)
            }
        }

        if (section == "misc") {
            if (name == "config-uri") {
                val server = urlServer.find(value) ?: return TAPADO
                return "${server.groupValues[1]}${server.groupValues[2]}/$TAPADO"
            }
            if (name.startsWith("config-uri-header")) return TAPADO
        }

        val withoutPushToken = pushToken.replace(value) { it.groupValues[1] + pushTokenSuffix(it.groupValues[2]) }
        return url.replace(withoutPushToken) {
            val userInfo = if (it.groups[2] != null) "$TAPADO@" else ""
            val query = if (it.groupValues[4].isNotEmpty()) "?$TAPADO" else ""
            "${it.groupValues[1]}$userInfo${it.groupValues[3]}$query"
        }
    }

    private fun pushTokenSuffix(token: String): String = when {
        token.isEmpty() -> token
        token.length < PUSH_TOKEN_MIN_FOR_SUFFIX -> TAPADO
        else -> "$TAPADO…${token.takeLast(PUSH_TOKEN_SUFFIX)}"
    }
}
