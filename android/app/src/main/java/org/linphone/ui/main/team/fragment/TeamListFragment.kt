/*
 * Copyright (c) 2026 MundoSMS.
 *
 * This file is part of MundoPhone (fork of linphone-android).
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
package org.linphone.ui.main.team.fragment

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.TeamListFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.contacts.adapter.ContactsListAdapter
import org.linphone.ui.main.contacts.fragment.ContactFragmentDirections
import org.linphone.ui.main.contacts.fragment.ContactsListMenuDialogFragment
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.team.adapter.TeamHiddenFooterAdapter
import org.linphone.ui.main.team.viewmodel.TeamListViewModel
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils

/**
 * MundoPhone: la pestaña Equipo. Es ContactsListFragment de la 6.2 sin filtro, sin CardDAV y sin visor de ficheros; el
 * detalle de un contacto se abre en el mismo panel (contacts_nav_graph).
 */
@UiThread
class TeamListFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Team List Fragment]"
    }

    private lateinit var binding: TeamListFragmentBinding

    private lateinit var listViewModel: TeamListViewModel

    private lateinit var allContactsAdapter: ContactsListAdapter
    private lateinit var favouritesContactsAdapter: ContactsListAdapter

    // MundoPhone: los ocultados, plegados al final de la lista tras «Ver ocultos (N)»
    private lateinit var hiddenContactsAdapter: ContactsListAdapter
    private lateinit var hiddenFooterAdapter: TeamHiddenFooterAdapter
    private lateinit var listAdapter: ConcatAdapter

    private var bottomSheetDialog: BottomSheetDialogFragment? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG READ_CONTACTS permission has been granted, start contacts loader")
            (requireActivity() as MainActivity).loadContacts()
        } else {
            Log.w("$TAG READ_CONTACTS permission has been denied")
        }
    }

    override fun onDefaultAccountChanged() {
        listViewModel.refresh()
    }

    override fun onSlidingPaneClosed() {
        listViewModel.currentlyDisplayedItemId = ""
        allContactsAdapter.resetSelection()
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (findNavController().currentDestination?.id == R.id.newContactFragment) {
            // Holds fragment in place while new fragment slides over it
            return AnimationUtils.loadAnimation(activity, R.anim.hold)
        }
        return super.onCreateAnimation(transit, enter, nextAnim)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allContactsAdapter = ContactsListAdapter()
        favouritesContactsAdapter = ContactsListAdapter(favourites = true)
        hiddenContactsAdapter = ContactsListAdapter()
        hiddenFooterAdapter = TeamHiddenFooterAdapter { listViewModel.toggleHiddenVisibility() }
        listAdapter = ConcatAdapter(allContactsAdapter, hiddenFooterAdapter, hiddenContactsAdapter)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TeamListFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listViewModel = ViewModelProvider(this)[TeamListViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = listViewModel
        observeToastEvents(listViewModel)

        binding.contactsList.setHasFixedSize(true)
        binding.contactsList.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsList.outlineProvider = outlineProvider

        binding.favouritesContactsList.setHasFixedSize(true)
        val favouritesLayoutManager = LinearLayoutManager(requireContext())
        favouritesLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
        binding.favouritesContactsList.layoutManager = favouritesLayoutManager

        configureAdapter(allContactsAdapter)
        configureAdapter(favouritesContactsAdapter)
        configureAdapter(hiddenContactsAdapter, hidden = true)

        listViewModel.hiddenList.observe(viewLifecycleOwner) {
            refreshHiddenSection()
        }
        listViewModel.showHidden.observe(viewLifecycleOwner) {
            refreshHiddenSection()
        }

        listViewModel.isListFiltered.observe(viewLifecycleOwner) { filtered ->
            binding.contactsList.clipToOutline = filtered
        }

        listViewModel.contactsList.observe(viewLifecycleOwner) {
            allContactsAdapter.submitList(it)

            // Wait for adapter to have items before setting it in the RecyclerView,
            // otherwise scroll position isn't retained
            if (binding.contactsList.adapter != listAdapter) {
                binding.contactsList.adapter = listAdapter

                coreContext.postOnMainThreadDelayed({
                    // Delay update to give the adapter enough time to update the list first
                    highlightSelectedContactIfAny()
                }, 200)
            }

            binding.contactsListSwipeRefresh.isRefreshing = false
            // MundoPhone: el indicador de carga lo quita el ViewModel al publicar la lista (TeamListViewModel)
            Log.i("$TAG Team list updated with [${it.size}] items")
        }

        listViewModel.favouritesList.observe(viewLifecycleOwner) {
            favouritesContactsAdapter.submitList(it)

            if (binding.favouritesContactsList.adapter != favouritesContactsAdapter) {
                binding.favouritesContactsList.adapter = favouritesContactsAdapter
            }
        }

        listViewModel.vCardTerminatedEvent.observe(viewLifecycleOwner) {
            it.consume { pair ->
                shareContact(pair.first, pair.second)
            }
        }

        // MundoPhone: tirar hacia abajo vuelve a pedir la lista (con la del borde se rehace sola en cada refresco)
        binding.contactsListSwipeRefresh.setOnRefreshListener {
            listViewModel.syncTeam()
        }

        binding.setOnNewContactClicked {
            if (findNavController().currentDestination?.id == R.id.teamListFragment) {
                Log.i("$TAG Opening contact editor for creating new contact")
                findNavController().navigate(
                    TeamListFragmentDirections.actionTeamListFragmentToNewContactFragment()
                )
            }
        }

        // Al guardar un contacto nuevo, NewContactFragment vuelve aquí y pide enseñarlo
        sharedViewModel.showContactEvent.observe(viewLifecycleOwner) {
            it.consume { refKey ->
                showContact(refKey)
            }
        }

        sharedViewModel.forceRefreshContactsList.observe(viewLifecycleOwner) {
            it.consume {
                listViewModel.filter()
            }
        }

        // AbstractMainFragment related

        listViewModel.title.value = getString(R.string.team)
        setViewModel(listViewModel)
        initViews(
            binding.slidingPaneLayout,
            binding.topBar,
            binding.bottomNavBar,
            R.id.teamListFragment
        )

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("$TAG READ_CONTACTS permission wasn't granted yet, asking for it now")
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    override fun onPause() {
        super.onPause()

        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null
    }

    private fun configureAdapter(adapter: ContactsListAdapter, hidden: Boolean = false) {
        adapter.contactLongClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                val modalBottomSheet = ContactsListMenuDialogFragment(
                    model.isFavourite.value == true,
                    model.isStored,
                    isReadOnly = model.isReadOnly,
                    isNative = model.isNative,
                    { // onDismiss
                        adapter.resetActivated()
                    },
                    { // onFavourite
                        listViewModel.toggleContactFavoriteFlag(model)
                    },
                    { // onShare
                        listViewModel.exportContactAsVCard(model.friend)
                    },
                    { // onDelete
                        showDeleteConfirmationDialog(model)
                    },
                    if (hidden) {
                        null
                    } else {
                        { // MundoPhone: onHideFromTeam
                            listViewModel.hideFromTeam(model)
                            (requireActivity() as GenericActivity).showGreenToast(
                                getString(R.string.team_hidden_toast, model.contactName.orEmpty()),
                                R.drawable.eye_slash
                            )
                        }
                    },
                    if (hidden) {
                        { // MundoPhone: onShowInTeam
                            listViewModel.showInTeam(model)
                            (requireActivity() as GenericActivity).showGreenToast(
                                getString(R.string.team_shown_toast, model.contactName.orEmpty()),
                                R.drawable.user_circle_plus
                            )
                        }
                    } else {
                        null
                    }
                )
                modalBottomSheet.show(parentFragmentManager, ContactsListMenuDialogFragment.TAG)
                bottomSheetDialog = modalBottomSheet
            }
        }

        adapter.favouriteContactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                sharedViewModel.displayedFriend = model.friend
                allContactsAdapter.resetSelection()
                showContact(model.id)
                highlightSelectedContactIfAny()
            }
        }

        adapter.contactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                sharedViewModel.displayedFriend = model.friend
                showContact(model.id)
            }
        }
    }

    private fun showContact(id: String) {
        Log.i("$TAG Displaying contact with ref key [$id]")
        listViewModel.currentlyDisplayedItemId = id

        val navController = binding.contactsNavContainer.findNavController()
        val action = ContactFragmentDirections.actionGlobalContactFragment(id)
        navController.navigate(action)
    }

    private fun shareContact(name: String, file: File) {
        val publicUri = FileProvider.getUriForFile(
            requireContext(),
            requireContext().getString(R.string.file_provider),
            file
        )
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, publicUri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
        }

        try {
            startActivity(Intent.createChooser(sendIntent, null))
        } catch (anfe: ActivityNotFoundException) {
            Log.e("$TAG Failed to start intent chooser: $anfe")
        }
    }

    /** MundoPhone: «Ver ocultos (N)» y, desplegado, los ocultados debajo. */
    private fun refreshHiddenSection() {
        val hidden = listViewModel.hiddenList.value.orEmpty()
        val expanded = listViewModel.showHidden.value == true
        hiddenFooterAdapter.update(hidden.size, expanded)
        hiddenContactsAdapter.submitList(if (expanded) hidden else emptyList())
    }

    private fun showDeleteConfirmationDialog(contactModel: ContactAvatarModel) {
        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getDeleteContactConfirmationDialog(
            requireActivity(),
            model,
            contactModel.contactName.orEmpty()
        )

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                listViewModel.deleteContact(contactModel)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun highlightSelectedContactIfAny() {
        if (listViewModel.currentlyDisplayedItemId.isNotEmpty()) {
            val index = listViewModel.contactsList.value.orEmpty().indexOfFirst { contact ->
                contact.id == listViewModel.currentlyDisplayedItemId
            }
            allContactsAdapter.notifyItemHasBeenSelected(index)
        }
    }
}
