/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
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
package org.linphone.ui.assistant.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantQrCodeScannerFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.viewmodel.QrCodeViewModel
import org.linphone.ui.sso.SingleSignOnActivity
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils

@UiThread
class QrCodeScannerFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Qr Code Scanner Fragment]"
    }

    private lateinit var binding: AssistantQrCodeScannerFragmentBinding

    private val viewModel: QrCodeViewModel by navGraphViewModels(
        R.id.assistant_nav_graph
    )

    // MundoPhone: el QR también desde la galería (ML Kit, D9): muchos QR del panel llegan como captura o por correo
    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                Log.w("$TAG No image picked from gallery")
                enableQrCodeVideoScanner()
                return@registerForActivityResult
            }
            decodeQrFromImage(uri)
        }

    // MundoPhone: se fue a los ajustes de Android a conceder la cámara (al volver sin ella, aviso y atrás)
    private var navigatedToSettings = false

    // MundoPhone: siempre se pide; si queda denegado para siempre, una ventana ofrece ir a los ajustes
    // (upstream saltaba a los ajustes sin preguntar ni explicar, también si nunca se había pedido)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG CAMERA permission has been granted")
            enableQrCodeVideoScanner()
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.CAMERA)) {
            Log.w("$TAG CAMERA permission permanently denied, offering to go to Android settings")
            showCameraPermissionSettingsDialog()
        } else {
            Log.e("$TAG CAMERA permission has been denied, leaving this fragment")
            showRedToast(R.string.call_camera_permission_not_granted_toast)
            goBack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AssistantQrCodeScannerFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            goBack()
        }

        binding.setGalleryClickListener {
            openGalleryPicker()
        }

        viewModel.remoteProvisioningSuccessfulEvent.observe(viewLifecycleOwner) {
            it.consume { atLeastOneAccountFound ->
                if (atLeastOneAccountFound) {
                    requireActivity().finish()
                } else {
                    goBack()
                }
            }
        }

        viewModel.onErrorEvent.observe(viewLifecycleOwner) {
            it.consume {
                // Core has restarted but something went wrong, restart video capture
                enableQrCodeVideoScanner()
            }
        }

        coreContext.bearerAuthenticationRequestedEvent.observe(viewLifecycleOwner) {
            it.consume { pair ->
                val serverUrl = pair.first
                val username = pair.second
                Log.i(
                    "$TAG Bearer auth requested, navigating to Single Sign On Fragment with server URL [$serverUrl] and username [$username]"
                )
                val intent = Intent(requireContext(), SingleSignOnActivity::class.java)
                intent.putExtra(SingleSignOnActivity.INTENT_EXTRA_USERNAME, username)
                intent.putExtra(SingleSignOnActivity.INTENT_EXTRA_SERVER_URL, serverUrl)
                startActivity(intent)
            }
        }

        if (!isCameraPermissionGranted()) {
            Log.w("$TAG CAMERA permission wasn't granted yet, asking for it now")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()

        if (isCameraPermissionGranted()) {
            Log.i(
                "$TAG Record video permission is granted, starting video preview with back cam if possible"
            )
            navigatedToSettings = false
            viewModel.setBackCamera()
            enableQrCodeVideoScanner()
        } else if (navigatedToSettings) {
            Log.w("$TAG Back from Android settings without CAMERA permission, leaving this fragment")
            navigatedToSettings = false
            showRedToast(R.string.call_camera_permission_not_granted_toast)
            goBack()
        }
    }

    private fun showCameraPermissionSettingsDialog() {
        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getCameraPermissionSettingsDialog(requireActivity(), model)

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
                goBack()
            }
        }

        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
                navigatedToSettings = true
                (requireActivity() as GenericActivity).goToAndroidPermissionSettings()
            }
        }

        // «Atrás» con la ventana abierta: también fuera del escáner, que sin cámara no sirve
        dialog.setOnCancelListener {
            goBack()
        }

        dialog.show()
    }

    override fun onPause() {
        disableQrCodeVideoScanner()
        coreContext.postOnCoreThread {
            coreContext.setFrontCamera()
        }

        super.onPause()
    }

    private fun goBack() {
        findNavController().popBackStack()
    }

    private fun isCameraPermissionGranted(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        Log.i("$TAG CAMERA permission is ${if (granted) "granted" else "denied"}")
        return granted
    }

    private fun enableQrCodeVideoScanner() {
        coreContext.postOnCoreThread { core ->
            core.nativePreviewWindowId = binding.qrCodePreview
            core.isQrcodeVideoPreviewEnabled = true
            core.isVideoPreviewEnabled = true
            Log.i("$TAG Video preview with QR scanner enabled")
        }
    }

    private fun disableQrCodeVideoScanner() {
        coreContext.postOnCoreThread { core ->
            core.nativePreviewWindowId = null
            core.isVideoPreviewEnabled = false
            core.isQrcodeVideoPreviewEnabled = false
        }
    }

    private fun openGalleryPicker() {
        disableQrCodeVideoScanner()
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun decodeQrFromImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    createInputImageWithQuietZone(uri)
                }
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(options)

                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val firstQrValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (firstQrValue.isNullOrBlank()) {
                            Log.w("$TAG No QR value detected from selected image")
                            showRedToast(R.string.assistant_qr_code_invalid_toast)
                            enableQrCodeVideoScanner()
                        } else {
                            Log.i("$TAG QR value detected from gallery image")
                            viewModel.processScannedQrPayload(firstQrValue)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e("$TAG Failed to decode QR from image: ${exception.message}")
                        showRedToast(R.string.assistant_qr_code_invalid_toast)
                        enableQrCodeVideoScanner()
                    }
                    .addOnCompleteListener {
                        scanner.close()
                    }
            } catch (exception: Exception) {
                Log.e("$TAG Failed to load selected image for QR decode: ${exception.message}")
                showRedToast(R.string.assistant_qr_code_invalid_toast)
                enableQrCodeVideoScanner()
            }
        }
    }

    // Un margen blanco alrededor: las capturas recortan el QR al borde y ML Kit necesita la «zona de silencio»
    @WorkerThread
    private fun createInputImageWithQuietZone(uri: Uri): InputImage {
        val bitmap = decodeBitmapFromUri(uri)
        if (bitmap == null) {
            Log.w("$TAG Failed to decode bitmap from gallery, falling back to file path image")
            return InputImage.fromFilePath(requireContext(), uri)
        }

        val paddedBitmap = addWhiteQuietZonePadding(bitmap)
        return InputImage.fromBitmap(paddedBitmap, 0)
    }

    @WorkerThread
    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(requireContext().contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to decode bitmap from gallery: ${e.message}")
            null
        }
    }

    @WorkerThread
    private fun addWhiteQuietZonePadding(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        val padding = (maxDimension * 0.14f).toInt().coerceAtLeast(24)
        val paddedBitmap = createBitmap(bitmap.width + padding * 2, bitmap.height + padding * 2)

        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, padding.toFloat(), padding.toFloat(), null)
        return paddedBitmap
    }

    private fun showRedToast(message: Int) {
        (requireActivity() as GenericActivity).showRedToast(getString(message), R.drawable.warning_circle)
    }
}
