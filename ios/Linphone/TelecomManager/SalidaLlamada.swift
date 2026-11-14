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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import Foundation
import SwiftUI
import Intents
import linphonesw
import LiveCommunicationKit

/**
 * MundoPhone: los números de teléfono que llegan desde fuera de la app. Es la redirección de Android
 * hecha con lo que deja iOS:
 *
 * - Desde iOS 18.2, si el usuario elige MundoPhone como app de llamadas por defecto (Ajustes > Apps > Apps por defecto >
 *   Llamadas; entitlement `com.apple.developer.calling-app`), los números de la ficha de Contactos y los enlaces `tel:`
 *   llegan aquí, como actividad de llamada o como URL. El teclado de la app Teléfono sigue saliendo por la SIM: iOS no
 *   deja tocarlo.
 * - Con cada número: códigos de operadora (`*`, `#`) y números con pausas o letras, por la SIM; si el número tiene una
 *   elección guardada («Recordar para este número»), esa; si no, el ajuste general (Preguntar / Siempre / Nunca). Igual
 *   que `CallRedirection` de Android, con los mismos nombres de ajuste.
 * - Salir por la SIM: desde iOS 26 con LiveCommunicationKit (`TelephonyConversationManager`, entitlement
 *   `com.apple.developer.dialing-app`). Si el usuario eligió MundoPhone también como app de marcar (en la UE), iOS la
 *   lanza sin preguntar; si no, el sistema pide confirmarla. Antes de iOS 26 Apple solo deja abrir `telephony:` tras un
 *   toque del usuario, así que «siempre por la SIM» enseña el número con el botón de la línea móvil.
 * - Apple dice que esa llamada móvil se «encamina a la app de llamadas por defecto», que la atiende o la «devuelve al
 *   sistema como llamada móvil». Si esa app somos nosotros, vuelve aquí: la marca de un solo uso (como SimRelaunchMark
 *   de Android, 10 s) la reconoce y la pasa a `telephony:`, que el sistema trata como llamada móvil. Sin ella,
 *   «siempre por la SIM» daría vueltas. Se mira ANTES del filtro de repetidas, que si no se la tragaría.
 * - Lo que trae una dirección SIP (las llamadas de MundoPhone en Recientes) o viene de devolver una llamada de Recientes
 *   sale por MundoPhone sin preguntar: el usuario ya eligió la app al tocar esa fila.
 *
 * No hay API para saber si somos la app de llamadas por defecto (`UIApplication.isDefault` solo cubre navegadores):
 * el ofrecimiento sale una vez y el ajuste lleva a la pantalla de iOS.
 *
 * Todo en el hilo principal salvo lo marcado. Los números no van enteros al registro (`ocultar`).
 */
final class SalidaLlamada: ObservableObject {
	static let shared = SalidaLlamada()

	static let modoPreguntar = "ask"
	static let modoSiempre = "always"
	static let modoNunca = "never"

	private static let claveModo = "mundosms_contactos_modo"
	private static let claveNumeros = "mundosms_contactos_numeros"
	private static let claveOferta = "mundosms_contactos_oferta_vista"
	private static let tag = "[MundoPhone SalidaLlamada]"

	struct Peticion: Identifiable, Equatable {
		let id = UUID()
		let numero: String
		/// El número ya está decidido por la SIM y hace falta el toque del usuario (antes de iOS 26, o iOS no la lanzó)
		let soloSim: Bool
		/// Con soloSim: ¿se puede ofrecer MundoPhone «esta vez»? No con códigos de operadora, números con pausas o sin
		/// cuenta, que van por la SIM porque MundoPhone no podría llamarlos
		var conMundoPhone = true
	}

	@Published var peticion: Peticion?
	@Published var mostrarOferta = false
	@Published private(set) var modo: String
	@Published private(set) var numeroDeElecciones: Int

	/// La dirección que espera a que arranque el Core (la app venía cerrada)
	private var pendiente: String?
	/// La última petición que llegó de fuera: el mismo número dos veces en 3 s (la URL y la actividad del mismo toque,
	/// o un doble toque) se atiende una vez; si no, «siempre por la SIM» lanzaría dos llamadas
	private var ultimaEntrada: (texto: String, cuando: Date)?
	/// El número que acabamos de mandar a la red móvil con LiveCommunicationKit, por si el sistema nos lo devuelve
	private var marcaSim: (numero: String, hasta: Date)?
	private static let duracionMarcaSim: TimeInterval = 10

	private init() {
		let defaults = UserDefaults.standard
		let guardado = defaults.string(forKey: SalidaLlamada.claveModo) ?? ""
		modo = [SalidaLlamada.modoSiempre, SalidaLlamada.modoNunca].contains(guardado) ? guardado : SalidaLlamada.modoPreguntar
		numeroDeElecciones = SalidaNumeros.leerReglas(defaults.string(forKey: SalidaLlamada.claveNumeros) ?? "").count
	}

	// MARK: - Entradas

	/// Una URL `tel:`/`callto:` (la que manda iOS a la app de llamadas por defecto). Devuelve false si no es de esas.
	func recibirURL(_ url: URL) -> Bool {
		guard let esquema = url.scheme?.lowercased(), esquema == "tel" || esquema == "callto" else {
			return false
		}
		var texto = String(url.absoluteString.dropFirst(esquema.count + 1))
		if texto.hasPrefix("//") {
			texto = String(texto.dropFirst(2))
		}
		texto = texto.removingPercentEncoding ?? texto
		Log.info("\(SalidaLlamada.tag) \(esquema): URL received for [\(SalidaNumeros.ocultar(texto))]")
		if esDevolucionDeLaSim(texto) {
			return true
		}
		if esRepetida(texto, origen: esquema) {
			return true
		}
		decidir(texto, origen: esquema)
		return true
	}

	/// La actividad de llamada de la ficha de Contactos, de un enlace, de Siri o de Recientes.
	func recibirActividad(_ actividad: NSUserActivity) {
		guard let texto = SalidaLlamada.direccionDeActividad(actividad) else {
			Log.warn("\(SalidaLlamada.tag) Call activity [\(actividad.activityType)] without address")
			return
		}
		let origen = actividad.activityType
		if esDevolucionDeLaSim(texto) {
			return
		}
		if esRepetida(texto, origen: origen) {
			return
		}
		if SalidaLlamada.esDevolucionDeRecientes(actividad) {
			Log.info("\(SalidaLlamada.tag) [\(origen)] call back from Recents for [\(SalidaNumeros.ocultar(texto))]: MundoPhone")
			llamarConMundoPhone(texto, origen: origen)
			return
		}
		Log.info("\(SalidaLlamada.tag) [\(origen)] call activity for [\(SalidaNumeros.ocultar(texto))]")
		decidir(texto, origen: origen)
	}

	/// ¿Es la llamada móvil que acabamos de lanzar y que el sistema nos devuelve como app de llamadas? Entonces va a
	/// telephony: (la marca vale una vez). Una marca caducada se borra.
	private func esDevolucionDeLaSim(_ texto: String) -> Bool {
		guard let marca = marcaSim else { return false }
		if Date() > marca.hasta {
			marcaSim = nil
			return false
		}
		guard let limpio = SalidaNumeros.limpiar(texto), SalidaNumeros.mismoNumero(limpio, marca.numero) else {
			return false
		}
		marcaSim = nil
		Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(marca.numero))] cellular call handed back to us: telephony URL")
		SalidaLlamada.abrirTelefono(marca.numero)
		return true
	}

	private func esRepetida(_ texto: String, origen: String) -> Bool {
		let ahora = Date()
		if let anterior = ultimaEntrada, anterior.texto == texto, ahora.timeIntervalSince(anterior.cuando) < 3 {
			Log.info("\(SalidaLlamada.tag) Duplicate request from [\(origen)] ignored")
			return true
		}
		ultimaEntrada = (texto, ahora)
		return false
	}

	/// MundoPhone o SIM para un número que llega de fuera.
	func decidir(_ texto: String, origen: String) {
		guard let limpio = SalidaNumeros.limpiar(texto) else {
			Log.warn("\(SalidaLlamada.tag) [\(origen)] ignored: [\(SalidaNumeros.ocultar(texto))] is not an address")
			return
		}
		if SalidaNumeros.esDireccionSip(limpio) {
			llamarConMundoPhone(limpio, origen: origen)
			return
		}
		if SalidaNumeros.esCodigoOperadora(limpio) || SalidaNumeros.marcable(limpio) == nil {
			Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(limpio))] operator code or not a plain number: SIM")
			simSinPreguntar(limpio, conMundoPhone: false)
			return
		}
		let numero = SalidaNumeros.marcable(limpio) ?? limpio
		let core = CoreContext.shared
		if core.coreIsStarted && core.accounts.isEmpty {
			Log.info("\(SalidaLlamada.tag) No account in MundoPhone: [\(SalidaNumeros.ocultar(numero))] by SIM")
			simSinPreguntar(numero, conMundoPhone: false)
			return
		}
		switch modoPara(numero) {
		case SalidaLlamada.modoSiempre:
			llamarConMundoPhone(numero, origen: origen)
		case SalidaLlamada.modoNunca:
			simSinPreguntar(numero)
		default:
			Log.info("\(SalidaLlamada.tag) Asking MundoPhone or SIM for [\(SalidaNumeros.ocultar(numero))]")
			peticion = Peticion(numero: numero, soloSim: false)
		}
	}

	// MARK: - El diálogo

	func elegirMundoPhone(_ peticion: Peticion, recordarTodos: Bool, recordarNumero: Bool) {
		Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(peticion.numero))] MundoPhone (all [\(recordarTodos)], number [\(recordarNumero)])")
		recordar(peticion.numero, regla: SalidaNumeros.reglaApp, todos: recordarTodos, soloEste: recordarNumero)
		self.peticion = nil
		llamarConMundoPhone(peticion.numero, origen: "dialog")
	}

	func elegirSim(_ peticion: Peticion, recordarTodos: Bool, recordarNumero: Bool) {
		Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(peticion.numero))] SIM (all [\(recordarTodos)], number [\(recordarNumero)])")
		recordar(peticion.numero, regla: SalidaNumeros.reglaSim, todos: recordarTodos, soloEste: recordarNumero)
		self.peticion = nil
		simTrasToque(peticion.numero)
	}

	func cancelar() {
		if let actual = peticion {
			Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(actual.numero))] dialog dismissed, no call")
		}
		peticion = nil
	}

	private func recordar(_ numero: String, regla: String, todos: Bool, soloEste: Bool) {
		if soloEste {
			guardarRegla(numero, regla: regla)
		} else if todos {
			cambiarModo(regla == SalidaNumeros.reglaApp ? SalidaLlamada.modoSiempre : SalidaLlamada.modoNunca)
		}
	}

	// MARK: - Ajustes

	func cambiarModo(_ nuevo: String) {
		modo = nuevo
		UserDefaults.standard.set(nuevo, forKey: SalidaLlamada.claveModo)
		Log.info("\(SalidaLlamada.tag) Mode saved: [\(nuevo)]")
	}

	/// La clave de texto del modo, para el ajuste
	var claveTextoModo: String {
		switch modo {
		case SalidaLlamada.modoSiempre: return "mundosms_contactos_modo_siempre"
		case SalidaLlamada.modoNunca: return "mundosms_contactos_modo_nunca"
		default: return "mundosms_contactos_modo_preguntar"
		}
	}

	func olvidarElecciones() {
		Log.info("\(SalidaLlamada.tag) Forgetting [\(numeroDeElecciones)] per-number choices")
		UserDefaults.standard.removeObject(forKey: SalidaLlamada.claveNumeros)
		numeroDeElecciones = 0
	}

	private func reglas() -> [(String, String)] {
		SalidaNumeros.leerReglas(UserDefaults.standard.string(forKey: SalidaLlamada.claveNumeros) ?? "")
	}

	private func guardarRegla(_ numero: String, regla: String?) {
		let nuevas = SalidaNumeros.conRegla(reglas(), numero: numero, regla: regla)
		UserDefaults.standard.set(SalidaNumeros.escribirReglas(nuevas), forKey: SalidaLlamada.claveNumeros)
		numeroDeElecciones = nuevas.count
		Log.info("\(SalidaLlamada.tag) Choice for [\(SalidaNumeros.ocultar(numero))]: [\(regla ?? "ask as the general setting")]")
	}

	/// El modo para un número: su elección guardada o el ajuste general
	private func modoPara(_ numero: String) -> String {
		switch SalidaNumeros.regla(reglas(), para: numero) {
		case SalidaNumeros.reglaApp: return SalidaLlamada.modoSiempre
		case SalidaNumeros.reglaSim: return SalidaLlamada.modoNunca
		default: return modo
		}
	}

	/// Abre la pantalla de iOS donde se elige la app de llamadas (iOS 18.3+) o, si no, los ajustes de la app
	static func abrirAjustesDeAppsPorDefecto() {
		var texto = UIApplication.openSettingsURLString
		if #available(iOS 18.3, *) {
			texto = UIApplication.openDefaultApplicationsSettingsURLString
		}
		if let url = URL(string: texto) {
			UIApplication.shared.open(url)
		}
	}

	// MARK: - Ofrecimiento (una vez por instalación, iOS 18.2+)

	/// Con la pantalla principal ya en marcha (hay cuenta)
	func ofrecerSiToca() {
		guard #available(iOS 18.2, *) else { return }
		guard !UserDefaults.standard.bool(forKey: SalidaLlamada.claveOferta) else { return }
		DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
			guard self.peticion == nil, !CoreContext.shared.accounts.isEmpty else { return }
			Log.info("\(SalidaLlamada.tag) Offering MundoPhone as the default calling app")
			self.mostrarOferta = true
		}
	}

	func cerrarOferta(abrirAjustes: Bool) {
		UserDefaults.standard.set(true, forKey: SalidaLlamada.claveOferta)
		mostrarOferta = false
		Log.info("\(SalidaLlamada.tag) Default calling app offer: [\(abrirAjustes ? "open settings" : "not now")]")
		if abrirAjustes {
			SalidaLlamada.abrirAjustesDeAppsPorDefecto()
		}
	}

	// MARK: - Por MundoPhone

	/// Llama con la cuenta por defecto y las mismas reglas que una llamada marcada a mano. Si el Core no ha arrancado,
	/// espera: CoreContext llama a lanzarPendiente() al ponerlo en marcha.
	func llamarConMundoPhone(_ texto: String, origen: String) {
		Log.info("\(SalidaLlamada.tag) MundoPhone call requested from [\(origen)]")
		if CoreContext.shared.coreIsStarted {
			lanzar(texto)
		} else {
			Log.info("\(SalidaLlamada.tag) Waiting for the core to start to call [\(SalidaNumeros.ocultar(texto))]")
			pendiente = texto
		}
	}

	/// En el hilo principal, con coreIsStarted ya puesto
	func lanzarPendiente() {
		guard let texto = pendiente else { return }
		pendiente = nil
		lanzar(texto)
	}

	private func lanzar(_ texto: String) {
		CoreContext.shared.doOnCoreQueue { core in
			guard core.defaultAccount != nil else {
				Log.warn("\(SalidaLlamada.tag) No default account, call to [\(SalidaNumeros.ocultar(texto))] dropped")
				return
			}
			guard let address = core.interpretUrl(url: texto, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) else {
				Log.error("\(SalidaLlamada.tag) Unable to call [\(SalidaNumeros.ocultar(texto))]")
				DispatchQueue.main.async {
					ToastViewModel.shared.toastMessage = "Failed_uri_handler_bad_call_address"
					ToastViewModel.shared.displayToast = true
				}
				return
			}
			Log.info("\(SalidaLlamada.tag) Calling with MundoPhone")
			TelecomManager.shared.doCallOrJoinConf(address: address)
		}
	}

	// MARK: - Por la SIM

	/// Sin toque del usuario: desde iOS 26, LiveCommunicationKit (sin preguntar si somos la app de marcar; si no, el
	/// sistema pide confirmarla). Antes, o si iOS no la lanza, el número con el botón de la línea móvil.
	private func simSinPreguntar(_ numero: String, conMundoPhone: Bool = true) {
		if #available(iOS 26.0, *) {
			Task { @MainActor in
				do {
					self.marcaSim = (numero, Date().addingTimeInterval(SalidaLlamada.duracionMarcaSim))
					try await SalidaLlamada.redMovil(numero)
					Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(numero))] handed to the cellular network")
				} catch {
					self.marcaSim = nil
					Log.warn("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(numero))] cellular call not started (\(error)): asking")
					self.peticion = Peticion(numero: numero, soloSim: true, conMundoPhone: conMundoPhone)
				}
			}
		} else {
			peticion = Peticion(numero: numero, soloSim: true, conMundoPhone: conMundoPhone)
		}
	}

	/// Tras un toque del usuario en la app: LiveCommunicationKit desde iOS 26 y, si no, `telephony:`
	private func simTrasToque(_ numero: String) {
		if #available(iOS 26.0, *) {
			Task { @MainActor in
				do {
					self.marcaSim = (numero, Date().addingTimeInterval(SalidaLlamada.duracionMarcaSim))
					try await SalidaLlamada.redMovil(numero)
					Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(numero))] handed to the cellular network")
				} catch {
					self.marcaSim = nil
					Log.warn("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(numero))] cellular call not started (\(error)): telephony URL")
					SalidaLlamada.abrirTelefono(numero)
				}
			}
		} else {
			SalidaLlamada.abrirTelefono(numero)
		}
	}

	@available(iOS 26.0, *)
	private static func redMovil(_ numero: String) async throws {
		let accion = StartCellularConversationAction(LiveCommunicationKit.Handle(type: .phoneNumber, value: numero))
		try await TelephonyConversationManager.sharedInstance.startCellularConversation(accion)
	}

	/// `telephony:` lleva a la línea móvil aunque la app de llamadas por defecto sea otra (iOS 18.2+). Antes de iOS 18.2
	/// los números no llegan aquí, pero por si acaso `tel:` (entonces es la app Teléfono).
	private static func abrirTelefono(_ numero: String) {
		var componentes = URLComponents()
		componentes.scheme = "telephony"
		componentes.path = numero
		var url = componentes.url
		if #unavailable(iOS 18.2) {
			url = URL(string: "tel:" + numero)
		}
		guard let destino = url else {
			Log.error("\(SalidaLlamada.tag) No cellular URL for [\(SalidaNumeros.ocultar(numero))]")
			return
		}
		UIApplication.shared.open(destino) { abierta in
			Log.info("\(SalidaLlamada.tag) [\(SalidaNumeros.ocultar(numero))] \(destino.scheme ?? ""): URL opened [\(abierta)]")
		}
	}

	// MARK: - Actividades

	/// La dirección que trae una actividad de llamada. La ficha y Siri mandan INStartCallIntent; Recientes, a veces,
	/// el INStartAudioCallIntent de antes de iOS 13.
	static func direccionDeActividad(_ actividad: NSUserActivity) -> String? {
		let intent = actividad.interaction?.intent
		var personas: [INPerson]?
		if let llamada = intent as? INStartCallIntent {
			personas = llamada.contacts
		} else if let audio = intent as? INStartAudioCallIntent {
			personas = audio.contacts
		} else if let video = intent as? INStartVideoCallIntent {
			personas = video.contacts
		}
		if let valor = personas?.first?.personHandle?.value, !valor.isEmpty {
			return valor
		}
		return actividad.userInfo?["number"] as? String
	}

	/// ¿Viene de tocar una llamada de MundoPhone en Recientes? Los tipos de antes de iOS 13 solo los manda Recientes; el
	/// nuevo lo dice con callRecordToCallBack o el tipo de destino.
	static func esDevolucionDeRecientes(_ actividad: NSUserActivity) -> Bool {
		let intent = actividad.interaction?.intent
		if intent is INStartAudioCallIntent || intent is INStartVideoCallIntent {
			return true
		}
		if let llamada = intent as? INStartCallIntent {
			return llamada.callRecordToCallBack != nil || llamada.destinationType == .callBack
				|| llamada.destinationType == .redial
		}
		return false
	}
}

/// MundoPhone: los números, como `RedirectionNumbers` de Android (mismas reglas y mismo formato guardado).
enum SalidaNumeros {
	static let reglaSim = "sim"
	static let reglaApp = "app"

	/// Caracteres invisibles de dirección de texto que traen a veces los números copiados en la agenda
	private static let invisibles: Set<UInt32> = [0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D,
												 0x202E, 0x2060, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF]

	/// Para el registro: nunca el número entero; los separadores y los dos últimos dígitos («+xxxxxxxxx56»)
	static func ocultar(_ numero: String) -> String {
		let cifras = numero.filter { $0.isASCII && $0.isNumber }.count
		var vistas = 0
		var resultado = ""
		for caracter in numero {
			if caracter.isASCII && caracter.isNumber {
				vistas += 1
				resultado.append(vistas > cifras - 2 ? caracter : "x")
			} else if caracter == "@" {
				return resultado + "@…"
			} else {
				resultado.append(caracter)
			}
		}
		return resultado
	}

	/// El texto sin invisibles, sin espacios alrededor y sin «tel:» delante; nil si no tiene ni una cifra ni «@» (el
	/// «Desconocido» de una llamada por push que acabó en Recientes)
	static func limpiar(_ texto: String) -> String? {
		var limpio = String(String.UnicodeScalarView(texto.unicodeScalars.filter { !invisibles.contains($0.value) }))
		limpio = limpio.trimmingCharacters(in: .whitespacesAndNewlines)
		if limpio.lowercased().hasPrefix("tel:") {
			limpio = String(limpio.dropFirst(4)).trimmingCharacters(in: .whitespacesAndNewlines)
		}
		guard !limpio.isEmpty, limpio.contains(where: { $0.isASCII && $0.isNumber }) || limpio.contains("@") else {
			return nil
		}
		return limpio
	}

	static func esDireccionSip(_ texto: String) -> Bool {
		let minusculas = texto.lowercased()
		return minusculas.contains("@") || minusculas.hasPrefix("sip:") || minusculas.hasPrefix("sips:")
	}

	/// Códigos de operadora, desvíos, `*#06#`…: siempre por la SIM
	static func esCodigoOperadora(_ numero: String) -> Bool {
		let limpio = numero.trimmingCharacters(in: .whitespaces)
		return limpio.hasPrefix("*") || limpio.hasPrefix("#")
	}

	/// El número limpio («+» y cifras) o nil si no es un número normal: pausas o esperas («,» «;»), letras o nada
	static func marcable(_ numero: String) -> String? {
		let separadores: Set<Character> = [" ", "-", ".", "(", ")", "/", "\u{00A0}"]
		let sinSeparadores = numero.trimmingCharacters(in: .whitespaces).filter { !separadores.contains($0) && !$0.isWhitespace }
		let mas = sinSeparadores.hasPrefix("+")
		let cifras = mas ? String(sinSeparadores.dropFirst()) : sinSeparadores
		guard !cifras.isEmpty, cifras.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
		return mas ? "+" + cifras : cifras
	}

	/// ¿El mismo número? Tal cual o, con y sin prefijo de país, por sus 9 últimas cifras
	static func mismoNumero(_ a: String, _ b: String) -> Bool {
		guard let limpioA = marcable(a), let limpioB = marcable(b) else { return false }
		if limpioA == limpioB { return true }
		let cifrasA = limpioA.hasPrefix("+") ? String(limpioA.dropFirst()) : limpioA
		let cifrasB = limpioB.hasPrefix("+") ? String(limpioB.dropFirst()) : limpioB
		return cifrasA.count >= 9 && cifrasB.count >= 9 && cifrasA.suffix(9) == cifrasB.suffix(9)
	}

	/// Cómo se guarda un número: sus 9 últimas cifras o, si es más corto, tal cual
	static func clave(_ numero: String) -> String? {
		guard var cifras = marcable(numero) else { return nil }
		if cifras.hasPrefix("+") { cifras.removeFirst() }
		return cifras.count >= 9 ? String(cifras.suffix(9)) : cifras
	}

	/// «<número>=sim;<número>=app», en orden
	static func leerReglas(_ texto: String) -> [(String, String)] {
		var reglas: [(String, String)] = []
		for entrada in texto.split(separator: ";") {
			let partes = entrada.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
			guard partes.count == 2, !partes[0].isEmpty, partes[0].allSatisfy({ $0.isASCII && $0.isNumber }),
				  partes[1] == reglaSim || partes[1] == reglaApp else { continue }
			reglas.removeAll { $0.0 == partes[0] }
			reglas.append((partes[0], partes[1]))
		}
		return reglas
	}

	static func escribirReglas(_ reglas: [(String, String)]) -> String {
		reglas.map { "\($0.0)=\($0.1)" }.joined(separator: ";")
	}

	static func regla(_ reglas: [(String, String)], para numero: String) -> String? {
		reglas.first { mismoNumero($0.0, numero) }?.1
	}

	/// Las elecciones con la de numero cambiada; regla nil la quita
	static func conRegla(_ reglas: [(String, String)], numero: String, regla: String?) -> [(String, String)] {
		var resultado = reglas.filter { !mismoNumero($0.0, numero) }
		if let nueva = regla, let clave = clave(numero) {
			resultado.append((clave, nueva))
		}
		return resultado
	}
}
