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
import CryptoKit
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
            }, onConfiguringStatus: { (_: Core, status: ConfiguringState, message: String) in
                Log.info("\(Coordinator.TAG) Configuring state is [\(status)]")
                if status == .Failed {
					self.isProvisioningInProgress = false
					self.accountKeyCountsBeforeProvisioning = nil
                    Log.error("\(Coordinator.TAG) Failure applying remote provisioning: \(message)")
                    DispatchQueue.main.async {
                        ToastViewModel.shared.toastMessage = "Failed_remote_provisioning"
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
			self.accountKeyCountsBeforeProvisioning = self.extractAccountKeyCounts(core: core)

			if result.trimmingCharacters(in: .whitespaces).hasPrefix("<config") {
				self.handleXmlConfiguration(core: core, xmlConfig: result)
				return
			}

			let accountsJson = self.buildAccountsJson(core: core)
			let url = Coordinator.upgradeToHttps(result)
			// Sin cuentas no hay nada que proteger, y si el QR ya apunta a la API se usa tal cual
			if core.accountList.isEmpty || Coordinator.isProvisioningUrl(url) {
				self.handleUrlConfiguration(core: core, url: Coordinator.appendAccounts(url, accountsJson))
				return
			}

			// MundoPhone (como en Android): el QR del panel es un enlace corto de urla.ws. Añadirle el login y el MD5 de
			// la clave de TODAS las cuentas los dejaría en el registro del acortador, así que se resuelve primero SIN
			// datos y solo se añaden a la URL final si es https://api.mundosms.es. Si no se llega
			// ahí, error: aprovisionar sin las cuentas que ya hay podría pisarlas.
			DispatchQueue.global(qos: .userInitiated).async {
				let finalUrl = Coordinator.resolveRedirects(url)
				self.coreContext.doOnCoreQueue { core in
					if let finalUrl = finalUrl {
						self.handleUrlConfiguration(core: core, url: Coordinator.appendAccounts(finalUrl, accountsJson))
					} else {
						self.isProvisioningInProgress = false
						self.accountKeyCountsBeforeProvisioning = nil
						Log.error("\(Coordinator.TAG) QR link didn't lead to https://\(Coordinator.provisioningHost), existing accounts haven't been sent")
						DispatchQueue.main.async {
							ToastViewModel.shared.toastMessage = "QR_code_invalid"
							ToastViewModel.shared.displayToast = true
							NotificationCenter.default.post(name: .qrCodeProvisioningError, object: nil)
						}
					}
				}
			}
		}
	}

	// MARK: - QR link (MundoPhone)

	/// Único destino al que se mandan los datos de las cuentas que ya hay (login + MD5 de la clave): la API del panel.
	static let provisioningHost = "api.mundosms.es"
	private static let maxRedirects = 5

	static func hostOf(_ url: String) -> String {
		return URL(string: url)?.host ?? ""
	}

	static func isProvisioningUrl(_ url: String) -> Bool {
		guard let u = URL(string: url) else { return false }
		return u.scheme?.lowercased() == "https" && u.host?.lowercased() == provisioningHost
	}

	/// urla.ws sirve http y https; iOS (ATS) no deja ir por http
	static func upgradeToHttps(_ url: String) -> String {
		return url.replacingOccurrences(of: "^http://", with: "https://", options: [.regularExpression, .caseInsensitive])
	}

	static func appendAccounts(_ url: String, _ accountsJson: String) -> String {
		var allowed = CharacterSet.alphanumerics
		allowed.insert(charactersIn: "-._~")
		let encoded = accountsJson.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
		let separator = url.contains("?") ? "&" : "?"
		return "\(url)\(separator)source=linphonexml&jsonaddaccounts=\(encoded)"
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
	
	// MARK: - Account JSON Builder
	
	private func buildAccountsJson(core: Core) -> String {
		var accountsArray: [[String: String]] = []
		
		for account in core.accountList {
			guard let params = account.params,
				  let identity = params.identityAddress?.username else {
				continue
			}
			
			var password = ""
			if let authInfo = account.findAuthInfo() {
				password = authInfo.password ?? ""
				Log.info("\(Coordinator.TAG) Found auth info for username [\(authInfo.username ?? "")]")
			} else {
				Log.warn("\(Coordinator.TAG) Failed to find auth info for account [\(params.identityAddress?.asStringUriOnly() ?? "")]")
			}
			
			accountsArray.append([
				"login": identity,
				"md5pass": calculateMD5Hash(input: password)
			])
		}
		
		let jsonObject: [String: Any] = ["accounts": accountsArray]
		if let data = try? JSONSerialization.data(withJSONObject: jsonObject),
		   let jsonString = String(data: data, encoding: .utf8) {
			// MundoPhone: sin el JSON en el registro (lleva el MD5 de las claves, y el registro acaba en Crashlytics)
			Log.info("\(Coordinator.TAG) Built accounts JSON for [\(accountsArray.count)] account(s)")
			return jsonString
		}
		
		return "{\"accounts\":[]}"
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
	
	// MARK: - MD5 Hashing
	
	private func calculateMD5Hash(input: String) -> String {
		let digest = Insecure.MD5.hash(data: Data(input.utf8))
		return digest.map { String(format: "%02x", $0) }.joined()
	}
	
	// MARK: - XML Configuration Handler
	
	private func handleXmlConfiguration(core: Core, xmlConfig: String) {
		Log.info("\(Coordinator.TAG) Processing XML configuration from QR")
		do {
			try core.setProvisioninguri(newValue: xmlConfig)
			Log.info("\(Coordinator.TAG) Setting provisioning URI with XML content: \(String(xmlConfig.prefix(100)))...")
			core.stop()
			Log.info("\(Coordinator.TAG) Core has been stopped, restarting it")
			try core.start()
			Log.info("\(Coordinator.TAG) Core has been restarted")
		} catch {
			isProvisioningInProgress = false
			accountKeyCountsBeforeProvisioning = nil
			Log.error("\(Coordinator.TAG) Error processing XML configuration: \(error)")
			DispatchQueue.main.async {
				ToastViewModel.shared.toastMessage = "QR_code_invalid"
				ToastViewModel.shared.displayToast = true
				NotificationCenter.default.post(name: .qrCodeProvisioningError, object: nil)
			}
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
