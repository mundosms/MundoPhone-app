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

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.BuildConfig

/**
 * Una [Config] de mentira, en memoria, con lo que usa MundoPhoneMigrations.beforeCoreStart. Es un Proxy de la interfaz
 * del SDK (no hace falta su parte nativa); cualquier otro método falla, para enterarse si el código empieza a usarlo.
 */
private class ConfigDeMentira(vararg secciones: Pair<String, Map<String, String>>) {
    private val valores = linkedMapOf<String, LinkedHashMap<String, String>>()
    var escrituras = 0
        private set

    init {
        for ((seccion, claves) in secciones) valores[seccion] = LinkedHashMap(claves)
    }

    fun get(seccion: String, clave: String): String? = valores[seccion]?.get(clave)

    fun set(seccion: String, clave: String, valor: String) {
        valores.getOrPut(seccion) { linkedMapOf() }[clave] = valor
    }

    val config: Config = Proxy.newProxyInstance(
        Config::class.java.classLoader,
        arrayOf(Config::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getSectionsNamesList" -> valores.keys.toTypedArray()
            "getString" -> get(args[0] as String, args[1] as String) ?: args[2]
            "getInt" -> get(args[0] as String, args[1] as String)?.toIntOrNull() ?: args[2]
            "setString" -> set(args[0] as String, args[1] as String, args[2] as String)
            "setInt" -> set(args[0] as String, args[1] as String, (args[2] as Int).toString())
            "sync" -> {
                escrituras++
                0
            }
            "toString" -> "ConfigDeMentira$valores"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> throw UnsupportedOperationException("ConfigDeMentira no tiene ${method.name}")
        }
    } as Config
}

/** Migraciones de configuración de MundoPhone (core/MundoPhoneMigrations.kt). */
class MundoPhoneMigrationsTest {
    // ---- La versión de configuración que ven las migraciones de upstream ----

    @Test
    fun `los versionCode de la serie 22 se presentan a upstream como 600009`() {
        assertEquals(600009, MundoPhoneMigrations.upstreamConfigVersion(60222))
        assertEquals(600009, MundoPhoneMigrations.upstreamConfigVersion(60231))
        assertEquals(600009, MundoPhoneMigrations.upstreamConfigVersion(60000))
        assertEquals(600009, MundoPhoneMigrations.upstreamConfigVersion(69999))
    }

    @Test
    fun `una instalación nueva y las versiones de upstream no se tocan`() {
        assertEquals(52005, MundoPhoneMigrations.upstreamConfigVersion(52005))
        assertEquals(59999, MundoPhoneMigrations.upstreamConfigVersion(59999))
        assertEquals(70000, MundoPhoneMigrations.upstreamConfigVersion(70000))
        assertEquals(600000, MundoPhoneMigrations.upstreamConfigVersion(600000))
        assertEquals(602009, MundoPhoneMigrations.upstreamConfigVersion(602009))
    }

    @Test
    fun `la serie 22 va de 60000 a 69999`() {
        assertFalse(MundoPhoneMigrations.isVersion22(59999))
        assertTrue(MundoPhoneMigrations.isVersion22(60000))
        assertTrue(MundoPhoneMigrations.isVersion22(69999))
        assertFalse(MundoPhoneMigrations.isVersion22(70000))
    }

    @Test
    fun `la versión de configuración sigue la numeración de upstream y no es de la serie 22`() {
        // Es lo que se guarda en [app] config_version y comparan las migraciones de upstream (600000, 602000…)
        val config = BuildConfig.LINPHONE_CONFIG_VERSION
        assertTrue(config in 602000..699999)
        assertFalse(MundoPhoneMigrations.isVersion22(config))
        assertEquals(config, MundoPhoneMigrations.upstreamConfigVersion(config))
    }

    @Test
    fun `el versionCode es el de nuestra versión y está por encima de todo lo firmado antes`() {
        // mayor·1.000.000 + menor·10.000 + parche·100 + firma (app/build.gradle.kts)
        val code = BuildConfig.VERSION_CODE
        val nombre = "${code / 1_000_000}.${code / 10_000 % 100}.${code / 100 % 100}"
        assertEquals(BuildConfig.VERSION_NAME, nombre)
        assertTrue(code > 602010) // la última prueba firmada de la 2.3.0 con el número de upstream
        assertFalse(MundoPhoneMigrations.isVersion22(code))
    }

    // ---- beforeCoreStart, sobre la configuración y sin el Core ----

    @Test
    fun `al proxy de push le pone el puerto 6060 y TCP`() {
        val c = ConfigDeMentira(
            "proxy_0" to mapOf(
                "reg_proxy" to "<sip:push.mundosms.es;transport=udp>",
                "reg_route" to "sip:push.mundosms.es",
                "reg_identity" to "\"Ana\" <sip:ana@push.mundosms.es>"
            )
        )
        val resumen = MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("<sip:push.mundosms.es:6060;transport=tcp>", c.get("proxy_0", "reg_proxy"))
        assertEquals("sip:push.mundosms.es:6060;transport=tcp", c.get("proxy_0", "reg_route"))
        assertEquals(
            "force_register_on_push on [1], port 6060 on [2], TCP on [2], remote push off on [0], push on [1] value(s)",
            resumen
        )
    }

    @Test
    fun `si el usuario eligió el transporte de la cuenta no se le vuelve a poner TCP, pero el puerto sí`() {
        val c = ConfigDeMentira(
            "app" to mapOf(),
            "proxy_0" to mapOf(
                "reg_proxy" to "<sip:push.mundosms.es;transport=udp>",
                "reg_identity" to "\"Ana\" <sip:Ana@push.mundosms.es>"
            ),
            "proxy_1" to mapOf(
                "reg_proxy" to "<sip:push.mundosms.es;transport=udp>",
                "reg_identity" to "sip:luis@push.mundosms.es"
            )
        )
        MundoPhoneMigrations.rememberUserTransport(c.config, "sip:ana@push.mundosms.es")
        MundoPhoneMigrations.rememberUserTransport(c.config, "sip:ana@push.mundosms.es") // una sola vez
        assertEquals("sip:ana@push.mundosms.es", c.get("app", "mundosms_user_transport"))
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("<sip:push.mundosms.es:6060;transport=udp>", c.get("proxy_0", "reg_proxy"))
        assertEquals("<sip:push.mundosms.es:6060;transport=tcp>", c.get("proxy_1", "reg_proxy"))
    }

    @Test
    fun `la identidad se saca con o sin nombre, corchetes y parámetros`() {
        assertEquals("sip:100@push.mundosms.es", MundoPhoneMigrations.identityKey("\"Pepe\" <sip:100@push.mundosms.es;transport=tcp>"))
        assertEquals("sip:100@push.mundosms.es", MundoPhoneMigrations.identityKey("sip:100@PUSH.mundosms.es"))
        assertEquals("", MundoPhoneMigrations.identityKey("sip:push.mundosms.es"))
    }

    @Test
    fun `con el corchete de cierre el transporte va dentro`() {
        val c = ConfigDeMentira("proxy_0" to mapOf("reg_proxy" to "<sip:push.mundosms.es>"))
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("<sip:push.mundosms.es:6060;transport=tcp>", c.get("proxy_0", "reg_proxy"))
    }

    @Test
    fun `un puerto ya puesto se respeta y el transporte pasa a TCP`() {
        val c = ConfigDeMentira(
            "proxy_0" to mapOf(
                "reg_proxy" to "sip:push.mundosms.es:6060;transport=tcp",
                "reg_route" to "<sip:push.mundosms.es:5060;transport=UDP>"
            )
        )
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("sip:push.mundosms.es:6060;transport=tcp", c.get("proxy_0", "reg_proxy"))
        assertEquals("<sip:push.mundosms.es:5060;transport=tcp>", c.get("proxy_0", "reg_route"))
    }

    @Test
    fun `otros servidores y las secciones que no son cuentas no se tocan`() {
        val c = ConfigDeMentira(
            "proxy_0" to mapOf("reg_proxy" to "<sip:sip.otro.com;transport=udp>"),
            "proxy_default_values" to mapOf(
                "reg_proxy" to "<sip:push.mundosms.es;transport=udp>",
                "force_register_on_push" to "0"
            )
        )
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("<sip:sip.otro.com;transport=udp>", c.get("proxy_0", "reg_proxy"))
        assertEquals("<sip:push.mundosms.es;transport=udp>", c.get("proxy_default_values", "reg_proxy"))
        assertEquals("0", c.get("proxy_default_values", "force_register_on_push"))
    }

    @Test
    fun `cada cuenta queda con force_register_on_push y sin push remoto`() {
        val c = ConfigDeMentira(
            "proxy_0" to mapOf("force_register_on_push" to "0", "remote_push_notification_allowed" to "1"),
            "proxy_1" to mapOf("reg_identity" to "<sip:pepe@sip.otro.com>")
        )
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("1", c.get("proxy_0", "force_register_on_push"))
        assertEquals("1", c.get("proxy_1", "force_register_on_push"))
        assertEquals("0", c.get("proxy_0", "remote_push_notification_allowed"))
    }

    @Test
    fun `el push se enciende una sola vez y solo en nuestras cuentas`() {
        val c = ConfigDeMentira(
            "proxy_0" to mapOf("reg_identity" to "<sip:ana@push.mundosms.es>", "push_notification_allowed" to "0"),
            "proxy_1" to mapOf("reg_identity" to "<sip:pepe@sip.otro.com>", "push_notification_allowed" to "0")
        )
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("1", c.get("proxy_0", "push_notification_allowed"))
        assertEquals("0", c.get("proxy_1", "push_notification_allowed"))
        assertEquals("1", c.get("app", "mundosms_push_allowed_fix"))

        // Si después el usuario lo apaga en los ajustes de la cuenta, el siguiente arranque no lo vuelve a encender
        c.set("proxy_0", "push_notification_allowed", "0")
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("0", c.get("proxy_0", "push_notification_allowed"))
    }

    @Test
    fun `quita el filtro de dominio de Contactos`() {
        val c = ConfigDeMentira("ui" to mapOf("contacts_filter" to "push.mundosms.es"))
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals("", c.get("ui", "contacts_filter"))
    }

    @Test
    fun `sin filtro de Contactos no escribe uno`() {
        val c = ConfigDeMentira()
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertNull(c.get("ui", "contacts_filter"))
    }

    @Test
    fun `solo escribe en disco si algo cambió`() {
        val c = ConfigDeMentira(
            "proxy_0" to mapOf(
                "reg_proxy" to "<sip:push.mundosms.es;transport=udp>",
                "reg_identity" to "<sip:ana@push.mundosms.es>"
            )
        )
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals(1, c.escrituras)
        // El siguiente arranque en frío (por push) ya no tiene nada que cambiar
        MundoPhoneMigrations.applyBeforeCoreStart(c.config)
        assertEquals(1, c.escrituras)
    }

    // ---- Las auth_info de pushserver02.mundosms.es (el servidor de push de antes) ----

    private fun auth(username: String?, domain: String?, realm: String? = null) =
        MundoPhoneMigrations.AuthInfoKey(username, domain, realm)

    private fun limpieza(vararg authInfos: MundoPhoneMigrations.AuthInfoKey, cuentas: List<String?> = listOf(PUSH)) =
        MundoPhoneMigrations.oldPushServerAuthInfos(authInfos.toList(), cuentas)

    @Test
    fun `la vieja de pushserver02 se quita si hay otra del mismo usuario en push`() {
        // Como una configuración real: [auth_info_0] la vieja, [auth_info_1] la de ahora
        val resultado = limpieza(auth("1001", VIEJO, VIEJO), auth("1001", PUSH, PUSH))
        assertEquals(listOf(0), resultado.remove)
        assertEquals(emptyList<Int>(), resultado.keep)
    }

    @Test
    fun `sin otra del mismo usuario en push la vieja se queda`() {
        val resultado = limpieza(auth("1001", VIEJO), auth("1002", PUSH), auth("1003", "otro.example.com"))
        assertEquals(emptyList<Int>(), resultado.remove)
        assertEquals(listOf(0), resultado.keep)
        assertFalse(resultado.accountUsesOldHost)
    }

    @Test
    fun `una cuenta que sigue en pushserver02 no pierde nada`() {
        val resultado = limpieza(auth("1001", VIEJO), auth("1001", PUSH), cuentas = listOf(PUSH, VIEJO))
        assertEquals(emptyList<Int>(), resultado.remove)
        assertEquals(listOf(0), resultado.keep)
        assertTrue(resultado.accountUsesOldHost)
    }

    @Test
    fun `por realm vale si la nueva sirve para el mismo desafío`() {
        // La vieja sin dominio se elegiría con un desafío de realm pushserver02: la nueva tiene que servir también
        assertEquals(listOf(0), limpieza(auth("1001", null, VIEJO), auth("1001", PUSH)).remove)
        assertEquals(listOf(0), limpieza(auth("1001", null, VIEJO), auth("1001", PUSH, VIEJO)).remove)
        assertEquals(emptyList<Int>(), limpieza(auth("1001", null, VIEJO), auth("1001", PUSH, PUSH)).remove)
    }

    @Test
    fun `la de push con realm de pushserver02 es la de ahora, no una vieja`() {
        // Si el servidor sigue desafiando con el realm de antes, la auth_info buena lleva ese realm
        val sola = limpieza(auth("1001", PUSH, VIEJO))
        assertEquals(emptyList<Int>(), sola.remove)
        assertEquals(emptyList<Int>(), sola.keep)
        val resultado = limpieza(auth("1001", VIEJO, VIEJO), auth("1001", PUSH, VIEJO))
        assertEquals(listOf(0), resultado.remove)
        assertEquals(emptyList<Int>(), resultado.keep)
    }

    @Test
    fun `mayúsculas, comillas y puerto no despistan, y sin usuario no se toca`() {
        val resultado = limpieza(
            auth("1001", "PushServer02.MundoSMS.es:5060"),
            auth("1001", null, "\"push.mundosms.es\""),
            auth(null, VIEJO),
            auth("", VIEJO),
            auth("1002", PUSH)
        )
        assertEquals(listOf(0), resultado.remove)
        assertEquals(listOf(2, 3), resultado.keep)
    }

    @Test
    fun `la nueva no se quita nunca, ni las de otros servidores`() {
        val resultado = limpieza(
            auth("1001", PUSH),
            auth("1001", "otro.example.com"),
            auth("1001", VIEJO),
            auth("1002", VIEJO)
        )
        assertEquals(listOf(2), resultado.remove)
        assertEquals(listOf(3), resultado.keep)
    }

    private companion object {
        const val VIEJO = "pushserver02.mundosms.es"
        const val PUSH = "push.mundosms.es"
    }
}
