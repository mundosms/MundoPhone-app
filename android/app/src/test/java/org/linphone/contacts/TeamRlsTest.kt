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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Equipo por RLS del borde (TeamRls / TeamRlsLista): lectura del RLMI y del PIDF, partes y fusión de NOTIFY. */
class TeamRlsTest {
    private fun rlmi(version: Int, full: Boolean, recursos: String) = """<?xml version="1.0" encoding="UTF-8"?>
<list xmlns="urn:ietf:params:xml:ns:rlmi" uri="sip:rls@push.mundosms.es" version="$version" fullState="$full">
$recursos
</list>"""

    private val tres = """
  <resource uri="sip:jmartin_mundosms@push.mundosms.es"><instance id="a1" state="active" cid="&lt;c1@push.mundosms.es&gt;"/></resource>
  <resource uri="sip:plopez_mundosms@push.mundosms.es"><instance id="b1" state="active"/></resource>
  <resource uri="sip:nsanchez_mundosms@push.mundosms.es"><instance id="c1" state="active" cid="c3@push.mundosms.es"/></resource>"""

    // Como lo manda el borde: agregado de los aparatos, entity sin «sip:», actividad en dm:person
    private val enLlamada = """<?xml version="1.0" encoding="UTF-8"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" entity="jmartin_mundosms@push.mundosms.es">
 <tuple id="tcall"><status><basic>open</basic></status><contact>sip:jmartin_mundosms@voip.mundosms.es</contact></tuple>
 <tuple id="tapp"><status><basic>closed</basic></status></tuple>
 <dm:person id="pcall"><rpid:activities><rpid:on-the-phone/></rpid:activities><dm:note>En llamada con 600</dm:note></dm:person>
</presence>"""

    private val libre = """<presence xmlns="urn:ietf:params:xml:ns:pidf" entity="sip:nsanchez_mundosms@push.mundosms.es">
 <tuple id="t"><status><basic>open</basic></status></tuple></presence>"""

    private val cerrado = """<presence xmlns="urn:ietf:params:xml:ns:pidf" entity="nsanchez_mundosms@push.mundosms.es">
 <tuple id="t"><status><basic>closed</basic></status></tuple></presence>"""

    private fun pidf(xml: String) = TeamRls.parsePidf(xml)!!

    @Test
    fun `el usuario de una URI, en minúsculas y sin esquema, puerto ni parámetros`() {
        assertEquals("jmartin_mundosms", TeamRls.usuario("sip:Jmartin_mundosms@push.mundosms.es"))
        assertEquals("jmartin_mundosms", TeamRls.usuario("<sips:jmartin_mundosms@push.mundosms.es:6060;transport=tcp>"))
        assertEquals("jmartin_mundosms", TeamRls.usuario("jmartin_mundosms@push.mundosms.es"))
        assertEquals("200", TeamRls.usuario("sip:200;user=phone@push.mundosms.es"))
    }

    @Test
    fun `el RLMI completo con sus recursos e instancias`() {
        val r = TeamRls.parseRlmi(rlmi(0, true, tres))!!
        assertEquals(0, r.version)
        assertTrue(r.fullState)
        assertEquals(3, r.recursos.size)
        assertEquals("c1@push.mundosms.es", r.recursos[0].instancias[0].cid)
        assertEquals("active", r.recursos[1].instancias[0].state)
        assertNull(r.recursos[1].instancias[0].cid)
    }

    @Test
    fun `el PIDF agregado del borde, abierto, en llamada y con su nota`() {
        val p = pidf(enLlamada)
        assertTrue(p.abierto)
        assertTrue("on-the-phone" in p.actividades)
        assertEquals("En llamada con 600", p.nota)
        assertEquals(TeamRls.Estado.EN_LLAMADA, TeamRls.estado(p))
    }

    @Test
    fun `la nota de la persona gana a la de la tupla, y ms call dice si suena o habla`() {
        val sonando = """<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" xmlns:ms="urn:mundosms:presence" entity="plopez_mundosms@push.mundosms.es">
 <tuple id="tcall"><status><basic>open</basic></status><note>Ringing</note></tuple>
 <dm:person id="pcall"><rpid:activities><rpid:on-the-phone/></rpid:activities><dm:note>Ringing: 981000000</dm:note></dm:person>
 <ms:call state="ringing" remote="981000000" start="1761529000"/>
</presence>"""
        val p = pidf(sonando)
        assertEquals("Ringing: 981000000", p.nota)
        assertEquals("981000000", p.llamada?.remoto)
        assertEquals(TeamRls.Estado.SONANDO, TeamRls.estado(p))
        assertEquals(TeamRls.Estado.EN_LLAMADA, TeamRls.estado(p.copy(llamada = TeamRls.Llamada("talking", "981000000"))))
    }

    @Test
    fun `una saliente marcando es en llamada aunque no lleve on-the-phone, y los atributos nuevos no molestan`() {
        val marcando = """<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:ms="urn:mundosms:presence" entity="sip:plopez_mundosms@push.mundosms.es">
 <tuple id="tcall"><status><basic>open</basic></status><ms:call id="x" dir="out" state="dialing" start="1" remote="600"/></tuple></presence>"""
        assertEquals(TeamRls.Estado.EN_LLAMADA, TeamRls.estado(pidf(marcando)))
        val enEspera = marcando.replace("state=\"dialing\"", "state=\"talking\" answer=\"2\" held=\"1\"")
        assertEquals("talking", pidf(enEspera).llamada?.estado)
        assertEquals(TeamRls.Estado.EN_LLAMADA, TeamRls.estado(pidf(enEspera)))
        // Un estado que no conocemos cae a la actividad (aquí ninguna): disponible, sin romper nada
        assertEquals(TeamRls.Estado.DISPONIBLE, TeamRls.estado(pidf(marcando.replace("dialing", "otra-cosa"))))
    }

    @Test
    fun `del PIDF al estado`() {
        assertEquals(TeamRls.Estado.DISPONIBLE, TeamRls.estado(pidf(libre)))
        assertEquals(TeamRls.Estado.DESCONECTADO, TeamRls.estado(pidf(cerrado)))
        assertEquals(TeamRls.Estado.DESCONECTADO, TeamRls.estado(null))
        assertEquals(TeamRls.Estado.OCUPADO, TeamRls.estado(TeamRls.Pidf(true, setOf("busy"), null)))
        assertEquals(TeamRls.Estado.AUSENTE, TeamRls.estado(TeamRls.Pidf(true, setOf("away"), null)))
        assertEquals(TeamRls.Estado.DESCONECTADO, TeamRls.estado(TeamRls.Pidf(false, setOf("on-the-phone"), null)))
    }

    @Test
    fun `XML roto o con DOCTYPE no se lee`() {
        assertNull(TeamRls.parseRlmi("<list version="))
        assertNull(TeamRls.parsePidf("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><presence>&e;</presence>"""))
        assertNull(TeamRls.parseRlmi("""<presence xmlns="urn:ietf:params:xml:ns:pidf"/>"""))
    }

    @Test
    fun `las partes de un multipart en texto y el Content-ID`() {
        val cuerpo = "--b42\r\nContent-ID: <rlmi@x>\r\nContent-Type: application/rlmi+xml\r\n\r\n" + rlmi(0, true, tres) +
            "\r\n--b42\r\nContent-ID: <c1@push.mundosms.es>\r\nContent-Type: application/pidf+xml\r\n\r\n" + enLlamada +
            "\r\n--b42--\r\n"
        val partes = TeamRls.partesMultipart(cuerpo)
        assertEquals(2, partes.size)
        assertEquals("application/rlmi+xml", partes[0].cabecera("content-type"))
        assertEquals("c1@push.mundosms.es", TeamRls.cid(partes[1].cabecera("Content-Id")!!))
        assertEquals(TeamRls.Estado.EN_LLAMADA, TeamRls.estado(TeamRls.parsePidf(partes[1].cuerpo)))
    }

    @Test
    fun `estado completo, todos, también los desconectados, en el orden del borde`() {
        val l = TeamRlsLista()
        val pidfs = mapOf("c1@push.mundosms.es" to pidf(enLlamada), "c3@push.mundosms.es" to pidf(libre))
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(0, true, tres))!!, pidfs))
        val m = l.miembros()
        assertEquals(listOf("jmartin_mundosms", "plopez_mundosms", "nsanchez_mundosms"), m.map { it.usuario })
        assertEquals(TeamRls.Estado.EN_LLAMADA, m[0].estado)
        assertEquals(TeamRls.Estado.DESCONECTADO, m[1].estado)
        assertEquals(TeamRls.Estado.DISPONIBLE, m[2].estado)
        // El mismo estado otra vez (un refresco): nada nuevo
        assertEquals(TeamRlsLista.Resultado.SIN_CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(1, true, tres))!!, pidfs))
    }

    @Test
    fun `parciales, se fusionan por recurso, los viejos se ignoran y un hueco pide estado completo`() {
        val l = TeamRlsLista()
        val c3 = """<resource uri="sip:nsanchez_mundosms@push.mundosms.es"><instance id="c1" state="active" cid="c3@x"/></resource>"""
        // Parcial antes de ningún completo
        assertEquals(TeamRlsLista.Resultado.PIDE_COMPLETO, l.aplicar(TeamRls.parseRlmi(rlmi(5, false, c3))!!, mapOf("c3@x" to pidf(cerrado))))
        l.aplicar(TeamRls.parseRlmi(rlmi(0, true, tres))!!, mapOf("c3@push.mundosms.es" to pidf(libre)))
        // Nsanchez se desconecta (versión 1)
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(1, false, c3))!!, mapOf("c3@x" to pidf(cerrado))))
        assertEquals(TeamRls.Estado.DESCONECTADO, l.miembros().first { it.usuario == "nsanchez_mundosms" }.estado)
        assertEquals(3, l.miembros().size)
        // La misma versión otra vez
        assertEquals(TeamRlsLista.Resultado.VIEJO, l.aplicar(TeamRls.parseRlmi(rlmi(1, false, c3))!!, mapOf("c3@x" to pidf(libre))))
        // Salto de la 1 a la 3: se aplica, pero hay que pedir el completo
        assertEquals(TeamRlsLista.Resultado.PIDE_COMPLETO, l.aplicar(TeamRls.parseRlmi(rlmi(3, false, c3))!!, mapOf("c3@x" to pidf(libre))))
        assertEquals(TeamRls.Estado.DISPONIBLE, l.miembros().first { it.usuario == "nsanchez_mundosms" }.estado)
    }

    @Test
    fun `un estado completo sin alguien lo quita de la lista`() {
        val l = TeamRlsLista()
        l.aplicar(TeamRls.parseRlmi(rlmi(0, true, tres))!!, emptyMap())
        val dos = tres.lines().filterNot { it.contains("plopez") }.joinToString("\n")
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(1, true, dos))!!, emptyMap()))
        assertFalse(l.contiene("plopez_mundosms"))
        assertEquals(2, l.miembros().size)
    }

    // ---- Nombres del borde: <name> solo en el completo, «(111) - nombre», ordenado por favorito ----

    private val conNombres = """
  <resource uri="sip:jmartin_mundosms@push.mundosms.es"><name>(111) - Joaquín Martín &amp; Cía</name><instance id="a1" state="active" cid="c1@x"/></resource>
  <resource uri="sip:nsanchez_mundosms@push.mundosms.es"><name>(120) - Nuria</name></resource>
  <resource uri="sip:plopez_mundosms@push.mundosms.es"><name>Pablo</name></resource>"""

    @Test
    fun `el nombre del recurso, con escape XML y acentos`() {
        val r = TeamRls.parseRlmi(rlmi(1, true, conNombres))!!
        assertEquals("(111) - Joaquín Martín & Cía", r.recursos[0].nombre)
        assertEquals("Pablo", r.recursos[2].nombre)
        // Sin <name> (el borde de antes): null
        assertNull(TeamRls.parseRlmi(rlmi(1, true, tres))!!.recursos[0].nombre)
    }

    @Test
    fun `el nombre del completo se queda en los parciales y el orden es el del completo`() {
        val l = TeamRlsLista()
        l.aplicar(TeamRls.parseRlmi(rlmi(1, true, conNombres))!!, mapOf("c1@x" to pidf(enLlamada)))
        assertEquals(listOf("(111) - Joaquín Martín & Cía", "(120) - Nuria", "Pablo"), l.miembros().map { it.nombre })
        // Parcial sin <name> y en otro orden, con un alta al final: los nombres y el orden se quedan
        val parcial = """
  <resource uri="sip:plopez_mundosms@push.mundosms.es"><instance id="b1" state="active" cid="p@x"/></resource>
  <resource uri="sip:jmartin_mundosms@push.mundosms.es"><instance id="a1" state="active" cid="j@x"/></resource>
  <resource uri="sip:nuevo_mundosms@push.mundosms.es"><instance id="n1" state="active" cid="n@x"/></resource>"""
        assertEquals(
            TeamRlsLista.Resultado.CAMBIO,
            l.aplicar(TeamRls.parseRlmi(rlmi(2, false, parcial))!!, mapOf("p@x" to pidf(libre), "j@x" to pidf(libre), "n@x" to pidf(libre)))
        )
        assertEquals(
            listOf("jmartin_mundosms", "nsanchez_mundosms", "plopez_mundosms", "nuevo_mundosms"),
            l.miembros().map {
            it.usuario
        }
        )
        assertEquals(listOf("(111) - Joaquín Martín & Cía", "(120) - Nuria", "Pablo", null), l.miembros().map { it.nombre })
        assertEquals(TeamRls.Estado.DISPONIBLE, l.miembros().first { it.usuario == "jmartin_mundosms" }.estado)
        // Otro completo con un nombre cambiado: es un cambio
        val renombrado = conNombres.replace("(120) - Nuria", "(105) - Nuria G.")
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(3, true, renombrado))!!, emptyMap()))
        assertEquals("(105) - Nuria G.", l.miembros().first { it.usuario == "nsanchez_mundosms" }.nombre)
    }

    @Test
    fun `sin el número de favorito para ordenar, y orden alfabético por el nombre`() {
        assertEquals("Ana", TeamRls.sinFavorito("(111) - Ana"))
        assertEquals("Bea", TeamRls.sinFavorito("( 20 )-Bea"))
        assertEquals("Ana", TeamRls.sinFavorito("Ana"))
        // Lo que no es el formato del borde no se toca
        assertEquals("(1) Jefe", TeamRls.sinFavorito("(1) Jefe"))
        assertEquals("(111) - ", TeamRls.sinFavorito("(111) - "))
        val collator = java.text.Collator.getInstance(java.util.Locale("es", "ES"))
        val orden = listOf("(111) - Carla", "(20) - Ana", "Bruno").sortedWith { a, b ->
            collator.compare(TeamRls.sinFavorito(a), TeamRls.sinFavorito(b))
        }
        assertEquals(listOf("(20) - Ana", "Bruno", "(111) - Carla"), orden)
    }

    // ---- NOTIFY reales del borde (logins de prueba), en crudo y con cabeceras ----

    /** Cabeceras SIP y cuerpo de un NOTIFY guardado tal cual; el cuerpo tiene que medir lo que dice su Content-Length. */
    private fun notifyReal(nombre: String): Pair<String, String> {
        val bytes = javaClass.getResource("/rls/$nombre")!!.readBytes()
        val texto = String(bytes, Charsets.UTF_8)
        val corte = texto.indexOf("\r\n\r\n")
        val cabeceras = texto.substring(0, corte)
        val cuerpo = texto.substring(corte + 4)
        val longitud = cabeceras.lines().first { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':').trim().toInt()
        assertEquals(longitud, cuerpo.toByteArray(Charsets.UTF_8).size)
        return cabeceras to cuerpo
    }

    @Test
    fun `NOTIFY real completo, multipart con start= y solo el RLMI`() {
        val (cabeceras, cuerpo) = notifyReal("rls-presence-fullstate.sip")
        assertTrue(cabeceras.contains("From: <sip:rls@"))
        assertTrue(cabeceras.contains("start=\"<"))
        val partes = TeamRls.partesMultipart(cuerpo)
        assertEquals(1, partes.size)
        val n = TeamRls.leerNotify(partes)
        val rlmi = n.rlmi!!
        assertEquals(1, rlmi.version)
        assertTrue(rlmi.fullState)
        assertEquals(10, rlmi.recursos.size)
        assertTrue(n.pidfPorCid.isEmpty())

        val l = TeamRlsLista()
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(rlmi, n.pidfPorCid))
        assertEquals(10, l.miembros().size)
        assertTrue(l.contiene("rem_xan"))
        // La propia extensión no viene en la lista
        assertFalse(l.contiene("mundokamailio1"))
        // Sin instancias el borde aún no lo sabe (los estados llegan en los parciales): sin dato, no «Desconectado»
        assertTrue(l.miembros().all { it.estado == TeamRls.Estado.SIN_DATO })
    }

    @Test
    fun `NOTIFY real parcial, un compañero en llamada con otro que suena`() {
        val l = TeamRlsLista()
        val (_, completo) = notifyReal("rls-presence-fullstate.sip")
        val inicial = TeamRls.leerNotify(TeamRls.partesMultipart(completo))
        l.aplicar(inicial.rlmi!!, inicial.pidfPorCid)

        val (_, cuerpo) = notifyReal("rls-presence-parcial.sip")
        val partes = TeamRls.partesMultipart(cuerpo)
        assertEquals(3, partes.size)
        val n = TeamRls.leerNotify(partes)
        val rlmi = n.rlmi!!
        assertEquals(3, rlmi.version)
        assertFalse(rlmi.fullState)
        // Cada instancia del RLMI casa con el Content-ID de su PIDF
        val cids = rlmi.recursos.flatMap { r -> r.instancias.mapNotNull { it.cid } }.toSet()
        assertEquals(cids, n.pidfPorCid.keys)

        // El borde guardó la versión 1 y la 3: con la 2 de por medio se fusiona sin pedir nada
        assertEquals(TeamRlsLista.Resultado.SIN_CAMBIO, l.aplicar(TeamRls.Rlmi(2, false, emptyList()), emptyMap()))
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(rlmi, n.pidfPorCid))
        assertEquals(10, l.miembros().size)
        val habla = l.miembros().first { it.usuario == "mundokamailio2" }
        assertEquals(TeamRls.Estado.EN_LLAMADA, habla.estado)
        assertEquals("On the phone: mundokamailio6", habla.nota)
        // Actividad on-the-phone, pero la llamada aún suena: manda <ms:call state="ringing">
        val suena = l.miembros().first { it.usuario == "mundokamailio6" }
        assertEquals(TeamRls.Estado.SONANDO, suena.estado)
        assertEquals("Ringing: mundokamailio2", suena.nota)
        assertEquals(TeamRls.Estado.SIN_DATO, l.miembros().first { it.usuario == "rem_xan" }.estado)
    }

    @Test
    fun `sin instancia es sin dato y no borra lo último que se supo, sin PIDF es desconectado, terminada se va`() {
        val l = TeamRlsLista()
        val recursos = """
  <resource uri="sip:a@x"/>
  <resource uri="sip:b@x"><instance id="1" state="pending"/></resource>
  <resource uri="sip:c@x"><instance id="1" state="terminated" reason="noresource"/></resource>
  <resource uri="sip:d@x"><instance id="1" state="active"/></resource>
  <resource uri="sip:e@x"><instance id="1" state="active" cid="e@x"/></resource>"""
        l.aplicar(TeamRls.parseRlmi(rlmi(1, true, recursos))!!, mapOf("e@x" to pidf(libre)))
        val estado = { u: String -> l.miembros().first { it.usuario == u }.estado }
        assertEquals(TeamRls.Estado.SIN_DATO, estado("a"))
        assertEquals(TeamRls.Estado.SIN_DATO, estado("b"))
        assertFalse(l.contiene("c"))
        assertEquals(TeamRls.Estado.DESCONECTADO, estado("d"))
        assertEquals(TeamRls.Estado.DISPONIBLE, estado("e"))
        // Llega el estado de «a» y luego un completo que aún no lo sabe: se queda el último que se supo
        val a = """<resource uri="sip:a@x"><instance id="1" state="active" cid="a@x"/></resource>"""
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(2, false, a))!!, mapOf("a@x" to pidf(enLlamada))))
        assertEquals(TeamRls.Estado.EN_LLAMADA, estado("a"))
        l.aplicar(TeamRls.parseRlmi(rlmi(3, true, recursos))!!, mapOf("e@x" to pidf(libre)))
        assertEquals(TeamRls.Estado.EN_LLAMADA, estado("a"))
        assertEquals(TeamRls.Estado.SIN_DATO, estado("b"))
        // Una baja en un parcial: terminada, y sale de la lista
        val baja = """<resource uri="sip:e@x"><instance id="1" state="terminated" reason="deactivated"/></resource>"""
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(TeamRls.parseRlmi(rlmi(4, false, baja))!!, emptyMap()))
        assertFalse(l.contiene("e"))
        assertEquals(listOf("a", "b", "d"), l.miembros().map { it.usuario })
    }

    @Test
    fun `NOTIFY real parcial tras perder uno, se aplica y pide el estado completo`() {
        val l = TeamRlsLista()
        val (_, completo) = notifyReal("rls-presence-fullstate.sip")
        val inicial = TeamRls.leerNotify(TeamRls.partesMultipart(completo))
        l.aplicar(inicial.rlmi!!, inicial.pidfPorCid)
        val (_, cuerpo) = notifyReal("rls-presence-parcial.sip")
        val n = TeamRls.leerNotify(TeamRls.partesMultipart(cuerpo))
        // De la 1 a la 3
        assertEquals(TeamRlsLista.Resultado.PIDE_COMPLETO, l.aplicar(n.rlmi!!, n.pidfPorCid))
        assertEquals(TeamRls.Estado.SONANDO, l.miembros().first { it.usuario == "mundokamailio6" }.estado)
    }

    @Test
    fun `NOTIFY real con nombres, con escapes, y el parcial sin nombres los conserva en el orden del completo`() {
        val l = TeamRlsLista()
        val (_, completo) = notifyReal("rls-nombres-nombres-presence-fullstate-v1.sip")
        val n = TeamRls.leerNotify(TeamRls.partesMultipart(completo))
        l.aplicar(n.rlmi!!, n.pidfPorCid)
        val nombres = l.miembros().associate { it.usuario to it.nombre }
        assertEquals("(105) - Iván", nombres["mundokamailio2"])
        assertEquals("(111) - Recepción & Caja", nombres["mundokamailio6"])
        assertEquals("Ana O'Neill", nombres["rem_ana"])
        assertEquals("Ohid <\"jefe\">", nombres["rem_ohid"])
        // El que no lleva <name>: sin nombre (la app enseña la extensión)
        assertNull(nombres["rem_yall"])
        assertEquals(10, l.miembros().size)
        assertEquals(listOf("mundokamailio2", "mundokamailio6", "aiagent_lab"), l.miembros().take(3).map { it.usuario })

        // El parcial (version 2) viene sin <name> y en otro orden (6 antes que 2)
        val (_, cuerpo) = notifyReal("rls-nombres-nombres-presence-parcial-01-v2.sip")
        val p = TeamRls.leerNotify(TeamRls.partesMultipart(cuerpo))
        assertTrue(p.rlmi!!.recursos.all { it.nombre == null })
        assertEquals(TeamRlsLista.Resultado.CAMBIO, l.aplicar(p.rlmi!!, p.pidfPorCid))
        assertEquals("(105) - Iván", l.miembros().first { it.usuario == "mundokamailio2" }.nombre)
        assertEquals("(111) - Recepción & Caja", l.miembros().first { it.usuario == "mundokamailio6" }.nombre)
        assertEquals(TeamRls.Estado.DISPONIBLE, l.miembros().first { it.usuario == "mundokamailio6" }.estado)
        assertEquals(listOf("mundokamailio2", "mundokamailio6", "aiagent_lab"), l.miembros().take(3).map { it.usuario })
    }

    @Test
    fun `NOTIFY real completo con la vista de antes, sin nombres`() {
        val (_, completo) = notifyReal("rls-nombres-sin-presence-fullstate-v1.sip")
        val n = TeamRls.leerNotify(TeamRls.partesMultipart(completo))
        val l = TeamRlsLista()
        l.aplicar(n.rlmi!!, n.pidfPorCid)
        assertEquals(10, l.miembros().size)
        assertTrue(l.miembros().all { it.nombre == null })
    }

    // ---- Línea de estado: qué se enseña, con quién y desde cuándo ----

    private fun miembroDe(pidf: TeamRls.Pidf?, estado: TeamRls.Estado = TeamRls.estado(pidf)) =
        TeamRls.Miembro("sip:mundokamailio1@x", "mundokamailio1", estado, pidf?.nota, null, pidf?.llamada)

    private fun lineaReal(nombre: String): TeamRls.Linea? {
        val (cabeceras, cuerpo) = notifyReal(nombre)
        assertTrue(cabeceras.contains("application/pidf+xml"))
        return TeamRls.linea(miembroDe(TeamRls.parsePidf(cuerpo)!!))
    }

    @Test
    fun `NOTIFY reales de ms call, marcando, hablando desde que contestó, en espera y de vuelta`() {
        assertEquals(TeamRls.Linea(TeamRls.Situacion.MARCANDO, "mundokamailio2"), lineaReal("mscall-dialing.sip"))
        // talking: el contador desde answer (start + 4 s en la captura), no desde start
        assertEquals(TeamRls.Linea(TeamRls.Situacion.EN_LLAMADA, "mundokamailio2", 1793069074), lineaReal("mscall-talking-answer.sip"))
        assertEquals(TeamRls.Linea(TeamRls.Situacion.EN_ESPERA, "mundokamailio2"), lineaReal("mscall-held.sip"))
        assertEquals(TeamRls.Linea(TeamRls.Situacion.EN_LLAMADA, "mundokamailio2", 1793069085), lineaReal("mscall-sin-held.sip"))
        val (_, cuerpo) = notifyReal("mscall-talking-answer.sip")
        val llamada = TeamRls.parsePidf(cuerpo)!!.llamada!!
        assertEquals("out", llamada.dir)
        assertEquals(1793069070L, llamada.inicio)
        assertFalse(llamada.enEspera)
    }

    @Test
    fun `NOTIFY real de la lista, sonando con quién`() {
        val (_, cuerpo) = notifyReal("rls-presence-parcial.sip")
        val n = TeamRls.leerNotify(TeamRls.partesMultipart(cuerpo))
        val suena = n.pidfPorCid.entries.first { it.key.contains("mundokamailio6") }.value
        assertEquals(TeamRls.Linea(TeamRls.Situacion.SONANDO, "mundokamailio2"), TeamRls.linea(miembroDe(suena)))
    }

    @Test
    fun `no molestar del borde, sin dato, desconectado, y con varias llamadas manda la que habla`() {
        // *78 en el borde: basic open + rpid:busy + «Do Not Disturb»
        val dnd =
            pidf(
                """<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" entity="sip:a@x">
 <tuple id="t"><status><basic>open</basic></status><note>DND</note></tuple>
 <dm:person id="p"><rpid:activities><rpid:busy/></rpid:activities><dm:note>Do Not Disturb</dm:note></dm:person></presence>"""
            )
        assertEquals(TeamRls.Linea(TeamRls.Situacion.NO_MOLESTAR), TeamRls.linea(miembroDe(dnd)))
        assertNull(TeamRls.linea(miembroDe(null, TeamRls.Estado.SIN_DATO)))
        assertEquals(TeamRls.Linea(TeamRls.Situacion.DESCONECTADO), TeamRls.linea(miembroDe(pidf(cerrado))))
        assertEquals(TeamRls.Linea(TeamRls.Situacion.DISPONIBLE), TeamRls.linea(miembroDe(pidf(libre))))
        // Al teléfono sin ms:call (un borde sin <ms:call>): en llamada, sin con quién
        assertEquals(TeamRls.Linea(TeamRls.Situacion.EN_LLAMADA), TeamRls.linea(miembroDe(pidf(enLlamada))))
        val dos =
            pidf(
                """<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:ms="urn:mundosms:presence" entity="sip:a@x">
 <tuple id="t"><status><basic>open</basic></status>
 <ms:call id="1" dir="in" state="ringing" start="5" remote="700"/>
 <ms:call id="2" dir="out" state="talking" start="1" answer="2" remote="600"/></tuple></presence>"""
            )
        assertEquals(TeamRls.Linea(TeamRls.Situacion.EN_LLAMADA, "600", 2), TeamRls.linea(miembroDe(dos)))
    }

    @Test
    fun `la duración de la llamada`() {
        assertEquals("0:00", TeamRls.duracion(0))
        assertEquals("3:12", TeamRls.duracion(192))
        assertEquals("1:02:03", TeamRls.duracion(3723))
        // Relojes desfasados: nunca negativo
        assertEquals("0:00", TeamRls.duracion(-5))
    }

    @Test
    fun `las partes como las trocea el SDK, por Content-Type y sin depender del orden`() {
        val (_, cuerpo) = notifyReal("rls-presence-parcial.sip")
        val delTexto = TeamRls.partesMultipart(cuerpo)
        // Lo que monta onNotify con las partes del SDK: tipo/subtipo y el Content-ID tal cual, con ángulos
        val delSdk = delTexto.reversed().map { p ->
            val cabeceras = linkedMapOf("Content-Type" to p.cabecera("Content-Type")!!.substringBefore(';'))
            p.cabecera("Content-ID")?.let { cabeceras["Content-ID"] = it }
            TeamRls.Parte(cabeceras, p.cuerpo)
        }
        assertEquals(TeamRls.leerNotify(delTexto), TeamRls.leerNotify(delSdk))
    }

    @Test
    fun `tras un fallo se vuelve a pedir la lista, como mucho una vez por minuto`() {
        // Sin intento previo: lo mínimo
        assertEquals(5_000L, TeamRls.esperaReintento(100_000L, 0L, 5_000L, 60_000L))
        // El último intento fue hace 2 s: falta casi un minuto
        assertEquals(58_000L, TeamRls.esperaReintento(102_000L, 100_000L, 5_000L, 60_000L))
        // Hace más de un minuto: lo mínimo
        assertEquals(5_000L, TeamRls.esperaReintento(200_000L, 100_000L, 5_000L, 60_000L))
        // Le faltan 3 s al minuto: no menos de lo mínimo
        assertEquals(5_000L, TeamRls.esperaReintento(157_000L, 100_000L, 5_000L, 60_000L))
    }
}
