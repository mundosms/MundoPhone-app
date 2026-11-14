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
import Combine
import SwiftUI

final class MagicSearchSingleton: ObservableObject {
	
	static let shared = MagicSearchSingleton()
	private var coreContext = CoreContext.shared
	private var contactsManager = ContactsManager.shared
	
	private var magicSearch: MagicSearch?
	
	var currentFilter: String = ""
	var previousFilter: String?
	/// MundoPhone: la búsqueda en curso viene del marcador con solo cifras y también busca nombres por
	/// las letras del teclado (MarcadorT9). En la cola del Core: se pone en searchForContacts y se lee en el resultado.
	private var busquedaT9 = ""
	
	var needUpdateLastSearchContacts = false
	
	private var limitSearchToLinphoneAccounts = true
	
	@Published var allContact = true
	let allContactKey = "all_contact"
	
	private var domainDefaultAccount = ""
	
	var searchDelegate: MagicSearchDelegate?
    
    private var contactLoadedDebounceWorkItem: DispatchWorkItem?
    
    let nativeAddressBookFriendList = "Native address-book"
    let linphoneAddressBookFriendList = "Linphone address-book"
    let tempRemoteAddressBookFriendList = "TempRemoteDirectoryContacts address-book"
	
	func destroyMagicSearch() {
		magicSearch = nil
	}
	
	private init() {
		let preferences = UserDefaults.standard
		if preferences.object(forKey: allContactKey) == nil {
			preferences.set(allContact, forKey: allContactKey)
		} else {
			allContact = preferences.bool(forKey: allContactKey)
		}
		
		coreContext.doOnCoreQueue { core in
			self.domainDefaultAccount = core.defaultAccount?.params?.domain ?? ""
			
			self.magicSearch = try? core.createMagicSearch()
			
			guard let magicSearch = self.magicSearch else {
				return
			}
			
			magicSearch.limitedSearch = false
			
			self.searchDelegate = MagicSearchDelegateStub(onSearchResultsReceived: { (magicSearch: MagicSearch) in
				print("[MagicSearchSingleton] [onSearchResultsReceived] Received search results")
				self.needUpdateLastSearchContacts = true
				
				var lastSearchFriend: [SearchResult] = []
				var lastSearchSuggestions: [SearchResult] = []
				// Sets instead of lastSearchFriend.contains(where:): that compared each result with every result
				// already kept (O(n²) SDK calls), which hung the search with thousands of contacts
				var keptAddressKeys = Set<String>()
				var keptPhoneNumbers = Set<String>()

				// MundoPhone: los compañeros de la lista del borde (TeamRlsManager), detrás y solo si la agenda no tiene ya a
				// alguien con la misma extensión: el de la agenda trae nombre y foto, y no se repite (como Android)
				var delBorde: [SearchResult] = []
				var resto: [SearchResult] = []
				magicSearch.lastSearch.forEach { searchResult in
					if searchResult.friend?.friendList?.displayName == TeamRlsManager.listName {
						delBorde.append(searchResult)
					} else {
						resto.append(searchResult)
					}
				}
				if !delBorde.isEmpty {
					var extensionesAgenda = Set<String>()
					for searchResult in resto {
						guard let friend = searchResult.friend, self.isOwnFriendList(friend.friendList?.displayName) else { continue }
						for address in friend.addresses {
							if let usuario = TeamRls.usuarioEquipo(address.asStringUriOnly()) { extensionesAgenda.insert(usuario) }
						}
					}
					delBorde.removeAll { searchResult in
						guard let address = searchResult.friend?.address,
							  let usuario = TeamRls.usuarioEquipo(address.asStringUriOnly()) else { return true }
						return extensionesAgenda.contains(usuario)
					}
				}
				let resultadosDelBorde = Set(delBorde.map { ObjectIdentifier($0) })

				(resto + delBorde).forEach { searchResult in
                    if searchResult.friend != nil && self.isOwnFriendList(searchResult.friend?.friendList?.displayName) {
						var keep = false
						if let address = searchResult.address,
						   !keptAddressKeys.contains(MagicSearchSingleton.weakAddressKey(address)) {
							keep = true
						} else if let phoneNumber = searchResult.phoneNumber,
								  !keptPhoneNumbers.contains(phoneNumber) {
							keep = true
						}
						if keep {
							lastSearchFriend.append(searchResult)
							if let address = searchResult.address {
								keptAddressKeys.insert(MagicSearchSingleton.weakAddressKey(address))
							}
							if let phoneNumber = searchResult.phoneNumber {
								keptPhoneNumbers.insert(phoneNumber)
							}
						}
					} else {
						lastSearchSuggestions.append(searchResult)
					}
				}
				
				lastSearchSuggestions.sort(by: {
					($0.address?.asStringUriOnly() ?? "") < ($1.address?.asStringUriOnly() ?? "")
				})
				
				if let defaultAccount = core.defaultAccount, let contactAddress = defaultAccount.contactAddress {
					lastSearchSuggestions.removeAll {
						$0.address?.weakEqual(address2: contactAddress) ?? false
					}
				}
				
				// Sort keys computed once per result, not twice per comparison. MundoPhone: los de la lista del borde se llaman
				// «(111) - Ana» y se ordenan por «Ana» (si no, todos delante por el paréntesis)
				let sortedLastSearch = lastSearchFriend
					.map { searchResult -> (key: String, result: SearchResult) in
						let nombre = searchResult.friend?.name ?? ""
						let clave = resultadosDelBorde.contains(ObjectIdentifier(searchResult)) ? TeamRls.sinFavorito(nombre) : nombre
						return (key: clave.lowercased().folding(options: .diacriticInsensitive, locale: .current), result: searchResult)
					}
					.sorted { $0.key < $1.key }
					.map { $0.result }
				
				var addedAvatarListModel: [ContactAvatarModel] = []
				// MundoPhone: los que casan por número siguen saliendo por su orden; los que solo casan por el nombre en
				// el teclado (7272 → «Papa») se añaden y se vuelve a ordenar todo por nombre
				let cifrasT9 = self.busquedaT9
				sortedLastSearch.forEach { searchResult in
					if searchResult.friend != nil {
                        if self.isOwnFriendList(searchResult.friend?.friendList?.displayName) {

                            addedAvatarListModel.append(
                                ContactAvatarModel(
                                    friend: searchResult.friend!,
                                    name: searchResult.friend?.name ?? "",
                                    address: searchResult.friend?.address?.clone()?.asStringUriOnly() ?? "",
                                    withPresence: true
                                )
                            )
                        }
					}
				}
				
				if !cifrasT9.isEmpty {
					addedAvatarListModel = self.conCoincidenciasT9(core: core, cifras: cifrasT9, ya: addedAvatarListModel)
				}

				self.contactsManager.avatarListModel.forEach { contactAvatarModel in
					contactAvatarModel.removeFriendDelegate()
				}
                
                self.updateContacts(sortedLastSearch: sortedLastSearch, lastSearchSuggestions: lastSearchSuggestions, addedAvatarListModel: addedAvatarListModel)
			})
			
			magicSearch.addDelegate(delegate: self.searchDelegate!)
		}
	}
	
	/// Same identity as Address.weakEqual (sal_address_weak_equals): user, host and port of a SIP URI,
	/// or the whole URI ignoring case otherwise
	static func weakAddressKey(_ address: Address) -> String {
		if let domain = address.domain, !domain.isEmpty {
			return "sip:\(address.username ?? "\u{0}")@\(domain.lowercased()):\(address.port)"  // el host no distingue mayúsculas
		}
		return "uri:" + address.asStringUriOnly().lowercased()
	}

	/// MundoPhone: añade a los contactos ya encontrados por número los que casan por el NOMBRE en el
	/// teclado (MarcadorT9: inicio de cualquier palabra, 7272 → «Papa», «Carlos SARAworld») y ordena todo por nombre como
	/// el resultado normal. Sin repetir a nadie: ni el que ya salió por número (misma dirección) ni el compañero de la
	/// lista del borde que ya está en la agenda con su extensión (como el resultado normal). En la cola del Core.
	private func conCoincidenciasT9(core: Core, cifras: String, ya: [ContactAvatarModel]) -> [ContactAvatarModel] {
		var claves = Set(ya.compactMap { $0.friend?.address.map { MagicSearchSingleton.weakAddressKey($0) } })
		var nombresSinDireccion = Set(ya.filter { $0.friend?.address == nil }.map { $0.name })
		var extensionesAgenda: Set<String>?
		var anadidos: [ContactAvatarModel] = []
		for friendList in core.friendsLists where isOwnFriendList(friendList.displayName) {
			let esDelBorde = friendList.displayName == TeamRlsManager.listName
			for friend in friendList.friends {
				guard let nombre = friend.name, !nombre.isEmpty else { continue }
				let nombreParaT9 = esDelBorde ? TeamRls.sinFavorito(nombre) : nombre
				guard MarcadorT9.casa(nombre: nombreParaT9, cifras: cifras) else { continue }
				if let address = friend.address {
					let clave = MagicSearchSingleton.weakAddressKey(address)
					guard !claves.contains(clave) else { continue }
					if esDelBorde, let usuario = TeamRls.usuarioEquipo(address.asStringUriOnly()) {
						if extensionesAgenda == nil {
							extensionesAgenda = extensionesDeLaAgenda(core: core)
						}
						guard !(extensionesAgenda?.contains(usuario) ?? false) else { continue }
					}
					claves.insert(clave)
				} else {
					guard !nombresSinDireccion.contains(nombre) else { continue }
					nombresSinDireccion.insert(nombre)
				}
				anadidos.append(ContactAvatarModel(
					friend: friend,
					name: nombre,
					address: friend.address?.clone()?.asStringUriOnly() ?? "",
					withPresence: true
				))
			}
		}
		guard !anadidos.isEmpty else { return ya }
		Log.info("[MagicSearchSingleton] MundoPhone: [\(anadidos.count)] contacts match the keypad letters")
		return (ya + anadidos)
			.map { modelo -> (clave: String, modelo: ContactAvatarModel) in
				let delBorde = modelo.friend?.friendList?.displayName == TeamRlsManager.listName
				let nombre = delBorde ? TeamRls.sinFavorito(modelo.name) : modelo.name
				return (clave: nombre.lowercased().folding(options: .diacriticInsensitive, locale: .current), modelo: modelo)
			}
			.sorted { $0.clave < $1.clave }
			.map { $0.modelo }
	}

	/// Las extensiones de la centralita que ya tienen ficha en la agenda (para no repetir al compañero del borde)
	private func extensionesDeLaAgenda(core: Core) -> Set<String> {
		var extensiones = Set<String>()
		for friendList in core.friendsLists
		where isOwnFriendList(friendList.displayName) && friendList.displayName != TeamRlsManager.listName {
			for friend in friendList.friends {
				for address in friend.addresses {
					if let usuario = TeamRls.usuarioEquipo(address.asStringUriOnly()) { extensiones.insert(usuario) }
				}
			}
		}
		return extensiones
	}

	/// The friend lists whose friends are shown as contacts (MundoPhone: also the team list from the edge, TeamRlsManager)
	private func isOwnFriendList(_ displayName: String?) -> Bool {
		return displayName == nativeAddressBookFriendList || displayName == linphoneAddressBookFriendList
			|| displayName == tempRemoteAddressBookFriendList || displayName == TeamRlsManager.listName
	}

	func changeAllContact(allContactBool: Bool) {
		let preferences = UserDefaults.standard
		
		allContact = allContactBool
		preferences.set(allContact, forKey: allContactKey)
	}
    
    func updateContacts(
        sortedLastSearch: [SearchResult],
        lastSearchSuggestions: [SearchResult],
        addedAvatarListModel: [ContactAvatarModel]
    ) {
        DispatchQueue.main.async {
            self.contactsManager.lastSearch = sortedLastSearch
            self.contactsManager.lastSearchSuggestions = lastSearchSuggestions
            
            self.contactsManager.avatarListModel.removeAll()
            self.contactsManager.avatarListModel += addedAvatarListModel

            // Cancel previous debounce task
            self.contactLoadedDebounceWorkItem?.cancel()

            // Schedule new debounce task
            let workItem = DispatchWorkItem {
                NotificationCenter.default.post(name: NSNotification.Name("ContactLoaded"), object: nil)
            }

            self.contactLoadedDebounceWorkItem = workItem
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: workItem)
        }
    }
	
	/// resetCache: the friends themselves changed (MundoPhone: the team list from the edge), not only the filter
	/// t9: la búsqueda viene del marcador (MundoPhone): con solo cifras, también nombres por las letras del teclado
	func searchForContacts(resetCache: Bool = false, t9: Bool = false) {
		coreContext.doOnCoreQueue { _ in
			var needResetCache = resetCache
			// A partir de 2 cifras, como Android: con una sola casa media agenda
			self.busquedaT9 = t9 && self.currentFilter.count >= 2 && MarcadorT9.esSoloCifras(self.currentFilter)
				? self.currentFilter : ""

			// The SDK can only narrow its cached results: keep them while the new filter extends the previous one
			// (the old condition reset on any change, i.e. a full scan of every contact on each key stroke)
			if let oldFilter = self.previousFilter, !self.currentFilter.hasPrefix(oldFilter) {
				needResetCache = true
			}
			
			self.previousFilter = self.currentFilter
			
			guard let magicSearch = self.magicSearch else {
				return
			}
			
			if needResetCache {
				magicSearch.resetSearchCache()
			}
			
			magicSearch.getContactsListAsync(
				filter: self.currentFilter,
				domain: self.allContact ? "" : self.domainDefaultAccount,
				sourceFlags: MagicSearch.Source.All.rawValue,
				aggregation: MagicSearch.Aggregation.Friend)
		}
	}
}
