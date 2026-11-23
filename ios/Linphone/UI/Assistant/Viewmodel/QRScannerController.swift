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

class QRScannerController: UIViewController {
	var captureSession = AVCaptureSession()
	var videoPreviewLayer: AVCaptureVideoPreviewLayer?
	var qrCodeFrameView: UIView?

	var delegate: AVCaptureMetadataOutputObjectsDelegate?

	private static let TAG = "[QR Code Scanner]"
	private var previewSizeLogged = false

	override func viewDidLoad() {
		super.viewDidLoad()
		view.backgroundColor = .black

		// MundoPhone: el permiso de la cámara, al registro (el 21/11/2026 un registro con la pantalla del lector en blanco no
		// decía nada de la cámara), y sin permiso un aviso con el botón de Ajustes en vez de la pantalla vacía
		let status = AVCaptureDevice.authorizationStatus(for: .video)
		Log.info("\(QRScannerController.TAG) Camera authorization is [\(QRScannerController.describe(status))]")
		switch status {
		case .authorized:
			setUpCapture()
		case .notDetermined:
			AVCaptureDevice.requestAccess(for: .video) { granted in
				DispatchQueue.main.async {
					Log.info("\(QRScannerController.TAG) Camera access \(granted ? "granted" : "denied") by the user")
					if granted {
						self.setUpCapture()
					} else {
						self.showCameraDenied()
					}
				}
			}
		default:
			showCameraDenied()
		}
	}

	override func viewDidLayoutSubviews() {
		super.viewDidLayoutSubviews()
		// MundoPhone: la imagen de la cámara sigue al tamaño de la vista. Upstream solo la dimensionaba en viewDidLoad, cuando
		// SwiftUI aún puede no haberle dado tamaño: con 0×0 la cámara funciona y lee códigos, pero no se ve (pantalla en
		// blanco en un iPhone 14 Pro con iOS 27.2.1, 21/11/2026).
		videoPreviewLayer?.frame = view.layer.bounds
		if !previewSizeLogged, videoPreviewLayer != nil, view.bounds.width > 0 {
			previewSizeLogged = true
			Log.info("\(QRScannerController.TAG) Camera preview laid out at [\(Int(view.bounds.width))x\(Int(view.bounds.height))]")
		}
	}

	private func setUpCapture() {
		// Get the back-facing camera for capturing videos
		guard let captureDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
			Log.error("\(QRScannerController.TAG) No back camera found")
			return
		}

		let videoInput: AVCaptureDeviceInput

		do {
			// Get an instance of the AVCaptureDeviceInput class using the previous device object.
			videoInput = try AVCaptureDeviceInput(device: captureDevice)

		} catch {
			Log.error("\(QRScannerController.TAG) Couldn't open the camera: \(error.localizedDescription)")
			return
		}

		// Set the input device on the capture session.
		captureSession.addInput(videoInput)

		// Initialize a AVCaptureMetadataOutput object and set it as the output device to the capture session.
		let captureMetadataOutput = AVCaptureMetadataOutput()
		captureSession.addOutput(captureMetadataOutput)

		// Set delegate and use the default dispatch queue to execute the call back
		captureMetadataOutput.setMetadataObjectsDelegate(delegate, queue: DispatchQueue.main)
		captureMetadataOutput.metadataObjectTypes = [ .qr ]

		// Initialize the video preview layer and add it as a sublayer to the viewPreview view's layer.
		videoPreviewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
		videoPreviewLayer?.videoGravity = AVLayerVideoGravity.resizeAspectFill
		videoPreviewLayer?.frame = view.layer.bounds
		view.layer.addSublayer(videoPreviewLayer!)
		Log.info("\(QRScannerController.TAG) Camera preview created at [\(Int(view.bounds.width))x\(Int(view.bounds.height))]")

		// Start video capture.
		DispatchQueue.global(qos: .background).async {
			self.captureSession.startRunning()
			Log.info("\(QRScannerController.TAG) Camera session running [\(self.captureSession.isRunning)]")
		}
	}

	/// Sin permiso de cámara: lo dice y lleva a Ajustes. La galería (botón de abajo, en QrCodeScannerFragment) sigue a mano.
	private func showCameraDenied() {
		Log.error("\(QRScannerController.TAG) No camera permission, showing the Settings hint")
		let label = UILabel()
		label.text = NSLocalizedString("assistant_qr_camera_denied", comment: "")
		label.textColor = .white
		label.textAlignment = .center
		label.numberOfLines = 0
		label.font = .systemFont(ofSize: 17, weight: .semibold)

		let button = UIButton(type: .system)
		button.setTitle(NSLocalizedString("assistant_qr_open_settings", comment: ""), for: .normal)
		button.titleLabel?.font = .systemFont(ofSize: 17, weight: .bold)
		button.addAction(UIAction { _ in
			if let url = URL(string: UIApplication.openSettingsURLString) {
				UIApplication.shared.open(url)
			}
		}, for: .touchUpInside)

		let stack = UIStackView(arrangedSubviews: [label, button])
		stack.axis = .vertical
		stack.spacing = 16
		stack.translatesAutoresizingMaskIntoConstraints = false
		view.addSubview(stack)
		NSLayoutConstraint.activate([
			stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
			stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
			stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32)
		])
	}

	private static func describe(_ status: AVAuthorizationStatus) -> String {
		switch status {
		case .authorized: return "authorized"
		case .denied: return "denied"
		case .restricted: return "restricted"
		case .notDetermined: return "not determined"
		@unknown default: return "unknown (\(status.rawValue))"
		}
	}
}
