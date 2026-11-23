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

import java.io.StringReader
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.linphone.core.MundoPhoneAprovisionamiento.Clave
import org.linphone.core.MundoPhoneAprovisionamiento.Cuenta
import org.linphone.core.MundoPhoneAprovisionamiento.Existentes
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** El QR del panel añade su cuenta sin tocar las demás (core/MundoPhoneAprovisionamiento.kt). */
class MundoPhoneAprovisionamientoTest {
    /** Con la forma del XML de provision_extension.php: una cuenta, numerada 0. Datos inventados. */
    private fun xmlDelPanel(vararg logins: String): String {
        val cuentas = logins.withIndex().joinToString("") { (n, login) ->
            """
	<section name="auth_info_$n" overwrite="true">
		<entry name="username" overwrite="true">$login</entry>
		<entry name="passwd" overwrite="true">clave-de-$login</entry>
		<entry name="domain" overwrite="true">push.mundosms.es</entry>
	</section>
	<section name="proxy_$n" overwrite="true">
		<entry name="reg_proxy" overwrite="true">&lt;sip:push.mundosms.es:6060;transport=tcp&gt;</entry>
		<entry name="reg_identity" overwrite="true">sip:$login@push.mundosms.es</entry>
		<entry name="realm" overwrite="true">mundopbx</entry>
		<entry name="reg_expires" overwrite="true">2678400</entry>
		<entry name="push_notification_allowed" overwrite="true">1</entry>
	</section>"""
        }
        return """<config xmlns="http://www.linphone.org/xsds/lpconfig.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.linphone.org/xsds/lpconfig.xsd lpconfig.xsd">
	<section name="sip">
		<entry name="default_proxy" overwrite="true">0</entry>
		<entry name="media_encryption" overwrite="true">none</entry>
	</section>
	<section name="misc">
		<entry name="transient_provisioning" overwrite="true">1</entry>
		<!--  apply remote provisioning only once -->
	</section>$cuentas
</config>"""
    }

    /** sección → (entrada → valor), y el overwrite de cada entrada aparte. */
    private class Leido(val valores: Map<String, Map<String, String>>, val overwrite: Map<String, Map<String, String>>)

    private fun leer(xml: String): Leido {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val valores = linkedMapOf<String, LinkedHashMap<String, String>>()
        val overwrite = linkedMapOf<String, LinkedHashMap<String, String>>()
        val secciones = doc.documentElement.getElementsByTagName("section")
        for (i in 0 until secciones.length) {
            val s = secciones.item(i) as Element
            val nombre = s.getAttribute("name")
            assertFalse("sección [$nombre] repetida", valores.containsKey(nombre))
            val v = linkedMapOf<String, String>()
            val o = linkedMapOf<String, String>()
            val entradas = s.getElementsByTagName("entry")
            for (j in 0 until entradas.length) {
                val e = entradas.item(j) as Element
                v[e.getAttribute("name")] = e.textContent
                o[e.getAttribute("name")] = e.getAttribute("overwrite")
            }
            valores[nombre] = v
            overwrite[nombre] = o
        }
        return Leido(valores, overwrite)
    }

    private val nada = Existentes(emptyList(), emptyList())

    // ---- Sin cuentas ----

    @Test
    fun `sin cuentas la del QR queda en el 0 y por defecto`() {
        val r = MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), nada)
        val l = leer(r.xml)
        assertEquals("sip:1001@push.mundosms.es", l.valores["proxy_0"]?.get("reg_identity"))
        assertEquals("1001", l.valores["auth_info_0"]?.get("username"))
        assertEquals("0", l.valores["sip"]?.get("default_proxy"))
        assertEquals("1", l.valores["misc"]?.get("transient_provisioning"))
        assertEquals(1, r.nuevas)
        assertEquals(0, r.actualizadas)
        // Una cuenta nueva no lleva ha1 vacío: no hay ninguno viejo que anular
        assertNull(l.valores["auth_info_0"]?.get("ha1"))
    }

    @Test
    fun `el resto del XML no cambia`() {
        val l = leer(MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), nada).xml)
        assertEquals("<sip:push.mundosms.es:6060;transport=tcp>", l.valores["proxy_0"]?.get("reg_proxy"))
        assertEquals("mundopbx", l.valores["proxy_0"]?.get("realm"))
        assertEquals("2678400", l.valores["proxy_0"]?.get("reg_expires"))
        assertEquals("none", l.valores["sip"]?.get("media_encryption"))
        assertEquals("clave-de-1001", l.valores["auth_info_0"]?.get("passwd"))
        assertEquals("true", l.overwrite["proxy_0"]?.get("reg_identity"))
    }

    // ---- Con cuentas ----

    private val dosCuentas = Existentes(
        listOf(
            Cuenta(0, "2001", "push.mundosms.es", "push.mundosms.es"),
            Cuenta(1, "alicia", "sip.otro-proveedor.example", "sip.otro-proveedor.example")
        ),
        listOf(
            Clave(0, "2001", "push.mundosms.es", ""),
            Clave(1, "alicia", "sip.otro-proveedor.example", "")
        )
    )

    @Test
    fun `una cuenta nueva va al primer hueco y no pisa las demas`() {
        val r = MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), dosCuentas)
        val l = leer(r.xml)
        assertEquals("sip:1001@push.mundosms.es", l.valores["proxy_2"]?.get("reg_identity"))
        assertFalse(l.valores.containsKey("proxy_0"))
        assertFalse(l.valores.containsKey("proxy_1"))
        // La clave va en la 0: el SDK borra todas las [auth_info_N] al aprovisionar y las lee desde la 0 (las que ya había
        // las conserva en memoria y las reescribe al llegar a On)
        assertEquals("1001", l.valores["auth_info_0"]?.get("username"))
        assertFalse(l.valores.containsKey("auth_info_1"))
        assertFalse(l.valores.containsKey("auth_info_2"))
        assertEquals(1, r.nuevas)
    }

    @Test
    fun `con cuentas no cambia la de por defecto`() {
        val l = leer(MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), dosCuentas).xml)
        assertNull(l.valores["sip"]?.get("default_proxy"))
        assertEquals("none", l.valores["sip"]?.get("media_encryption"))
    }

    @Test
    fun `reescanear la misma extension la actualiza en su sitio y anula el ha1 viejo`() {
        val existentes = Existentes(
            listOf(
                Cuenta(0, "alicia", "sip.otro-proveedor.example", "sip.otro-proveedor.example"),
                Cuenta(1, "1001", "push.mundosms.es", "push.mundosms.es")
            ),
            listOf(Clave(0, "alicia", "sip.otro-proveedor.example", ""), Clave(1, "1001", "push.mundosms.es", ""))
        )
        val r = MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), existentes)
        val l = leer(r.xml)
        assertEquals("sip:1001@push.mundosms.es", l.valores["proxy_1"]?.get("reg_identity"))
        assertFalse(l.valores.containsKey("proxy_2"))
        assertEquals("1001", l.valores["auth_info_0"]?.get("username"))
        assertEquals("", l.valores["auth_info_0"]?.get("ha1"))
        assertEquals("true", l.overwrite["auth_info_0"]?.get("ha1"))
        assertEquals(0, r.nuevas)
        assertEquals(1, r.actualizadas)
    }

    @Test
    fun `la misma extension con el proxy viejo tambien se reconoce`() {
        // Cuentas antiguas: identidad igual, proxy por UDP
        val existentes = Existentes(
            listOf(Cuenta(0, "1001", "push.mundosms.es", "push.mundosms.es")),
            listOf(Clave(0, "1001", "push.mundosms.es", ""))
        )
        val l = leer(MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), existentes).xml)
        assertEquals("sip:1001@push.mundosms.es", l.valores["proxy_0"]?.get("reg_identity"))
        assertFalse(l.valores.containsKey("proxy_1"))
    }

    @Test
    fun `el mismo usuario en otro proveedor es otra cuenta`() {
        val existentes = Existentes(
            listOf(Cuenta(0, "1001", "sip.otro-proveedor.example", "sip.otro-proveedor.example")),
            listOf(Clave(0, "1001", "sip.otro-proveedor.example", ""))
        )
        val l = leer(MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), existentes).xml)
        assertEquals("sip:1001@push.mundosms.es", l.valores["proxy_1"]?.get("reg_identity"))
        assertEquals("1001", l.valores["auth_info_0"]?.get("username"))
        assertNull(l.valores["auth_info_0"]?.get("ha1"))
    }

    @Test
    fun `una cuenta repetida dentro del XML se queda una vez`() {
        // Lo que devolvía el panel viejo con jsonaddaccounts si la extensión del QR ya estaba en la app
        val r = MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001", "1001"), nada)
        val l = leer(r.xml)
        assertTrue(l.valores.containsKey("proxy_0"))
        assertFalse(l.valores.containsKey("proxy_1"))
        assertFalse(l.valores.containsKey("auth_info_1"))
        assertEquals(1, r.nuevas)
    }

    @Test
    fun `las claves quedan seguidas desde 0 aunque se quite una repetida`() {
        // auth_info_0 = 1001, auth_info_1 = 1001 repetida, auth_info_2 = 1002: sin la repetida, la 1002 pasa a la 1 (con un
        // hueco en la 1, el SDK no la leería)
        val l = leer(MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001", "1001", "1002"), nada).xml)
        assertEquals("1001", l.valores["auth_info_0"]?.get("username"))
        assertEquals("1002", l.valores["auth_info_1"]?.get("username"))
        assertFalse(l.valores.containsKey("auth_info_2"))
        assertEquals("sip:1002@push.mundosms.es", l.valores["proxy_1"]?.get("reg_identity"))
        assertFalse(l.valores.containsKey("proxy_2"))
    }

    @Test
    fun `sin transient se le pone, para que el file no se quede en la configuracion`() {
        val sinMisc = xmlDelPanel("1001").replace(Regex("""\s*<section name="misc">.*?</section>""", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse(sinMisc.contains("transient"))
        val l = leer(MundoPhoneAprovisionamiento.reescribir(sinMisc, nada).xml)
        assertEquals("1", l.valores["misc"]?.get("transient_provisioning"))
        assertEquals("true", l.overwrite["misc"]?.get("transient_provisioning"))
    }

    // ---- XML que no vale ----

    @Test
    fun `un XML con DOCTYPE no se procesa`() {
        val conEntidad = """<?xml version="1.0"?><!DOCTYPE config [<!ENTITY x SYSTEM "file:///etc/passwd">]><config>&x;</config>"""
        try {
            MundoPhoneAprovisionamiento.reescribir(conEntidad, nada)
            fail("tenía que rechazarlo")
        } catch (_: MundoPhoneAprovisionamiento.XmlNoValido) {
        }
    }

    @Test
    fun `lo que no es config no se procesa`() {
        for (malo in listOf("""{"status":"error","message":"Link expired"}""", "<html><body>hola</body></html>", "<config>")) {
            try {
                MundoPhoneAprovisionamiento.reescribir(malo, nada)
                fail("tenía que rechazar [$malo]")
            } catch (_: MundoPhoneAprovisionamiento.XmlNoValido) {
            }
        }
    }

    // ---- La petición del XML: la firma en el cuerpo, no en la URL ----

    private val enlace = "https://api.mundosms.es/APIv3/provision_extension?v=1&p=aprovisionar&c=1&x=zz-prueba&e=1795119733" +
        "&k=prueba-1&s=ZmlybWEtZGUtcHJ1ZWJhLW5vLXJlYWw"
    private val extra = listOf("source" to "linphonexml", "app" to "MundoPhone-Android", "ver" to "1.2.3-4")

    @Test
    fun `por POST la firma va en el cuerpo y en la URL solo lo que conviene registrar`() {
        val p = MundoPhoneAprovisionamiento.peticionPost(enlace, extra)
        assertEquals(
            "https://api.mundosms.es/APIv3/provision_extension?source=linphonexml&app=MundoPhone-Android&ver=1.2.3-4&x=zz-prueba",
            p.url
        )
        assertEquals("v=1&p=aprovisionar&c=1&x=zz-prueba&e=1795119733&k=prueba-1&s=ZmlybWEtZGUtcHJ1ZWJhLW5vLXJlYWw", p.cuerpo)
        assertFalse(p.url.contains("s=Zmly"))
        assertFalse(p.url.contains("e=1795119733"))
    }

    @Test
    fun `los valores codificados del enlace pasan igual al cuerpo`() {
        val p = MundoPhoneAprovisionamiento.peticionPost("https://api.mundosms.es/a?x=ana%40b&s=a%2Bb", extra)
        assertEquals("x=ana%40b&s=a%2Bb", p.cuerpo)
        assertTrue(p.url.endsWith("&x=ana%40b"))
    }

    @Test
    fun `un enlace sin campos va por GET`() {
        val p = MundoPhoneAprovisionamiento.peticionPost("https://api.mundosms.es/APIv3/provision_extension", extra)
        assertNull(p.cuerpo)
        assertEquals("https://api.mundosms.es/APIv3/provision_extension?source=linphonexml&app=MundoPhone-Android&ver=1.2.3-4", p.url)
    }

    @Test
    fun `por GET todo va en la URL, como antes`() {
        val p = MundoPhoneAprovisionamiento.peticionGet(enlace, extra)
        assertNull(p.cuerpo)
        assertEquals("$enlace&source=linphonexml&app=MundoPhone-Android&ver=1.2.3-4", p.url)
    }

    // ---- URIs SIP ----

    @Test
    fun `usuario y host de las URIs SIP`() {
        assertEquals("1001", MundoPhoneAprovisionamiento.usuarioSip("sip:1001@push.mundosms.es"))
        assertEquals("1001", MundoPhoneAprovisionamiento.usuarioSip("\"Recepción\" <sip:1001@push.mundosms.es>"))
        assertEquals("alicia", MundoPhoneAprovisionamiento.usuarioSip("sips:Alicia@sip.example;transport=tls"))
        assertEquals("", MundoPhoneAprovisionamiento.usuarioSip("<sip:push.mundosms.es:6060;transport=tcp>"))
        assertEquals("", MundoPhoneAprovisionamiento.usuarioSip(""))
        assertEquals("push.mundosms.es", MundoPhoneAprovisionamiento.hostSip("<sip:push.mundosms.es:6060;transport=tcp>"))
        assertEquals("push.mundosms.es", MundoPhoneAprovisionamiento.hostSip("sip:1001@Push.MundoSMS.es"))
        assertEquals("push.mundosms.es", MundoPhoneAprovisionamiento.hostSip("<sip:push.mundosms.es>"))
        assertEquals("[2001:db8::1]", MundoPhoneAprovisionamiento.hostSip("sip:1001@[2001:db8::1]:5060"))
        assertEquals("", MundoPhoneAprovisionamiento.hostSip(""))
    }

    // ---- Lo que hay en la configuración ----

    private fun config(vararg secciones: Pair<String, Map<String, String>>): Config {
        val valores = linkedMapOf(*secciones)
        return Proxy.newProxyInstance(Config::class.java.classLoader, arrayOf(Config::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSectionsNamesList" -> valores.keys.toTypedArray()
                "getString" -> valores[args[0] as String]?.get(args[1] as String) ?: args[2]
                "toString" -> "Config$valores"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Config
    }

    @Test
    fun `lee las cuentas seguidas desde 0, como el SDK`() {
        val c = config(
            "sip" to mapOf("default_proxy" to "0"),
            "proxy_0" to mapOf("reg_identity" to "\"Ana\" <sip:2001@push.mundosms.es>", "reg_proxy" to "<sip:push.mundosms.es:6060;transport=tcp>"),
            "proxy_1" to mapOf("reg_identity" to "sip:alicia@sip.example", "reg_proxy" to "<sip:sip.example>"),
            "proxy_3" to mapOf("reg_identity" to "sip:suelta@sip.example"),
            "auth_info_0" to mapOf("username" to "2001", "domain" to "push.mundosms.es", "ha1" to "x"),
            "auth_info_1" to mapOf("username" to "Alicia", "realm" to "Example")
        )
        val e = MundoPhoneAprovisionamiento.leerExistentes(c)
        assertEquals(2, e.cuentas.size)
        assertEquals("2001", e.cuentas[0].usuario)
        assertEquals("push.mundosms.es", e.cuentas[0].dominio)
        assertEquals("push.mundosms.es", e.cuentas[0].proxy)
        assertEquals("sip.example", e.cuentas[1].proxy)
        assertEquals(2, e.claves.size)
        assertEquals("alicia", e.claves[1].usuario)
        assertEquals("example", e.claves[1].realm)
        // proxy_3 no la lee el SDK (hay un hueco en el 2): la siguiente libre es la 2
        val l = leer(MundoPhoneAprovisionamiento.reescribir(xmlDelPanel("1001"), e).xml)
        assertTrue(l.valores.containsKey("proxy_2"))
    }

    // --- El mismo QR otra vez: sin cambios no se reinicia el Core (sinCambios) ---

    private fun md5(texto: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

    private val cuenta1001 =
        Cuenta(0, "1001", "push.mundosms.es", "push.mundosms.es", "<sip:push.mundosms.es:6060;transport=tcp>")

    @Test
    fun sinCambiosConLaMismaClaveEnClaro() {
        val e =
            Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "", passwd = "clave-de-1001")))
        assertTrue(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), e))
    }

    @Test
    fun sinCambiosConElHa1QueGuardaElSdk() {
        // Tras registrar, el SDK cambia la contraseña por su ha1 y guarda el realm del 401
        val ha1 = md5("1001:mundopbx:clave-de-1001")
        val e = Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "mundopbx", ha1 = ha1)))
        assertTrue(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), e))
    }

    @Test
    fun conOtraClaveSiHayCambios() {
        val ha1 = md5("1001:mundopbx:la-de-antes")
        val e = Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "mundopbx", ha1 = ha1)))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), e))
        val enClaro =
            Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "", passwd = "la-de-antes")))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), enClaro))
    }

    @Test
    fun conOtroProxyOOtraCuentaSiHayCambios() {
        val clave = listOf(Clave(0, "1001", "push.mundosms.es", "", passwd = "clave-de-1001"))
        val otroProxy =
            Existentes(listOf(Cuenta(0, "1001", "push.mundosms.es", "sip.otro.example", "<sip:sip.otro.example:6060;transport=tcp>")), clave)
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), otroProxy))
        val otraCuenta =
            Existentes(listOf(Cuenta(0, "2002", "push.mundosms.es", "push.mundosms.es", "<sip:push.mundosms.es:6060;transport=tcp>")), clave)
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), otraCuenta))
    }

    @Test
    fun conOtroPuertoUOtroTransporteSiHayCambios() {
        val clave = listOf(Clave(0, "1001", "push.mundosms.es", "", passwd = "clave-de-1001"))
        for (proxy in listOf("<sip:push.mundosms.es:5060;transport=tcp>", "<sip:push.mundosms.es:6060>", "sip:push.mundosms.es", "")) {
            val e = Existentes(listOf(Cuenta(0, "1001", "push.mundosms.es", "push.mundosms.es", proxy)), clave)
            assertFalse(proxy, MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), e))
        }
        // La misma, escrita de otra forma (sin < > y en mayúsculas), sí es la misma
        val igual =
            Existentes(listOf(Cuenta(0, "1001", "push.mundosms.es", "push.mundosms.es", "sip:PUSH.mundosms.es:6060;transport=TCP")), clave)
        assertTrue(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), igual))
    }

    @Test
    fun sinClaveConQueCompararSiHayCambios() {
        // Ni contraseña ni ha1, o sin realm para calcularlo: ante la duda, se aplica
        val nada = Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "")))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), nada))
        val sinRealm =
            Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "", ha1 = md5("1001::clave-de-1001"))))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), sinRealm))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios(xmlDelPanel("1001"), Existentes(emptyList(), emptyList())))
    }

    @Test
    fun xmlRaroNoEsSinCambios() {
        val e =
            Existentes(listOf(cuenta1001), listOf(Clave(0, "1001", "push.mundosms.es", "", passwd = "clave-de-1001")))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios("<!DOCTYPE x><config/>", e))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios("no es xml", e))
        assertFalse(MundoPhoneAprovisionamiento.sinCambios("<config></config>", e))
    }

    @Test
    fun enlacesDeMundoSms() {
        assertTrue(MundoPhoneAprovisionamiento.esEnlaceDeMundoSms("https://api.mundosms.es/v3/links?v=1"))
        assertTrue(MundoPhoneAprovisionamiento.esEnlaceDeMundoSms("http://urla.ws/abc"))
        assertTrue(MundoPhoneAprovisionamiento.esEnlaceDeMundoSms("https://www.urla.ws/abc"))
        assertFalse(MundoPhoneAprovisionamiento.esEnlaceDeMundoSms("https://rs.ciggws.net/rd.cgi?FNC=QR_CART"))
        assertFalse(MundoPhoneAprovisionamiento.esEnlaceDeMundoSms("https://mundosms.es.trampa.example/x"))
        assertFalse(MundoPhoneAprovisionamiento.esEnlaceDeMundoSms("no es un enlace"))
    }
}
