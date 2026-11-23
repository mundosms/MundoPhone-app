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

import Foundation
import SwiftUI
import AVFoundation
import linphonesw

// Posted when remote provisioning completes successfully.
// object is a Bool: true if at least one account exists, false otherwise.
extension Notification.Name {
	static let qrCodeProvisioningSuccess = Notification.Name("QRCodeProvisioningSuccess")
	static let qrCodeProvisioningError = Notification.Name("QRCodeProvisioningError")
	static let qrCodeProvisioningInputDetected = Notification.Name("QRCodeProvisioningInputDetected")
}

struct QRScanner: UIViewControllerRepresentable {
	
	func makeUIViewController(context: Context) -> QRScannerController {
		let controller = QRScannerController()
		controller.delegate = context.coordinator
		return controller
	}
	
	func makeCoordinator() -> Coordinator {
		Coordinator()
	}
	
	func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {
	}
}

class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
	
	static let TAG = "[QR Code Scanner]"
	
	private var coreContext = CoreContext.shared
	private var coreDelegate: CoreDelegateStub?
		private var provisioningInputObserver: NSObjectProtocol?
	
	/// Snapshot of account key counts taken just before starting a provisioning flow.
	/// Key format is username@domain.
	private var accountKeyCountsBeforeProvisioning: [String: Int]?
	private var isProvisioningInProgress: Bool = false
	/// MundoPhone: el QR en curso no es de MundoSMS (se lo dimos al SDK, como upstream): si falla, se dice eso y no un error
	/// genérico. Visto el 21/11/2026: el lector leyó un QR de cartuchos de Canon y salió «error al aplicar la configuración».
	private var foreignQr = false

	private var lastResult: String = ""

	override init() {
		super.init()
		setupCoreDelegate()
		setupProvisioningInputObserver()
	}
	
	deinit {
		if let observer = provisioningInputObserver {
			NotificationCenter.default.removeObserver(observer)
		}
		if let delegate = coreDelegate {
			coreContext.removeCoreDelegateStub(delegate: delegate)
		}
	}
	
	// MARK: - Core Delegate
	
	private func setupCoreDelegate() {
		coreDelegate = CoreDelegateStub(
            onGlobalStateChanged: { (core: Core, state: GlobalState, _: String) in
                if state == .On {
					guard self.isProvisioningInProgress else {
						return
					}

					let currentCounts = self.extractAccountKeyCounts(core: core)
					if let previousCounts = self.accountKeyCountsBeforeProvisioning {
						self.accountKeyCountsBeforeProvisioning = nil
						if self.didProvisioningAddOnlyDuplicates(previousCounts: previousCounts, currentCounts: currentCounts) {
							self.removeProvisionedDuplicates(core: core, previousCounts: previousCounts)
							self.isProvisioningInProgress = false
							Log.warn("\(Coordinator.TAG) Duplicate account detected after provisioning attempt, duplicate accounts were rolled back")
							DispatchQueue.main.async {
								ToastViewModel.shared.toastMessage = "Failed_duplicate_account"
								ToastViewModel.shared.displayToast = true
								NotificationCenter.default.post(name: .qrCodeProvisioningError, object: nil)
							}
							return
						}
					}

                    if core.accountList.isEmpty {
						self.isProvisioningInProgress = false
                        Log.warn("\(Coordinator.TAG) Provisioning was successful but no account has been configured yet, staying in assistant")
                        DispatchQueue.main.async {
                            NotificationCenter.default.post(name: .qrCodeProvisioningSuccess, object: false)
                        }
                    } else {
						self.isProvisioningInProgress = false
                        Log.info("\(Coordinator.TAG) At least an account exists in Core, leaving assistant")
                        DispatchQueue.main.async {
                            NotificationCenter.default.post(name: .qrCodeProvisioningSuccess, object: true)
                        }
                    }
                }
            }, onConfiguringStatus: { (core: Core, status: ConfiguringState, message: String) in
                Log.info("\(Coordinator.TAG) Configuring state is [\(status)]")
				if status != .Skipped {
					// MundoPhone: el XML del QR (lleva la clave de su cuenta) no se queda en disco, y si no se pudo aplicar
					// tampoco su file:// en la configuración, que el SDK volvería a intentar en cada arranque
					if (core.provisioningUri ?? "").hasSuffix("/\(Coordinator.qrXmlFileName)") {
						try? core.setProvisioninguri(newValue: nil)
						// Al disco en el acto: el Core de iOS relee la configuración del disco al volver a arrancar
						try? core.config?.sync()
					}
					try? FileManager.default.removeItem(at: Coordinator.qrXmlFile())
				}
                if status == .Failed {
					self.isProvisioningInProgress = false
					self.accountKeyCountsBeforeProvisioning = nil
                    Log.error("\(Coordinator.TAG) Failure applying remote provisioning: \(message)")
					if self.foreignQr {
						// El enlace de un QR ajeno que ha fallado no se queda para reintentarlo en cada arranque (se quedaba
						// así uno de cartuchos de Canon)
						try? core.setProvisioninguri(newValue: nil)
						try? core.config?.sync()
					}
					let toast = self.foreignQr ? "QR_code_not_mundosms" : "Failed_remote_provisioning"
					self.foreignQr = false
                    DispatchQueue.main.async {
                        ToastViewModel.shared.toastMessage = toast
                        ToastViewModel.shared.displayToast = true
                        NotificationCenter.default.post(name: .qrCodeProvisioningError, object: nil)
                    }
                }
            }
		)
		coreContext.addCoreDelegateStub(delegate: coreDelegate!)
	}

	private func setupProvisioningInputObserver() {
		provisioningInputObserver = NotificationCenter.default.addObserver(
			forName: .qrCodeProvisioningInputDetected,
			object: nil,
			queue: .main
		) { [weak self] notification in
			guard let self = self,
				  let result = notification.object as? String else {
				return
			}
			self.processScannedResult(result)
		}
	}
	
	// MARK: - AVCaptureMetadataOutputObjectsDelegate
	
	func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
		
		if metadataObjects.isEmpty {
			return
		}
		
		guard let metadataObj = metadataObjects[0] as? AVMetadataMachineReadableCodeObject else {
			return
		}
		
		if metadataObj.type == AVMetadataObject.ObjectType.qr,
		   let result = metadataObj.stringValue {
			processScannedResult(result)
		}
	}

	private func processScannedResult(_ result: String) {
		guard !result.isEmpty && result != lastResult else {
			return
		}

		lastResult = result
		// MundoPhone: solo el host; la URL lleva la firma del panel
		Log.info("\(Coordinator.TAG) QR Code found, host [\(Coordinator.hostOf(result))]")

		coreContext.doOnCoreQueue { core in
			self.isProvisioningInProgress = true
			self.foreignQr = false
			self.accountKeyCountsBeforeProvisioning = self.extractAccountKeyCounts(core: core)

			let payload = result.trimmingCharacters(in: .whitespacesAndNewlines)
			// MundoPhone: el QR puede traer el XML entero (antes se le daba al SDK como URI y lo rechazaba)
			if payload.hasPrefix("<config") || payload.hasPrefix("<?xml") {
				self.applyXml(core: core, xml: payload)
				return
			}

			// MundoPhone (como en Android): el XML de la cuenta lo descarga la app, sin mandar nada de las que ya tiene
			// (antes iban en la URL el usuario y el MD5 de la clave de todas). Primero se resuelve el enlace corto de
			// urla.ws, también sin datos.
			let url = Coordinator.upgradeToHttps(result)
			DispatchQueue.global(qos: .userInitiated).async {
				guard let finalUrl = Coordinator.resolveRedirects(url) else {
					self.coreContext.doOnCoreQueue { core in
						let nuestro = Coordinator.isMundoSmsLink(url)
						if core.accountList.isEmpty {
							// Un QR que no es del panel (otro proveedor): sin cuentas que pisar, lo aplica el SDK, como upstream
							Log.warn("\(Coordinator.TAG) QR link didn't lead to https://\(Coordinator.provisioningHost), letting the SDK apply it (MundoSMS link [\(nuestro)])")
							self.foreignQr = !nuestro
							self.handleUrlConfiguration(core: core, url: url)
						} else {
							Log.error("\(Coordinator.TAG) QR link didn't lead to https://\(Coordinator.provisioningHost), existing accounts could be overwritten (MundoSMS link [\(nuestro)])")
							self.provisioningFailed(toast: nuestro ? "QR_code_invalid" : "QR_code_not_mundosms")
						}
					}
					return
				}
				switch Coordinator.downloadXml(finalUrl) {
				case .xml(let xml):
					self.coreContext.doOnCoreQueue { core in
						self.applyXml(core: core, xml: xml)
					}
				case .failed(let toast):
					self.coreContext.doOnCoreQueue { _ in
						self.provisioningFailed(toast: toast)
					}
				}
			}
		}
	}

	/// Error al aprovisionar desde el QR: se avisa y se vuelve al lector. En la cola del Core.
	private func provisioningFailed(toast: String) {
		isProvisioningInProgress = false
		accountKeyCountsBeforeProvisioning = nil
		DispatchQueue.main.async {
			ToastViewModel.shared.toastMessage = toast
			ToastViewModel.shared.displayToast = true
			NotificationCenter.default.post(name: .qrCodeProvisioningError, object: nil)
		}
	}

	// MARK: - QR link (MundoPhone)

	/// La API del panel, por https: de ahí la app descarga el XML de la cuenta (MundoPhoneAprovisionamiento).
	static let provisioningHost = "api.mundosms.es"
	private static let maxRedirects = 5

	static func hostOf(_ url: String) -> String {
		return URL(string: url)?.host ?? ""
	}

	static func isProvisioningUrl(_ url: String) -> Bool {
		guard let u = URL(string: url) else { return false }
		return u.scheme?.lowercased() == "https" && u.host?.lowercased() == provisioningHost
	}

	/// Un enlace del panel: a mundosms.es o al acortador (urla.ws). Solo decide el aviso cuando el enlace no lleva a la API
	/// (sin red, el del panel sigue diciendo «no válido»; uno ajeno, «no es de MundoSMS»).
	static func isMundoSmsLink(_ url: String) -> Bool {
		guard let host = URL(string: url)?.host?.lowercased() else { return false }
		return host == "mundosms.es" || host.hasSuffix(".mundosms.es") || host == "urla.ws" || host.hasSuffix(".urla.ws")
	}

	/// urla.ws sirve http y https; iOS (ATS) no deja ir por http
	static func upgradeToHttps(_ url: String) -> String {
		return url.replacingOccurrences(of: "^http://", with: "https://", options: [.regularExpression, .caseInsensitive])
	}

	private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
		func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
						newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
			completionHandler(nil)
		}
	}

	/// Sigue las redirecciones del enlace (Location de un 3xx o la cabecera Refresh) sin mandar ningún dato, hasta llegar
	/// a https://api.mundosms.es. Devuelve nil si no se llega. Bloquea: llamarla fuera del hilo principal y del Core.
	static func resolveRedirects(_ start: String) -> String? {
		var current = upgradeToHttps(start)
		let session = URLSession(configuration: .ephemeral, delegate: NoRedirectDelegate(), delegateQueue: nil)
		defer { session.invalidateAndCancel() }
		for _ in 0..<maxRedirects {
			if isProvisioningUrl(current) { return current }
			guard let url = URL(string: current) else { return nil }
			let semaphore = DispatchSemaphore(value: 0)
			var response: HTTPURLResponse?
			var failure: Error?
			session.dataTask(with: URLRequest(url: url, timeoutInterval: 8)) { _, resp, error in
				response = resp as? HTTPURLResponse
				failure = error
				semaphore.signal()
			}.resume()
			_ = semaphore.wait(timeout: .now() + 10)
			guard let http = response else {
				Log.error("\(TAG) Failed to follow QR link [\(hostOf(current))]: \(failure.map { String(describing: type(of: $0)) } ?? "timeout")")
				return nil
			}
			var next: String?
			if (300...399).contains(http.statusCode) {
				next = http.value(forHTTPHeaderField: "Location")
			} else if let refresh = http.value(forHTTPHeaderField: "Refresh"),
					  let range = refresh.range(of: "url=", options: .caseInsensitive) {
				next = String(refresh[range.upperBound...])
			}
			guard let target = next?.trimmingCharacters(in: .whitespaces), !target.isEmpty,
				  let nextUrl = URL(string: target, relativeTo: url)?.absoluteURL else {
				Log.error("\(TAG) QR link [\(hostOf(current))] answered [\(http.statusCode)] without a redirection")
				return nil
			}
			current = upgradeToHttps(nextUrl.absoluteString)
		}
		return isProvisioningUrl(current) ? current : nil
	}
	
	// MARK: - Account Key Helpers
	
	private func extractAccountKeyCounts(core: Core) -> [String: Int] {
		var counts: [String: Int] = [:]
		for account in core.accountList {
			if let params = account.params,
			   let username = params.identityAddress?.username,
			   let domain = params.identityAddress?.domain {
				let key = "\(username)@\(domain)"
				counts[key, default: 0] += 1
			}
		}
		return counts
	}

	private func didProvisioningAddOnlyDuplicates(previousCounts: [String: Int], currentCounts: [String: Int]) -> Bool {
		guard !previousCounts.isEmpty else { return false }

		let previousKeys = Set(previousCounts.keys)
		let currentKeys = Set(currentCounts.keys)
		let hasNewKey = !currentKeys.subtracting(previousKeys).isEmpty
		if hasNewKey {
			return false
		}

		let previousTotal = previousCounts.values.reduce(0, +)
		let currentTotal = currentCounts.values.reduce(0, +)
		return currentTotal > previousTotal
	}

	private func removeProvisionedDuplicates(core: Core, previousCounts: [String: Int]) {
		var removableByKey: [String: Int] = [:]
		let currentCounts = extractAccountKeyCounts(core: core)

		for (key, currentCount) in currentCounts {
			let previousCount = previousCounts[key] ?? 0
			if currentCount > previousCount {
				removableByKey[key] = currentCount - previousCount
			}
		}

		guard !removableByKey.isEmpty else { return }

		var accountsToRemove: [Account] = []
		for account in core.accountList.reversed() {
			if let params = account.params,
			   let username = params.identityAddress?.username,
			   let domain = params.identityAddress?.domain {
				let key = "\(username)@\(domain)"
				if let remaining = removableByKey[key], remaining > 0 {
					accountsToRemove.append(account)
					if remaining == 1 {
						removableByKey.removeValue(forKey: key)
					} else {
						removableByKey[key] = remaining - 1
					}
				}
			}
		}

		for account in accountsToRemove {
			Log.warn("\(Coordinator.TAG) Removing duplicate account: \(account.params?.identityAddress?.asStringUriOnly() ?? "")")
			core.removeAccount(account: account)
		}
	}
	
	// MARK: - XML de la cuenta (MundoPhone)

	enum Descarga {
		case xml(String)
		/// El aviso que toca (ToastView): caducado, ya usado o el genérico
		case failed(toast: String)
	}

	/// El XML de la cuenta del QR. Los campos del enlace (con la firma) van en el cuerpo de un POST, que no queda en ningún
	/// registro de URLs; en la URL, para el registro del panel (nginx), que no los usa ni los firma: source, la app, su
	/// versión y la extensión (x). Nada de las cuentas que ya tiene la app. El panel contesta con un JSON de error si el
	/// enlace ha caducado, no es válido o la extensión no existe: su «message» va al registro. Bloquea: llamarla fuera del
	/// hilo principal y del Core.
	static func downloadXml(_ url: String) -> Descarga {
		let info = Bundle.main.infoDictionary
		let version = "\(info?["CFBundleShortVersionString"] as? String ?? "")-\(info?["CFBundleVersion"] as? String ?? "")"
		let extra = [("source", "linphonexml"), ("app", "MundoPhone-iOS"), ("ver", version)]
		let post = MundoPhoneAprovisionamiento.peticionPost(enlace: url, extra: extra)
		var respuesta = request(post)
		// Transición: el panel acepta el POST desde una entrega reciente. Hasta que esté subida
		// solo lee el enlace de la URL y contesta que le faltan campos o que no es válido: entonces, una vez, como antes.
		// Quitar cuando la entrega lleve tiempo en producción.
		if post.cuerpo != nil, respuesta?.xml == nil,
		   let m = respuesta?.message, m.range(of: "not valid", options: .caseInsensitive) != nil || m.range(of: "missing", options: .caseInsensitive) != nil {
			Log.warn("\(TAG) Panel didn't take the link by POST [\(m)], trying again by GET")
			respuesta = request(MundoPhoneAprovisionamiento.peticionGet(enlace: url, extra: extra))
		}
		guard let r = respuesta else { return .failed(toast: "Failed_remote_provisioning") }
		if let xml = r.xml {
			Log.info("\(TAG) QR code configuration downloaded from [\(hostOf(url))], [\(r.bytes)] bytes")
			return .xml(xml)
		}
		Log.error("\(TAG) QR code configuration not downloaded: HTTP [\(r.code)], [\(r.bytes)] bytes, message [\(r.message)]")
		if r.message.range(of: "expired", options: .caseInsensitive) != nil { return .failed(toast: "QR_code_expired") }
		// El panel gasta el enlace al entregar la configuración (un solo uso, con unos minutos de margen)
		if r.message.range(of: "already used", options: .caseInsensitive) != nil { return .failed(toast: "QR_code_used") }
		return .failed(toast: "Failed_remote_provisioning")
	}

	private struct Respuesta {
		let code: Int
		let bytes: Int
		let xml: String?
		let message: String
	}

	/// Una petición al panel; nil si ni siquiera se pudo hacer (ya registrado). Bloquea.
	private static func request(_ peticion: MundoPhoneAprovisionamiento.Peticion) -> Respuesta? {
		guard let target = URL(string: peticion.url) else { return nil }
		var req = URLRequest(url: target, timeoutInterval: 8)
		if let cuerpo = peticion.cuerpo {
			req.httpMethod = "POST"
			req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
			req.httpBody = Data(cuerpo.utf8)
		}
		let session = URLSession(configuration: .ephemeral, delegate: NoRedirectDelegate(), delegateQueue: nil)
		defer { session.invalidateAndCancel() }
		let semaphore = DispatchSemaphore(value: 0)
		var response: HTTPURLResponse?
		var body: Data?
		var failure: Error?
		session.dataTask(with: req) { data, resp, error in
			body = data
			response = resp as? HTTPURLResponse
			failure = error
			semaphore.signal()
		}.resume()
		_ = semaphore.wait(timeout: .now() + 10)
		guard let http = response, let data = body else {
			Log.error("\(TAG) Failed to download QR code configuration from [\(hostOf(peticion.url))]: \(failure.map { String(describing: type(of: $0)) } ?? "timeout")")
			return nil
		}
		var text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		if text.hasPrefix("\u{FEFF}") {
			text = String(text.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines)
		}
		if (200...299).contains(http.statusCode) && data.count <= MundoPhoneAprovisionamiento.maxBytes &&
			(text.hasPrefix("<config") || text.hasPrefix("<?xml")) {
			return Respuesta(code: http.statusCode, bytes: data.count, xml: text, message: "")
		}
		let message = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["message"] as? String ?? ""
		return Respuesta(code: http.statusCode, bytes: data.count, xml: nil, message: String(message.prefix(80)))
	}

	static let qrXmlFileName = "mundophone-qr.xml"

	static func qrXmlFile() -> URL {
		return FileManager.default.temporaryDirectory.appendingPathComponent(qrXmlFileName)
	}

	/// Añade la cuenta del XML sin tocar las demás: con el Core parado (al parar, el SDK reescribe sus cuentas en la
	/// configuración), se renumera contra lo que hay y se le da al SDK como file://, que lo aplica al arrancar y antes de
	/// leer las cuentas (MundoPhoneAprovisionamiento).
	private func applyXml(core: Core, xml: String) {
		// MundoPhone: el mismo QR otra vez (la misma cuenta, con la misma contraseña) no reinicia el Core: no hay nada que
		// aplicar, y en Android varios reinicios seguidos tumbaron la app dentro del SDK (21/11/2026, ver sinCambios)
		if let config = core.config,
		   MundoPhoneAprovisionamiento.sinCambios(xml: xml, existentes: MundoPhoneAprovisionamiento.leerExistentes(config: config)) {
			Log.info("\(Coordinator.TAG) QR code account is already configured with the same password, nothing to apply")
			isProvisioningInProgress = false
			accountKeyCountsBeforeProvisioning = nil
			DispatchQueue.main.async {
				ToastViewModel.shared.toastMessage = "Success_qr_code_already_configured"
				ToastViewModel.shared.displayToast = true
				NotificationCenter.default.post(name: .qrCodeProvisioningSuccess, object: true)
			}
			return
		}
		Log.info("\(Coordinator.TAG) Adding the QR code account, restarting the Core to apply it")
		core.stop()
		do {
			guard let config = core.config as Config? else {
				throw MundoPhoneAprovisionamiento.XmlNoValido(description: "no config")
			}
			let existing = MundoPhoneAprovisionamiento.leerExistentes(config: config)
			let result = try MundoPhoneAprovisionamiento.reescribir(xml: xml, existentes: existing)
			let file = Coordinator.qrXmlFile()
			try result.xml.write(to: file, atomically: true, encoding: .utf8)
			try core.setProvisioninguri(newValue: "file://\(file.path)")
			// Al disco ANTES de arrancar: con el Core parado, start() vuelve a leer la configuración del disco («Reading config
			// information from …/linphonerc») y lo que solo estaba en memoria se pierde. Sin esto el SDK no veía el file:// y no
			// aplicaba la cuenta del QR; en un iPhone con una URI vieja guardada (un QR de Canon) aplicaba esa
			// (visto en iOS 27.2.1).
			try config.sync()
			Log.info("\(Coordinator.TAG) QR code configuration ready: [\(result.nuevas)] new account(s), [\(result.actualizadas)] updated, [\(existing.cuentas.count)] already there")
		} catch {
			Log.error("\(Coordinator.TAG) Invalid QR code configuration: \(error)")
			provisioningFailed(toast: "Failed_remote_provisioning")
		}
		do {
			Log.info("\(Coordinator.TAG) Core has been stopped, restarting it")
			try core.start()
			Log.info("\(Coordinator.TAG) Core has been restarted")
		} catch {
			Log.error("\(Coordinator.TAG) Error restarting the Core: \(error)")
		}
	}

	// MARK: - URL Configuration Handler
	
	private func handleUrlConfiguration(core: Core, url: String) {
		guard let urlObj = URL(string: url),
			  urlObj.scheme == "https" else {  // MundoPhone: solo https (los http ya se han pasado a https)
			isProvisioningInProgress = false
			accountKeyCountsBeforeProvisioning = nil
			Log.error("\(Coordinator.TAG) The content of the QR Code doesn't seem to be a valid web URL")
			DispatchQueue.main.async {
				ToastViewModel.shared.toastMessage = "QR_code_invalid"
				ToastViewModel.shared.displayToast = true
			}
			return
		}
		
		do {
			Log.info("\(Coordinator.TAG) Processing URL configuration, host [\(Coordinator.hostOf(url))]")
			try core.setProvisioninguri(newValue: url)
			core.stop()
			Log.info("\(Coordinator.TAG) Core has been stopped, restarting it")
			try core.start()
			Log.info("\(Coordinator.TAG) Core has been restarted")
		} catch {
			isProvisioningInProgress = false
			accountKeyCountsBeforeProvisioning = nil
			Log.error("\(Coordinator.TAG) Error processing URL configuration: \(error)")
			DispatchQueue.main.async {
				ToastViewModel.shared.toastMessage = "QR_code_invalid"
				ToastViewModel.shared.displayToast = true
				NotificationCenter.default.post(name: .qrCodeProvisioningError, object: nil)
			}
		}
	}
}
