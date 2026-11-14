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
import UIKit
import Combine
import linphonesw

/**
 * MundoPhone: Equipo por la lista del borde (RLS). Igual que `TeamRlsManager.kt` de Android: cierra la suscripción que
 * falla o que ya no es la actual, y la vuelve a pedir sola con un intento por minuto como mucho.
 *
 * Una sola suscripción a `sip:rls@<dominio>` en vez de una por compañero. El borde manda la lista de la empresa con el
 * estado de cada uno; con ella, Equipo enseña solo a esos compañeros (los de la agenda con su nombre y foto; los demás, con
 * el de la centralita) y una línea de estado por fila. Sin ella (hoy en producción el borde contesta 403) todo sigue como
 * siempre: Equipo es la agenda, con una petición cada vez que se abre la app.
 *
 * Diferencias con Android, por cómo vive la app en iOS:
 * - iOS para el Core al irse a segundo plano y lo vuelve a arrancar al volver: al parar se suelta la suscripción y los
 *   estados pasan a «sin dato» (nada de enseñar una llamada de hace una hora); al volver se pide otra vez.
 * - Solo se pide con la app en primer plano. Un push de llamada arranca el Core en segundo plano, y pedir la lista entera
 *   ahí sería tráfico para nada en pleno timbre.
 * - Sin lista en el borde (403…) no se vuelve a pedir durante 30 min (en Android, hasta el siguiente arranque de la app,
 *   que en iOS sería cada vez que se abre).
 * - El estado no se fija en el modelo de presencia del SDK (en iOS no hay suscripciones sueltas que lo pisen): lo publica
 *   este gestor y lo lee `ContactAvatarModel`.
 *
 * Todo lo que toca el SDK va en la cola del Core; `estados` y `miembros` se publican en el hilo principal para las vistas.
 */
final class TeamRlsManager: ObservableObject {
	static let shared = TeamRlsManager()

	/** La lista de compañeros de la app: ni se guarda en la base de datos ni se suscribe (el estado lo trae el borde). */
	static let listName = "MundoPhone Equipo (RLS)"

	private static let TAG = "[TeamRls]"
	private static let expires = 600
	private static let notifyTimeout: TimeInterval = 5
	private static let retryInterval: TimeInterval = 60
	private static let permanentRetry: TimeInterval = 30 * 60

	// El borde sin la ruta (hoy: 403), sin la cabecera eventlist (421), empresa sin lista (404), evento no admitido (489)
	private static let permanentCodes: Set<Int> = [403, 404, 421, 489]

	// Las mismas claves que Android. Se apaga con [app] mundosms_team_rls=0
	private static let keyEnabled = "mundosms_team_rls"
	private static let enabledDefault = 1
	private static let keyUri = "mundosms_team_rls_uri"
	private static let keyCache = "mundosms_team_rls_cache"

	enum Modo { case apagado, esperando, activo, noDisponible }

	/** Lo que pinta la vista de un compañero: su línea (nil = el borde aún no lo sabe) y con quién, ya con nombre. */
	struct Vista: Equatable {
		let linea: TeamRls.Linea?
		let con: String?
	}

	// ---- Hilo principal ----

	/** Usuario (extensión, en minúsculas) → lo que se pinta. Vacío si la lista del borde no manda. */
	@Published private(set) var estados: [String: Vista] = [:]

	/** Quién es del equipo, para el filtro de Equipo: aparte, porque cambia poco y los estados con cada NOTIFY. */
	let miembros = TeamRlsMiembros()

	// ---- Cola del Core ----

	private var modo = Modo.apagado
	private var core: Core?
	private var event: Event?
	private let lista = TeamRlsLista()
	private var actuales: [String: TeamRls.Miembro] = [:]
	private var permanenteDesde: TimeInterval?
	private var ultimoIntento: TimeInterval?
	private var generacion = 0
	private var enPrimerPlano = false
	private var nombresRemotos: [String: String] = [:]
	private var coreDelegate: CoreDelegate?
	private var eventDelegate: EventDelegate?

	private init() {}

	private static var ahora: TimeInterval { ProcessInfo.processInfo.systemUptime }

	// ---- Ciclo de vida ----

	/** Desde CoreContext, en la cola del Core y antes de arrancarlo. Una vez por proceso. */
	func instalar(core: Core) {
		guard coreDelegate == nil else { return }
		self.core = core
		eventDelegate = EventDelegateStub(
			onNotifyReceived: { [weak self] (ev: Event, content: Content?) in
				guard let self = self else { return }
				if ev !== self.event {
					self.soltar(ev, "NOTIFY de una suscripción que ya no es la actual")
					return
				}
				guard let content = content else { return }
				self.onNotify(ev, content)
			},
			onSubscribeStateChanged: { [weak self] (ev: Event, state: SubscriptionState) in
				guard let self = self else { return }
				if ev !== self.event {
					if state != .Terminated { self.soltar(ev, "suscripción que ya no es la actual pasa a [\(state)]") }
					return
				}
				self.onState(ev, state)
			}
		)
		let delegate = CoreDelegateStub(
			onGlobalStateChanged: { [weak self] (core: Core, state: GlobalState, _: String) in
				if state == .On {
					self?.arrancar(core)
				} else if state == .Shutdown {
					self?.parar()
				}
			},
			onAccountRegistrationStateChanged: { [weak self] (core: Core, account: Account, state: RegistrationState, _: String) in
				guard state == .Ok, let id = account.params?.identityAddress?.asStringUriOnly(),
					  id == core.defaultAccount?.params?.identityAddress?.asStringUriOnly() else { return }
				self?.subscribeIfNeeded(core, "cuenta registrada")
			},
			onDefaultAccountChanged: { [weak self] (core: Core, _: Account?) in
				self?.cambioDeCuenta(core)
			}
		)
		coreDelegate = delegate
		core.addDelegate(delegate: delegate)

		DispatchQueue.main.async {
			let centro = NotificationCenter.default
			centro.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
				self?.cambiarPrimerPlano(true)
			}
			centro.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
				self?.cambiarPrimerPlano(false)
			}
			self.cambiarPrimerPlano(UIApplication.shared.applicationState == .active)
		}
	}

	private func cambiarPrimerPlano(_ valor: Bool) {
		coreQueue.async {
			self.enPrimerPlano = valor
			if valor, let core = self.core, core.globalState == .On { self.subscribeIfNeeded(core, "app en primer plano") }
		}
	}

	private func arrancar(_ core: Core) {
		self.core = core
		guard let config = core.config else { return }
		if config.getInt(section: "app", key: Self.keyEnabled, defaultValue: Self.enabledDefault) == 0 {
			Log.info("\(Self.TAG) Disabled by [app] \(Self.keyEnabled)=0")
			modo = .apagado
			publicar(core, listaCambiada: false)
			return
		}
		if let desde = permanenteDesde {
			if Self.ahora - desde < Self.permanentRetry {
				Log.info("\(Self.TAG) The edge had no team list for us [\(Int(Self.ahora - desde))] s ago, not asking again yet")
				return
			}
			permanenteDesde = nil
		}
		if actuales.isEmpty {
			// «<identidad> <uri> <uri>…»: solo vale para la cuenta con la que se guardó
			let guardada = config.getString(section: "app", key: Self.keyCache, defaultString: "")
				.split(separator: " ").map(String.init)
			if guardada.count > 1, let id = identidad(core), guardada[0] == id {
				let miembros = guardada.dropFirst().map {
					TeamRls.Miembro(uri: $0, usuario: TeamRls.usuario($0), estado: .sinDato, nota: nil, nombre: nil, llamada: nil)
				}
				Log.info("\(Self.TAG) [\(miembros.count)] team member(s) from the last list, until the new one arrives")
				actuales = porUsuario(Array(miembros))
			}
		}
		// También tras parar: el SDK vacía al parar las listas que no van a la base de datos, como esta
		if !actuales.isEmpty { syncFriends(core, Array(actuales.values)) }
		modo = .esperando
		publicar(core, listaCambiada: true)
		subscribeIfNeeded(core, "arranque")
	}

	/** El Core se para (la app se va a segundo plano sin llamadas): fuera la suscripción y los estados, la lista se queda. */
	private func parar() {
		stop()
		lista.reiniciar()
		ultimoIntento = nil
		nombresRemotos = [:]
		actuales = actuales.mapValues {
			TeamRls.Miembro(uri: $0.uri, usuario: $0.usuario, estado: .sinDato, nota: nil, nombre: $0.nombre, llamada: nil)
		}
		if modo == .activo { modo = .esperando }
		publicar(nil, listaCambiada: false)
	}

	private func cambioDeCuenta(_ core: Core) {
		// Al arrancar, el SDK avisa del cambio de cuenta por defecto con el Core aún parado: no es un cambio de verdad (la
		// caché ya se mira por identidad al llegar a «On») y con el Core parado no se puede tocar nada
		guard modo != .apagado, core.globalState == .On else { return }
		Log.info("\(Self.TAG) Default account changed, starting over")
		stop()
		lista.reiniciar()
		permanenteDesde = nil
		ultimoIntento = nil
		nombresRemotos = [:]
		actuales = [:]
		syncFriends(core, [])
		modo = .esperando
		publicar(core, listaCambiada: true)
		subscribeIfNeeded(core, "cambio de cuenta por defecto")
	}

	private func identidad(_ core: Core) -> String? {
		return core.defaultAccount?.params?.identityAddress?.asStringUriOnly().lowercased()
	}

	// ---- Suscripción ----

	private func subscribeIfNeeded(_ core: Core, _ por: String) {
		guard core.globalState == .On, let config = core.config else { return }
		if config.getInt(section: "app", key: Self.keyEnabled, defaultValue: Self.enabledDefault) == 0 { return }
		if event != nil || modo == .apagado { return }
		if let desde = permanenteDesde {
			if Self.ahora - desde < Self.permanentRetry {
				Log.info("\(Self.TAG) Not subscribing (\(por)): the edge had no team list for us [\(Int(Self.ahora - desde))] s ago")
				return
			}
			permanenteDesde = nil
		}
		if !enPrimerPlano { return }
		let ahora = Self.ahora
		if let ultimo = ultimoIntento, ahora - ultimo < Self.retryInterval { return }
		guard let account = core.defaultAccount, account.state == .Ok,
			  let domain = account.params?.identityAddress?.domain else { return }
		var uri = config.getString(section: "app", key: Self.keyUri, defaultString: "")
		if uri.isEmpty { uri = "sip:rls@\(domain)" }
		guard let address = core.interpretUrl(url: uri, applyInternationalPrefix: false) else {
			Log.error("\(Self.TAG) Invalid team list URI [\(uri)]")
			return
		}
		ultimoIntento = ahora
		let ev: Event
		do {
			ev = try core.createSubscribe(resource: address, event: "presence", expires: Self.expires)
		} catch {
			Log.error("\(Self.TAG) Can't create the team list subscription: \(error)")
			return
		}
		ev.addCustomHeader(name: "Supported", value: "eventlist")
		ev.addCustomHeader(name: "Accept", value: "multipart/related, application/rlmi+xml, application/pidf+xml")
		if let delegate = eventDelegate { ev.addDelegate(delegate: delegate) }
		event = ev
		lista.reiniciar()
		if modo != .activo { modo = .esperando }
		generacion += 1
		let miGeneracion = generacion
		Log.info("\(Self.TAG) Subscribing to team list [\(uri)] (\(por))")
		do {
			try ev.sendSubscribe(body: nil)
		} catch {
			Log.error("\(Self.TAG) Can't send the team list SUBSCRIBE: \(error)")
			soltar(ev, "no se ha podido mandar")
			event = nil
			setUnavailable(core)
			return
		}
		despues(Self.notifyTimeout) { core in
			if miGeneracion == self.generacion && !self.lista.completa && self.modo == .esperando {
				Log.warn("\(Self.TAG) No full team list after [\(Int(Self.notifyTimeout))] s, using the team from the address book meanwhile")
				self.setUnavailable(core)
			}
		}
	}

	private func despues(_ segundos: TimeInterval, _ bloque: @escaping (Core) -> Void) {
		coreQueue.asyncAfter(deadline: .now() + segundos) { [weak self] in
			guard let self = self, let core = self.core, core.globalState == .On else { return }
			bloque(core)
		}
	}

	private func stop() {
		generacion += 1
		guard let ev = event else { return }
		event = nil
		if let delegate = eventDelegate { ev.removeDelegate(delegate: delegate) }
		ev.terminate()
	}

	/**
	 * Cierra una suscripción que ya no es la actual. Ante un 481 a un refresco, belle-sip vuelve a suscribirse solo aunque al
	 * evento le llegue «Error»; si no se cierra, revive y quedan dos o tres a la misma lista (se ve en el borde como NOTIFY
	 * por duplicado en el mismo milisegundo). Cerrarla para el refresco y da de baja el diálogo.
	 */
	private func soltar(_ ev: Event, _ motivo: String) {
		Log.warn("\(Self.TAG) Closing a team list subscription that is not the current one: \(motivo)")
		if let delegate = eventDelegate { ev.removeDelegate(delegate: delegate) }
		ev.terminate()
	}

	private func onState(_ ev: Event, _ state: SubscriptionState) {
		let code = ev.errorInfo?.protocolCode ?? 0
		Log.info("\(Self.TAG) Team list subscription is [\(state)] (code [\(code)], reason [\(ev.reason)])")
		guard state == .Error || state == .Terminated else { return }
		event = nil
		generacion += 1
		// La que ha fallado se cierra del todo: si no, belle-sip la vuelve a suscribir por su cuenta y, con la nueva que se
		// pide más abajo, quedaban dos a la misma lista
		if state == .Error { soltar(ev, "ha fallado ([\(code)])") }
		guard let core = core else { return }
		if Self.permanentCodes.contains(code) {
			permanenteDesde = Self.ahora
			Log.warn("\(Self.TAG) The edge has no team list for us ([\(code)]): team from the address book, no retry for [\(Int(Self.permanentRetry))] s")
			setUnavailable(core)
			return
		}
		if modo == .activo {
			// Ya funcionaba (corte de red, refresco perdido): se queda la lista y se vuelve a pedir; si no llega en 5 s, ahí
			// sí se cae
			Log.warn("\(Self.TAG) Team list subscription lost ([\(code)]), keeping the list and subscribing again")
			modo = .esperando
			ultimoIntento = nil
			despues(Self.notifyTimeout) { core in self.subscribeIfNeeded(core, "reintento") }
			return
		}
		setUnavailable(core)
		// Sin lista todavía: un 481 antes del primer completo dejaría Equipo con la agenda hasta el siguiente REGISTER. Se
		// pide sola, con un intento por minuto como mucho
		let espera = TeamRls.esperaReintento(ahora: Self.ahora, ultimoIntento: ultimoIntento, minimo: Self.notifyTimeout,
											 intervalo: Self.retryInterval)
		let miGeneracion = generacion
		Log.info("\(Self.TAG) Asking for the team list again in [\(Int(espera))] s")
		despues(espera) { core in
			if miGeneracion == self.generacion { self.subscribeIfNeeded(core, "reintento tras [\(code)]") }
		}
	}

	private func setUnavailable(_ core: Core) {
		if modo == .noDisponible { return }
		modo = .noDisponible
		actuales = [:]
		syncFriends(core, [])
		publicar(core, listaCambiada: true)
	}

	// ---- NOTIFY ----

	private func onNotify(_ ev: Event, _ content: Content) {
		guard let core = core else { return }
		let partes: [TeamRls.Parte]
		if content.isMultipart {
			partes = content.parts.map { part in
				var cabeceras: [(String, String)] = [("Content-Type", "\(part.type)/\(part.subtype)")]
				let cid = [part.getCustomHeader(headerName: "Content-Id"), part.getCustomHeader(headerName: "Content-ID")]
					.first { !$0.isEmpty }
				if let cid = cid { cabeceras.append(("Content-ID", cid)) }
				return TeamRls.Parte(cabeceras: cabeceras, cuerpo: part.utf8Text ?? "")
			}
		} else {
			// Por si el SDK no lo ha troceado: el cuerpo en texto
			partes = TeamRls.partesMultipart(content.utf8Text ?? "")
		}
		let notify = TeamRls.leerNotify(partes)
		guard let rlmi = notify.rlmi else {
			Log.warn("\(Self.TAG) NOTIFY without a readable RLMI ([\(content.type)/\(content.subtype)]), ignored")
			return
		}
		let resultado = lista.aplicar(rlmi, pidfPorCid: notify.pidfPorCid)
		Log.info("\(Self.TAG) NOTIFY version [\(rlmi.version)] fullState [\(rlmi.fullState)] with [\(rlmi.recursos.count)] resource(s): [\(resultado)]")
		if resultado == .pideCompleto {
			Log.warn("\(Self.TAG) Missing a full state (or version gap), refreshing the subscription")
			try? ev.refreshSubscribe()
		}
		guard lista.completa else { return }
		// Sin cambios y ya activo: nada que hacer. Si no estaba activo (p. ej. llegó tras los 5 s), hay que volver a crear a
		// los compañeros aunque la lista no haya cambiado
		let sinNovedad = resultado == .viejo || resultado == .sinCambio
		if sinNovedad && modo == .activo { return }
		let miembros = lista.miembros
		actuales = porUsuario(miembros)
		let cambiada = syncFriends(core, miembros)
		if rlmi.fullState, let id = identidad(core) {
			core.config?.setString(section: "app", key: Self.keyCache, value: ([id] + miembros.map { $0.uri }).joined(separator: " "))
		}
		let estaba = modo == .activo
		if !estaba {
			Log.info("\(Self.TAG) Team list is active with [\(miembros.count)] member(s)")
			modo = .activo
		}
		publicar(core, listaCambiada: cambiada || !estaba)
	}

	private func porUsuario(_ miembros: [TeamRls.Miembro]) -> [String: TeamRls.Miembro] {
		return Swift.Dictionary(miembros.map { ($0.usuario, $0) }, uniquingKeysWith: { primero, _ in primero })
	}

	// ---- Compañeros y lo que se publica ----

	private func ownList(_ core: Core, crear: Bool) -> FriendList? {
		if let list = core.getFriendListByName(name: Self.listName) { return list }
		guard crear, let list = try? core.createFriendList() else { return nil }
		list.displayName = Self.listName
		list.databaseStorageEnabled = false
		list.subscriptionsEnabled = false
		core.addFriendList(list: list)
		return list
	}

	/** Deja la lista propia con estos miembros (el nombre de la centralita o, sin él, la extensión). ¿Ha cambiado algo? */
	@discardableResult
	private func syncFriends(_ core: Core, _ miembros: [TeamRls.Miembro]) -> Bool {
		guard let list = ownList(core, crear: !miembros.isEmpty) else { return false }
		let quiero = porUsuario(miembros)
		var cambio = false
		var vistos = Set<String>()
		for friend in list.friends {
			if let usuario = friend.address?.username?.lowercased(), quiero[usuario] != nil, vistos.insert(usuario).inserted { continue }
			_ = list.removeFriend(linphoneFriend: friend)
			cambio = true
		}
		for miembro in miembros {
			guard let address = core.interpretUrl(url: miembro.uri, applyInternationalPrefix: false) else { continue }
			let nombre = miembro.nombre ?? miembro.usuario
			if let friend = list.findFriendByAddress(address: address) {
				if friend.name != nombre {
					friend.edit()
					try? friend.setName(newValue: nombre)
					friend.done()
					cambio = true
				}
				continue
			}
			guard let friend = try? core.createFriend() else { continue }
			friend.edit()
			try? friend.setAddress(newValue: address)
			try? friend.setName(newValue: nombre)
			try? friend.setSubscribesenabled(newValue: false)
			try? friend.setIncsubscribepolicy(newValue: .SPDeny)
			friend.done()
			_ = list.addLocalFriend(linphoneFriend: friend)
			cambio = true
		}
		return cambio
	}

	/**
	 * Con quién habla un compañero (el `remote` de ms:call: extensión o número): el nombre de la agenda si lo tenemos; si
	 * no, el de la centralita si es un compañero; si no, tal cual. Se recuerda hasta que cambia la lista o se para el Core:
	 * buscar un teléfono en la agenda recorre todos los contactos.
	 */
	private func nombreParaRemoto(_ core: Core, _ remoto: String) -> String {
		if let nombre = nombresRemotos[remoto] { return nombre }
		var nombre: String?
		let cifras = remoto.hasPrefix("+") ? String(remoto.dropFirst()) : remoto
		if !cifras.isEmpty && cifras.allSatisfy({ ("0"..."9").contains($0) }) {
			if let friend = core.findFriendByPhoneNumber(phoneNumber: remoto), friend.friendList?.displayName != Self.listName {
				nombre = friend.name
			}
		} else if let address = core.interpretUrl(url: remoto, applyInternationalPrefix: false) {
			for list in core.friendsLists where list.displayName != Self.listName {
				if let friend = list.findFriendByAddress(address: address) {
					nombre = friend.name
					break
				}
			}
		}
		let resultado = (nombre?.isEmpty == false ? nombre : nil) ?? actuales[remoto.lowercased()]?.nombre ?? remoto
		nombresRemotos[remoto] = resultado
		return resultado
	}

	private func publicar(_ core: Core?, listaCambiada: Bool) {
		if listaCambiada { nombresRemotos = [:] }
		let activoAhora = modo == .activo || (modo == .esperando && !actuales.isEmpty)
		var vistas: [String: Vista] = [:]
		if activoAhora {
			for (usuario, miembro) in actuales {
				let linea = TeamRls.linea(miembro)
				let con = core.flatMap { core in linea?.con.map { nombreParaRemoto(core, $0) } } ?? linea?.con
				vistas[usuario] = Vista(linea: linea, con: con)
			}
		}
		let usuarios = activoAhora ? Set(actuales.keys) : []
		DispatchQueue.main.async {
			self.miembros.cambiar(activo: activoAhora, usuarios: usuarios)
			if self.estados != vistas { self.estados = vistas }
		}
		// Entran o salen compañeros, o cambia su nombre: la búsqueda de contactos se rehace sin su caché
		if listaCambiada { MagicSearchSingleton.shared.searchForContacts(resetCache: true) }
	}
}

/** MundoPhone: ¿manda la lista del borde? (activa, o esperándola con la de la última vez) y sus extensiones. Hilo principal. */
final class TeamRlsMiembros: ObservableObject {
	@Published private(set) var activo = false
	@Published private(set) var usuarios: Set<String> = []

	fileprivate func cambiar(activo: Bool, usuarios: Set<String>) {
		if self.activo != activo { self.activo = activo }
		if self.usuarios != usuarios { self.usuarios = usuarios }
	}

	/** ¿Es de Equipo un contacto con estas extensiones? Con la lista, si alguna es de ella. */
	func contiene(_ usuariosContacto: [String]) -> Bool {
		return usuariosContacto.contains { usuarios.contains($0) }
	}
}
