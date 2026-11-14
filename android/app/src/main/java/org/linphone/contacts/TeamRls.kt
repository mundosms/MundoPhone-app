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
package org.linphone.contacts

import androidx.annotation.AnyThread
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * MundoPhone: Equipo por RLS (RFC 4662) del borde, la parte que no depende del SDK (para probarla en la JVM).
 *
 * Contrato del borde: SUBSCRIBE a `sip:rls@<dominio>` con `Event: presence` y
 * `Supported: eventlist`; la lista son las extensiones de la misma empresa menos la propia, y se rehace en cada SUBSCRIBE
 * y en cada refresco. El NOTIFY es `multipart/related` con un RLMI (`<resource uri>` + `<instance id state cid>`, SIN
 * `<name>`) y un PIDF por instancia activa. El primero y los refrescos llevan fullState="true"; los cambios van parciales,
 * con version+1. El PIDF del borde agrega todos los aparatos de la extensión y su entity puede venir sin «sip:».
 *
 * Nombres: el completo lleva `<name>` en cada `<resource>`, «(111) - <remitente o login>» con el
 * favorito más bajo que apunta a la extensión (sin favorito, solo el nombre); los parciales no lo traen y vale el del
 * último completo. Con un contacto de la agenda manda el nombre de la agenda.
 */
object TeamRls {
    /**
     * SIN_DATO: el borde aún no lo sabe (recurso sin instancia, o solo pendientes). No es DESCONECTADO: el completo inicial
     * sale al aceptar la suscripción, antes de que vuelvan los estados de cada compañero, que llegan en parciales al
     * segundo o dos.
     */
    enum class Estado { DISPONIBLE, SONANDO, EN_LLAMADA, OCUPADO, AUSENTE, DESCONECTADO, SIN_DATO }

    data class Instancia(val id: String, val state: String, val cid: String?)

    data class Recurso(val uri: String, val instancias: List<Instancia>, val nombre: String? = null)

    data class Rlmi(val version: Int, val fullState: Boolean, val recursos: List<Recurso>)

    /**
     * `<ms:call>` del borde: state ringing|dialing|talking (dialing hasta el 200), remote (con quién:
     * extensión o número, sale siempre), dir in|out, start y answer (epoch de empezar y de contestar) y held="1" mientras
     * está en espera.
     */
    data class Llamada(
        val estado: String,
        val remoto: String?,
        val dir: String? = null,
        val inicio: Long? = null,
        val contestada: Long? = null,
        val enEspera: Boolean = false
    )

    data class Pidf(val abierto: Boolean, val actividades: Set<String>, val nota: String?, val llamada: Llamada? = null)

    data class Parte(val cabeceras: Map<String, String>, val cuerpo: String) {
        fun cabecera(nombre: String): String? = cabeceras.entries.firstOrNull {
            it.key.equals(nombre, ignoreCase = true)
        }?.value
    }

    data class Miembro(
        val uri: String,
        val usuario: String,
        val estado: Estado,
        val nota: String?,
        val nombre: String? = null,
        val llamada: Llamada? = null
    )

    /** Lo que se enseña de un compañero en Equipo y en su ficha. */
    enum class Situacion { DISPONIBLE, SONANDO, MARCANDO, EN_LLAMADA, EN_ESPERA, NO_MOLESTAR, AUSENTE, DESCONECTADO }

    /** La situación, con quién (el `remote` del borde, sin resolver) y desde cuándo (epoch de contestar, para el contador). */
    data class Linea(val situacion: Situacion, val con: String? = null, val desde: Long? = null)

    // Con varias llamadas a la vez se enseña la que más pesa
    private val PESO_LLAMADA = mapOf("talking" to 3, "dialing" to 2, "ringing" to 1)

    private val FAVORITO = Regex("""^\(\s*\d+\s*\)\s*-\s*""")

    private val ACTIVIDADES_OCUPADO =
        setOf("busy", "dnd", "do-not-disturb", "meeting", "appointment", "presentation", "performance")
    private val ACTIVIDADES_AUSENTE = setOf(
        "away", "vacation", "holiday", "permanent-absence", "sleeping", "lunch", "meal", "breakfast", "dinner", "travel",
        "in-transit"
    )

    /** El usuario (la extensión) de una URI, en minúsculas: «sip:Ana_mundosms@push.mundosms.es» → «ana_mundosms». */
    @AnyThread
    fun usuario(uri: String): String {
        var resto = uri.trim().removePrefix("<").removeSuffix(">")
        val dosPuntos = resto.indexOf(':')
        if (dosPuntos > 0 && resto.substring(0, dosPuntos).lowercase(Locale.ROOT) in setOf("sip", "sips")) {
            resto = resto.substring(dosPuntos + 1)
        }
        return resto.substringBefore('@').substringBefore(';').lowercase(Locale.ROOT)
    }

    /**
     * «(111) - Ana» → «Ana»: el nombre de la centralita sin su número de favorito, para ordenar alfabéticamente (si no,
     * todos irían delante por el paréntesis y «(20)» detrás de «(111)») y para las iniciales.
     */
    @AnyThread
    fun sinFavorito(nombre: String): String = nombre.replaceFirst(FAVORITO, "").ifEmpty { nombre }

    /**
     * Cuánto esperar para volver a pedir la lista tras un fallo: al menos [minimoMs] y sin pasar de un intento por
     * [intervaloMs] desde el último ([ultimoIntentoMs] 0 = no hubo ninguno).
     */
    fun esperaReintento(ahoraMs: Long, ultimoIntentoMs: Long, minimoMs: Long, intervaloMs: Long): Long {
        if (ultimoIntentoMs == 0L) return minimoMs
        return maxOf(minimoMs, intervaloMs - (ahoraMs - ultimoIntentoMs))
    }

    /** El Content-ID sin los ángulos, para casar el `cid` del RLMI con la cabecera de su parte. */
    @AnyThread
    fun cid(valor: String): String = valor.trim().removePrefix("<").removeSuffix(">").removePrefix("cid:")

    @AnyThread
    fun parseRlmi(xml: String): Rlmi? {
        val raiz = documento(xml)?.documentElement ?: return null
        if (raiz.localName != "list") return null
        val version = raiz.getAttribute("version").trim().toIntOrNull() ?: return null
        val full = raiz.getAttribute("fullState").trim().let { it.equals("true", ignoreCase = true) || it == "1" }
        val recursos = hijos(raiz, "resource").mapNotNull { recurso ->
            val uri = recurso.getAttribute("uri").trim()
            if (uri.isEmpty()) return@mapNotNull null
            val instancias = hijos(recurso, "instance").map { instancia ->
                Instancia(
                    id = instancia.getAttribute("id"),
                    state = instancia.getAttribute("state").trim().lowercase(Locale.ROOT),
                    cid = instancia.getAttribute("cid").takeIf { it.isNotBlank() }?.let { cid(it) }
                )
            }
            val nombre = hijos(recurso, "name").map { it.textContent.trim() }.firstOrNull { it.isNotEmpty() }
            Recurso(uri, instancias, nombre)
        }
        return Rlmi(version, full, recursos)
    }

    /**
     * Un PIDF, también el agregado del borde (varias <tuple>, una por aparato, y un <dm:person> con las actividades de
     * RPID): abierto si alguna tupla lo está; las actividades por su nombre local; la nota de la persona antes que la de
     * las tuplas (el borde pone en la persona «On the phone: <número>» / «Ringing: <número>» y en la tupla solo «On the
     * phone»); y la llamada de `<ms:call>` (namespace urn:mundosms:presence), si viene.
     */
    @AnyThread
    fun parsePidf(xml: String): Pidf? {
        val raiz = documento(xml)?.documentElement ?: return null
        if (raiz.localName != "presence") return null
        val abierto = descendientes(raiz, "basic").any { it.textContent.trim().equals("open", ignoreCase = true) }
        val actividades = descendientes(raiz, "activities")
            .flatMap { hijosElemento(it) }
            .map { it.localName.lowercase(Locale.ROOT) }
            .filter { it != "note" }
            .toSet()
        val notaPersona = descendientes(raiz, "person").flatMap { descendientes(it, "note") }
            .map { it.textContent.trim() }.firstOrNull { it.isNotEmpty() }
        val nota = notaPersona ?: descendientes(raiz, "note").map { it.textContent.trim() }.firstOrNull { it.isNotEmpty() }
        val llamada = descendientes(raiz, "call")
            .filter { it.namespaceURI.orEmpty().contains("mundosms") }
            .map { c ->
                Llamada(
                    estado = c.getAttribute("state").trim().lowercase(Locale.ROOT),
                    remoto = c.getAttribute("remote").trim().takeIf { it.isNotEmpty() },
                    dir = c.getAttribute("dir").trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() },
                    inicio = c.getAttribute("start").trim().toLongOrNull(),
                    contestada = c.getAttribute("answer").trim().toLongOrNull(),
                    enEspera = c.getAttribute("held").trim() == "1"
                )
            }
            .maxByOrNull { PESO_LLAMADA[it.estado] ?: 0 }
        return Pidf(abierto, actividades, nota, llamada)
    }

    @AnyThread
    fun estado(pidf: Pidf?): Estado {
        if (pidf == null || !pidf.abierto) return Estado.DESCONECTADO
        val a = pidf.actividades
        return when {
            pidf.llamada?.estado == "ringing" -> Estado.SONANDO
            // Marcando (hasta el 200) también está al teléfono, lleve o no la actividad on-the-phone
            pidf.llamada?.estado == "talking" || pidf.llamada?.estado == "dialing" -> Estado.EN_LLAMADA
            "on-the-phone" in a -> Estado.EN_LLAMADA
            a.any { it in ACTIVIDADES_OCUPADO } -> Estado.OCUPADO
            a.any { it in ACTIVIDADES_AUSENTE } -> Estado.AUSENTE
            else -> Estado.DISPONIBLE
        }
    }

    /**
     * Qué enseñar de un compañero, o null si el borde aún no lo sabe. El no molestar es el que se pone con *78 en el borde:
     * basic open + rpid:busy; el del panel no pasa por la presencia.
     */
    @AnyThread
    fun linea(m: Miembro): Linea? {
        val ll = m.llamada
        return when (m.estado) {
            Estado.SIN_DATO -> null
            Estado.DESCONECTADO -> Linea(Situacion.DESCONECTADO)
            Estado.DISPONIBLE -> Linea(Situacion.DISPONIBLE)
            Estado.OCUPADO -> Linea(Situacion.NO_MOLESTAR)
            Estado.AUSENTE -> Linea(Situacion.AUSENTE)
            Estado.SONANDO -> Linea(Situacion.SONANDO, ll?.remoto)
            Estado.EN_LLAMADA -> when {
                ll == null -> Linea(Situacion.EN_LLAMADA)
                ll.estado == "dialing" -> Linea(Situacion.MARCANDO, ll.remoto)
                ll.enEspera -> Linea(Situacion.EN_ESPERA, ll.remoto)
                ll.estado == "talking" -> Linea(Situacion.EN_LLAMADA, ll.remoto, ll.contestada ?: ll.inicio)
                else -> Linea(Situacion.EN_LLAMADA, ll.remoto)
            }
        }
    }

    /** «3:12», «1:02:03»: lo que lleva una llamada (nunca negativo, por si los relojes no coinciden). */
    @AnyThread
    fun duracion(segundos: Long): String {
        val s = segundos.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, r) else String.format(Locale.ROOT, "%d:%02d", m, r)
    }

    /**
     * Las partes de un cuerpo multipart en texto, por si el SDK no lo entrega ya troceado. El separador es la primera línea
     * que empieza por «--».
     */
    @AnyThread
    fun partesMultipart(cuerpo: String): List<Parte> {
        val lineas = cuerpo.replace("\r\n", "\n").split('\n')
        val primera = lineas.firstOrNull { it.startsWith("--") } ?: return emptyList()
        val separador = primera.trimEnd()
        val fin = "$separador--"
        val partes = arrayListOf<Parte>()
        var actual: MutableList<String>? = null
        for (linea in lineas) {
            val l = linea.trimEnd()
            if (l == separador || l == fin) {
                actual?.let { partes.add(parte(it)) }
                actual = if (l == fin) null else arrayListOf()
                if (l == fin) break
                continue
            }
            actual?.add(linea)
        }
        return partes
    }

    /** Lo que interesa de un NOTIFY: el RLMI y los PIDF por su Content-ID (sin ángulos). */
    data class Notify(val rlmi: Rlmi?, val pidfPorCid: Map<String, Pidf>)

    /**
     * El RLMI y los PIDF de las partes de un NOTIFY, vengan troceadas por el SDK o por [partesMultipart]. Se mira el
     * Content-Type de cada parte, no su orden ni el `start=` del multipart (el del borde lo lleva y el de Asterisk no).
     */
    @AnyThread
    fun leerNotify(partes: List<Parte>): Notify {
        var rlmi: Rlmi? = null
        val pidfs = hashMapOf<String, Pidf>()
        for (p in partes) {
            val tipo = p.cabecera("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            if (tipo.endsWith("/rlmi+xml")) {
                if (rlmi == null) rlmi = parseRlmi(p.cuerpo)
            } else if (tipo.endsWith("/pidf+xml")) {
                val cid = p.cabecera("Content-ID")
                val pidf = parsePidf(p.cuerpo)
                if (cid != null && pidf != null) pidfs[cid(cid)] = pidf
            }
        }
        return Notify(rlmi, pidfs)
    }

    private fun parte(lineas: List<String>): Parte {
        val cabeceras = linkedMapOf<String, String>()
        var i = 0
        while (i < lineas.size && lineas[i].isNotBlank()) {
            val l = lineas[i]
            val dp = l.indexOf(':')
            if (dp > 0) cabeceras[l.substring(0, dp).trim()] = l.substring(dp + 1).trim()
            i++
        }
        val cuerpo = lineas.drop(i + 1).joinToString("\n").trim()
        return Parte(cabeceras, cuerpo)
    }

    private fun documento(xml: String): Document? {
        return try {
            val fabrica = DocumentBuilderFactory.newInstance()
            fabrica.isNamespaceAware = true
            fabrica.isExpandEntityReferences = false
            // Sin DOCTYPE ni entidades externas (XXE); no todas las implementaciones admiten la característica
            try {
                fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
            fabrica.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    private fun hijosElemento(e: Element): List<Element> {
        val lista = arrayListOf<Element>()
        val nodos = e.childNodes
        for (i in 0 until nodos.length) {
            val n = nodos.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) lista.add(n as Element)
        }
        return lista
    }

    private fun hijos(e: Element, nombreLocal: String): List<Element> = hijosElemento(e).filter {
        it.localName == nombreLocal
    }

    private fun descendientes(e: Element, nombreLocal: String): List<Element> {
        val lista = arrayListOf<Element>()
        val nodos = e.getElementsByTagNameNS("*", nombreLocal)
        for (i in 0 until nodos.length) lista.add(nodos.item(i) as Element)
        return lista
    }
}

/**
 * MundoPhone: la lista de una suscripción RLS, fusionando los NOTIFY completos y parciales por `<resource uri>` y
 * respetando la versión (RFC 4662 §4.4). Sin SDK, para probarlo en la JVM.
 */
class TeamRlsLista {
    enum class Resultado {
        /** Ha cambiado la lista o el estado de alguien */
        CAMBIO,

        /** Nada nuevo */
        SIN_CAMBIO,

        /** Parcial de una versión ya vista: se ignora */
        VIEJO,

        /** Parcial sin haber recibido aún un estado completo, o con hueco de versión: hay que pedir uno completo */
        PIDE_COMPLETO
    }

    var version = -1
        private set

    var completa = false
        private set

    private val miembros = LinkedHashMap<String, TeamRls.Miembro>()

    fun miembros(): List<TeamRls.Miembro> = miembros.values.toList()

    fun contiene(usuario: String): Boolean = miembros.containsKey(usuario.lowercase(Locale.ROOT))

    fun reiniciar() {
        version = -1
        completa = false
        miembros.clear()
    }

    fun aplicar(rlmi: TeamRls.Rlmi, pidfPorCid: Map<String, TeamRls.Pidf>): Resultado {
        if (!rlmi.fullState) {
            if (!completa) return Resultado.PIDE_COMPLETO
            if (rlmi.version <= version) return Resultado.VIEJO
        }
        val hueco = !rlmi.fullState && rlmi.version > version + 1
        val antes = LinkedHashMap(miembros)
        if (rlmi.fullState) miembros.clear()
        for (recurso in rlmi.recursos) {
            val usuario = TeamRls.usuario(recurso.uri)
            // Terminada: la suscripción a ese recurso se acabó (baja de la lista o ya sin autorización): fuera de la lista,
            // no «Desconectado» (igual que la versión web)
            if (recurso.instancias.isNotEmpty() && recurso.instancias.all { it.state == "terminated" }) {
                miembros.remove(usuario)
                continue
            }
            // Activa sin PIDF: presence no tiene ninguna publicación suya (nunca registrado): Desconectado, como con un
            // SUBSCRIBE suelto
            val activa = recurso.instancias.firstOrNull { it.state == "active" && it.cid != null && pidfPorCid.containsKey(it.cid) }
            val pidf = activa?.let { pidfPorCid[it.cid] }
            val sinDato = pidf == null && recurso.instancias.none { it.state == "active" }
            // El nombre solo viene en los completos: en los parciales, el que ya había
            val nombre = recurso.nombre ?: antes[usuario]?.nombre
            miembros[usuario] = if (sinDato) {
                // Lo último que se supo, si se sabía; si no, sin dato (nunca «Desconectado» por no saber)
                antes[usuario]?.copy(uri = recurso.uri, nombre = nombre)
                    ?: TeamRls.Miembro(recurso.uri, usuario, TeamRls.Estado.SIN_DATO, null, nombre)
            } else {
                TeamRls.Miembro(recurso.uri, usuario, TeamRls.estado(pidf), pidf?.nota, nombre, pidf?.llamada)
            }
        }
        version = rlmi.version
        if (rlmi.fullState) completa = true
        if (hueco) return Resultado.PIDE_COMPLETO
        return if (antes != miembros || antes.keys.toList() != miembros.keys.toList()) Resultado.CAMBIO else Resultado.SIN_CAMBIO
    }
}
