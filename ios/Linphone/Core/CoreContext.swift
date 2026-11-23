/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of Linphone
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

// swiftlint:disable line_length
// swiftlint:disable cyclomatic_complexity
// swiftlint:disable identifier_name

import linphonesw
import Combine
import UniformTypeIdentifiers
import Network
import SwiftUI

#if USE_CRASHLYTICS
import Firebase
#endif

class CoreContext: ObservableObject {
    
    static let shared = CoreContext()
    
	var pipViewModel = PIPViewModel()
	
	var coreVersion: String = Core.getVersion
	@Published var loggedIn: Bool = false
	@Published var loggingInProgress: Bool = false
	@Published var coreHasStartedOnce: Bool = false
	@Published var coreIsStarted: Bool = false
	@Published var accounts: [AccountModel] = []
	@Published var shortcuts: [ShortcutModel] = []
	var mCore: Core!
	
	var bearerAuthInfoPendingPasswordUpdate: AuthInfo?
	var imdnToEverybodyThreshold: Bool = true
	
	let monitor = NWPathMonitor()
	var networkStatusIsConnected: Bool = true // updated on core queue
	
	private var mCoreDelegate: CoreDelegate!
	private var actionsToPerformOnCoreQueueWhenCoreIsStarted: [((Core) -> Void)] = []
	private var callStateCallBacks: [((Call.State) -> Void)] = []
	private var configuringStateCallBacks: [((ConfiguringState) -> Void)] = []
	
	var digestAuthInfoPendingPasswordUpdate: AuthInfo?
	
	private init() {
		do {
			try initialiseCore()
		} catch {
			
		}
	}
	
	func doOnCoreQueue(synchronous: Bool = false, lambda: @escaping (Core) -> Void) {
		let isOnQueue = DispatchQueue.getSpecific(key: coreQueueKey) != nil

		let execute = {
			guard self.mCore.globalState != .Off else {
				Log.warn("Skipped execution: core is off")
				return
			}
			lambda(self.mCore)
		}

		switch (synchronous, isOnQueue) {
		case (true, true), (false, true):
			// Already on the queue → run directly
			execute()
		case (true, false):
			coreQueue.sync(execute: execute)
		case (false, false):
			coreQueue.async(execute: execute)
		}
	}
	
	private let coreQueueKey: DispatchSpecificKey<Void> = {
		let key = DispatchSpecificKey<Void>()
		coreQueue.setSpecific(key: key, value: ())
		return key
	}()
	
	func initialiseCore() throws {
		Log.info("Initialising core")
#if USE_CRASHLYTICS
		FirebaseApp.configure()
#endif
		monitor.pathUpdateHandler = { path in
			let isConnected = path.status == .satisfied
			if self.networkStatusIsConnected != isConnected {
				DispatchQueue.main.async {
					if isConnected {
						Log.info("Network is now satisfied")
						ToastViewModel.shared.toastMessage = "Success_toast_network_connected"
					} else {
						Log.error("Network is now \(path.status)")
						ToastViewModel.shared.toastMessage = "Unavailable_network"
					}
					ToastViewModel.shared.displayToast = true
				}
				self.networkStatusIsConnected = isConnected
			}
			
		}
		monitor.start(queue: coreQueue)
		
		coreQueue.async {
			LoggingService.Instance.logLevel = LogLevel.Debug
			Factory.Instance.logCollectionPath = Factory.Instance.getConfigDir(context: nil)
			Factory.Instance.enableLogCollection(state: LogCollectionState.Enabled)
			
			Log.info("Checking if linphonerc file exists already. If not, creating one as a copy of linphonerc-default")
			if let rcDir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Config.appGroupName)?
				.appendingPathComponent("Library/Preferences/linphone") {
				let rcFileUrl = rcDir.appendingPathComponent("linphonerc")
				if !FileManager.default.fileExists(atPath: rcFileUrl.path) {
					do {
						try FileManager.default.createDirectory(at: rcDir, withIntermediateDirectories: true)
						if let pathToDefaultConfig = Bundle.main.path(forResource: "linphonerc-default", ofType: nil) {
							try FileManager.default.copyItem(at: URL(fileURLWithPath: pathToDefaultConfig), to: rcFileUrl)
							Log.info("Successfully copied linphonerc-default configuration")
						}
					} catch let error {
						Log.error("Failed to copy default linphonerc file: \(error.localizedDescription)")
					}
				} else {
					Log.info("Found existing linphonerc file, skip copying of linphonerc-default configuration")
				}
			}
			
			// MundoPhone: cuentas de push.mundosms.es a TCP 6060 antes de que el Core lea la configuración
			let tcpFixed = Config.get().mundosmsEnsurePushTcp6060()
			if tcpFixed > 0 {
				Log.info("MundoPhone: set TCP 6060 on [\(tcpFixed)] push.mundosms.es proxy/route value(s)")
			}
			// MundoPhone: nada a Belledonne (registros a mundophonedebug; sin servidor de ficheros, versión ni alta de linphone.org)
			let sinBelledonne = Config.get().mundosmsSinBelledonne()
			if !sinBelledonne.isEmpty {
				Log.warn("MundoPhone: sin Belledonne: \(sinBelledonne.joined(separator: ", "))")
			}
			
			self.mCore = try? Factory.Instance.createSharedCoreWithConfig(config: Config.get(), systemContext: Unmanaged.passUnretained(coreQueue).toOpaque(), appGroupId: Config.appGroupName, mainCore: true)
			
			self.mCore.callkitEnabled = true
			self.mCore.pushNotificationEnabled = true
			
			let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
			
			let userAgent = "MundoPhone iOS/\(version ?? "2.0.0") (\(UIDevice.current.localizedModel.replacingOccurrences(of: "'", with: "")))"
			self.mCore.setUserAgent(name: userAgent, version: self.coreVersion)
			self.mCore.videoCaptureEnabled = true
			self.mCore.videoDisplayEnabled = true
			self.mCore.videoPreviewEnabled = false
			self.mCore.fecEnabled = true
			
			// MundoPhone: control adaptativo de la tasa de audio apagado UNA vez por instalación (el usuario puede volver a
			// encenderlo en ajustes). Con pérdida de radio en 4G bajaba la tasa de Opus hasta 6 kbit/s y estropeaba la FEC
			// de subida de Opus+FEC del borde; con G.711 no cambia nada. Misma clave que Android.
			if self.mCore.config!.getInt(section: "app", key: "mundosms_audio_rate_policy", defaultValue: 0) < 1 {
				self.mCore.adaptiveRateControlEnabled = false
				self.mCore.config!.setInt(section: "app", key: "mundosms_audio_rate_policy", value: 1)
				Log.warn("MundoPhone audio rate policy applied: adaptive rate control disabled")
			}
			
			// Migration
			self.mCore.config!.setBool(section: "sip", key: "auto_answer_replacing_calls", value: false)
			self.mCore.config!.setBool(section: "sip", key: "deliver_imdn", value: false)
			
			self.mCore.imdnToEverybodyThreshold = 1
			self.imdnToEverybodyThreshold = self.mCore.imdnToEverybodyThreshold == 1
			//self.copyDatabaseFileToDocumentsDirectory()
			
			let shortcutsCount = self.mCore.config!.getInt(section: "ui", key: "shortcut_count", defaultValue: 0)
			if shortcutsCount > 0 {
				var shortcuts: [ShortcutModel] = []
				for i in 0...shortcutsCount {
					let shortcutSection = "shortcut_\(i)"
					let link = self.mCore.config!.getString(section: shortcutSection, key: "link", defaultString: "")
					let linkUrl = URL(string: link)
					let name = self.mCore.config!.getString(section: shortcutSection, key: "name", defaultString: "")
					let iconLink = self.mCore.config!.getString(section: shortcutSection, key: "icon", defaultString: "")
					let iconLinkUrl =  URL(string: iconLink)
					
					if linkUrl == nil {
						Log.error("Could not add shortcut #\(i) pointing to \(name) because the link URL '\(link)' is invalid")
						continue
					}
					if iconLinkUrl == nil {
						Log.error("Could not add shortcut #\(i) pointing to \(name) because the icon link URL '\(iconLink)' is invalid")
						continue
					}
					shortcuts.append(ShortcutModel(linkUrl: linkUrl!, name: name, iconLinkUrl: iconLinkUrl!))
				}
				
				DispatchQueue.main.async {
					self.shortcuts = shortcuts
				}
			}
			
			for acc in self.mCore.accountList {
				self.forceRemotePushToMatchVoipPushSettings(account: acc)
			}
			
			self.mCoreDelegate = CoreDelegateStub(onGlobalStateChanged: { (core: Core, state: GlobalState, _: String) in
				if state == GlobalState.On {
#if DEBUG
					let pushEnvironment = ".dev"
#else
					let pushEnvironment = ""
#endif
					for account in core.accountList {
                        let newParams = account.params?.clone()

						if account.params?.pushNotificationConfig?.provider != ("apns" + pushEnvironment) {
							Log.info("Account \(String(describing: newParams?.identityAddress?.asStringUriOnly())) - updating apple push provider from \(String(describing: newParams?.pushNotificationConfig?.provider)) to apns\(pushEnvironment)")
							newParams?.pushNotificationConfig?.provider = "apns" + pushEnvironment
						}
                        //newParams?.pushNotificationConfig?.param = "B922HQ77WT"
                        newParams?.pushNotificationConfig?.teamId = "B922HQ77WT"
                        account.params = newParams
					}
					// MundoPhone: TCP 6060 en las cuentas que acaba de crear o actualizar un QR (ver mundosmsAccountsToTcp6060)
					self.mundosmsAccountsToTcp6060(core: core)

					self.actionsToPerformOnCoreQueueWhenCoreIsStarted.forEach {	$0(core) }
					self.actionsToPerformOnCoreQueueWhenCoreIsStarted.removeAll()
					
					var accountModels: [AccountModel] = []
					for account in self.mCore.accountList {
						accountModels.append(AccountModel(account: account, core: self.mCore))
					}
					
					// MundoPhone: orden de códecs y política de medios de Android, una vez por instalación
					self.mundosmsPoliticaDeMedios(core: core)
					
					DispatchQueue.main.async {
						self.coreHasStartedOnce = true
						self.coreIsStarted = true
						self.accounts = accountModels
						// MundoPhone: la llamada de Contactos, Siri o Recientes que llegó con el Core parado
						SalidaLlamada.shared.lanzarPendiente()
					}
				} else {
					DispatchQueue.main.async {
						self.coreIsStarted = state == GlobalState.On
					}
				}
				
			}, onCallStateChanged: { (core: Core, call: Call, cstate: Call.State, message: String) in
				TelecomManager.shared.onCallStateChanged(core: core, call: call, state: cstate, message: message)
				
				if core.calls.isEmpty {
					DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
						if UIApplication.shared.applicationState == .background {
							Log.info("[CoreContext] core is currently in background with no calls, triggering onEnterBackground procedure")
							self.onEnterBackground()
						}
					}
				}
			}, onAuthenticationRequested: { (core: Core, authInfo: AuthInfo, method: AuthMethod) in
				if method == .Bearer {
					if let server = authInfo.authorizationServer, !server.isEmpty {
						Log.info("Authentication requested method is Bearer, starting Single Sign On activity with server URL \(server) and username \(authInfo.username ?? "")")
						self.bearerAuthInfoPendingPasswordUpdate = authInfo
						SingleSignOnManager.shared.setUp(ssoUrl: server, user: authInfo.username ?? "")
					}
				}
				
				if method == .HttpDigest {
					guard let username = authInfo.username, let domain = authInfo.domain, let realm = authInfo.realm else {
						Log.error("Authentication requested but either username [\(String(describing: authInfo.username))], domain [\(String(describing: authInfo.domain))] or server [\(String(describing: authInfo.authorizationServer))] is nil or empty!")
						return
					}
					
					guard let accountFound = core.accountList.first(where: {
						$0.params?.identityAddress?.username == authInfo.username &&
						$0.params?.identityAddress?.domain == authInfo.domain
					}) else {
						Log.info("[CoreContext] Failed to find account matching auth info, aborting auth dialog")
						return
					}

					let identity = "\(authInfo.username ?? "username")@\(authInfo.domain ?? "domain")"
					Log.info("[CoreContext] Authentication requested method is HttpDigest, showing dialog asking user for password for identity [\(identity)]")
					/*
					DispatchQueue.main.async {
						NotificationCenter.default.post(
							name: NSNotification.Name("PasswordUpdate"),
							object: nil,
							userInfo: ["address": "sip:" + identity]
						)
					}
					
					self.digestAuthInfoPendingPasswordUpdate = authInfo
                     */
                    core.refreshRegisters()
				}
			}, onTransferStateChanged: { (_: Core, transferred: Call, callState: Call.State) in
				Log.info("[CoreContext] Transferred call \(transferred.remoteAddress!.asStringUriOnly()) state changed \(callState)")
				DispatchQueue.main.async {
					if callState == Call.State.Connected {
						ToastViewModel.shared.toastMessage = "Success_toast_call_transfer_successful"
						ToastViewModel.shared.displayToast = true
					} else if callState == Call.State.OutgoingProgress {
						ToastViewModel.shared.toastMessage = "Success_toast_call_transfer_in_progress"
						ToastViewModel.shared.displayToast = true
					} else if callState == Call.State.End || callState == Call.State.Error {
						ToastViewModel.shared.toastMessage = "Failed_toast_call_transfer_failed"
						ToastViewModel.shared.displayToast = true
					}
				}
			}, onConfiguringStatus: { (core: Core, status: ConfiguringState, message: String) in
				Log.info("New configuration state is \(status) = \(message)\n")
				// MundoPhone: el file:// del XML del QR no se queda en la configuración aunque la pantalla del QR ya no exista (si
				// la app cae justo después de aplicarlo, cada arranque fallaría con «Bad URI»; visto en Android el 21/11/2026). Al
				// disco en el acto: el Core de iOS relee la configuración del disco al arrancar.
				if status != .Skipped, (core.provisioningUri ?? "").hasSuffix("/\(Coordinator.qrXmlFileName)") {
					Log.info("[CoreContext] MundoPhone: clearing the QR code provisioning URI")
					try? core.setProvisioninguri(newValue: nil)
					try? core.config?.sync()
					try? FileManager.default.removeItem(at: Coordinator.qrXmlFile())
				}
				DispatchQueue.main.async {
					if status == ConfiguringState.Successful {
						var accountModels: [AccountModel] = []
						for account in self.mCore.accountList {
							accountModels.append(AccountModel(account: account, core: self.mCore))
						}
						self.accounts = accountModels
					}
				}
			}, onAccountRegistrationStateChanged: { (core: Core, account: Account, state: RegistrationState, message: String) in
				// If account has been configured correctly, we will go through Progress and Ok states
				// Otherwise, we will be Failed.
				Log.info("New registration state is \(state) for user id " +
						 "\( String(describing: account.params?.identityAddress?.asString())) = \(message)\n")
				
				switch state {
				case .Ok:
					DispatchQueue.main.async {
						NotificationCenter.default.post(name: NSNotification.Name("CoreStarted"), object: nil)
					}
					ContactsManager.shared.fetchContacts()
					
					if let defaultAccountParams = self.mCore.defaultAccount?.params, defaultAccountParams.publishEnabled == false {
						let params = defaultAccountParams
						let clonedParams = params.clone()
						clonedParams?.publishEnabled = true
                        clonedParams?.pushNotificationConfig?.param = "B922HQ77WT"
						self.mCore.defaultAccount?.params = clonedParams
					}
					
					if self.mCore.consolidatedPresence !=  ConsolidatedPresence.Online {
						self.updatePresence(core: self.mCore, presence: ConsolidatedPresence.Online)
					}
				case .Cleared:
					Log.info("[CoreContext][onAccountRegistrationStateChanged] Account \(account.displayName()) registration was cleared.")
				case .Failed:
					Log.error("[CoreContext][onAccountRegistrationStateChanged] Account \(account.displayName()) registration failed.")
				default:
					break
				}
				
				TelecomManager.shared.onAccountRegistrationStateChanged(core: core, account: account, state: state, message: message)
				
				DispatchQueue.main.async {
					if state == .Ok {
						self.loggingInProgress = false
						self.loggedIn = true
					} else if state == .Progress || state == .Refreshing {
						self.loggingInProgress = true
					} else if state == .Cleared {
						self.loggingInProgress = false
						self.loggedIn = false
					} else {
						self.loggingInProgress = false
						self.loggedIn = false
						if self.networkStatusIsConnected {
							// If network is disconnected, a toast message with key "Unavailable_network" should already be displayed
							ToastViewModel.shared.toastMessage = "Registration_failed"
							ToastViewModel.shared.displayToast = true
						}
						
					}
				}
			}, onDefaultAccountChanged: { (_: Core, account: Account?) in
				if let account = account, self.mCore.globalState == GlobalState.On {
					Log.info("[CoreContext][onDefaultAccountChanged] Default account set to: \(account.displayName())")
					DispatchQueue.main.async {
						for accountModel in self.accounts {
							accountModel.isDefaultAccount = accountModel.account == account
						}
						
						NotificationCenter.default.post(name: NSNotification.Name("DefaultAccountChanged"), object: nil)
					}
					
					ContactsManager.shared.fetchContacts()
				}
			}, onAccountAdded: { (_: Core, acc: Account) in
				self.forceRemotePushToMatchVoipPushSettings(account: acc)
				
				var accountModels: [AccountModel] = []
				for account in self.mCore.accountList {
					accountModels.append(AccountModel(account: account, core: self.mCore))
				}
				DispatchQueue.main.async {
					self.accounts = accountModels
				}
			}, onAccountRemoved: { (_: Core, acc: Account) in
				var accountModels: [AccountModel] = []
				for account in self.mCore.accountList {
					accountModels.append(AccountModel(account: account, core: self.mCore))
				}
				DispatchQueue.main.async {
					self.accounts = accountModels
				}
			})
			
			self.mCore.addDelegate(delegate: self.mCoreDelegate)
			
			// MundoPhone: Equipo por la lista del borde (RLS), antes de arrancar para ver el primer «On»
			TeamRlsManager.shared.instalar(core: self.mCore)
			
			self.mCore.autoIterateEnabled = true
			
			try? self.mCore.start()
		}
	}
	
	func updatePresence(core: Core, presence: ConsolidatedPresence) {
		if core.config!.getBool(section: "app", key: "publish_presence", defaultValue: true) {
			core.consolidatedPresence = presence
		}
	}
	
	func onEnterForeground() {
		coreQueue.async {
			Log.info("[onEnterForegroundOrBackground] Entering foreground")
			
			try? self.mCore.start()
		}
	}

	func onEnterBackground() {
		coreQueue.async {
			Log.info("[onEnterForegroundOrBackground] Entering background, un-PUBLISHING presence info")
			
			self.updatePresence(core: self.mCore, presence: .Offline)
			self.mCore.iterate()
			
			if self.mCore.currentCall == nil && self.mCore.globalState == .On {
				Log.info("[onEnterForegroundOrBackground] Stopping core because no active calls")
				self.mCore.stop()
			} else {
				Log.info("[onEnterForegroundOrBackground] Skipped stop: core not fully On or active call in progress")
			}
		}
	}
	
	func crashForCrashlytics() {
		fatalError("Crashing app to test crashlytics")
	}
	
	func performActionOnCoreQueueWhenCoreIsStarted(action: @escaping (_ core: Core) -> Void ) {
		if coreIsStarted {
			doOnCoreQueue { core in
				action(core)
			}
		} else {
			actionsToPerformOnCoreQueueWhenCoreIsStarted.append(action)
		}
	}
	
	func addCoreDelegateStub(delegate: CoreDelegateStub) {
		mCore.addDelegate(delegate: delegate)
	}
	func removeCoreDelegateStub(delegate: CoreDelegateStub) {
		mCore.removeDelegate(delegate: delegate)
	}
	
	/// MundoPhone: lo mismo que Config.mundosmsEnsurePushTcp6060, para las cuentas que acaba de crear un QR con la app ya
	/// en marcha: si no, irían por UDP hasta el siguiente arranque. Va en GlobalState.On y NO en ConfiguringState.Successful:
	/// ahí la lista de cuentas aún es la de antes del XML (el SDK la vuelve a leer justo después), y si se toca una el SDK
	/// ya no la retira y quedan dos iguales registrándose a la vez. Le pasó a Android (dos cuentas
	/// y la app cerrada al escanear dos veces el QR). Aquí no se disparaba porque el XML del panel ya va en TCP 6060.
	func mundosmsAccountsToTcp6060(core: Core) {
		for account in core.accountList {
			guard let params = account.params, let server = params.serverAddress,
				  server.domain == "push.mundosms.es", server.transport != .Tcp || server.port == 0,
				  let newParams = params.clone(), let newServer = server.clone() else {
				continue
			}
			do {
				try newServer.setTransport(newValue: .Tcp)
				if newServer.port == 0 {
					try newServer.setPort(newValue: 6060)
				}
				try newParams.setServeraddress(newValue: newServer)
				account.params = newParams
				Log.info("[CoreContext] MundoPhone: account \(params.identityAddress?.asStringUriOnly() ?? "") moved to \(newServer.asStringUriOnly())")
			} catch {
				Log.error("[CoreContext] MundoPhone: could not move account to TCP 6060: \(error)")
			}
		}
	}
	
	/// MundoPhone: la política de medios de Android (MundoPhoneMigrations.afterCoreStart: applyCodecOrderOnce y
	/// applyMediaPolicyOnce), con las MISMAS claves de «una vez» en [app]. Una configuración que no las tenga la recibe en
	/// el primer arranque, igual que una instalación nueva; después manda lo que el usuario cambie en ajustes. La de tasa de audio (mundosms_audio_rate_policy) ya está arriba, en initialiseCore.
	/// Va con el Core encendido (GlobalState.On), como afterCoreStart: antes de eso el SDK no guarda en la configuración la
	/// política de vídeo (linphone_core_set_video_activation_policy solo escribe con linphone_core_ready) y lo cambiado se
	/// perdería en la siguiente parada del Core, que iOS hace en cada paso a segundo plano. En la cola del Core.
	/// NO se porta applyNetworkPolicyOnce (mundosms_network_policy, IPv6 apagado): Apple exige que la app funcione en redes solo
	/// IPv6 (NAT64) y revisa en una; con IPv6 apagado liblinphone abre solo sockets IPv4 (anyaddr «0.0.0.0», linphonecore.c)
	/// y en una red sin IPv4 la cuenta no registraría. Android tiene 464XLAT (clatd) en el sistema y ahí no pasa. En iOS el
	/// IPv6 lo deja el usuario en Ajustes > Red.
	func mundosmsPoliticaDeMedios(core: Core) {
		guard let config = core.config else {
			return
		}

		// Orden de códecs en NUESTRAS ofertas (salientes, re-INVITE, retener/reanudar): Opus, PCMA, PCMU y el resto como
		// estaba. El de fábrica del SDK pone PCMU antes que PCMA, y Asterisk respeta el orden de la oferta mientras la troncal
		// del operador va en PCMA: con el orden de fábrica, 10 de 15 puentes transcodificaban ulaw↔alaw (medido por el
		// borde). Como en Android. Va antes que el bloque de abajo por una razón del SDK: el setter del orden
		// (linphone_core_set_audio_payload_types) NO lo escribe en la configuración hasta que se para el Core, y la clave de
		// «una vez» sí se guarda al segundo; si la app muriera antes, el orden se perdería para siempre. PayloadType.enable()
		// escribe la lista entera, con su orden (payload-type.cpp, _linphone_core_enable_payload_type): se llama con el
		// valor que ya tiene.
		if config.getInt(section: "app", key: "mundosms_codec_order", defaultValue: 0) < 1 {
			let prioridad = ["opus", "pcma", "pcmu"]
			let actuales = core.audioPayloadTypes
			var ordenados: [PayloadType] = []
			for mime in prioridad {
				if let payloadType = actuales.first(where: { $0.mimeType.lowercased() == mime }) {
					ordenados.append(payloadType)
				}
			}
			ordenados += actuales.filter { !prioridad.contains($0.mimeType.lowercased()) }
			core.audioPayloadTypes = ordenados
			if let primero = core.audioPayloadTypes.first {
				_ = primero.enable(enabled: primero.enabled())
			}
			config.setInt(section: "app", key: "mundosms_codec_order", value: 1)
			let lista = ordenados.map { $0.mimeType + "/" + String($0.clockRate) }.joined(separator: ", ")
			Log.warn("MundoPhone codec order applied: \(lista)")
		}

		// El vídeo no se inicia ni se acepta solo (la respuesta sale con m=video puerto 0 y el usuario lo añade con un
		// re-INVITE), y fuera speex, GSM, H265 y AV1. Acordado con el borde. CallKit ya va sin vídeo.
		if config.getInt(section: "app", key: "mundosms_media_policy", defaultValue: 0) < 1 {
			if let politica = core.videoActivationPolicy?.clone() {
				politica.automaticallyInitiate = false
				politica.automaticallyAccept = false
				core.videoActivationPolicy = politica
			}
			for payloadType in core.audioPayloadTypes where ["speex", "gsm"].contains(payloadType.mimeType.lowercased()) {
				_ = payloadType.enable(enabled: false)
			}
			for payloadType in core.videoPayloadTypes where ["h265", "av1"].contains(payloadType.mimeType.lowercased()) {
				_ = payloadType.enable(enabled: false)
			}
			config.setInt(section: "app", key: "mundosms_media_policy", value: 1)
			Log.warn("MundoPhone media policy applied: no automatic video, speex/GSM/H265/AV1 disabled")
		}
	}

	func forceRemotePushToMatchVoipPushSettings(account: Account) {
		if let params = account.params, params.pushNotificationAllowed && !params.remotePushNotificationAllowed {
			Log.warn("account \(account.displayName()): VOIP and REMOTE push setting mismatch, force \(params.pushNotificationAllowed ? "enabling" : "disabling") of REMOTE Push")
			let newParams = params.clone()
			newParams?.remotePushNotificationAllowed = params.pushNotificationAllowed
            newParams?.pushNotificationConfig?.param = "B922HQ77WT"
			account.params = newParams
		}
	}
	
	func copyDatabaseFileToDocumentsDirectory() {
		if let rcDir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Config.appGroupName)?
			.appendingPathComponent("Library/Application Support/linphone") {
			let rcFileUrl = rcDir.appendingPathComponent("linphone.db")
			let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
			if directory != nil {
				do {
					if FileManager.default.fileExists(atPath: directory!.appendingPathComponent("linphone.db").path) {
						try FileManager.default.removeItem(at: directory!.appendingPathComponent("linphone.db"))
					}
					try FileManager.default.copyItem(at: rcFileUrl, to: directory!.appendingPathComponent("linphone.db"))
				} catch {
					print("Error: ", error)
				}
			}
		}
	}
}

// swiftlint:enable line_length
// swiftlint:enable cyclomatic_complexity
// swiftlint:enable identifier_name
