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

import SwiftUI
import PhotosUI
import Vision
import UIKit
import ImageIO
import linphonesw

struct QrCodeScannerFragment: View {
	
	@ObservedObject private var coreContext = CoreContext.shared
	
	@Environment(\.dismiss) var dismiss
	
	@State private var isPhotoPickerPresented = false
	@State private var isGalleryProvisioningInProgress = false
	
	var body: some View {
		ZStack(alignment: .top) {
			QRScanner()
			
			Text("assistant_scan_qr_code")
				.default_text_style_white_800(styleSize: 20)
				.padding(.top, 175)
			
			HStack {
				Button {
					dismiss()
				} label: {
					Image("caret-left")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.white)
						.frame(width: 25, height: 25, alignment: .leading)
						.padding(.all, 10)
						.padding(.leading, -10)
				}
				.padding()
				.padding(.top, 50)
				
				Spacer()
			}

			VStack {
				Spacer()
				HStack {
					Spacer()
					Button {
						guard !isGalleryProvisioningInProgress else { return }
						isPhotoPickerPresented = true
					} label: {
						Image(systemName: "photo.on.rectangle.angled")
							.font(.system(size: 23, weight: .semibold))
							.foregroundStyle(Color.white)
							.padding(.all, 16)
							.background(Color.black.opacity(0.45))
							.clipShape(Circle())
					}
					.padding(.trailing, 24)
					.padding(.bottom, 42)
					.disabled(isGalleryProvisioningInProgress)
				}
			}

			if isGalleryProvisioningInProgress {
				Color.black.opacity(0.35)
					.edgesIgnoringSafeArea(.all)

				ProgressView {
					Text("assistant_qr_importing_account")
                        .default_text_style_white_800(styleSize: 16)
				}
				.progressViewStyle(.circular)
				.tint(.white)
				.padding(.horizontal, 26)
				.padding(.vertical, 18)
				.background(Color.black.opacity(0.7))
				.clipShape(RoundedRectangle(cornerRadius: 12))
				.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
			}
		}
		.edgesIgnoringSafeArea(.all)
		.navigationBarHidden(true)
		.sheet(isPresented: $isPhotoPickerPresented) {
			PhotoPicker(filter: .images, limit: 1) { results in
				handleSelectedPhoto(results: results)
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .qrCodeProvisioningSuccess)) { notification in
			isGalleryProvisioningInProgress = false
			let hasAccount = notification.object as? Bool ?? false
			if hasAccount {
				// MundoPhone: el QR no cambia la cuenta por defecto (igual que en Android). Upstream pone siempre la última
				// añadida, y con varias cuentas las salientes pasarían a salir por la nueva sin que nadie lo pidiera. Solo se
				// pone si todavía no hay ninguna.
				CoreContext.shared.doOnCoreQueue { core in
					if core.defaultAccount == nil, let lastAccount = core.accountList.last {
						core.defaultAccount = lastAccount
						Log.info("[QrCodeScannerFragment] No default account yet, set the new one: \(lastAccount.params?.identityAddress?.asStringUriOnly() ?? "")")
					} else {
						Log.info("[QrCodeScannerFragment] Default account kept: \(core.defaultAccount?.params?.identityAddress?.asStringUriOnly() ?? "")")
					}
				}
				SharedMainViewModel.shared.changeHideProfileMode()
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .qrCodeProvisioningError)) { _ in
			isGalleryProvisioningInProgress = false
			dismiss()
		}
	}

	private func handleSelectedPhoto(results: [PHPickerResult]) {
		guard !results.isEmpty else {
			return
		}

		isGalleryProvisioningInProgress = true

		PhotoPicker.convertToUIImageArray(fromResults: results) { images, error in
			if let error = error {
				Log.error("[QrCodeScannerFragment] Failed to load selected photo: \(error.localizedDescription)")
				isGalleryProvisioningInProgress = false
				showInvalidQrToast()
				return
			}

			guard let image = images?.first else {
				Log.warn("[QrCodeScannerFragment] Selected photo is missing image content")
				isGalleryProvisioningInProgress = false
				showInvalidQrToast()
				return
			}

			DispatchQueue.global(qos: .userInitiated).async {
				let decodedText = decodeQrText(from: image)
				DispatchQueue.main.async {
					guard let decodedText = decodedText, !decodedText.isEmpty else {
						isGalleryProvisioningInProgress = false
						showInvalidQrToast()
						return
					}
					NotificationCenter.default.post(name: .qrCodeProvisioningInputDetected, object: decodedText)
				}
			}
		}
	}

	private func decodeQrText(from image: UIImage) -> String? {
		guard let cgImage = image.cgImage else {
			Log.warn("[QrCodeScannerFragment] Selected image has no CGImage backing")
			return nil
		}

		let request = VNDetectBarcodesRequest()
		request.symbologies = [.QR]

		let orientation = CGImagePropertyOrientation(image.imageOrientation)
		let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation, options: [:])

		do {
			try handler.perform([request])
			guard let observations = request.results as? [VNBarcodeObservation] else {
				return nil
			}
			return observations.first(where: { $0.symbology == .QR })?.payloadStringValue
		} catch {
			Log.error("[QrCodeScannerFragment] Failed to detect QR from selected photo: \(error.localizedDescription)")
			return nil
		}
	}

	private func showInvalidQrToast() {
		ToastViewModel.shared.toastMessage = "QR_code_invalid"
		ToastViewModel.shared.displayToast = true
	}
}

private extension CGImagePropertyOrientation {
	init(_ orientation: UIImage.Orientation) {
		switch orientation {
		case .up:
			self = .up
		case .down:
			self = .down
		case .left:
			self = .left
		case .right:
			self = .right
		case .upMirrored:
			self = .upMirrored
		case .downMirrored:
			self = .downMirrored
		case .leftMirrored:
			self = .leftMirrored
		case .rightMirrored:
			self = .rightMirrored
		@unknown default:
			self = .up
		}
	}
}

#Preview {
	QrCodeScannerFragment()
}
