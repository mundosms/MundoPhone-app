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

import SwiftUI
import UniformTypeIdentifiers
import QuickLook
import linphonesw

struct DebugFragment: View {
	@ObservedObject var helpViewModel: HelpViewModel
	
	@Environment(\.dismiss) var dismiss
	
	@State private var selectedURLAttachment: URL?
	
	// MundoPhone: formulario de «Compartir registros» (qué ha pasado y un contacto), como Android
	@State private var showShareLogsForm: Bool = false
	@State private var shareLogsDescription: String = ""
	@State private var shareLogsContact: String = ""
	@FocusState var isShareLogsDescriptionFocused: Bool
	@FocusState var isShareLogsContactFocused: Bool
	
	private var isShareLogsDescriptionEmpty: Bool {
		shareLogsDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
	}
	
	var body: some View {
		ZStack {
			VStack(spacing: 1) {
				Rectangle()
					.foregroundColor(Color.orangeMain500)
					.edgesIgnoringSafeArea(.top)
					.frame(height: 0)
				
				HStack {
					Image("caret-left")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.orangeMain500)
						.frame(width: 25, height: 25, alignment: .leading)
						.padding(.all, 10)
						.padding(.top, 4)
						.padding(.leading, -10)
						.onTapGesture {
							dismiss()
						}
					
					Text("help_troubleshooting_title")
						.default_text_style_orange_800(styleSize: 16)
						.frame(maxWidth: .infinity, alignment: .leading)
						.padding(.top, 4)
						.lineLimit(1)
					
					Spacer()
				}
				.frame(maxWidth: .infinity)
				.frame(height: 50)
				.padding(.horizontal)
				.padding(.bottom, 4)
				.background(.white)
				
				ScrollView {
					VStack(spacing: 0) {
						VStack(spacing: 20) {
							HStack {
								Spacer()
								
								Button(
									action: {
										helpViewModel.cleanLogs()
									}, label: {
										Text("help_troubleshooting_clean_logs")
											.default_text_style_orange_500(styleSize: 14)
											.lineLimit(1)
									}
								)
								.padding(.horizontal, 15)
								.padding(.vertical, 10)
								.background(Color.orangeMain100)
								.cornerRadius(60)
								
								// MundoPhone: sin servidor de registros configurado, sin botón (como Android)
								if helpViewModel.uploadLogsAvailable {
									Spacer()
									
									Button(
										action: {
											shareLogsDescription = ""
											shareLogsContact = ""
											showShareLogsForm = true
										}, label: {
											Text("help_troubleshooting_share_logs")
												.default_text_style_orange_500(styleSize: 14)
												.lineLimit(1)
										}
									)
									.padding(.horizontal, 15)
									.padding(.vertical, 10)
									.background(Color.orangeMain100)
									.cornerRadius(60)
								}
								
								Spacer()
							}
							
							Button {
								UIPasteboard.general.setValue(
									helpViewModel.version,
									forPasteboardType: UTType.plainText.identifier
								)
								ToastViewModel.shared.toastMessage = "Success_text_copied_into_clipboard"
							   	ToastViewModel.shared.displayToast = true
							} label: {
								HStack {
									Image("app-store-logo")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.orangeMain500)
										.frame(width: 30, height: 30)
									
									VStack {
										Text("help_troubleshooting_app_version_title")
											.default_text_style_700(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.multilineTextAlignment(.leading)
										
										Text(helpViewModel.version)
											.default_text_style(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.multilineTextAlignment(.leading)
									}
									.padding(.horizontal, 5)
									
									Image("copy")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.grayMain2c600)
										.frame(width: 25, height: 25)
								}
							}
							
							Button {
								UIPasteboard.general.setValue(
									helpViewModel.sdkVersion,
									forPasteboardType: UTType.plainText.identifier
								)
								ToastViewModel.shared.toastMessage = "Success_text_copied_into_clipboard"
								ToastViewModel.shared.displayToast = true
							} label: {
								HStack {
									Image("package")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.orangeMain500)
										.frame(width: 30, height: 30)
									
									VStack {
										Text("help_troubleshooting_sdk_version_title")
											.default_text_style_700(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.multilineTextAlignment(.leading)
										
										Text(helpViewModel.sdkVersion)
											.default_text_style(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.multilineTextAlignment(.leading)
									}
									.padding(.horizontal, 5)
									
									Image("copy")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.grayMain2c600)
										.frame(width: 25, height: 25)
								}
							}
							
							HStack {
								Button(
									action: {
										showConfigFile()
									}, label: {
										Text("help_troubleshooting_show_config_file")
											.default_text_style_orange_500(styleSize: 14)
											.lineLimit(1)
									}
								)
								.padding(.horizontal, 15)
								.padding(.vertical, 10)
								.background(Color.orangeMain100)
								.cornerRadius(60)
								
								Spacer()
							}
						}
						.padding(.vertical, 20)
						.padding(.horizontal, 20)
						.background(Color.gray100)
						.frame(maxWidth: .infinity, alignment: .leading)
					}
				}
				.background(Color.gray100)
			}
			.background(Color.gray100)
			
			if showShareLogsForm {
				shareLogsForm
			}
			
			if helpViewModel.logsUploadInProgress {
				PopupLoadingView()
					.background(.black.opacity(0.65))
			}
			
			// MundoPhone: el servidor de soporte contesta con …/r/<referencia>; se enseña por si soporte la pide
			if let reference = helpViewModel.logsUploadReference {
				PopupView(
					isShowPopup: .constant(true),
					title: Text("help_troubleshooting_debug_logs_sent_title"),
					content: Text(String(format: String(localized: "help_troubleshooting_debug_logs_sent_message"), reference)),
					actionFirstButton: {},
					titleSecondButton: Text("dialog_understood"),
					actionSecondButton: {
						helpViewModel.logsUploadReference = nil
					}
				)
				.background(.black.opacity(0.65))
				.onTapGesture {
					helpViewModel.logsUploadReference = nil
				}
			}
			
			if helpViewModel.logsUploadFailed {
				PopupView(
					isShowPopup: .constant(true),
					title: Text("help_troubleshooting_debug_logs_upload_error_toast_message"),
					actionFirstButton: {},
					titleSecondButton: Text("dialog_understood"),
					actionSecondButton: {
						helpViewModel.logsUploadFailed = false
					}
				)
				.background(.black.opacity(0.65))
				.onTapGesture {
					helpViewModel.logsUploadFailed = false
				}
			}
		}
		.navigationTitle("")
		.navigationBarHidden(true)
		.quickLookPreview($selectedURLAttachment)
	}
	
	// MundoPhone: antes de enviar se pide qué ha pasado (obligatorio: sin ello el servidor rechaza el registro) y un
	// contacto opcional, como Android (DebugFragment.showShareLogsForm). Topes de 500 y 120 caracteres, los de Android.
	// Sin gestos de toque encima del formulario: el fondo es una capa aparte, para que los campos se enfoquen solos.
	var shareLogsForm: some View {
		ZStack {
			Color.black.opacity(0.65)
				.ignoresSafeArea()
				.onTapGesture {
					isShareLogsDescriptionFocused = false
					isShareLogsContactFocused = false
				}
			
			VStack(alignment: .leading) {
				Text("help_troubleshooting_share_logs_form_title")
					.default_text_style_800(styleSize: 16)
					.frame(alignment: .leading)
					.padding(.bottom, 10)
				
				Text("help_troubleshooting_share_logs_description_label")
					.default_text_style_700(styleSize: 14)
					.fixedSize(horizontal: false, vertical: true)
					.padding(.bottom, 5)
				
				TextEditor(text: $shareLogsDescription)
					.default_text_style(styleSize: 15)
					.frame(height: 100)
					.focused($isShareLogsDescriptionFocused)
					.padding(.horizontal, 10)
					.padding(.vertical, 5)
					.overlay(
						RoundedRectangle(cornerRadius: 10)
							.inset(by: 0.5)
							.stroke(isShareLogsDescriptionFocused ? Color.orangeMain500 : Color.gray200, lineWidth: 1)
					)
					.padding(.bottom, 15)
					.onChange(of: shareLogsDescription) { newValue in
						if newValue.count > 500 {
							shareLogsDescription = String(newValue.prefix(500))
						}
					}
				
				Text("help_troubleshooting_share_logs_contact_label")
					.default_text_style_700(styleSize: 14)
					.fixedSize(horizontal: false, vertical: true)
					.padding(.bottom, 5)
				
				TextField("", text: $shareLogsContact)
					.default_text_style(styleSize: 15)
					.disableAutocorrection(true)
					.autocapitalization(.none)
					.keyboardType(.emailAddress)
					.frame(height: 25)
					.focused($isShareLogsContactFocused)
					.padding(.horizontal, 20)
					.padding(.vertical, 15)
					.overlay(
						RoundedRectangle(cornerRadius: 60)
							.inset(by: 0.5)
							.stroke(isShareLogsContactFocused ? Color.orangeMain500 : Color.gray200, lineWidth: 1)
					)
					.padding(.bottom)
					.onChange(of: shareLogsContact) { newValue in
						if newValue.count > 120 {
							shareLogsContact = String(newValue.prefix(120))
						}
					}
				
				HStack {
					Button(action: {
						showShareLogsForm = false
					}, label: {
						Text("dialog_cancel")
							.default_text_style_orange_600(styleSize: 14)
							.frame(height: 30)
					})
					.padding(.horizontal, 20)
					.padding(.vertical, 10)
					.cornerRadius(60)
					.overlay(
						RoundedRectangle(cornerRadius: 60)
							.inset(by: 0.5)
							.stroke(Color.orangeMain500, lineWidth: 1)
					)
					.padding(.horizontal, 2)
					
					Button(action: {
						let description = shareLogsDescription.trimmingCharacters(in: .whitespacesAndNewlines)
						if description.isEmpty {
							return
						}
						showShareLogsForm = false
						helpViewModel.uploadLogsToSupport(
							description: description,
							contact: shareLogsContact.trimmingCharacters(in: .whitespacesAndNewlines)
						)
					}, label: {
						Text("help_troubleshooting_share_logs_send")
							.default_text_style_white_600(styleSize: 14)
							.frame(height: 30)
					})
					.padding(.horizontal, 20)
					.padding(.vertical, 10)
					.background(isShareLogsDescriptionEmpty ? Color.orangeMain100 : Color.orangeMain500)
					.cornerRadius(60)
					.disabled(isShareLogsDescriptionEmpty)
					.padding(.horizontal, 2)
				}
				.frame(maxWidth: .infinity, alignment: .trailing)
			}
			.padding(.horizontal, 20)
			.padding(.vertical, 20)
			.background(.white)
			.cornerRadius(20)
			.padding(.horizontal)
			.shadow(color: Color.orangeMain500, radius: 0, x: 0, y: 2)
			.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
		}
	}
	
	func showConfigFile() {
		Log.info("[DebugFragment][showConfigFile] Dumping & displaying Core's config")
		if let rcDir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Config.appGroupName)?
			.appendingPathComponent("Library/Preferences/linphone") {
			let rcFileUrl = rcDir.appendingPathComponent("linphonerc")
			if FileManager.default.fileExists(atPath: rcFileUrl.path) {
				let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
				if directory != nil {
					do {
						if FileManager.default.fileExists(atPath: directory!.appendingPathComponent("linphonerc.txt").path) {
							try FileManager.default.removeItem(at: directory!.appendingPathComponent("linphonerc.txt"))
						}
						try FileManager.default.copyItem(at: rcFileUrl, to: directory!.appendingPathComponent("linphonerc.txt"))
						DispatchQueue.main.async {
							selectedURLAttachment = directory!.appendingPathComponent("linphonerc.txt")
						}
					} catch {
						print("Error: ", error)
					}
				}
			}
		}
	}
}

struct ShareAnySheet: UIViewControllerRepresentable {
	var items: [Any] // The content to share
	
	func makeUIViewController(context: Context) -> UIActivityViewController {
		UIActivityViewController(activityItems: items, applicationActivities: nil)
	}
	
	func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
		// No updates needed
	}
}
