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

import Foundation
import linphonesw

class LinphoneUtils: NSObject {
	public class func isChatRoomAGroup(chatRoom: ChatRoom) -> Bool {
		let oneToOne = chatRoom.hasCapability(mask: ChatRoom.Capabilities.OneToOne.rawValue)
		let conference = chatRoom.hasCapability(mask: ChatRoom.Capabilities.Conference.rawValue)
		return !oneToOne && conference
	}
	
	public class func getChatIconState(chatState: Int) -> String {
		return switch chatState {
		case ChatMessage.State.Displayed.rawValue, ChatMessage.State.FileTransferDone.rawValue:
			"checks"
		case ChatMessage.State.DeliveredToUser.rawValue:
			"check"
		case ChatMessage.State.Delivered.rawValue:
			"envelope-simple"
		case ChatMessage.State.NotDelivered.rawValue, ChatMessage.State.FileTransferError.rawValue:
			"warning-circle"
		case ChatMessage.State.InProgress.rawValue, ChatMessage.State.FileTransferInProgress.rawValue:
			"animated-in-progress"
		default:
			"animated-in-progress"
		}
	}
	
	public class func getChatRoomId(room: ChatRoom) -> String {
		return room.identifier ?? ""
		//return getChatRoomId(localAddress: room.localAddress!, remoteAddress: room.peerAddress!)
	}
	
	public class func getChatRoomId(localAddress: Address, remoteAddress: Address) -> String {
		let localSipUri = localAddress.clone()
		localSipUri!.clean()
		let remoteSipUri = remoteAddress.clone()
		remoteSipUri!.clean()
		return getChatRoomId(localSipUri: localSipUri!.asStringUriOnly(), remoteSipUri: remoteSipUri!.asStringUriOnly())
	}
	
	public class func getChatRoomId(localSipUri: String, remoteSipUri: String) -> String {
		return "\(localSipUri)#~#\(remoteSipUri)"
	}
	
	public class func applyInternationalPrefix(core: Core, account: Account? = nil) -> Bool {
		return	account?.params?.useInternationalPrefixForCallsAndChats == true 
		|| core.defaultAccount?.params?.useInternationalPrefixForCallsAndChats == true
	}
	
	public class func isEndToEndEncryptedChatAvailable(core: Core) -> Bool {
		return core.limeX3DhEnabled &&
		core.defaultAccount?.params?.limeServerUrl != nil &&
		core.defaultAccount?.params?.conferenceFactoryUri != nil
	}
	
	public class func createConferenceScheduler(core: Core) -> ConferenceScheduler? {
		let account = LinphoneUtils.getDefaultAccount()
		if let url = account?.params?.ccmpServerUrl, !url.isEmpty {
			Log.info(
				"CCMP server URL has been set in Account's params, using CCMP conference scheduler"
			)
			
			let conferenceScheduler = try? core.createConferenceSchedulerWithType(
				account: account,
				schedulingType: .CCMP
			)
				
			return conferenceScheduler
		}
		Log.info(
			"CCMP server URL hasn't been set in Account's params, using SIP conference scheduler"
		)
		
		let conferenceScheduler = try? core.createConferenceSchedulerWithType(
			account: account,
			schedulingType: .SIP
		)
		
		return conferenceScheduler
	}
	
	public class func createGroupCall(core: Core, account: Account?, subject: String) -> Conference? {
		do {
			let conferenceParams = try core.createConferenceParams(conference: nil)
			conferenceParams.videoEnabled = true
			conferenceParams.account = account
			conferenceParams.subject = subject
			
			// Enable end-to-end encryption if client supports it
			//if isEndToEndEncryptedChatAvailable(core: core) {
			if false {
				Log.info("\(#function) Requesting EndToEnd security level for conference")
				conferenceParams.securityLevel = .EndToEnd
			} else {
				Log.info("\(#function) Requesting PointToPoint security level for conference")
				conferenceParams.securityLevel = .PointToPoint
			}
			
			// Allows to have a chat room within the conference
			conferenceParams.chatEnabled = true
			
			Log.info("\(#function) Creating group call with subject \(conferenceParams.subject ?? "Unknown")")
			
			let confWithParams = try core.createConferenceWithParams(params: conferenceParams)
			
			return confWithParams
		} catch let error {
			Log.info("\(#function) Error while creating group call: \(error)")
			return nil
		}
	}
	
	public class func getConversationId(chatRoom: ChatRoom) -> String {
		return chatRoom.identifier ?? ""
		
	}
	
	public class func getDefaultAccount() -> Account? {
		return CoreContext.shared.mCore.defaultAccount ?? (CoreContext.shared.mCore.accountList.first ?? nil)
	}
	
	public class func getAccountForAddress(address: Address) -> Account? {
		return CoreContext.shared.mCore.accountList.first { $0.params?.identityAddress?.weakEqual(address2: address) == true }
	}
	
	public class func isRemoteConferencingAvailable(core: Core) -> Bool {
		return core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != nil
	}
	
	public class func isGroupChatAvailable(core: Core) -> Bool {
		return core.defaultAccount?.params?.conferenceFactoryUri != nil
	}

	public class func normalizeUserOrPhone(
		_ value: String,
		defaultCountryCode: String = "34",
		nationalNumberLength: Int = 9
	) -> String {
		let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmed.isEmpty else {
			return ""
		}

		if trimmed.rangeOfCharacter(from: .letters) != nil {
			return trimmed
		}

		let phoneLikePattern = "^[+0-9\\s\\-().]+$"
		guard trimmed.range(of: phoneLikePattern, options: .regularExpression) != nil else {
			return trimmed
		}

		let digitsOnly = trimmed.replacingOccurrences(of: "[^0-9]", with: "", options: .regularExpression)
		guard !digitsOnly.isEmpty else {
			return trimmed
		}

		let expectedWithCountryCode = defaultCountryCode.count + nationalNumberLength
		let expectedWith00CountryCode = 2 + expectedWithCountryCode

		if digitsOnly.hasPrefix("00\(defaultCountryCode)") && digitsOnly.count == expectedWith00CountryCode {
			return String(digitsOnly.dropFirst(2 + defaultCountryCode.count))
		}

		if digitsOnly.hasPrefix(defaultCountryCode) && digitsOnly.count == expectedWithCountryCode {
			return String(digitsOnly.dropFirst(defaultCountryCode.count))
		}

		return digitsOnly
	}

	public class func isPhoneNumber(_ value: String) -> Bool {
		let normalized = normalizeUserOrPhone(value)
		guard !normalized.isEmpty,
			  normalized.allSatisfy({ $0.isNumber }) else {
			return false
		}

		return normalized.count >= 7 && normalized.count <= 15
	}

	public class func cleanedSipLocalPart(from address: Address?) -> String {
		guard let address else {
			return ""
		}

		if let username = address.username, !username.isEmpty {
			return normalizeUserOrPhone(username)
		}

		return cleanedSipLocalPart(from: address.asStringUriOnly())
	}

	public class func cleanedSipLocalPart(from value: String?) -> String {
		guard let value else {
			return ""
		}

		var localPart = value.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !localPart.isEmpty else {
			return ""
		}

		if localPart.lowercased().hasPrefix("sip:") {
			localPart = String(localPart.dropFirst(4))
		} else if localPart.lowercased().hasPrefix("sips:") {
			localPart = String(localPart.dropFirst(5))
		} else if localPart.lowercased().hasPrefix("tel:") {
			localPart = String(localPart.dropFirst(4))
		}

		if let atIndex = localPart.firstIndex(of: "@") {
			localPart = String(localPart[..<atIndex])
		}
		if let semicolonIndex = localPart.firstIndex(of: ";") {
			localPart = String(localPart[..<semicolonIndex])
		}
		if let questionMarkIndex = localPart.firstIndex(of: "?") {
			localPart = String(localPart[..<questionMarkIndex])
		}

		return normalizeUserOrPhone(localPart)
	}
}
