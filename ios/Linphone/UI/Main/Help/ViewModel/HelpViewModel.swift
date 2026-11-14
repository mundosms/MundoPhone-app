/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-iphone
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

import linphonesw
import SwiftUI

class HelpViewModel: ObservableObject {
	private let TAG = "[HelpViewModel]"

	@Published var logcat: Bool = false
	@Published var version: String = ""
	@Published var appVersion: String = ""
	@Published var sdkVersion: String = ""
	@Published var firebaseProjectId: String = ""
	@Published var checkUpdateAvailable: Bool = false
	@Published var uploadLogsAvailable: Bool = false
	@Published var logsUploadInProgress: Bool = false
	@Published var versionAvailable: String = ""
	@Published var urlVersionAvailable: String = ""
	// MundoPhone: resultado de enviar los registros a soporte. La referencia es lo que el servidor devuelve tras
	// «/r/», para que el usuario la dé si le llaman; nil = no hay nada que enseñar.
	@Published var logsUploadReference: String?
	@Published var logsUploadFailed: Bool = false
	
	private var coreDelegate: CoreDelegate?
	
	/// MundoPhone: como queryValue de Android. El SDK descodifica la URL de subida y la vuelve a codificar (belle-sip,
	/// generic-uri, con sus reglas de query): un «+» por espacio llegaba como «+» literal y un «&» del usuario quedaba sin
	/// codificar y partía el campo. Con %20 para el espacio y el «&» de ancho completo (U+FF06) los dos viajes dejan el
	/// texto igual. Se codifica todo menos letras y cifras ASCII y «-._*», igual que URLEncoder de Java, así que la URL sale
	/// idéntica a la de Android.
	private static let queryAllowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._*")
	
	private static func queryValue(_ value: String) -> String {
		return value.replacingOccurrences(of: "&", with: "＆").addingPercentEncoding(withAllowedCharacters: queryAllowed) ?? ""
	}
	
	/// MundoPhone: «Apple iPhone15,2 / iOS 18.1», como el «Google Pixel 8 / Android 15» de Android: el servidor lo
	/// pone en el correo a soporte. Usa UIDevice: llamar desde el hilo principal.
	private static func deviceDescription() -> String {
		var systemInfo = utsname()
		uname(&systemInfo)
		let machine = withUnsafeBytes(of: &systemInfo.machine) { raw in
			String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
		}
		return "Apple \(machine) / \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
	}

	init() {
		let appName = Bundle.main.infoDictionary?["CFBundleName"] as? String
		let versionTmp = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
		let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String
		
		self.version = (versionTmp ?? "6.0.0")
		
		self.sdkVersion = Core.getVersion
		
		if let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
		   let plist = NSDictionary(contentsOfFile: path) as? [String: Any],
		   let projectID = plist["PROJECT_ID"] as? String {
			firebaseProjectId = projectID
		}
		
		CoreContext.shared.doOnCoreQueue { core in
			// MundoPhone: sin servidor de registros, sin botón de enviar (como Android)
			let uploadLogsAvailableTmp = !CorePreferences.logsUploadUrl.isEmpty
			DispatchQueue.main.async {
				self.uploadLogsAvailable = uploadLogsAvailableTmp
			}
			
			self.coreDelegate = CoreDelegateStub(
				onLogCollectionUploadStateChanged: {(
							core: Core,
							state: Core.LogCollectionUploadState,
							info: String
						) in
					// MundoPhone: upstream solo atiende el final bueno y, si falla, el aviso de «subiendo» se queda para
					// siempre. Aquí: entregado → referencia; no entregado → error.
					if state == .InProgress {
						return
					}
					// La URL de subida llevaba lo que escribió el usuario: se vuelve a la de reposo
					let base = CorePreferences.logsUploadUrl
					if !base.isEmpty && core.logCollectionUploadServerUrl != base {
						core.logCollectionUploadServerUrl = base
					}
					if state == .Delivered && info.hasPrefix("http") {
						var reference = info
						if let range = info.range(of: "/r/", options: .backwards) {
							reference = String(info[range.upperBound...])
						}
						Log.info("\(self.TAG) Debug logs delivered to support server")
						DispatchQueue.main.async {
							self.logsUploadInProgress = false
							self.logsUploadReference = reference
						}
					} else {
						Log.error("\(self.TAG) Debug logs upload failed [\(state)]: \(info)")
						DispatchQueue.main.async {
							self.logsUploadInProgress = false
							self.logsUploadFailed = true
						}
					}
				},
				onVersionUpdateCheckResultReceived: {(
					core: Core,
					result: VersionUpdateCheckResult,
					version: String?,
					url: String?
				) in
					switch result {
					case .NewVersionAvailable:
						if let version = version, let url = url {
							Log.info("\(self.TAG): Update available, version [\(version)], url [\(url)]")
							DispatchQueue.main.async {
								self.versionAvailable = version
								self.urlVersionAvailable = url
								self.checkUpdateAvailable = true
							}
						}
					case .UpToDate:
						Log.info("\(self.TAG): This version is up-to-date")
						DispatchQueue.main.async {
							ToastViewModel.shared.toastMessage = "Success_version_up_to_date"
							ToastViewModel.shared.displayToast = true
						}
					default:
						Log.info("\(self.TAG): Can't check for update, an error happened [\(result)]")
						DispatchQueue.main.async {
							ToastViewModel.shared.toastMessage = "Error"
							ToastViewModel.shared.displayToast = true
						}
					}
				}
			)
			core.addDelegate(delegate: self.coreDelegate!)
		}
	}
	
	deinit {
		if let delegate = coreDelegate {
			CoreContext.shared.doOnCoreQueue { core in
				core.removeDelegate(delegate: delegate)
			}
		}
	}
	
	/*
	func toggleLogcat() {
		let newValue = !self.logcat
		CoreContext.shared.doOnCoreQueue { core in
			CorePreferences.printLogsInLogcat = newValue
			Factory.Instance.enableLogcatLogs(newValue)
			self.logcat = newValue
		}
	}
	*/
	
	/// MundoPhone: sube el registro al servidor de soporte (mundophonedebug) con el mismo contrato que Android. Lo
	/// que escribe el usuario (d, obligatorio; c, opcional), sus extensiones (a), la versión de la app (v) y el móvil (m)
	/// van en la URL de subida; el SDK solo manda el fichero, con el protocolo de liblinphone (RCS 5.1): POST vacío → 204,
	/// POST multipart con el campo «File» (.gz) → 200 y un XML cuya URL acaba en /r/<referencia>. Sustituye a la subida a
	/// files.linphone.org de upstream. Llamar desde el hilo principal.
	func uploadLogsToSupport(description: String, contact: String) {
		let device = HelpViewModel.deviceDescription()
		let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
		logsUploadInProgress = true
		CoreContext.shared.doOnCoreQueue { core in
			let base = CorePreferences.logsUploadUrl
			if base.isEmpty {
				Log.error("\(self.TAG) No logs upload server configured")
				DispatchQueue.main.async {
					self.logsUploadInProgress = false
					self.logsUploadFailed = true
				}
				return
			}
			let accounts = core.accountList.compactMap { $0.params?.identityAddress?.username }.joined(separator: ",")
			let query = [
				"d=" + HelpViewModel.queryValue(description),
				"c=" + HelpViewModel.queryValue(contact),
				"a=" + HelpViewModel.queryValue(accounts),
				"v=" + HelpViewModel.queryValue(appVersion),
				"m=" + HelpViewModel.queryValue(device)
			].joined(separator: "&")
			// La URL lleva lo que ha escrito el usuario: no va al registro. Se restaura al acabar (delegado de arriba).
			core.logCollectionUploadServerUrl = base + "?" + query
			Log.info("\(self.TAG) Uploading debug logs to support server")
			core.uploadLogCollection()
		}
	}
	
	func cleanLogs() {
		CoreContext.shared.doOnCoreQueue { _ in
			Core.resetLogCollection()
			Log.info("\(self.TAG) Debug logs have been cleaned")
			DispatchQueue.main.async {
				ToastViewModel.shared.toastMessage = "Success_clear_logs"
				ToastViewModel.shared.displayToast = true
			}
		}
	}

	func checkForUpdate() {
		let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
		CoreContext.shared.doOnCoreQueue { core in
			Log.info("\(self.TAG) Checking for update using current version \(currentVersion ?? "6.0.0")")
			core.checkForUpdate(currentVersion: currentVersion ?? "6.0.0")
		}
	}

	/*
	func showConfigFile() {
		CoreContext.shared.doOnCoreQueue { core in
			Log.i("\(self.TAG) Dumping & displaying Core's config")
			let config = core.config.dump()
			let file = FileUtils.getFileStorageCacheDir(
				"linphonerc.txt",
				overrideExisting: true
			)
			DispatchQueue.main.async {
				if FileUtils.dumpStringToFile(config, file: file) {
					Log.i("\(self.TAG) .linphonerc string saved as file in cache folder")
					self.showConfigFileEvent = Event(value: file.absolutePath)
				} else {
					Log.e("\(self.TAG) Failed to save .linphonerc string as file in cache folder")
				}
			}
		}
	}

	func clearNativeFriendsDatabase() {
		CoreContext.shared.doOnCoreQueue { core in
			if let list = core.getFriendListByName(NATIVE_ADDRESS_BOOK_FRIEND_LIST) {
				let friends = list.friends
				Log.i("\(self.TAG) Friend list to remove found with [\(friends.count)] friends")
				for friend in friends {
					list.removeFriend(friend)
				}
				core.removeFriendList(list)
				Log.i("\(self.TAG) Friend list [\(NATIVE_ADDRESS_BOOK_FRIEND_LIST)] removed")
			}
		}
	}
	*/
}
