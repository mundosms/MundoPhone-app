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

import androidx.annotation.WorkerThread
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * MundoPhone: el QR del panel añade SU cuenta sin tocar las que ya hay y sin mandar nada de ellas.
 *
 * El XML del panel numera la cuenta como proxy_0 / auth_info_0. Dado al SDK tal cual, pisaría la primera cuenta de la
 * app. Para evitarlo, hasta la 2.3.x la app mandaba en la URL (jsonaddaccounts) el usuario y el MD5 de la clave de TODAS
 * sus cuentas, y el panel las devolvía en el mismo XML: un MD5 sin sal que en ese punto del panel vale como la clave, y
 * que quedaba en el registro de cualquiera que viera la URL (acortador, nginx, proxies).
 *
 * Ahora la app descarga el XML sin mandar nada y aquí se renumera la cuenta: al índice de esa misma cuenta si ya está (un
 * segundo escaneo la actualiza en vez de duplicarla) o al primer hueco. Después se aplica por el camino del
 * aprovisionamiento remoto del SDK, desde un fichero local (file://): al arrancar el Core y antes de que lea las cuentas.
 *
 * Las CLAVES (auth_info_N) no se renumeran: van seguidas desde 0. Al aplicar un aprovisionamiento, el SDK borra TODAS las
 * [auth_info_N] de la configuración (linphone_remote_provisioning_apply, remote_provisioning.c del SDK 5.5.38) y luego
 * las lee desde la 0 hasta el primer hueco; las de las cuentas que ya había no se pierden porque el Core las leyó en
 * memoria al arrancar (antes del aprovisionamiento) y las vuelve a escribir todas al llegar a On (Core::writeAuthInfos).
 * Numerada detrás de las existentes, la clave nueva caía en un hueco y la cuenta se quedaba sin ella (visto en el
 * emulador el 19/11/2026).
 */
object MundoPhoneAprovisionamiento {
    /** Tope de lo que se descarga: el XML del panel ocupa unos 3 KB. */
    const val MAX_BYTES = 64 * 1024

    /** El XML del QR ya renumerado, en noBackupFilesDir, que el SDK aplica como file:// al arrancar el Core. */
    const val FICHERO_QR = "mundophone-qr.xml"

    private val PROXY = Regex("proxy_(\\d+)")
    private val AUTH = Regex("auth_info_(\\d+)")

    // proxyTal: el reg_proxy tal cual, para comparar también puerto y transporte (sinCambios)
    class Cuenta(val indice: Int, val usuario: String, val dominio: String, val proxy: String, val proxyTal: String = "")

    class Clave(
        val indice: Int,
        val usuario: String,
        val dominio: String,
        val realm: String,
        // Para saber si un QR trae la MISMA clave (sinCambios): tal cual, sin pasar a minúsculas (el ha1 las distingue)
        val usuarioTal: String = usuario,
        val realmTal: String = realm,
        val passwd: String = "",
        val ha1: String = "",
        val algoritmo: String = ""
    )

    /** Las secciones proxy_N / auth_info_N que hay, seguidas desde 0: el SDK deja de leer en el primer hueco. */
    class Existentes(val cuentas: List<Cuenta>, val claves: List<Clave>)

    class Resultado(val xml: String, val nuevas: Int, val actualizadas: Int)

    class XmlNoValido(motivo: String) : Exception(motivo)

    /** Una petición del XML: con cuerpo, POST (application/x-www-form-urlencoded); sin él, GET. */
    class Peticion(val url: String, val cuerpo: String?)

    /** Campos del enlace que no son secretos y van también en la URL, para que consten en el registro (la extensión). */
    private val A_LA_VISTA = setOf("x")

    /**
     * Los campos del enlace del panel (v, p, c, x, e, k, s: la firma, que con la extensión da su clave mientras no caduca) en
     * el CUERPO de un POST, que no queda en ningún registro de URLs. En la URL solo `extra` (source, la app y su versión) y la
     * extensión, que no son secretos y conviene ver en el registro del panel. El panel junta query y cuerpo y exige que un
     * campo repetido valga lo mismo. Un enlace sin campos va por GET.
     */
    fun peticionPost(enlace: String, extra: List<Pair<String, String>>): Peticion {
        val base = enlace.substringBefore('?').substringBefore('#')
        val campos = paresCrudos(enlace)
        if (campos.isEmpty()) return peticionGet(enlace, extra)
        val vista = campos.filter { (nombre, _) -> nombre in A_LA_VISTA }
        val query = une(extra.map { (k, v) -> codifica(k) to codifica(v) } + vista)
        return Peticion("$base?$query", une(campos))
    }

    /** La forma de siempre: todo en la URL. */
    fun peticionGet(enlace: String, extra: List<Pair<String, String>>): Peticion {
        val sinFragmento = enlace.substringBefore('#')
        val separador = if (sinFragmento.contains("?")) "&" else "?"
        val query = une(extra.map { (k, v) -> codifica(k) to codifica(v) })
        return Peticion("$sinFragmento$separador$query", null)
    }

    /** Los pares de la query tal como vienen (sin decodificar: se reenvían igual en el cuerpo, que PHP decodifica igual). */
    private fun paresCrudos(enlace: String): List<Pair<String, String>> {
        val query = try {
            URI(enlace).rawQuery
        } catch (_: Exception) {
            enlace.substringAfter('?', "").substringBefore('#')
        }
        if (query.isNullOrEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map {
            val igual = it.indexOf('=')
            if (igual < 0) it to "" else it.substring(0, igual) to it.substring(igual + 1)
        }
    }

    private fun codifica(texto: String): String = URLEncoder.encode(texto, "UTF-8")

    private fun une(pares: List<Pair<String, String>>): String = pares.joinToString("&") { "${it.first}=${it.second}" }

    @WorkerThread
    fun leerExistentes(config: Config): Existentes {
        val secciones = config.sectionsNamesList.toHashSet()
        val cuentas = ArrayList<Cuenta>()
        var i = 0
        while ("proxy_$i" in secciones) {
            val identidad = config.getString("proxy_$i", "reg_identity", null).orEmpty()
            val proxy = config.getString("proxy_$i", "reg_proxy", null).orEmpty()
            cuentas.add(Cuenta(i, usuarioSip(identidad), hostSip(identidad), hostSip(proxy), proxy))
            i++
        }
        val claves = ArrayList<Clave>()
        i = 0
        while ("auth_info_$i" in secciones) {
            val seccion = "auth_info_$i"
            val usuario = config.getString(seccion, "username", null).orEmpty().trim()
            val realm = config.getString(seccion, "realm", null).orEmpty().trim()
            claves.add(
                Clave(
                    i,
                    usuario.lowercase(),
                    config.getString(seccion, "domain", null).orEmpty().trim().lowercase(),
                    realm.lowercase(),
                    usuario,
                    realm,
                    config.getString(seccion, "passwd", null).orEmpty(),
                    config.getString(seccion, "ha1", null).orEmpty().trim().lowercase(),
                    config.getString(seccion, "algorithm", null).orEmpty().trim()
                )
            )
            i++
        }
        return Existentes(cuentas, claves)
    }

    /**
     * El XML con las cuentas renumeradas. Si la app ya tenía cuentas, la de por defecto no cambia (es también la favorita
     * que elige el usuario, CoreContext.setUserFavoriteAccount); el método viejo la cambiaba siempre a la primera.
     */

    /**
     * true si el XML trae, tal cual, cuentas que la app ya tiene: misma extensión, mismo proxy y misma contraseña (la
     * guardada en claro o su ha1). Escanear otra vez el mismo QR es lo más habitual, y aplicarlo obliga a parar y arrancar
     * el Core: el 21/11/2026, en un A52s, el tercer reinicio en 7 segundos tumbó la app dentro del SDK («Bad cast to
     * belle_sip_cpp_object_t», CRASH_NATIVE). Ante cualquier duda, false (se aplica como siempre).
     */
    fun sinCambios(xml: String, existentes: Existentes): Boolean {
        val raiz = try {
            documento(xml).documentElement
        } catch (e: XmlNoValido) {
            return false
        }
        val secciones = hijos(raiz, "section")
        val proxies = ordenadas(secciones, PROXY)
        if (proxies.isEmpty()) return false
        for (seccion in proxies) {
            val identidad = valor(seccion, "reg_identity")
            val usuario = usuarioSip(identidad)
            val dominio = hostSip(identidad)
            if (usuario.isEmpty()) return false
            // El proxy entero (host, puerto y transporte): si el panel cambia el puerto o el transporte, hay que aplicarlo
            val destino = destinoSip(valor(seccion, "reg_proxy")) ?: return false
            existentes.cuentas.firstOrNull {
                it.usuario == usuario && it.dominio == dominio && destinoSip(it.proxyTal) == destino
            } ?: return false
        }
        for (seccion in ordenadas(secciones, AUTH)) {
            val usuarioTal = valor(seccion, "username")
            val passwd = entrada(seccion, "passwd")?.textContent.orEmpty()
            if (usuarioTal.isEmpty() || passwd.isEmpty()) return false
            val dominio = valor(seccion, "domain").lowercase()
            val realm = valor(seccion, "realm")
            val clave = existentes.claves.firstOrNull {
                it.usuario == usuarioTal.lowercase() && (it.dominio == dominio || (realm.isNotEmpty() && it.realm == realm.lowercase()))
            } ?: return false
            val misma = when {
                clave.passwd.isNotEmpty() -> clave.passwd == passwd
                clave.ha1.isNotEmpty() -> {
                    val r = realm.ifEmpty { clave.realmTal }
                    if (r.isEmpty()) return false
                    val algoritmo = if (clave.algoritmo.equals("SHA-256", ignoreCase = true)) "SHA-256" else "MD5"
                    resumen(algoritmo, "${clave.usuarioTal}:$r:$passwd") == clave.ha1
                }
                else -> false
            }
            if (!misma) return false
        }
        return true
    }

    /** Un enlace del panel: a mundosms.es o al acortador (urla.ws). Solo decide el aviso cuando el enlace no lleva a la API. */
    fun esEnlaceDeMundoSms(enlace: String): Boolean {
        val host = try {
            URI(enlace.trim()).host
        } catch (_: Exception) {
            null
        }?.lowercase() ?: return false
        return host == "mundosms.es" || host.endsWith(".mundosms.es") || host == "urla.ws" || host.endsWith(".urla.ws")
    }

    /** «host:puerto;transporte» de una URI SIP, con los valores por defecto (5060 y udp; 5061 y tls con sips), o null. */
    private fun destinoSip(uri: String): String? {
        val esquema = Regex("(sips?):", RegexOption.IGNORE_CASE).find(uri) ?: return null
        val seguro = esquema.groupValues[1].equals("sips", ignoreCase = true)
        val host = hostSip(uri)
        if (host.isEmpty()) return null
        val resto = uri.substring(esquema.range.last + 1).substringBefore('>')
        val hostPuerto = resto.substringAfter('@').substringBefore(';').substringBefore('?').trim()
        val puerto = if (host.startsWith("[")) {
            hostPuerto.substringAfter("]", "").removePrefix(":")
        } else {
            hostPuerto.substringAfter(':', "")
        }
        val transporte = Regex(";transport=([a-z]+)", RegexOption.IGNORE_CASE).find(resto)?.groupValues?.get(1)?.lowercase()
            ?: if (seguro) "tls" else "udp"
        return "$host:${puerto.ifEmpty { if (seguro || transporte == "tls") "5061" else "5060" }};$transporte"
    }

    private fun resumen(algoritmo: String, texto: String): String =
        MessageDigest.getInstance(algoritmo).digest(texto.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // Sin DOCTYPE no hay entidades externas que resolver (el parser de Android no admite desactivarlas por feature)
    private fun documento(xml: String): Document {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw XmlNoValido("DOCTYPE not allowed")
        }
        val doc = try {
            val fabrica = DocumentBuilderFactory.newInstance()
            fabrica.isExpandEntityReferences = false
            fabrica.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw XmlNoValido("unparsable XML (${e.javaClass.simpleName})")
        }
        val raiz = doc.documentElement
        if (raiz == null || raiz.tagName != "config") throw XmlNoValido("root isn't <config>")
        return doc
    }

    fun reescribir(xml: String, existentes: Existentes): Resultado {
        val doc = documento(xml)
        val raiz = doc.documentElement

        val secciones = hijos(raiz, "section")
        val sobran = ArrayList<Element>()
        var nuevas = 0
        var actualizadas = 0

        var siguiente = existentes.cuentas.size
        val usadas = HashSet<Int>()
        val vistas = HashSet<String>()
        var primera: Int? = null
        for (seccion in ordenadas(secciones, PROXY)) {
            val identidad = valor(seccion, "reg_identity")
            val usuario = usuarioSip(identidad)
            val dominio = hostSip(identidad)
            val proxy = hostSip(valor(seccion, "reg_proxy"))
            if (!vistas.add("$usuario@$dominio")) {
                sobran.add(seccion)
                continue
            }
            val existente = if (usuario.isEmpty()) {
                null
            } else {
                existentes.cuentas.firstOrNull {
                    it.indice !in usadas && it.usuario == usuario &&
                        (it.dominio == dominio || (proxy.isNotEmpty() && it.proxy == proxy))
                }
            }
            val destino = existente?.indice ?: siguiente++
            if (existente != null) actualizadas++ else nuevas++
            usadas.add(destino)
            seccion.setAttribute("name", "proxy_$destino")
            if (primera == null) primera = destino
        }

        // Claves: seguidas desde 0, en el orden del XML (ver la cabecera); sin las repetidas
        var clave = 0
        vistas.clear()
        for (seccion in ordenadas(secciones, AUTH)) {
            val usuario = valor(seccion, "username").lowercase()
            val dominio = valor(seccion, "domain").lowercase()
            val realm = valor(seccion, "realm").lowercase()
            if (!vistas.add("$usuario@$dominio|$realm")) {
                sobran.add(seccion)
                continue
            }
            seccion.setAttribute("name", "auth_info_${clave++}")
            // Si ya había una clave de esa cuenta y la contraseña cambió, el ha1 guardado (el SDK cambia la contraseña por
            // su ha1 tras registrar) ya no vale. Vacío, el SDK lo recalcula con la nueva (lo lee con "" por defecto).
            val yaEstaba = usuario.isNotEmpty() && existentes.claves.any {
                it.usuario == usuario && (it.dominio == dominio || (realm.isNotEmpty() && it.realm == realm))
            }
            if (yaEstaba && tieneEntrada(seccion, "passwd") && !tieneEntrada(seccion, "ha1")) {
                fijar(doc, seccion, "ha1", "")
            }
        }

        for (seccion in sobran) seccion.parentNode.removeChild(seccion)

        val sip = seccion(doc, raiz, secciones, "sip")
        if (existentes.cuentas.isEmpty() && primera != null) {
            fijar(doc, sip, "default_proxy", primera.toString())
        } else {
            quitar(sip, "default_proxy")
        }
        // Que el SDK borre el file:// de la configuración en cuanto lo aplique
        fijar(doc, seccion(doc, raiz, secciones, "misc"), "transient_provisioning", "1")

        val salida = StringWriter()
        val transformador = TransformerFactory.newInstance().newTransformer()
        transformador.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformador.transform(DOMSource(doc), StreamResult(salida))
        return Resultado(salida.toString(), nuevas, actualizadas)
    }

    /** La parte de usuario de una URI SIP («sip:1234@push.mundosms.es» → «1234»), en minúsculas; "" si no hay. */
    fun usuarioSip(uri: String): String {
        val resto = trasEsquema(uri) ?: return ""
        val arroba = resto.indexOf('@')
        if (arroba < 0) return ""
        return resto.substring(0, arroba).trim().lowercase()
    }

    /** El host de una URI SIP («<sip:push.mundosms.es:6060;transport=tcp>» → «push.mundosms.es»), en minúsculas. */
    fun hostSip(uri: String): String {
        val resto = trasEsquema(uri) ?: return ""
        val host = resto.substringAfter('@')
        if (host.startsWith("[")) return host.substringBefore(']').plus("]").lowercase()
        return host.takeWhile { it !in ":;>?" }.trim().lowercase()
    }

    private fun trasEsquema(uri: String): String? {
        val esquema = Regex("sips?:", RegexOption.IGNORE_CASE).find(uri) ?: return null
        return uri.substring(esquema.range.last + 1)
    }

    private fun hijos(padre: Element, etiqueta: String): List<Element> {
        val lista = ArrayList<Element>()
        val nodos = padre.childNodes
        for (i in 0 until nodos.length) {
            val nodo = nodos.item(i)
            if (nodo is Element && nodo.tagName == etiqueta) lista.add(nodo)
        }
        return lista
    }

    private fun ordenadas(secciones: List<Element>, patron: Regex): List<Element> =
        secciones.mapNotNull { s -> patron.matchEntire(s.getAttribute("name"))?.let { it.groupValues[1].toInt() to s } }
            .sortedBy { it.first }
            .map { it.second }

    private fun entrada(seccion: Element, nombre: String): Element? =
        hijos(seccion, "entry").firstOrNull { it.getAttribute("name") == nombre }

    private fun tieneEntrada(seccion: Element, nombre: String): Boolean = entrada(seccion, nombre) != null

    private fun valor(seccion: Element, nombre: String): String = entrada(seccion, nombre)?.textContent.orEmpty().trim()

    private fun fijar(doc: Document, seccion: Element, nombre: String, valor: String) {
        val e = entrada(seccion, nombre) ?: doc.createElement("entry").also {
            it.setAttribute("name", nombre)
            seccion.appendChild(it)
        }
        e.setAttribute("overwrite", "true")
        e.textContent = valor
    }

    private fun quitar(seccion: Element, nombre: String) {
        entrada(seccion, nombre)?.let { seccion.removeChild(it) }
    }

    private fun seccion(doc: Document, raiz: Element, secciones: List<Element>, nombre: String): Element =
        secciones.firstOrNull { it.getAttribute("name") == nombre } ?: doc.createElement("section").also {
            it.setAttribute("name", nombre)
            raiz.appendChild(it)
        }
}
