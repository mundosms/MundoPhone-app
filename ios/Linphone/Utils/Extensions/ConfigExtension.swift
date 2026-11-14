/*
* Copyright (c) 2010-2023 Belledonne Communications SARL.
*
* This file is part of linphone
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
import linphonesw

// Singleton that manages the Shared Config between app and app extension.

extension Config {

	private static var _instance: Config?

	public func getDouble(section: String, key: String, defaultValue: Double) -> Double {
		if self.hasEntry(section: section, key: key) != 1 {
			return defaultValue
		}
		let stringValue = self.getString(section: section, key: key, defaultString: "")
		return Double(stringValue) ?? defaultValue
	}

	/// MundoPhone: pasa a TCP 6060 el proxy (y la ruta) de las cuentas de push.mundosms.es, ANTES de crear el Core.
	/// Varias redes móviles tienen un SIP ALG con estado en UDP 5060: esconde la Via interna de las llamadas entrantes y
	/// quita el ;rport de los REGISTER. Si el iPhone cambia de red mientras suena, el 200 sale sin la Via de Asterisk y la
	/// llamada no se establece. Medido en el borde: con TCP no pasa, ni en 5060 ni en 6060, y 10 de las 18
	/// llamadas tocadas por el ALG eran de iOS. Además, sin puerto el SDK consulta NAPTR/SRV, que no existen. Es lo mismo
	/// que hace Android (ensureProxyPort + ensureTcpTransport). Se puede repetir: devuelve cuántos valores
	/// ha cambiado.
	@discardableResult
	public func mundosmsEnsurePushTcp6060() -> Int {
		guard let withoutPort = try? NSRegularExpression(pattern: "sip:push\\.mundosms\\.es(?=[;>,]|$)"),
			  let transportParam = try? NSRegularExpression(pattern: ";transport=[A-Za-z]+", options: .caseInsensitive) else {
			return 0
		}
		var fixed = 0
		for section in sectionsNamesList where section.range(of: "^proxy_[0-9]+$", options: .regularExpression) != nil {
			for key in ["reg_proxy", "reg_route"] {
				let value = getString(section: section, key: key, defaultString: "")
				guard value.contains("push.mundosms.es") else { continue }
				var newValue = withoutPort.stringByReplacingMatches(
					in: value, range: NSRange(value.startIndex..., in: value), withTemplate: "sip:push.mundosms.es:6060")
				let range = NSRange(newValue.startIndex..., in: newValue)
				if transportParam.firstMatch(in: newValue, range: range) != nil {
					newValue = transportParam.stringByReplacingMatches(in: newValue, range: range, withTemplate: ";transport=tcp")
				} else if newValue.hasSuffix(">") {
					newValue = String(newValue.dropLast()) + ";transport=tcp>"
				} else {
					newValue += ";transport=tcp"
				}
				if newValue != value {
					setString(section: section, key: key, value: newValue)
					fixed += 1
				}
			}
		}
		if fixed > 0 {
			try? sync()
		}
		return fixed
	}

	public static func get() -> Config {
		if _instance == nil {
			let factoryPath = FileUtil.bundleFilePath("linphonerc-factory")!
			_instance =  Config.newForSharedCore(appGroupId: Config.appGroupName, configFilename: "linphonerc", factoryConfigFilename: factoryPath)!
		}
		return _instance!
	}
	
	public func getString(section: String, key: String) -> String? {
		return hasEntry(section: section, key: key) == 1  ? getString(section: section, key: key, defaultString: "") : nil
	}
	
	static let appGroupName = "group.es.mundosms.phone.msgNotification"
	// Needs to be the same name in App Group (capabilities in ALL targets - app & extensions - content + service), can't be stored in the Config itself the Config needs this value to get created
	static let teamID = Config.get().getString(section: "app", key: "team_id", defaultString: "B922HQ77WT")
	static let earlymediaContentExtCatIdentifier = Config.get().getString(section: "app", key: "extension_category", defaultString: "")
	
	// Default values in app
	static let serveraddress =  Config.get().getString(section: "app", key: "server", defaultString: "")
	static let defaultUsername =  Config.get().getString(section: "app", key: "user", defaultString: "")
	static let defaultPass =  Config.get().getString(section: "app", key: "pass", defaultString: "")
	
	static let pushNotificationsInterval =  Config.get().getInt(section: "net", key: "pn-call-remote-push-interval", defaultValue: 3)
	
	static let voiceRecordingMaxDuration =  Config.get().getInt(section: "app", key: "voice_recording_max_duration", defaultValue: 600000)

	/// MundoPhone: servidor de registros de soporte, el mismo que Android (MundoPhoneMigrations.LOGS_UPLOAD_URL).
	public static let mundosmsLogsUploadUrl = "https://mundophonedebug.mundosms.es/upload"

	/// MundoPhone: los registros nunca a Belledonne. Una configuración ya existente puede guardar files.linphone.org en
	/// [misc] log_collection_upload_server_url, y linphonerc-default solo se copia en una instalación nueva. Vacío o de
	/// linphone.org pasa a mundophonedebug, como removeBelledonneEndpoints de Android. También limpia la URL con «?d=»
	/// que deja una subida cortada a medias (HelpViewModel la pone con lo que escribe el usuario y la restaura al acabar).
	/// Barato y repetible: solo escribe si hay que cambiar algo. No hace sync(); lo hace quien la llama.
	@discardableResult
	public func mundosmsRegistrosSinBelledonne() -> Bool {
		let actual = getString(section: "misc", key: "log_collection_upload_server_url", defaultString: "")
		guard actual.isEmpty || actual.contains("linphone.org") || actual.contains("?d=") else { return false }
		setString(section: "misc", key: "log_collection_upload_server_url", value: Config.mundosmsLogsUploadUrl)
		return true
	}

	/// MundoPhone: nada a Belledonne (como Android, removeBelledonneEndpoints). La configuración de upstream pone
	/// files.linphone.org como servidor de registros y de ficheros del chat, download.linphone.org para comprobar la
	/// versión y el alta de cuentas de linphone.org, y una configuración ya existente puede conservarlos. Cada arranque,
	/// ANTES de crear el Core: registros a mundophonedebug y fuera lo demás
	/// si apunta a linphone.org (sin servidor de ficheros, el chat no sube adjuntos a ningún sitio). Barato y repetible:
	/// solo escribe si hay algo que cambiar. Devuelve lo que ha cambiado, para el registro.
	@discardableResult
	public func mundosmsSinBelledonne() -> [String] {
		var cambios: [String] = []
		if mundosmsRegistrosSinBelledonne() {
			cambios.append("misc/log_collection_upload_server_url a mundophonedebug")
		}
		for (section, key) in [("misc", "file_transfer_server_url"), ("misc", "version_check_url_root"), ("account_creator", "url")]
		where getString(section: section, key: key, defaultString: "").contains("linphone.org") {
			cleanEntry(section: section, key: key)
			cambios.append("\(section)/\(key) quitado")
		}
		if !cambios.isEmpty {
			try? sync()
		}
		return cambios
	}
}
