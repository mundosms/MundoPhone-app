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
import linphonesw
import Combine

class ContactAvatarModel: ObservableObject, Identifiable {
	let id = UUID()
	
	var friend: Friend?
	
	@Published var name: String = ""
	@Published var address: String = ""
	@Published var addresses: [String] = []
	@Published var phoneNumbersWithLabel: [(label: String, phoneNumber: String)] = []
	
	var nativeUri: String = ""
	var withPresence: Bool?
	
	@Published var starred: Bool = false
	
	var vcard: Vcard?
	var organization: String = ""
	var jobTitle: String = ""
	
	@Published var photo: String = ""
	@Published var lastPresenceInfo: String = ""
	@Published var presenceStatus: ConsolidatedPresence = .Offline

	// MundoPhone: Equipo por la lista del borde (TeamRlsManager). Las extensiones del contacto (sus direcciones del equipo:
	// casa con su compañero por cualquiera, como Android), su línea de estado (nil = sin dato o no es de la lista) y con quién
	// habla, ya con nombre
	@Published var teamUsers: [String] = []
	@Published var teamLinea: TeamRls.Linea?
	@Published var teamCon: String?
	/** Compañero que solo está en la lista del borde: se llama «(111) - Ana» y se ordena (e inicia) por «Ana» */
	var esDelBorde = false
	var nombreOrden: String { esDelBorde ? TeamRls.sinFavorito(name) : name }
	private var teamCancellable: AnyCancellable?

	private var friendDelegate: FriendDelegate?
	
	init(friend: Friend?, name: String, address: String, withPresence: Bool?) {
		self.name = name
	 	self.address = address
		self.resetContactAvatarModel(friend: friend, name: name, address: address, withPresence: withPresence)
	}
	
	func resetContactAvatarModel(friend: Friend?, name: String, address: String, withPresence: Bool?) {
		CoreContext.shared.doOnCoreQueue { _ in
			self.friend = friend
			let nameTmp = name
			let addressTmp = address
			var addressesTmp: [String] = []
			if let friend = friend {
				friend.addresses.forEach { address in
					addressesTmp.append(address.asStringUriOnly())
				}
			}
			var phoneNumbersWithLabelTmp: [(label: String, phoneNumber: String)] = []
			if let friend = friend {
				friend.phoneNumbersWithLabel.forEach { phoneNum in
					phoneNumbersWithLabelTmp.append((label: phoneNum.label ?? "", phoneNumber: phoneNum.phoneNumber))
				}
			}
			let nativeUriTmp = friend?.nativeUri ?? ""
			let withPresenceTmp = withPresence
			let starredTmp = friend?.starred ?? false
			let vcardTmp = friend?.vcard ?? nil
			let organizationTmp = friend?.organization ?? ""
			let jobTitleTmp = friend?.jobTitle ?? ""
			let photoTmp = friend?.photo ?? ""
			var lastPresenceInfoTmp = ""
			var presenceStatusTmp: ConsolidatedPresence = .Offline
			let esDelBordeTmp = friend?.friendList?.displayName == TeamRlsManager.listName
			var teamUsersTmp: [String] = []
			for direccion in (friend != nil ? addressesTmp : [addressTmp]) {
				if let usuario = TeamRls.usuarioEquipo(direccion), !teamUsersTmp.contains(usuario) { teamUsersTmp.append(usuario) }
			}

			if let friend = friend, withPresence == true {
                
				lastPresenceInfoTmp = ""
				
				presenceStatusTmp = friend.consolidatedPresence
                
				if friend.consolidatedPresence == .Online || friend.consolidatedPresence == .Busy {
					if friend.consolidatedPresence == .Online || friend.presenceModel?.latestActivityTimestamp != -1 {
						lastPresenceInfoTmp = (friend.consolidatedPresence == .Online) ?
						"Online" : self.getCallTime(startDate: friend.presenceModel!.latestActivityTimestamp)
					} else {
						lastPresenceInfoTmp = "Away"
					}
				}
				
				if let delegate = self.friendDelegate {
					self.friend?.removeDelegate(delegate: delegate)
					self.friendDelegate = nil
				}
				
				self.addFriendDelegate()
			}
			
			DispatchQueue.main.async {
				self.name = nameTmp
				self.address = addressTmp
				self.addresses = addressesTmp
				self.phoneNumbersWithLabel = phoneNumbersWithLabelTmp
				self.nativeUri = nativeUriTmp
				self.withPresence = withPresenceTmp
				self.starred = starredTmp
				self.vcard = vcardTmp
				self.organization = organizationTmp
				self.jobTitle = jobTitleTmp
				self.photo = photoTmp
				self.lastPresenceInfo = lastPresenceInfoTmp
				self.presenceStatus = presenceStatusTmp
				self.esDelBorde = esDelBordeTmp
				if self.teamUsers != teamUsersTmp { self.teamUsers = teamUsersTmp }
				self.engancharEquipo()
			}
		}
	}

	/** MundoPhone: sigue el estado de su compañero en la lista del borde (en el hilo principal, como lo publica el gestor). */
	private func engancharEquipo() {
		guard !teamUsers.isEmpty else {
			teamCancellable = nil
			aplicarEquipo([:])
			return
		}
		if teamCancellable == nil {
			// Al engancharse llega enseguida el valor de ahora
			teamCancellable = TeamRlsManager.shared.$estados.sink { [weak self] estados in
				self?.aplicarEquipo(estados)
			}
		} else {
			aplicarEquipo(TeamRlsManager.shared.estados)
		}
	}

	private func aplicarEquipo(_ estados: [String: TeamRlsManager.Vista]) {
		guard let vista = teamUsers.lazy.compactMap({ estados[$0] }).first, let linea = vista.linea else {
			// Sin dato no se pinta nada; si antes había, se quita
			if teamLinea != nil {
				teamLinea = nil
				teamCon = nil
				presenceStatus = .Offline
			}
			return
		}
		if teamLinea != linea { teamLinea = linea }
		if teamCon != vista.con { teamCon = vista.con }
		// El punto del avatar: verde libre, rojo al teléfono; el resto sin punto (iOS no pinta los demás)
		let presencia: ConsolidatedPresence
		switch linea.situacion {
		case .disponible: presencia = .Online
		case .sonando, .marcando, .enLlamada: presencia = .Busy
		case .enEspera, .noMolestar, .ausente, .desconectado: presencia = .Offline
		}
		if presenceStatus != presencia { presenceStatus = presencia }
	}
	
	func addFriendDelegate() {
		friendDelegate = FriendDelegateStub(onPresenceReceived: { (friend: Friend) in
			let latestActivityTimestamp = friend.presenceModel?.latestActivityTimestamp ?? -1
			let consolidatedPresenceTmp = friend.consolidatedPresence
			DispatchQueue.main.async {
				self.presenceStatus = consolidatedPresenceTmp
				if consolidatedPresenceTmp == .Online || consolidatedPresenceTmp == .Busy {
					if consolidatedPresenceTmp == .Online || latestActivityTimestamp != -1 {
						self.lastPresenceInfo = consolidatedPresenceTmp == .Online ?
						"Online" : self.getCallTime(startDate: latestActivityTimestamp)
					} else {
						self.lastPresenceInfo = "Away"
					}
				} else {
					self.lastPresenceInfo = ""
				}
			}
		})
		
		if friend != nil && friendDelegate != nil {
			friend!.addDelegate(delegate: friendDelegate!)
		}
	}
	
	func removeFriendDelegate() {
		if let delegate = friendDelegate {
			DispatchQueue.main.async {
				self.presenceStatus = .Offline
			}
			if let friendTmp = friend {
				friendTmp.removeDelegate(delegate: delegate)
			}
			friendDelegate = nil
		}
	}
	
	func getCallTime(startDate: time_t) -> String {
		let timeInterval = TimeInterval(startDate)
		
		let myNSDate = Date(timeIntervalSince1970: timeInterval)
		
		if Calendar.current.isDateInToday(myNSDate) {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "HH:mm" : "h:mm a"
			return "Online today at " + formatter.string(from: myNSDate)
		} else if Calendar.current.isDateInYesterday(myNSDate) {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "HH:mm" : "h:mm a"
			return "Online yesterday at " + formatter.string(from: myNSDate)
		} else if Calendar.current.isDate(myNSDate, equalTo: .now, toGranularity: .year) {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "dd/MM | HH:mm" : "MM/dd | h:mm a"
			return "Online on " + formatter.string(from: myNSDate)
		} else {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "dd/MM/yy | HH:mm" : "MM/dd/yy | h:mm a"
			return "Online on " + formatter.string(from: myNSDate)
		}
	}
	
	static func getAvatarModelFromAddress(address: Address, completion: @escaping (ContactAvatarModel) -> Void) {
		ContactsManager.shared.getFriendWithAddressInCoreQueue(address: address) { resultFriend in
			if let addressFriend = resultFriend {
				if addressFriend.address != nil {
					var avatarModel = ContactsManager.shared.avatarListModel.first(where: {
						$0.friend != nil && $0.friend!.name == addressFriend.name && $0.friend!.address != nil
						&& $0.friend!.address!.asStringUriOnly() == addressFriend.address!.asStringUriOnly()
					})
					
					if avatarModel == nil {
						avatarModel = ContactAvatarModel(friend: nil, name: addressFriend.name!, address: addressFriend.address!.asStringUriOnly(), withPresence: false)
					}
					completion(avatarModel!)
				} else if let delContacto = ContactsManager.shared.avatarListModel.first(where: {
					$0.friend != nil && $0.friend!.name == addressFriend.name
				}) {
					// MundoPhone: contacto de la agenda (solo teléfono, sin dirección SIP): su ficha, con su nombre y sus
					// iniciales, y no el número («+» en el círculo de la pantalla de llamada)
					completion(delContacto)
				} else {
					var name = ""
					if address.displayName != nil {
						name = address.displayName!
					} else if address.username != nil {
						name = address.username!
					} else {
						name = String(address.asStringUriOnly().dropFirst(4))
					}
					completion(ContactAvatarModel(friend: nil, name: name, address: address.asStringUriOnly(), withPresence: false))
				}
			} else {
				var name = ""
				if address.displayName != nil {
					name = address.displayName!
				} else if address.username != nil {
					name = address.username!
				} else {
					name = String(address.asStringUriOnly().dropFirst(4))
				}
				completion(ContactAvatarModel(friend: nil, name: name, address: address.asStringUriOnly(), withPresence: false))
			}
		}
	}
}
