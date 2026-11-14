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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La configuración que va en el registro de soporte, sin secretos (core/SupportLogsConfig.kt). */
class SupportLogsConfigTest {
    private val tapado = SupportLogsConfig.TAPADO

    // Un token de FCM con la forma de los de verdad; sus 6 últimos son «Xy9_Kq»
    private val fcmToken = "eH3kL0pQ:APA91bSECRETOfcmSECRETOfcmSECRETOfcmSECRETOfcmXy9_Kq"

    /** Como lo escribe linphone_config_dump (lpconfig.c): «[sección]» y «\tclave=valor». */
    private fun dump(vararg sections: Pair<String, List<Pair<String, String>>>): String =
        sections.joinToString("") { (section, entries) ->
            "[$section]\n" + entries.joinToString("") { (key, value) -> "\t$key=$value\n" }
        }

    private fun redacted(vararg sections: Pair<String, List<Pair<String, String>>>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var section = ""
        for (line in SupportLogsConfig.redact(dump(*sections))) {
            if (line.startsWith("[")) {
                section = line.trim('[', ']')
            } else {
                result["$section/${line.trim().substringBefore('=')}"] = line.trim().substringAfter('=')
            }
        }
        return result
    }

    // ---- [auth_info_N] ----

    @Test
    fun `en auth_info tapa passwd, ha1, el algoritmo del hash y los tokens`() {
        val result = redacted(
            "auth_info_0" to listOf(
                "username" to "1001",
                "userid" to "1001",
                "ha1" to "0123456789abcdefSECRETO",
                "passwd" to "SECRETOclave",
                "realm" to "push.mundosms.es",
                "domain" to "push.mundosms.es",
                "algorithm" to "MD5",
                "access_token" to "SECRETOacceso",
                "refresh_token" to "SECRETOrefresco",
                "client_cert_key_password" to "SECRETOcert",
                "client_secret" to "SECRETOcliente"
            )
        )
        assertEquals(tapado, result["auth_info_0/ha1"])
        assertEquals(tapado, result["auth_info_0/passwd"])
        assertEquals(tapado, result["auth_info_0/algorithm"])
        assertEquals(tapado, result["auth_info_0/access_token"])
        assertEquals(tapado, result["auth_info_0/refresh_token"])
        assertEquals(tapado, result["auth_info_0/client_cert_key_password"])
        assertEquals(tapado, result["auth_info_0/client_secret"])
        // Lo que no es secreto se queda: con eso soporte ve qué cuenta y qué servidor
        assertEquals("1001", result["auth_info_0/username"])
        assertEquals("1001", result["auth_info_0/userid"])
        assertEquals("push.mundosms.es", result["auth_info_0/realm"])
        assertEquals("push.mundosms.es", result["auth_info_0/domain"])
    }

    @Test
    fun `sin ha1 el algoritmo se ve, y un valor vacío se queda vacío`() {
        val result = redacted(
            "auth_info_1" to listOf(
                "username" to "1002",
                "algorithm" to "SHA-256",
                "ha1" to "",
                "passwd" to "SECRETO"
            )
        )
        assertEquals("SHA-256", result["auth_info_1/algorithm"])
        assertEquals("", result["auth_info_1/ha1"])
        assertEquals(tapado, result["auth_info_1/passwd"])
    }

    @Test
    fun `el algoritmo se tapa aunque vaya antes que el ha1`() {
        val result = redacted("auth_info_2" to listOf("algorithm" to "MD5", "ha1" to "SECRETO"))
        assertEquals(tapado, result["auth_info_2/algorithm"])
    }

    // ---- Push ----

    @Test
    fun `del token de push quedan los 6 últimos y el resto de parámetros se ve`() {
        val result = redacted(
            "proxy_0" to listOf(
                "reg_identity" to "\"Ana\" <sip:1001@push.mundosms.es>",
                "contact_uri_parameters" to "pn-provider=fcm;pn-prid=$fcmToken;pn-param=mundophone-1234;pn-silent=1",
                "push_parameters" to "pn-prid=$fcmToken;pn-provider=fcm;pn-param=mundophone-1234;",
                "push_notification_allowed" to "1"
            )
        )
        assertEquals(
            "pn-provider=fcm;pn-prid=$tapado…Xy9_Kq;pn-param=mundophone-1234;pn-silent=1",
            result["proxy_0/contact_uri_parameters"]
        )
        assertEquals(
            "pn-prid=$tapado…Xy9_Kq;pn-provider=fcm;pn-param=mundophone-1234;",
            result["proxy_0/push_parameters"]
        )
        assertEquals("\"Ana\" <sip:1001@push.mundosms.es>", result["proxy_0/reg_identity"])
        assertEquals("1", result["proxy_0/push_notification_allowed"])
    }

    @Test
    fun `un token de push corto se tapa entero`() {
        val result = redacted("proxy_1" to listOf("push_parameters" to "pn-prid=abc123def;pn-provider=fcm;"))
        assertEquals("pn-prid=$tapado;pn-provider=fcm;", result["proxy_1/push_parameters"])
    }

    // ---- URLs ----

    @Test
    fun `la URL de subida va sin el informe del envío anterior`() {
        val result = redacted(
            "misc" to listOf(
                "log_collection_upload_server_url" to
                    "https://mundophonedebug.mundosms.es/upload?d=SECRETO%20texto&c=600111222&a=1001&v=2.3.0&m=A52s"
            )
        )
        assertEquals(
            "https://mundophonedebug.mundosms.es/upload?$tapado",
            result["misc/log_collection_upload_server_url"]
        )
    }

    @Test
    fun `del enlace de aprovisionamiento solo queda el servidor`() {
        val result = redacted(
            "misc" to listOf(
                "config-uri" to "https://api.mundosms.es/prov/SECRETOcodigo?source=linphonexml&jsonaddaccounts=SECRETO",
                "config-uri-header_0" to "Authorization: Bearer SECRETO"
            )
        )
        assertEquals("https://api.mundosms.es/$tapado", result["misc/config-uri"])
        assertEquals(tapado, result["misc/config-uri-header_0"])
    }

    @Test
    fun `de cualquier URL se tapan el usuario con su clave y los parámetros`() {
        val result = redacted(
            "misc" to listOf(
                "contacts-vcard-list" to "https://usuario:SECRETO@dav.example.com/agenda/?SECRETO=1",
                "version_check_url_root" to "https://example.com/version"
            )
        )
        assertEquals("https://$tapado@dav.example.com/agenda/?$tapado", result["misc/contacts-vcard-list"])
        assertEquals("https://example.com/version", result["misc/version_check_url_root"])
    }

    // ---- Por nombre de clave ----

    @Test
    fun `fuera de auth_info se tapan las claves con pass, secret, token, ha1 o key`() {
        val result = redacted(
            "ldap_0" to listOf("password" to "SECRETO", "server" to "ldap://ldap.example.com"),
            "sip" to listOf("client_cert_key" to "/data/SECRETO.pem", "zrtp_secrets_db_uri" to "SECRETO.db"),
            "app" to listOf("api_token" to "SECRETO", "some_ha1" to "SECRETO", "mundosms_media_policy" to "1")
        )
        assertEquals(tapado, result["ldap_0/password"])
        assertEquals(tapado, result["sip/client_cert_key"])
        assertEquals(tapado, result["sip/zrtp_secrets_db_uri"])
        assertEquals(tapado, result["app/api_token"])
        assertEquals(tapado, result["app/some_ha1"])
        assertEquals("ldap://ldap.example.com", result["ldap_0/server"])
        assertEquals("1", result["app/mundosms_media_policy"])
    }

    @Test
    fun `las claves con esas palabras y valor sin secreto se ven`() {
        val result = redacted(
            "sip" to listOf(
                "zrtp_key_agreements_suites" to "MS_ZRTP_KEY_AGREEMENT_K255_MLK512,MS_ZRTP_KEY_AGREEMENT_K255_KYB512",
                "keep_srtp_keys" to "0",
                "store_ha1_passwd" to "1",
                "ice_password_ufrag_in_media_description" to "0"
            ),
            "assistant" to listOf("password_min_length" to "6", "password_max_length" to "-1")
        )
        assertEquals(
            "MS_ZRTP_KEY_AGREEMENT_K255_MLK512,MS_ZRTP_KEY_AGREEMENT_K255_KYB512",
            result["sip/zrtp_key_agreements_suites"]
        )
        assertEquals("0", result["sip/keep_srtp_keys"])
        assertEquals("1", result["sip/store_ha1_passwd"])
        assertEquals("0", result["sip/ice_password_ufrag_in_media_description"])
        assertEquals("6", result["assistant/password_min_length"])
        assertEquals("-1", result["assistant/password_max_length"])
    }

    // ---- El conjunto ----

    @Test
    fun `una configuración entera no deja salir ningún secreto`() {
        // Todos los secretos llevan SECRETO: si se quita cualquier tapado, aparece en la salida
        val text = dump(
            "sip" to listOf("contact" to "\"MundoPhone Android\" <sip:mundophone.android@unknown-host>"),
            "proxy_0" to listOf(
                "reg_proxy" to "<sip:push.mundosms.es:6060;transport=tcp>",
                "contact_uri_parameters" to "pn-provider=fcm;pn-prid=$fcmToken;pn-param=mundophone-1234"
            ),
            "auth_info_0" to listOf(
                "username" to "1001",
                "ha1" to "SECRETO",
                "algorithm" to "MD5",
                "passwd" to "SECRETO"
            ),
            "misc" to listOf(
                "log_collection_upload_server_url" to "https://mundophonedebug.mundosms.es/upload?d=SECRETO",
                "config-uri" to "https://api.mundosms.es/SECRETO?jsonaddaccounts=SECRETO"
            )
        ) + "\tclient_cert_key=-----BEGIN PRIVATE KEY-----\n" +
            "MIIEvSECRETOQIBADANBgkqhkiG9w0BAQEFAASC\n" +
            "-----END PRIVATE KEY-----\n"
        val output = SupportLogsConfig.redact(text).joinToString("\n")

        assertFalse("Se cuela un secreto:\n$output", output.contains("SECRETO"))
        assertFalse("Se cuela el algoritmo del hash:\n$output", output.contains("MD5"))
        assertTrue(output.contains("<sip:push.mundosms.es:6060;transport=tcp>"))
        assertTrue(output.contains("\tusername=1001"))
    }

    @Test
    fun `los números guardados para la redirección salen con solo las 2 últimas cifras`() {
        val result = redacted(
            "app" to listOf(
                "mundosms_call_redirection_numbers" to "600111222=sim;611333444=app",
                "mundosms_call_redirection" to "ask"
            )
        )
        assertEquals("xxxxxxx22=sim;xxxxxxx44=app", result["app/mundosms_call_redirection_numbers"])
        assertEquals("ask", result["app/mundosms_call_redirection"])
        // En otra sección la misma clave no es nuestra: se deja
        val other = redacted("misc" to listOf("mundosms_call_redirection_numbers" to "600111222=sim"))
        assertEquals("600111222=sim", other["misc/mundosms_call_redirection_numbers"])
    }

    @Test
    fun `una línea suelta de un valor de varias líneas se tapa entera`() {
        val lines = SupportLogsConfig.redact("[sip]\n\tclient_cert_chain=-----BEGIN CERTIFICATE-----\nMIIBSECRETO\n")
        assertEquals("\t$tapado (línea suelta de un valor de varias líneas)", lines[2])
    }

    @Test
    fun `en el registro va entre una línea de inicio y otra de fin, todo con el prefijo`() {
        val lines = SupportLogsConfig.logLines(
            dump("sip" to listOf("use_ipv6" to "0"), "rtp" to listOf("audio_rtp_port" to "7078"))
        )
        assertTrue(lines.all { it.startsWith("${SupportLogsConfig.PREFIX} ") })
        assertTrue(lines.first().contains("Configuración de la app"))
        assertEquals("[Configuración] [sip]", lines[1])
        assertEquals("[Configuración]   use_ipv6=0", lines[2])
        assertEquals("[Configuración]   audio_rtp_port=7078", lines[4])
        assertTrue(lines.last().contains("(4 líneas)"))
        assertEquals(6, lines.size)
    }
}
