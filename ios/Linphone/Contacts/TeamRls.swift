/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone (fork of linphone-iphone).
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

import Foundation

/**
 * MundoPhone: Equipo por RLS (RFC 4662) del borde, la parte que no depende del SDK. Es el mismo código que
 * `TeamRls.kt` de la app Android (mismo contrato, mismas reglas, mismos nombres), para que las dos apps enseñen lo
 * mismo.
 *
 * Contrato del borde: SUBSCRIBE a `sip:rls@<dominio>` con `Event: presence` y
 * `Supported: eventlist`; la lista son las extensiones de la misma empresa menos la propia. El NOTIFY es
 * `multipart/related` con un RLMI (`<resource uri>` + `<instance id state cid>`) y un PIDF por instancia activa. El primero
 * y los refrescos llevan fullState="true"; los cambios van parciales, con version+1. El PIDF del borde agrega todos los
 * aparatos de la extensión y su entity puede venir sin «sip:».
 *
 * Nombres: el completo lleva `<name>` en cada `<resource>`, «(111) - <remitente o login>»; los parciales
 * no lo traen y vale el del último completo. Con un contacto de la agenda manda el nombre de la agenda.
 */
enum TeamRls {
	/**
	 * sinDato: el borde aún no lo sabe (recurso sin instancia, o solo pendientes). No es desconectado: el completo inicial
	 * sale al aceptar la suscripción, antes de que vuelvan los estados de cada compañero, que llegan en parciales.
	 */
	enum Estado: Equatable {
		case disponible, sonando, enLlamada, ocupado, ausente, desconectado, sinDato
	}

	struct Instancia: Equatable {
		let id: String
		let state: String
		let cid: String?
	}

	struct Recurso: Equatable {
		let uri: String
		let instancias: [Instancia]
		var nombre: String?
	}

	struct Rlmi: Equatable {
		let version: Int
		let fullState: Bool
		let recursos: [Recurso]
	}

	/**
	 * `<ms:call>` del borde: state ringing|dialing|talking (dialing hasta el 200), remote (con quién),
	 * dir in|out, start y answer (epoch de empezar y de contestar) y held="1" mientras está en espera.
	 */
	struct Llamada: Equatable {
		let estado: String
		let remoto: String?
		var dir: String?
		var inicio: Int64?
		var contestada: Int64?
		var enEspera: Bool = false
	}

	struct Pidf: Equatable {
		let abierto: Bool
		let actividades: Set<String>
		let nota: String?
		var llamada: Llamada?
	}

	struct Parte {
		let cabeceras: [(String, String)]
		let cuerpo: String

		func cabecera(_ nombre: String) -> String? {
			return cabeceras.first { $0.0.caseInsensitiveCompare(nombre) == .orderedSame }?.1
		}
	}

	struct Miembro: Equatable {
		var uri: String
		let usuario: String
		let estado: Estado
		let nota: String?
		var nombre: String?
		var llamada: Llamada?
	}

	/** Lo que se enseña de un compañero en Equipo y en su ficha. */
	enum Situacion: Equatable {
		case disponible, sonando, marcando, enLlamada, enEspera, noMolestar, ausente, desconectado
	}

	/** La situación, con quién (el `remote` del borde, sin resolver) y desde cuándo (epoch de contestar, para el contador). */
	struct Linea: Equatable {
		let situacion: Situacion
		var con: String?
		var desde: Int64?
	}

	/** Lo que interesa de un NOTIFY: el RLMI y los PIDF por su Content-ID (sin ángulos). */
	struct Notify {
		let rlmi: Rlmi?
		let pidfPorCid: [String: Pidf]
	}

	// Con varias llamadas a la vez se enseña la que más pesa
	private static let pesoLlamada: [String: Int] = ["talking": 3, "dialing": 2, "ringing": 1]

	private static let actividadesOcupado: Set<String> = ["busy", "dnd", "do-not-disturb", "meeting", "appointment", "presentation", "performance"]
	private static let actividadesAusente: Set<String> = [
		"away", "vacation", "holiday", "permanent-absence", "sleeping", "lunch", "meal", "breakfast", "dinner", "travel", "in-transit"
	]

	/** El usuario (la extensión) de una URI, en minúsculas: «sip:Jortiz_mundosms@push.mundosms.es» → «jortiz_mundosms». */
	static func usuario(_ uri: String) -> String {
		var resto = uri.trimmingCharacters(in: .whitespacesAndNewlines).quitandoPrefijo("<").quitandoSufijo(">")
		if let dosPuntos = resto.firstIndex(of: ":"), dosPuntos > resto.startIndex,
		   ["sip", "sips"].contains(resto[..<dosPuntos].lowercased()) {
			resto = String(resto[resto.index(after: dosPuntos)...])
		}
		let sinDominio = resto.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? resto
		let sinParametros = sinDominio.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? sinDominio
		return sinParametros.lowercased()
	}

	/**
	 * «(111) - Ana» → «Ana»: el nombre de la centralita sin su número de favorito, para ordenar alfabéticamente (si no,
	 * todos irían delante por el paréntesis y «(20)» detrás de «(111)») y para las iniciales.
	 */
	static func sinFavorito(_ nombre: String) -> String {
		guard let rango = nombre.range(of: #"^\(\s*\d+\s*\)\s*-\s*"#, options: .regularExpression) else { return nombre }
		let resto = String(nombre[rango.upperBound...])
		return resto.isEmpty ? nombre : resto
	}

	/**
	 * Cuánto esperar para volver a pedir la lista tras un fallo: al menos `minimo` y sin pasar de un intento por
	 * `intervalo` desde el último (`ultimoIntento` nil = no hubo ninguno). En segundos.
	 */
	static func esperaReintento(ahora: TimeInterval, ultimoIntento: TimeInterval?, minimo: TimeInterval, intervalo: TimeInterval) -> TimeInterval {
		guard let ultimo = ultimoIntento else { return minimo }
		return max(minimo, intervalo - (ahora - ultimo))
	}

	/** El Content-ID sin los ángulos, para casar el `cid` del RLMI con la cabecera de su parte. */
	static func cid(_ valor: String) -> String {
		return valor.trimmingCharacters(in: .whitespacesAndNewlines).quitandoPrefijo("<").quitandoSufijo(">").quitandoPrefijo("cid:")
	}

	static func parseRlmi(_ xml: String) -> Rlmi? {
		guard let raiz = LectorXml.leer(xml), raiz.nombre == "list" else { return nil }
		guard let version = Int(raiz.atributo("version").trimmingCharacters(in: .whitespaces)) else { return nil }
		let full = raiz.atributo("fullState").trimmingCharacters(in: .whitespaces)
		let fullState = full.caseInsensitiveCompare("true") == .orderedSame || full == "1"
		let recursos: [Recurso] = raiz.hijos("resource").compactMap { recurso in
			let uri = recurso.atributo("uri").trimmingCharacters(in: .whitespaces)
			if uri.isEmpty { return nil }
			let instancias = recurso.hijos("instance").map { instancia -> Instancia in
				let c = instancia.atributo("cid").trimmingCharacters(in: .whitespaces)
				return Instancia(
					id: instancia.atributo("id"),
					state: instancia.atributo("state").trimmingCharacters(in: .whitespaces).lowercased(),
					cid: c.isEmpty ? nil : cid(c)
				)
			}
			let nombre = recurso.hijos("name").map { $0.contenido.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
			return Recurso(uri: uri, instancias: instancias, nombre: nombre)
		}
		return Rlmi(version: version, fullState: fullState, recursos: recursos)
	}

	/**
	 * Un PIDF, también el agregado del borde (varias <tuple>, una por aparato, y un <dm:person> con las actividades de
	 * RPID): abierto si alguna tupla lo está; las actividades por su nombre local; la nota de la persona antes que la de
	 * las tuplas; y la llamada de `<ms:call>` (namespace urn:mundosms:presence), si viene.
	 */
	static func parsePidf(_ xml: String) -> Pidf? {
		guard let raiz = LectorXml.leer(xml), raiz.nombre == "presence" else { return nil }
		let abierto = raiz.descendientes("basic").contains {
			$0.contenido.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare("open") == .orderedSame
		}
		let actividades = Set(raiz.descendientes("activities").flatMap { $0.hijos }.map { $0.nombre.lowercased() }.filter { $0 != "note" })
		let notaPersona = raiz.descendientes("person").flatMap { $0.descendientes("note") }
			.map { $0.contenido.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
		let nota = notaPersona ?? raiz.descendientes("note").map { $0.contenido.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
		let llamada = raiz.descendientes("call")
			.filter { $0.ns.contains("mundosms") }
			.map { c -> Llamada in
				let remoto = c.atributo("remote").trimmingCharacters(in: .whitespaces)
				let dir = c.atributo("dir").trimmingCharacters(in: .whitespaces).lowercased()
				return Llamada(
					estado: c.atributo("state").trimmingCharacters(in: .whitespaces).lowercased(),
					remoto: remoto.isEmpty ? nil : remoto,
					dir: dir.isEmpty ? nil : dir,
					inicio: Int64(c.atributo("start").trimmingCharacters(in: .whitespaces)),
					contestada: Int64(c.atributo("answer").trimmingCharacters(in: .whitespaces)),
					enEspera: c.atributo("held").trimmingCharacters(in: .whitespaces) == "1"
				)
			}
			.max { (pesoLlamada[$0.estado] ?? 0) < (pesoLlamada[$1.estado] ?? 0) }
		return Pidf(abierto: abierto, actividades: actividades, nota: nota, llamada: llamada)
	}

	static func estado(_ pidf: Pidf?) -> Estado {
		guard let pidf = pidf, pidf.abierto else { return .desconectado }
		let a = pidf.actividades
		if pidf.llamada?.estado == "ringing" { return .sonando }
		// Marcando (hasta el 200) también está al teléfono, lleve o no la actividad on-the-phone
		if pidf.llamada?.estado == "talking" || pidf.llamada?.estado == "dialing" { return .enLlamada }
		if a.contains("on-the-phone") { return .enLlamada }
		if !a.isDisjoint(with: actividadesOcupado) { return .ocupado }
		if !a.isDisjoint(with: actividadesAusente) { return .ausente }
		return .disponible
	}

	/**
	 * Qué enseñar de un compañero, o nil si el borde aún no lo sabe. El no molestar es el que se pone con *78 en el borde:
	 * basic open + rpid:busy; el del panel no pasa por la presencia.
	 */
	static func linea(_ m: Miembro) -> Linea? {
		let ll = m.llamada
		switch m.estado {
		case .sinDato: return nil
		case .desconectado: return Linea(situacion: .desconectado)
		case .disponible: return Linea(situacion: .disponible)
		case .ocupado: return Linea(situacion: .noMolestar)
		case .ausente: return Linea(situacion: .ausente)
		case .sonando: return Linea(situacion: .sonando, con: ll?.remoto)
		case .enLlamada:
			guard let ll = ll else { return Linea(situacion: .enLlamada) }
			if ll.estado == "dialing" { return Linea(situacion: .marcando, con: ll.remoto) }
			if ll.enEspera { return Linea(situacion: .enEspera, con: ll.remoto) }
			if ll.estado == "talking" { return Linea(situacion: .enLlamada, con: ll.remoto, desde: ll.contestada ?? ll.inicio) }
			return Linea(situacion: .enLlamada, con: ll.remoto)
		}
	}

	/** «3:12», «1:02:03»: lo que lleva una llamada (nunca negativo, por si los relojes no coinciden). */
	static func duracion(_ segundos: Int64) -> String {
		let s = max(0, segundos)
		let h = s / 3600
		let m = (s % 3600) / 60
		let r = s % 60
		return h > 0 ? String(format: "%lld:%02lld:%02lld", h, m, r) : String(format: "%lld:%02lld", m, r)
	}

	/**
	 * La extensión (usuario SIP, en minúsculas) si la dirección es del equipo: `sip:`/`sips:` con un usuario que no es un
	 * número (un «+» delante como mucho y el resto cifras es un teléfono o una extensión numérica). El criterio de
	 * `Team.kt` de Android, para casar a un contacto con su compañero de la lista.
	 */
	static func usuarioEquipo(_ uri: String) -> String? {
		let limpio = uri.trimmingCharacters(in: .whitespacesAndNewlines).quitandoPrefijo("<").lowercased()
		guard limpio.hasPrefix("sip:") || limpio.hasPrefix("sips:"), limpio.contains("@") else { return nil }
		let u = usuario(uri)
		let cifras = u.quitandoPrefijo("+")
		if u.isEmpty || (!cifras.isEmpty && cifras.allSatisfy { ("0"..."9").contains($0) }) { return nil }
		return u
	}

	/** Los textos de la línea en español: lo que sale si el idioma del móvil no los trae (los nombres son los de Android). */
	static let textosEs: [String: String] = [
		"team_status_available": "Disponible",
		"team_status_ringing": "Sonando",
		"team_status_ringing_with": "Sonando · %@",
		"team_status_dialing": "Marcando",
		"team_status_dialing_with": "Marcando · %@",
		"team_status_in_call": "En llamada",
		"team_status_in_call_with": "En llamada con %@",
		"team_status_on_hold": "En espera",
		"team_status_on_hold_with": "En espera con %@",
		"team_status_dnd": "No molestar",
		"team_status_away": "Ausente",
		"team_status_offline": "Desconectado"
	]

	/**
	 * La línea de estado de Equipo, como `refreshTeamStatus` de Android: «En llamada con Ana · 3:12». `con` es el nombre ya
	 * resuelto (agenda, centralita o el número tal cual); `ahora` en epoch, para el contador de una llamada contestada.
	 */
	static func texto(_ l: Linea, con: String?, ahora: Int64, traducir: (String) -> String = { textosEs[$0] ?? $0 }) -> String {
		func conQuien(_ sin: String, _ conClave: String) -> String {
			guard let quien = con?.trimmingCharacters(in: .whitespacesAndNewlines), !quien.isEmpty else { return traducir(sin) }
			return String(format: traducir(conClave), quien)
		}
		let base: String
		switch l.situacion {
		case .disponible: base = traducir("team_status_available")
		case .sonando: base = conQuien("team_status_ringing", "team_status_ringing_with")
		case .marcando: base = conQuien("team_status_dialing", "team_status_dialing_with")
		case .enLlamada: base = conQuien("team_status_in_call", "team_status_in_call_with")
		case .enEspera: base = conQuien("team_status_on_hold", "team_status_on_hold_with")
		case .noMolestar: base = traducir("team_status_dnd")
		case .ausente: base = traducir("team_status_away")
		case .desconectado: base = traducir("team_status_offline")
		}
		guard let desde = l.desde else { return base }
		return base + " · " + duracion(ahora - desde)
	}

	/** El color de la línea, como Android: verde libre, naranja en espera/no molestar/ausente, rojo el resto. */
	enum Tono: Equatable { case verde, naranja, rojo }

	static func tono(_ s: Situacion) -> Tono {
		switch s {
		case .disponible: return .verde
		case .enEspera, .noMolestar, .ausente: return .naranja
		case .sonando, .marcando, .enLlamada, .desconectado: return .rojo
		}
	}

	/**
	 * Las partes de un cuerpo multipart en texto, por si el SDK no lo entrega ya troceado. El separador es la primera línea
	 * que empieza por «--».
	 */
	static func partesMultipart(_ cuerpo: String) -> [Parte] {
		let lineas = cuerpo.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
		guard let primera = lineas.first(where: { $0.hasPrefix("--") }) else { return [] }
		let separador = primera.recortandoFinal()
		let fin = separador + "--"
		var partes: [Parte] = []
		var actual: [String]?
		for linea in lineas {
			let l = linea.recortandoFinal()
			if l == separador || l == fin {
				if let a = actual { partes.append(parte(a)) }
				if l == fin { actual = nil; break }
				actual = []
				continue
			}
			actual?.append(linea)
		}
		return partes
	}

	/**
	 * El RLMI y los PIDF de las partes de un NOTIFY, vengan troceadas por el SDK o por `partesMultipart`. Se mira el
	 * Content-Type de cada parte, no su orden ni el `start=` del multipart.
	 */
	static func leerNotify(_ partes: [Parte]) -> Notify {
		var rlmi: Rlmi?
		var pidfs: [String: Pidf] = [:]
		for p in partes {
			let tipo = (p.cabecera("Content-Type") ?? "").split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first
				.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
			if tipo.hasSuffix("/rlmi+xml") {
				if rlmi == nil { rlmi = parseRlmi(p.cuerpo) }
			} else if tipo.hasSuffix("/pidf+xml") {
				if let c = p.cabecera("Content-ID"), let pidf = parsePidf(p.cuerpo) { pidfs[cid(c)] = pidf }
			}
		}
		return Notify(rlmi: rlmi, pidfPorCid: pidfs)
	}

	private static func parte(_ lineas: [String]) -> Parte {
		var cabeceras: [(String, String)] = []
		var i = 0
		while i < lineas.count && !lineas[i].trimmingCharacters(in: .whitespaces).isEmpty {
			let l = lineas[i]
			if let dp = l.firstIndex(of: ":"), dp > l.startIndex {
				cabeceras.append((l[..<dp].trimmingCharacters(in: .whitespaces), l[l.index(after: dp)...].trimmingCharacters(in: .whitespaces)))
			}
			i += 1
		}
		let cuerpo = lineas.dropFirst(i + 1).joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
		return Parte(cabeceras: cabeceras, cuerpo: cuerpo)
	}
}

/**
 * MundoPhone: la lista de una suscripción RLS, fusionando los NOTIFY completos y parciales por `<resource uri>` y
 * respetando la versión (RFC 4662 §4.4). Sin SDK, para probarlo en tests.
 */
final class TeamRlsLista {
	enum Resultado: Equatable {
		/** Ha cambiado la lista o el estado de alguien */
		case cambio
		/** Nada nuevo */
		case sinCambio
		/** Parcial de una versión ya vista: se ignora */
		case viejo
		/** Parcial sin haber recibido aún un estado completo, o con hueco de versión: hay que pedir uno completo */
		case pideCompleto
	}

	private(set) var version = -1
	private(set) var completa = false
	private var orden: [String] = []
	private var porUsuario: [String: TeamRls.Miembro] = [:]

	var miembros: [TeamRls.Miembro] { orden.compactMap { porUsuario[$0] } }

	func contiene(_ usuario: String) -> Bool { porUsuario[usuario.lowercased()] != nil }

	func reiniciar() {
		version = -1
		completa = false
		orden = []
		porUsuario = [:]
	}

	func aplicar(_ rlmi: TeamRls.Rlmi, pidfPorCid: [String: TeamRls.Pidf]) -> Resultado {
		if !rlmi.fullState {
			if !completa { return .pideCompleto }
			if rlmi.version <= version { return .viejo }
		}
		let hueco = !rlmi.fullState && rlmi.version > version + 1
		let antesOrden = orden
		let antes = porUsuario
		if rlmi.fullState {
			orden = []
			porUsuario = [:]
		}
		for recurso in rlmi.recursos {
			let usuario = TeamRls.usuario(recurso.uri)
			// Terminada: la suscripción a ese recurso se acabó (baja de la lista o ya sin autorización): fuera de la lista,
			// no «Desconectado»
			if !recurso.instancias.isEmpty && recurso.instancias.allSatisfy({ $0.state == "terminated" }) {
				porUsuario[usuario] = nil
				orden.removeAll { $0 == usuario }
				continue
			}
			// Activa sin PIDF: presence no tiene ninguna publicación suya (nunca registrado): Desconectado
			let activa = recurso.instancias.first { $0.state == "active" && $0.cid != nil && pidfPorCid[$0.cid!] != nil }
			let pidf = activa.flatMap { pidfPorCid[$0.cid!] }
			let sinDato = pidf == nil && !recurso.instancias.contains { $0.state == "active" }
			// El nombre solo viene en los completos: en los parciales, el que ya había
			let nombre = recurso.nombre ?? antes[usuario]?.nombre
			let miembro: TeamRls.Miembro
			if sinDato {
				// Lo último que se supo, si se sabía; si no, sin dato (nunca «Desconectado» por no saber)
				if var previo = antes[usuario] {
					previo.uri = recurso.uri
					previo.nombre = nombre
					miembro = previo
				} else {
					miembro = TeamRls.Miembro(uri: recurso.uri, usuario: usuario, estado: .sinDato, nota: nil, nombre: nombre, llamada: nil)
				}
			} else {
				miembro = TeamRls.Miembro(uri: recurso.uri, usuario: usuario, estado: TeamRls.estado(pidf), nota: pidf?.nota, nombre: nombre, llamada: pidf?.llamada)
			}
			if porUsuario[usuario] == nil { orden.append(usuario) }
			porUsuario[usuario] = miembro
		}
		version = rlmi.version
		if rlmi.fullState { completa = true }
		if hueco { return .pideCompleto }
		return (antes != porUsuario || antesOrden != orden) ? .cambio : .sinCambio
	}
}

// MARK: - XML mínimo (iOS no trae un DOM; XMLParser con espacios de nombres y sin entidades externas)

final class NodoXml {
	let nombre: String
	let ns: String
	let atributos: [String: String]
	var hijos: [NodoXml] = []
	var texto = ""

	init(nombre: String, ns: String, atributos: [String: String]) {
		self.nombre = nombre
		self.ns = ns
		self.atributos = atributos
	}

	func atributo(_ nombre: String) -> String { atributos[nombre] ?? "" }

	func hijos(_ nombreLocal: String) -> [NodoXml] { hijos.filter { $0.nombre == nombreLocal } }

	func descendientes(_ nombreLocal: String) -> [NodoXml] {
		var lista: [NodoXml] = []
		for h in hijos {
			if h.nombre == nombreLocal { lista.append(h) }
			lista.append(contentsOf: h.descendientes(nombreLocal))
		}
		return lista
	}

	/** Todo el texto del nodo y sus descendientes (el textContent del DOM). */
	var contenido: String { texto + hijos.map { $0.contenido }.joined() }
}

final class LectorXml: NSObject, XMLParserDelegate {
	private var pila: [NodoXml] = []
	private var raiz: NodoXml?

	static func leer(_ xml: String) -> NodoXml? {
		// Sin DOCTYPE (ni entidades, ni XXE): el borde nunca lo manda, y así se rechaza como en Android
		guard xml.range(of: "<!DOCTYPE", options: .caseInsensitive) == nil else { return nil }
		guard let datos = xml.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8) else { return nil }
		let parser = XMLParser(data: datos)
		parser.shouldProcessNamespaces = true
		parser.shouldReportNamespacePrefixes = false
		parser.shouldResolveExternalEntities = false
		let lector = LectorXml()
		parser.delegate = lector
		guard parser.parse() else { return nil }
		return lector.raiz
	}

	func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?,
				attributes attributeDict: [String: String] = [:]) {
		let nodo = NodoXml(nombre: elementName, ns: namespaceURI ?? "", atributos: attributeDict)
		if let padre = pila.last { padre.hijos.append(nodo) } else { raiz = nodo }
		pila.append(nodo)
	}

	func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
		_ = pila.popLast()
	}

	func parser(_ parser: XMLParser, foundCharacters string: String) {
		pila.last?.texto += string
	}
}

private extension String {
	func quitandoPrefijo(_ p: String) -> String { hasPrefix(p) ? String(dropFirst(p.count)) : self }
	func quitandoSufijo(_ s: String) -> String { hasSuffix(s) ? String(dropLast(s.count)) : self }
	func recortandoFinal() -> String {
		var s = self
		while let ultimo = s.last, ultimo == " " || ultimo == "\t" || ultimo == "\r" { s.removeLast() }
		return s
	}
}
