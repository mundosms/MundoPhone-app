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
package org.linphone.ui.main.help.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import org.linphone.R
import org.linphone.databinding.HelpDebugFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.fileviewer.FileViewerActivity
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.ui.main.help.viewmodel.HelpViewModel
import org.linphone.utils.AppUtils

class DebugFragment : GenericMainFragment() {
    companion object {
        // MundoPhone (F2): la llamada de la que se informa (detalle del historial o aviso tras colgar) y el motivo del aviso
        const val ARGUMENT_REPORT_CALL_ID = "ReportCallId"
        const val ARGUMENT_REPORT_REASON = "ReportReason"
    }

    private lateinit var binding: HelpDebugFragmentBinding

    private lateinit var viewModel: HelpViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = HelpDebugFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[HelpViewModel::class.java]
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        viewModel.canConfigFileBeViewed.postValue(requireActivity() is MainActivity)

        binding.setBackClickListener {
            goBack()
        }

        binding.setAppVersionClickListener {
            val label = getString(R.string.help_troubleshooting_app_version_title)
            val value = viewModel.appVersion.value.orEmpty()
            AppUtils.copyToClipboard(requireContext(), label, value)
        }

        binding.setSdkVersionClickListener {
            val label = getString(R.string.help_troubleshooting_sdk_version_title)
            val value = viewModel.sdkVersion.value.orEmpty()
            AppUtils.copyToClipboard(requireContext(), label, value)
        }

        viewModel.debugLogsCleanedEvent.observe(viewLifecycleOwner) {
            it.consume {
                (requireActivity() as GenericActivity).showGreenToast(
                    getString(R.string.help_troubleshooting_debug_logs_cleaned_toast_message),
                    R.drawable.info
                )
            }
        }

        viewModel.showShareLogsFormEvent.observe(viewLifecycleOwner) {
            it.consume {
                showShareLogsForm()
            }
        }

        viewModel.showCallReportFormEvent.observe(viewLifecycleOwner) {
            it.consume { header ->
                showShareLogsForm(header)
            }
        }

        // MundoPhone (F2): venimos de «Informar de un problema» (historial) o del aviso: el formulario, ya con la llamada.
        // Solo la primera vez: al girar la pantalla no se vuelve a abrir.
        val reportCallId = arguments?.getString(ARGUMENT_REPORT_CALL_ID)
        if (savedInstanceState == null && !reportCallId.isNullOrEmpty()) {
            viewModel.reportCallProblem(reportCallId, arguments?.getString(ARGUMENT_REPORT_REASON))
        }

        viewModel.uploadDebugLogsFinishedEvent.observe(viewLifecycleOwner) {
            it.consume { url ->
                // El servidor de soporte contesta con …/r/<referencia>: se le enseña al usuario por si llama
                val reference = url.substringAfterLast("/r/", "")
                (requireActivity() as GenericActivity).showGreenToast(
                    getString(R.string.help_troubleshooting_debug_logs_sent_toast_message, reference),
                    R.drawable.info
                )
            }
        }

        viewModel.uploadDebugLogsErrorEvent.observe(viewLifecycleOwner) {
            it.consume {
                (requireActivity() as GenericActivity).showRedToast(
                    getString(R.string.help_troubleshooting_debug_logs_upload_error_toast_message),
                    R.drawable.warning_circle
                )
            }
        }

        viewModel.showConfigFileEvent.observe(viewLifecycleOwner) {
            it.consume { path ->
                if (findNavController().currentDestination?.id == R.id.debugFragment) {
                    val intent = Intent(requireActivity(), FileViewerActivity::class.java)
                    val bundle = Bundle()
                    bundle.putString("path", path)
                    val nowInSeconds = System.currentTimeMillis() / 1000
                    bundle.putLong("timestamp", nowInSeconds)
                    intent.putExtras(bundle)
                    startActivity(intent)
                }
            }
        }
    }

    private fun showShareLogsForm(callHeader: String? = null) {
        val form = layoutInflater.inflate(R.layout.dialog_share_logs_form, null)
        val description = form.findViewById<EditText>(R.id.share_logs_description)
        val contact = form.findViewById<EditText>(R.id.share_logs_contact)
        if (callHeader != null) {
            // Con la llamada delante, soporte no recibe el registro a ciegas: lo que pasó es opcional
            form.findViewById<View>(R.id.share_logs_call_label).visibility = View.VISIBLE
            form.findViewById<TextView>(R.id.share_logs_call).apply {
                text = callHeader
                visibility = View.VISIBLE
            }
            form.findViewById<TextView>(R.id.share_logs_description_label)
                .setText(R.string.help_troubleshooting_share_logs_call_description_label)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.help_troubleshooting_share_logs_dialog_title)
            .setView(form)
            .setPositiveButton(R.string.help_troubleshooting_share_logs_send, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            // Sin descripción no se envía (ni se cierra el diálogo), salvo si va con una llamada
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = description.text.toString().trim()
                if (text.isEmpty() && callHeader == null) {
                    description.error = getString(R.string.help_troubleshooting_share_logs_description_missing)
                    return@setOnClickListener
                }
                viewModel.uploadLogsToSupport(text, contact.text.toString().trim(), callHeader)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
