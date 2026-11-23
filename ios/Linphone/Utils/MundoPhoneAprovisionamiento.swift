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
import CryptoKit
#if canImport(linphonesw)
import linphonesw
#endif

/// MundoPhone: el QR del panel añade SU cuenta sin tocar las que ya hay y sin mandar nada de ellas.
///
/// El XML del panel numera la cuenta como proxy_0 / auth_info_0. Dado al SDK tal cual, pisaría la primera cuenta de la
/// app. Para evitarlo, hasta la 2.3.x la app mandaba en la URL (jsonaddaccounts) el usuario y el MD5 de la clave de TODAS
/// sus cuentas, y el panel las devolvía en el mismo XML: un MD5 sin sal que en ese punto del panel vale como la clave.
///
/// Ahora la app descarga el XML sin mandar nada y aquí se renumera la cuenta: al índice de esa misma cuenta si ya está (un
/// segundo escaneo la actualiza en vez de duplicarla) o al primer hueco. Después se aplica por el camino del
/// aprovisionamiento remoto del SDK, desde un fichero local (file://): al arrancar el Core y antes de que lea las cuentas.
///
/// Las CLAVES (auth_info_N) no se renumeran: van seguidas desde 0. Al aplicar un aprovisionamiento, el SDK borra TODAS las
/// [auth_info_N] de la configuración (linphone_remote_provisioning_apply, remote_provisioning.c del SDK 5.5.38) y luego
/// las lee desde la 0 hasta el primer hueco; las de las cuentas que ya había no se pierden porque el Core las leyó en
/// memoria al arrancar y las vuelve a escribir todas al llegar a On (Core::writeAuthInfos). Visto en Android el 19/11/2026.
///
/// Es el mismo algoritmo que core/MundoPhoneAprovisionamiento.kt de Android. Sin dependencias del SDK salvo
/// leerExistentes, para poder probarlo con swiftc fuera del proyecto.
enum MundoPhoneAprovisionamiento {
	/// Tope de lo que se descarga: el XML del panel ocupa unos 3 KB.
	static let maxBytes = 64 * 1024

	struct Cuenta {
		let indice: Int
		let usuario: String
		let dominio: String
		let proxy: String
		/// El reg_proxy tal cual, para comparar también puerto y transporte (sinCambios)
		var proxyTal: String = ""
	}

	struct Clave {
		let indice: Int
		let usuario: String
		let dominio: String
		let realm: String
		/// Para saber si un QR trae la MISMA clave (sinCambios): tal cual, sin pasar a minúsculas (el ha1 las distingue)
		var usuarioTal: String = ""
		var realmTal: String = ""
		var passwd: String = ""
		var ha1: String = ""
		var algoritmo: String = ""
	}

	/// Las secciones proxy_N / auth_info_N que hay, seguidas desde 0: el SDK deja de leer en el primer hueco.
	struct Existentes {
		let cuentas: [Cuenta]
		let claves: [Clave]
	}

	struct Resultado {
		let xml: String
		let nuevas: Int
		let actualizadas: Int
	}

	struct XmlNoValido: Error, CustomStringConvertible {
		let description: String
	}

	/// Una petición del XML: con cuerpo, POST (application/x-www-form-urlencoded); sin él, GET.
	struct Peticion {
		let url: String
		let cuerpo: String?
	}

	/// Campos del enlace que no son secretos y van también en la URL, para que consten en el registro (la extensión).
	private static let aLaVista: Set<String> = ["x"]

	/// Los campos del enlace del panel (v, p, c, x, e, k, s: la firma, que con la extensión da su clave mientras no caduca)
	/// en el CUERPO de un POST, que no queda en ningún registro de URLs. En la URL solo `extra` (source, la app y su versión)
	/// y la extensión. El panel junta query y cuerpo y exige que un campo repetido valga lo mismo.
	/// Un enlace sin campos va por GET. Como peticionPost de Android.
	static func peticionPost(enlace: String, extra: [(String, String)]) -> Peticion {
		let sinFragmento = String(enlace.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
		let partes = sinFragmento.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
		let base = String(partes.first ?? "")
		let campos = partes.count > 1 ? paresCrudos(String(partes[1])) : []
		if campos.isEmpty { return peticionGet(enlace: enlace, extra: extra) }
		let vista = campos.filter { aLaVista.contains($0.0) }
		let query = une(extra.map { (codifica($0.0), codifica($0.1)) } + vista)
		return Peticion(url: "\(base)?\(query)", cuerpo: une(campos))
	}

	/// La forma de siempre: todo en la URL.
	static func peticionGet(enlace: String, extra: [(String, String)]) -> Peticion {
		let sinFragmento = String(enlace.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
		let separador = sinFragmento.contains("?") ? "&" : "?"
		return Peticion(url: sinFragmento + separador + une(extra.map { (codifica($0.0), codifica($0.1)) }), cuerpo: nil)
	}

	/// Los pares de la query tal como vienen (sin decodificar: se reenvían igual en el cuerpo, que PHP decodifica igual).
	private static func paresCrudos(_ query: String) -> [(String, String)] {
		return query.split(separator: "&").filter { !$0.isEmpty }.map { par in
			if let igual = par.firstIndex(of: "=") {
				return (String(par[..<igual]), String(par[par.index(after: igual)...]))
			}
			return (String(par), "")
		}
	}

	private static func codifica(_ texto: String) -> String {
		var permitidos = CharacterSet.alphanumerics
		permitidos.insert(charactersIn: "-._~")
		return texto.addingPercentEncoding(withAllowedCharacters: permitidos) ?? ""
	}

	private static func une(_ pares: [(String, String)]) -> String {
		return pares.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
	}

	final class Entrada {
		let nombre: String
		var overwrite: String?
		var valor: String

		init(nombre: String, overwrite: String?, valor: String) {
			self.nombre = nombre
			self.overwrite = overwrite
			self.valor = valor
		}
	}

	final class Seccion {
		var nombre: String
		var entradas: [Entrada] = []

		init(nombre: String) {
			self.nombre = nombre
		}

		func entrada(_ nombre: String) -> Entrada? {
			return entradas.first { $0.nombre == nombre }
		}

		func valor(_ nombre: String) -> String {
			return entrada(nombre)?.valor.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		}

		func fijar(_ nombre: String, _ valor: String) {
			if let e = entrada(nombre) {
				e.overwrite = "true"
				e.valor = valor
			} else {
				entradas.append(Entrada(nombre: nombre, overwrite: "true", valor: valor))
			}
		}

		func quitar(_ nombre: String) {
			entradas.removeAll { $0.nombre == nombre }
		}
	}

	/// El XML con las cuentas renumeradas. Si la app ya tenía cuentas, la de por defecto no cambia (el método viejo la
	/// cambiaba siempre a la primera).
	/// Las secciones del XML del QR, o error. Sin DOCTYPE no hay entidades que resolver; la raíz tiene que ser <config>.
	private static func leerXml(_ xml: String) throws -> [Seccion] {
		if xml.range(of: "<!DOCTYPE", options: .caseInsensitive) != nil || xml.range(of: "<!ENTITY", options: .caseInsensitive) != nil {
			throw XmlNoValido(description: "DOCTYPE not allowed")
		}
		let lector = LectorXml()
		let parser = XMLParser(data: Data(xml.utf8))
		parser.shouldResolveExternalEntities = false
		parser.shouldProcessNamespaces = false
		parser.delegate = lector
		if !parser.parse() || lector.fallo {
			throw XmlNoValido(description: "unparsable XML (\(parser.parserError.map { String(describing: type(of: $0)) } ?? "parser"))")
		}
		guard lector.raiz == "config" else { throw XmlNoValido(description: "root isn't <config>") }
		return lector.secciones
	}

	/// true si el XML trae, tal cual, cuentas que la app ya tiene: misma extensión, mismo proxy (host, puerto y transporte)
	/// y misma contraseña (la guardada en claro o su ha1). Escanear otra vez el mismo QR es lo más habitual, y aplicarlo
	/// obliga a parar y arrancar el Core: en Android, el 21/11/2026, varios reinicios seguidos tumbaron la app dentro del SDK.
	/// Ante cualquier duda, false (se aplica como siempre). Como sinCambios de Android.
	static func sinCambios(xml: String, existentes: Existentes) -> Bool {
		guard let secciones = try? leerXml(xml) else { return false }
		let proxies = ordenadas(secciones, prefijo: "proxy_")
		if proxies.isEmpty { return false }
		for seccion in proxies {
			let identidad = seccion.valor("reg_identity")
			let usuario = usuarioSip(identidad)
			let dominio = hostSip(identidad)
			guard !usuario.isEmpty, let destino = destinoSip(seccion.valor("reg_proxy")) else { return false }
			guard existentes.cuentas.contains(where: {
				$0.usuario == usuario && $0.dominio == dominio && destinoSip($0.proxyTal) == destino
			}) else { return false }
		}
		for seccion in ordenadas(secciones, prefijo: "auth_info_") {
			let usuarioTal = seccion.valor("username")
			let passwd = seccion.entrada("passwd")?.valor ?? ""
			if usuarioTal.isEmpty || passwd.isEmpty { return false }
			let dominio = seccion.valor("domain").lowercased()
			let realm = seccion.valor("realm")
			guard let clave = existentes.claves.first(where: {
				$0.usuario == usuarioTal.lowercased() && ($0.dominio == dominio || (!realm.isEmpty && $0.realm == realm.lowercased()))
			}) else { return false }
			let misma: Bool
			if !clave.passwd.isEmpty {
				misma = clave.passwd == passwd
			} else if !clave.ha1.isEmpty {
				let r = realm.isEmpty ? clave.realmTal : realm
				if r.isEmpty { return false }
				let texto = Data("\(clave.usuarioTal):\(r):\(passwd)".utf8)
				let resumen = clave.algoritmo.caseInsensitiveCompare("SHA-256") == .orderedSame
					? SHA256.hash(data: texto).map { String(format: "%02x", $0) }.joined()
					: Insecure.MD5.hash(data: texto).map { String(format: "%02x", $0) }.joined()
				misma = resumen == clave.ha1
			} else {
				misma = false
			}
			if !misma { return false }
		}
		return true
	}

	/// «host:puerto;transporte» de una URI SIP, con los valores por defecto (5060 y udp; 5061 y tls con sips), o nil.
	static func destinoSip(_ uri: String) -> String? {
		guard let esquema = uri.range(of: "sips?:", options: [.regularExpression, .caseInsensitive]) else { return nil }
		let seguro = uri[esquema].lowercased() == "sips:"
		let host = hostSip(uri)
		if host.isEmpty { return nil }
		let resto = String(uri[esquema.upperBound...].split(separator: ">", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
		let tras = resto.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).last.map(String.init) ?? resto
		let hostPuerto = String(tras.split(whereSeparator: { $0 == ";" || $0 == "?" }).first ?? "").trimmingCharacters(in: .whitespaces)
		var puerto = ""
		if host.hasPrefix("[") {
			if let cierre = hostPuerto.firstIndex(of: "]") {
				puerto = String(hostPuerto[hostPuerto.index(after: cierre)...]).replacingOccurrences(of: ":", with: "")
			}
		} else if let dos = hostPuerto.firstIndex(of: ":") {
			puerto = String(hostPuerto[hostPuerto.index(after: dos)...])
		}
		var transporte = seguro ? "tls" : "udp"
		if let r = resto.range(of: ";transport=([a-zA-Z]+)", options: .regularExpression) {
			transporte = String(resto[r].dropFirst(";transport=".count)).lowercased()
		}
		if puerto.isEmpty { puerto = (seguro || transporte == "tls") ? "5061" : "5060" }
		return "\(host):\(puerto);\(transporte)"
	}

	static func reescribir(xml: String, existentes: Existentes) throws -> Resultado {
		var secciones = try leerXml(xml)
		var sobran = Set<ObjectIdentifier>()
		var nuevas = 0
		var actualizadas = 0

		// Cuentas: a su índice si ya está, si no al primer hueco
		var siguiente = existentes.cuentas.count
		var usadas = Set<Int>()
		var vistas = Set<String>()
		var primera: Int?
		for seccion in ordenadas(secciones, prefijo: "proxy_") {
			let identidad = seccion.valor("reg_identity")
			let usuario = usuarioSip(identidad)
			let dominio = hostSip(identidad)
			let proxy = hostSip(seccion.valor("reg_proxy"))
			if !vistas.insert("\(usuario)@\(dominio)").inserted {
				sobran.insert(ObjectIdentifier(seccion))
				continue
			}
			let existente = usuario.isEmpty ? nil : existentes.cuentas.first {
				!usadas.contains($0.indice) && $0.usuario == usuario &&
					($0.dominio == dominio || (!proxy.isEmpty && $0.proxy == proxy))
			}
			let destino: Int
			if let existente = existente {
				destino = existente.indice
				actualizadas += 1
			} else {
				destino = siguiente
				siguiente += 1
				nuevas += 1
			}
			usadas.insert(destino)
			seccion.nombre = "proxy_\(destino)"
			if primera == nil { primera = destino }
		}

		// Claves: seguidas desde 0, en el orden del XML (ver la cabecera); sin las repetidas
		var clave = 0
		vistas.removeAll()
		for seccion in ordenadas(secciones, prefijo: "auth_info_") {
			let usuario = seccion.valor("username").lowercased()
			let dominio = seccion.valor("domain").lowercased()
			let realm = seccion.valor("realm").lowercased()
			if !vistas.insert("\(usuario)@\(dominio)|\(realm)").inserted {
				sobran.insert(ObjectIdentifier(seccion))
				continue
			}
			seccion.nombre = "auth_info_\(clave)"
			clave += 1
			// Si ya había una clave de esa cuenta y la contraseña cambió, el ha1 guardado (el SDK cambia la contraseña por
			// su ha1 tras registrar) ya no vale. Vacío, el SDK lo recalcula con la nueva (lo lee con "" por defecto).
			let yaEstaba = !usuario.isEmpty && existentes.claves.contains {
				$0.usuario == usuario && ($0.dominio == dominio || (!realm.isEmpty && $0.realm == realm))
			}
			if yaEstaba && seccion.entrada("passwd") != nil && seccion.entrada("ha1") == nil {
				seccion.fijar("ha1", "")
			}
		}

		secciones.removeAll { sobran.contains(ObjectIdentifier($0)) }

		let sip = seccionLlamada("sip", en: &secciones)
		if existentes.cuentas.isEmpty, let primera = primera {
			sip.fijar("default_proxy", String(primera))
		} else {
			sip.quitar("default_proxy")
		}
		// Que el SDK borre el file:// de la configuración en cuanto lo aplique
		seccionLlamada("misc", en: &secciones).fijar("transient_provisioning", "1")

		return Resultado(xml: serializar(secciones), nuevas: nuevas, actualizadas: actualizadas)
	}

	/// La parte de usuario de una URI SIP («sip:1234@push.mundosms.es» → «1234»), en minúsculas; "" si no hay.
	static func usuarioSip(_ uri: String) -> String {
		guard let resto = trasEsquema(uri), let arroba = resto.firstIndex(of: "@") else { return "" }
		return String(resto[..<arroba]).trimmingCharacters(in: .whitespaces).lowercased()
	}

	/// El host de una URI SIP («<sip:push.mundosms.es:6060;transport=tcp>» → «push.mundosms.es»), en minúsculas.
	static func hostSip(_ uri: String) -> String {
		guard let resto = trasEsquema(uri) else { return "" }
		let host: String = resto.firstIndex(of: "@").map { String(resto[resto.index(after: $0)...]) } ?? String(resto)
		if host.hasPrefix("["), let cierre = host.firstIndex(of: "]") {
			return String(host[...cierre]).lowercased()
		}
		return String(host.prefix { !":;>?".contains($0) }).trimmingCharacters(in: .whitespaces).lowercased()
	}

	private static func trasEsquema(_ uri: String) -> Substring? {
		guard let rango = uri.range(of: "sips?:", options: [.regularExpression, .caseInsensitive]) else { return nil }
		return uri[rango.upperBound...]
	}

	private static func ordenadas(_ secciones: [Seccion], prefijo: String) -> [Seccion] {
		return secciones.compactMap { s -> (Int, Seccion)? in
			guard s.nombre.hasPrefix(prefijo), let n = Int(s.nombre.dropFirst(prefijo.count)), n >= 0 else { return nil }
			return (n, s)
		}.sorted { $0.0 < $1.0 }.map { $0.1 }
	}

	private static func seccionLlamada(_ nombre: String, en secciones: inout [Seccion]) -> Seccion {
		if let s = secciones.first(where: { $0.nombre == nombre }) { return s }
		let s = Seccion(nombre: nombre)
		secciones.append(s)
		return s
	}

	static func serializar(_ secciones: [Seccion]) -> String {
		var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config xmlns=\"http://www.linphone.org/xsds/lpconfig.xsd\">\n"
		for s in secciones {
			xml += "\t<section name=\"\(escapar(s.nombre))\">\n"
			for e in s.entradas {
				let ow = e.overwrite.map { " overwrite=\"\(escapar($0))\"" } ?? ""
				xml += "\t\t<entry name=\"\(escapar(e.nombre))\"\(ow)>\(escapar(e.valor))</entry>\n"
			}
			xml += "\t</section>\n"
		}
		return xml + "</config>\n"
	}

	private static func escapar(_ texto: String) -> String {
		return texto.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
			.replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
	}

	/// Lee <config><section name><entry name overwrite>valor</entry></section></config>, como xml2lpc del SDK.
	private final class LectorXml: NSObject, XMLParserDelegate {
		var raiz: String?
		var secciones: [Seccion] = []
		var fallo = false
		private var profundidad = 0
		private var seccion: Seccion?
		private var entrada: Entrada?

		func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
					qualifiedName qName: String?, attributes: [String: String] = [:]) {
			profundidad += 1
			switch profundidad {
			case 1:
				raiz = elementName
			case 2 where elementName == "section":
				let s = Seccion(nombre: attributes["name"] ?? "")
				secciones.append(s)
				seccion = s
			case 3 where elementName == "entry" && seccion != nil:
				if let nombre = attributes["name"] {
					let e = Entrada(nombre: nombre, overwrite: attributes["overwrite"], valor: "")
					seccion?.entradas.append(e)
					entrada = e
				}
			default:
				break
			}
		}

		func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
			if profundidad == 3 { entrada = nil }
			if profundidad == 2 { seccion = nil }
			profundidad -= 1
		}

		func parser(_ parser: XMLParser, foundCharacters string: String) {
			entrada?.valor += string
		}

		func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
			entrada?.valor += String(data: CDATABlock, encoding: .utf8) ?? ""
		}

		func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
			fallo = true
		}
	}
}

#if canImport(linphonesw)
extension MundoPhoneAprovisionamiento {
	static func leerExistentes(config: Config) -> Existentes {
		let secciones = Set(config.sectionsNamesList)
		var cuentas: [Cuenta] = []
		var i = 0
		while secciones.contains("proxy_\(i)") {
			let identidad = config.getString(section: "proxy_\(i)", key: "reg_identity", defaultString: "")
			let proxy = config.getString(section: "proxy_\(i)", key: "reg_proxy", defaultString: "")
			cuentas.append(Cuenta(indice: i, usuario: usuarioSip(identidad), dominio: hostSip(identidad), proxy: hostSip(proxy), proxyTal: proxy))
			i += 1
		}
		var claves: [Clave] = []
		i = 0
		while secciones.contains("auth_info_\(i)") {
			let s = "auth_info_\(i)"
			func leer(_ k: String) -> String {
				return config.getString(section: s, key: k, defaultString: "").trimmingCharacters(in: .whitespaces)
			}
			let usuario = leer("username")
			let realm = leer("realm")
			claves.append(Clave(indice: i, usuario: usuario.lowercased(), dominio: leer("domain").lowercased(), realm: realm.lowercased(),
								usuarioTal: usuario, realmTal: realm,
								passwd: config.getString(section: s, key: "passwd", defaultString: ""),
								ha1: leer("ha1").lowercased(), algoritmo: leer("algorithm")))
			i += 1
		}
		return Existentes(cuentas: cuentas, claves: claves)
	}
}
#endif
