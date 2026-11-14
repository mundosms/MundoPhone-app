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

class HistoryListViewModel: ObservableObject {
	
	private var coreContext = CoreContext.shared
	
	@Published var callLogs: [HistoryModel] = []
	var callLogsTmp: [HistoryModel] = []
	
	@Published var showAllAccountsCallLogs: Bool = true
	
	var callLogsAddressToDelete = ""
	var callLogCoreDelegate: CoreDelegate?
	
	@Published var selectedCall: HistoryModel?
	
	@Published var displayedConversation: ConversationModel?
	
	private var historyChatRoomDelegate: ChatRoomDelegate?
	
	init(showAllAccounts: Bool = true) {
		self.showAllAccountsCallLogs = showAllAccounts
		computeCallLogsList()
        SharedMainViewModel.shared.updateMissedCallsCount()
	}
	
	func computeCallLogsList() {
		coreContext.doOnCoreQueue { core in
			let account = core.defaultAccount
			let showAll = self.showAllAccountsCallLogs
			
			let logs: [CallLog]
			if showAll {
				logs = core.callLogs
			} else {
				logs = account?.callLogs ?? core.callLogs
			}
			
			var callLogsBis: [HistoryModel] = []
			var callLogsTmpBis: [HistoryModel] = []
			
			logs.forEach { log in
				let history = HistoryModel(callLog: log)
				callLogsBis.append(history)
				callLogsTmpBis.append(history)
			}
			
			DispatchQueue.main.async {
				self.callLogs.removeAll()
				self.callLogsTmp.removeAll()
				
				self.callLogs = callLogsBis
				self.callLogsTmp = callLogsTmpBis
			}
			
			self.callLogCoreDelegate = CoreDelegateStub(onCallLogUpdated: { (_: Core, _: CallLog) in
				let account = core.defaultAccount
				let showAll = self.showAllAccountsCallLogs
				let logs: [CallLog]
				if showAll {
					logs = core.callLogs
				} else {
					logs = account?.callLogs ?? core.callLogs
				}
				
				var callLogsBis: [HistoryModel] = []
				var callLogsTmpBis: [HistoryModel] = []
				
				logs.forEach { log in
					let history = HistoryModel(callLog: log)
					callLogsBis.append(history)
					callLogsTmpBis.append(history)
				}
				
				DispatchQueue.main.async {
					self.callLogs.removeAll()
					self.callLogsTmp.removeAll()
					
					self.callLogs = callLogsBis
					self.callLogsTmp = callLogsTmpBis
				}
				
                SharedMainViewModel.shared.updateMissedCallsCount()
			})
			core.addDelegate(delegate: self.callLogCoreDelegate!)
		}
	}
	
	func getCallIconResId(callStatus: Call.Status, isOutgoing: Bool) -> String {
		switch callStatus {
		case Call.Status.Missed:
			if isOutgoing {
				"outgoing-call-missed"
			} else {
				"incoming-call-missed"
			}
			
		case Call.Status.Success:
			if isOutgoing {
				"outgoing-call"
			} else {
				"incoming-call"
			}
			
		default:
			if isOutgoing {
				"outgoing-call-rejected"
			} else {
				"incoming-call-rejected"
			}
		}
	}
	
	func getCallText(callStatus: Call.Status, isOutgoing: Bool) -> String {
		switch callStatus {
		case Call.Status.Missed:
			if isOutgoing {
				String(localized: "call_outgoing")
			} else {
				String(localized: "notification_missed_call_title")
			}
			
		case Call.Status.Success:
			if isOutgoing {
				String(localized: "call_outgoing")
			} else {
				String(localized: "call_audio_incoming")
			}
			
		default:
			if isOutgoing {
				String(localized: "call_outgoing")
			} else {
				String(localized: "call_audio_incoming")
			}
		}
	}
	
	func getCallTime(startDate: time_t) -> String {
		return Date(timeIntervalSince1970: TimeInterval(startDate)).mundoPhoneFechaHistorial
	}
	
	func filterCallLogs(filter: String) {
		callLogs.removeAll()
		callLogsTmp.forEach { callLog in
			if callLog.addressName.lowercased().contains(filter.lowercased()) {
				callLogs.append(callLog)
			}
		}
	}
	
	func resetFilterCallLogs() {
		callLogs = callLogsTmp
	}
	
	func toggleAccountFilter(showAll: Bool) {
		showAllAccountsCallLogs = showAll
		computeCallLogsList()
	}
	
	func removeCallLogs() {
		if callLogsAddressToDelete.isEmpty {
			coreContext.doOnCoreQueue { core in
				if self.showAllAccountsCallLogs {
					core.clearCallLogs()
				} else {
					let account = core.defaultAccount
					if account != nil {
						account!.clearCallLogs()
					} else {
						core.clearCallLogs()
					}
				}
				
				DispatchQueue.main.async {
					self.callLogs.removeAll()
					self.callLogsTmp.removeAll()
				}
			}
		} else {
			removeCallLogsWithAddress()
			callLogsAddressToDelete = ""
		}
	}
	
	func removeCallLogsWithAddress() {
		self.callLogs.filter { $0.address == callLogsAddressToDelete || $0.address == callLogsAddressToDelete }.forEach { historyModel in
			removeCallLog(historyModel: historyModel)
		}
	}
	
	func removeCallLog(historyModel: HistoryModel) {
		let index = self.callLogs.firstIndex(where: {$0.id == historyModel.id})
		self.callLogs.remove(at: index!)
		
		let indexTmp = self.callLogsTmp.firstIndex(where: {$0.id == historyModel.id})
		self.callLogsTmp.remove(at: indexTmp!)
		
		coreContext.doOnCoreQueue { core in
			core.removeCallLog(callLog: historyModel.callLog)
		}
	}
	
	func refreshHistoryAvatarModel() {
		coreContext.doOnCoreQueue { _ in
			self.callLogs.forEach { historyModel in
				historyModel.refreshAvatarModel()
			}
		}
	}
	
	func createOneToOneChatRoomWith(remote: Address) {
		CoreContext.shared.doOnCoreQueue { core in
			let account = core.defaultAccount
			if account == nil {
				Log.error(
					"\(ConversationForwardMessageViewModel.TAG) No default account found, can't create conversation with \(remote.asStringUriOnly())"
				)
				return
			}
			
			DispatchQueue.main.async {
				SharedMainViewModel.shared.operationInProgress = true
			}
			
			do {
				let params = try core.createConferenceParams(conference: nil)
				params.chatEnabled = true
				params.groupEnabled = false
				params.subject = NSLocalizedString("conversation_one_to_one_hidden_subject", comment: "")
				params.account = account
				
				guard let chatParams = params.chatParams else { return }
				chatParams.ephemeralLifetime = 0 // Make sure ephemeral is disabled by default
				
				let sameDomain = remote.domain == CorePreferences.defaultDomain && remote.domain == account!.params?.domain
				if account!.params != nil && (account!.params!.instantMessagingEncryptionMandatory && sameDomain) {
					Log.info("\(ConversationForwardMessageViewModel.TAG) Account is in secure mode & domain matches, creating an E2E encrypted conversation")
					chatParams.backend = ChatRoom.Backend.FlexisipChat
					params.securityLevel = Conference.SecurityLevel.EndToEnd
				} else if account!.params != nil && (!account!.params!.instantMessagingEncryptionMandatory) {
					if LinphoneUtils.isEndToEndEncryptedChatAvailable(core: core) {
						Log.info(
							"\(ConversationForwardMessageViewModel.TAG) Account is in interop mode but LIME is available, creating an E2E encrypted conversation"
						)
						chatParams.backend = ChatRoom.Backend.FlexisipChat
						params.securityLevel = Conference.SecurityLevel.EndToEnd
					} else {
						Log.info(
							"\(ConversationForwardMessageViewModel.TAG) Account is in interop mode but LIME isn't available, creating a SIP simple conversation"
						)
						chatParams.backend = ChatRoom.Backend.Basic
						params.securityLevel = Conference.SecurityLevel.None
					}
				} else {
					Log.error(
						"\(ConversationForwardMessageViewModel.TAG) Account is in secure mode, can't chat with SIP address of different domain \(remote.asStringUriOnly())"
					)
					
					DispatchQueue.main.async {
						SharedMainViewModel.shared.operationInProgress = false
						ToastViewModel.shared.toastMessage = "Failed_to_create_conversation_error"
						ToastViewModel.shared.displayToast = true
					}
					return
				}
				
				let participants = [remote]
				let localAddress = account?.params?.identityAddress
				let existingChatRoom = core.searchChatRoom(params: params, localAddr: localAddress, remoteAddr: nil, participants: participants)
				if existingChatRoom == nil {
					Log.info(
						"\(ConversationForwardMessageViewModel.TAG) No existing 1-1 conversation between local account \(localAddress?.asStringUriOnly() ?? "") and remote \(remote.asStringUriOnly()) was found for given parameters, let's create it"
					)
					
					do {
						let chatRoom = try core.createChatRoom(params: params, participants: participants)
						if chatParams.backend == ChatRoom.Backend.FlexisipChat {
							let state = chatRoom.state
							if state == ChatRoom.State.Created {
								let chatRoomId = LinphoneUtils.getConversationId(chatRoom: chatRoom)
								Log.info("\(ConversationForwardMessageViewModel.TAG) 1-1 conversation \(chatRoomId) has been created")
								
								let model = ConversationModel(chatRoom: chatRoom)
								DispatchQueue.main.async {
									self.displayedConversation = model
									SharedMainViewModel.shared.operationInProgress = false
								}
							} else {
								Log.info("\(ConversationForwardMessageViewModel.TAG) Conversation isn't in Created state yet (state is \(state)), wait for it")
								self.chatRoomAddDelegate(core: core, chatRoom: chatRoom)
							}
						} else {
							let chatRoomId = LinphoneUtils.getConversationId(chatRoom: chatRoom)
							Log.info("\(ConversationForwardMessageViewModel.TAG) Conversation successfully created \(chatRoomId)")
							
							let model = ConversationModel(chatRoom: chatRoom)
							DispatchQueue.main.async {
								self.displayedConversation = model
								SharedMainViewModel.shared.operationInProgress = false
							}
						}
					} catch {
						Log.error("\(ConversationForwardMessageViewModel.TAG) Failed to create 1-1 conversation with \(remote.asStringUriOnly())")
						
						DispatchQueue.main.async {
							SharedMainViewModel.shared.operationInProgress = false
							ToastViewModel.shared.toastMessage = "Failed_to_create_conversation_error"
							ToastViewModel.shared.displayToast = true
						}
					}
				} else {
					Log.warn(
						"\(ConversationForwardMessageViewModel.TAG) A 1-1 conversation between local account \(localAddress?.asStringUriOnly() ?? "") and remote \(remote.asStringUriOnly()) for given parameters already exists!"
					)
					let model = ConversationModel(chatRoom: existingChatRoom!)
					DispatchQueue.main.async {
						self.displayedConversation = model
						SharedMainViewModel.shared.operationInProgress = false
					}
				}
			} catch {
			}
		}
	}
	
	func chatRoomAddDelegate(core: Core, chatRoom: ChatRoom) {
		historyChatRoomDelegate = ChatRoomDelegateStub(onStateChanged: { (chatRoom: ChatRoom, state: ChatRoom.State) in
			let state = chatRoom.state
			let id = LinphoneUtils.getChatRoomId(room: chatRoom)
			if state == ChatRoom.State.CreationFailed {
				Log.error("\(StartConversationViewModel.TAG) Conversation \(id) creation has failed!")
				if let delegate = self.historyChatRoomDelegate {
					chatRoom.removeDelegate(delegate: delegate)
					self.historyChatRoomDelegate = nil
				}
				DispatchQueue.main.async {
					SharedMainViewModel.shared.operationInProgress = false
					ToastViewModel.shared.toastMessage = "Failed_to_create_conversation_error"
					ToastViewModel.shared.displayToast = true
				}
			}
		}, onConferenceJoined: { (chatRoom: ChatRoom, _: EventLog) in
			let state = chatRoom.state
			let id = LinphoneUtils.getChatRoomId(room: chatRoom)
			Log.info("\(StartConversationViewModel.TAG) Conversation \(id) \(chatRoom.subject ?? "") state changed: \(state)")
			if state == ChatRoom.State.Created {
				Log.info("\(StartConversationViewModel.TAG) Conversation \(id) successfully created")
				if let delegate = self.historyChatRoomDelegate {
					chatRoom.removeDelegate(delegate: delegate)
					self.historyChatRoomDelegate = nil
				}
				let model = ConversationModel(chatRoom: chatRoom)
				if 	SharedMainViewModel.shared.operationInProgress == false {
					DispatchQueue.main.async {
						SharedMainViewModel.shared.operationInProgress = true
					}
					
					DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
						SharedMainViewModel.shared.operationInProgress = false
						self.displayedConversation = model
					}
				} else {
					DispatchQueue.main.async {
						SharedMainViewModel.shared.operationInProgress = false
						self.displayedConversation = model
					}
				}
			} else if state == ChatRoom.State.CreationFailed {
				Log.error("\(StartConversationViewModel.TAG) Conversation \(id) creation has failed!")
				
				if let delegate = self.historyChatRoomDelegate {
					chatRoom.removeDelegate(delegate: delegate)
					self.historyChatRoomDelegate = nil
				}
				DispatchQueue.main.async {
					SharedMainViewModel.shared.operationInProgress = false
					ToastViewModel.shared.toastMessage = "Failed_to_create_conversation_error"
					ToastViewModel.shared.displayToast = true
				}
			}
		})
		chatRoom.addDelegate(delegate: historyChatRoomDelegate!)
	}
}

/// MundoPhone: fechas cortas con el formato del idioma del móvil: «Hoy | 17:36», «Ayer», «14/11» en español; «Today»,
/// «11/14» en inglés. En vez de «Today»/«Yesterday» fijos en inglés y el formato de EE. UU. (mes/día y 12 horas).
extension Date {
	private static func formato(_ plantilla: String) -> DateFormatter {
		let formatter = DateFormatter()
		formatter.locale = Locale.current
		formatter.setLocalizedDateFormatFromTemplate(plantilla)
		return formatter
	}

	/// «Hoy» o «Ayer» en el idioma del móvil, con mayúscula; nil si no es ni hoy ni ayer
	var mundoPhoneHoyOAyer: String? {
		guard Calendar.current.isDateInToday(self) || Calendar.current.isDateInYesterday(self) else { return nil }
		let formatter = DateFormatter()
		formatter.locale = Locale.current
		formatter.dateStyle = .medium
		formatter.timeStyle = .none
		formatter.doesRelativeDateFormatting = true
		let texto = formatter.string(from: self)
		return texto.prefix(1).uppercased(with: Locale.current) + String(texto.dropFirst())
	}

	/// La hora con el formato del móvil (24 h en español)
	var mundoPhoneHora: String {
		return Date.formato("jm").string(from: self)
	}

	private var mundoPhoneDia: String {
		let esteAno = Calendar.current.isDate(self, equalTo: .now, toGranularity: .year)
		return Date.formato(esteAno ? "dMM" : "dMMyy").string(from: self)
	}

	/// Historial de llamadas: «Hoy | 17:36», «14/11 | 17:36», «14/11/25 | 17:36»
	var mundoPhoneFechaHistorial: String {
		return (mundoPhoneHoyOAyer ?? mundoPhoneDia) + " | " + mundoPhoneHora
	}

	/// Lista de conversaciones: la hora si es de hoy, «Ayer», o la fecha
	var mundoPhoneFechaLista: String {
		if Calendar.current.isDateInToday(self) { return mundoPhoneHora }
		return mundoPhoneHoyOAyer ?? mundoPhoneDia
	}
}
