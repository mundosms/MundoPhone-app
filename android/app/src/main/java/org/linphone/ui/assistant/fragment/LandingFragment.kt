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

import android.content.Context
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantLandingFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.model.AcceptConditionsAndPolicyDialogModel
import org.linphone.ui.assistant.viewmodel.AccountLoginViewModel
import org.linphone.utils.DialogUtils
import org.linphone.utils.PhoneNumberUtils

@UiThread
class LandingFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Landing Fragment]"
    }

    private lateinit var binding: AssistantLandingFragmentBinding

    private val viewModel: AccountLoginViewModel by navGraphViewModels(
        R.id.assistant_nav_graph
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AssistantLandingFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        // MundoPhone: transporte del alta manual (TCP primero; en tableta no hay selector y se queda en TCP)
        val transportAdapter = ArrayAdapter(requireContext(), R.layout.drop_down_item, viewModel.availableTransports)
        transportAdapter.setDropDownViewResource(R.layout.generic_dropdown_cell)
        binding.transport?.let { spinner ->
            spinner.adapter = transportAdapter
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    viewModel.transport.value = viewModel.availableTransports[position]
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }
        }

        binding.setBackClickListener {
            requireActivity().finish()
        }

        // MundoPhone: sin botón «Ayuda» y el alta de cuentas en la web de MundoSMS (sin términos de
        // Linphone ni FlexiAPI)
        binding.setRegisterClickListener {
            openUrlInBrowser(getString(R.string.website_mundosms_register_url))
        }

        // MundoPhone: antes del escáner, cómo sacar el QR de la extensión en la web
        binding.setQrCodeClickListener {
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                val dialog = DialogUtils.getQrScanPopupDialog(requireContext()) {
                    if (findNavController().currentDestination?.id == R.id.landingFragment) {
                        val action =
                            LandingFragmentDirections.actionLandingFragmentToQrCodeScannerFragment()
                        findNavController().navigate(action)
                    }
                }
                dialog.show()
            }
        }

        binding.setThirdPartySipAccountLoginClickListener {
            if (viewModel.conditionsAndPrivacyPolicyAccepted) {
                goToLoginThirdPartySipAccountFragment(false)
            } else {
                showAcceptConditionsAndPrivacyDialog(goToThirdPartySipAccountLogin = true)
            }
        }

        // MundoPhone: la contraseña de la extensión se ve en la web de MundoSMS: una ventana propia en lugar de
        // RecoverAccountFragment (subscribe.linphone.org)
        binding.setForgottenPasswordClickListener {
            showForgottenPasswordPopup()
        }

        binding.setDisplayNameTooltipClickListener {
            showDisplayNameTooltip()
        }

        viewModel.showPassword.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                delay(50)
                binding.password.setSelection(binding.password.text?.length ?: 0)
            }
        }

        viewModel.accountLoggedInEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG Account successfully logged-in, leaving assistant")
                requireActivity().finish()
            }
        }

        viewModel.accountLoginErrorEvent.observe(viewLifecycleOwner) {
            it.consume { message ->
                (requireActivity() as GenericActivity).showRedToast(
                    message,
                    R.drawable.warning_circle
                )
            }
        }

        viewModel.skipLandingToThirdPartySipAccountEvent.observe(viewLifecycleOwner) {
            it.consume {
                goToLoginThirdPartySipAccountFragment(true)
            }
        }

        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val countryIso = telephonyManager.networkCountryIso
        coreContext.postOnCoreThread {
            val dialPlan = PhoneNumberUtils.getDeviceDialPlan(countryIso)
            if (dialPlan != null) {
                viewModel.internationalPrefix.postValue(dialPlan.countryCallingCode)
                viewModel.internationalPrefixIsoCountryCode.postValue(dialPlan.isoCountryCode)
            }
        }
    }

    // MundoPhone: la ventana de «¿Olvidaste tu contraseña?», con «web» enlazado a la página de acceso
    private fun showForgottenPasswordPopup() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_popup, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).create()

        dialogView.findViewById<ImageButton>(R.id.close_popup_button)?.setOnClickListener {
            dialog.dismiss()
        }

        val textView = dialogView.findViewById<TextView>(R.id.popup_paso1)
        val text = getString(R.string.popup_paso1)
        val linkText = "web"
        val start = text.indexOf(linkText)
        if (start != -1) {
            val spannable = SpannableString(text)
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (!openUrlInBrowser(getString(R.string.website_mundosms_access_url))) {
                        dialog.dismiss() // Que la ventana no tape el aviso de que no hay navegador
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.color = ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark)
                }
            }
            spannable.setSpan(clickableSpan, start, start + linkText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            textView.text = spannable
            textView.movementMethod = LinkMovementMethod.getInstance()
        } else {
            textView.text = text
        }

        dialog.show()
    }

    private fun goToLoginThirdPartySipAccountFragment(skipWarning: Boolean) {
        if (findNavController().currentDestination?.id == R.id.landingFragment) {
            val action = if (skipWarning) {
                LandingFragmentDirections.actionLandingFragmentToThirdPartySipAccountLoginFragment()
            } else {
                LandingFragmentDirections.actionLandingFragmentToThirdPartySipAccountWarningFragment()
            }
            findNavController().navigate(action)
        }
    }

    private fun showAcceptConditionsAndPrivacyDialog(
        goToThirdPartySipAccountLogin: Boolean = false
    ) {
        val model = AcceptConditionsAndPolicyDialogModel()
        val dialog = DialogUtils.getAcceptConditionsAndPrivacyDialog(
            requireActivity(),
            model
        )

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.conditionsAcceptedEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG Conditions & Privacy policy have been accepted")
                coreContext.postOnCoreThread {
                    corePreferences.conditionsAndPrivacyPolicyAccepted = true
                }
                dialog.dismiss()

                if (goToThirdPartySipAccountLogin) {
                    goToLoginThirdPartySipAccountFragment(false)
                }
            }
        }

        model.privacyPolicyClickedEvent.observe(viewLifecycleOwner) {
            it.consume {
                val url = getString(R.string.website_privacy_policy_url)
                openUrlInBrowser(url)
            }
        }

        model.generalTermsClickedEvent.observe(viewLifecycleOwner) {
            it.consume {
                val url = getString(R.string.website_terms_and_conditions_url)
                openUrlInBrowser(url)
            }
        }

        dialog.show()
    }

    // MundoPhone: sin navegador, un aviso rojo («Crear cuenta», el enlace de la contraseña olvidada…)
    private fun openUrlInBrowser(url: String): Boolean {
        return (requireActivity() as GenericActivity).openUrlInBrowser(url)
    }

    // MundoPhone: el «?» del remitente
    private fun showDisplayNameTooltip() {
        val dialog = DialogUtils.getAccountDisplayNameHelpDialog(requireActivity())
        dialog.show()
    }
}
