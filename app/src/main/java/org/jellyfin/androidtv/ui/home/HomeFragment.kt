package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.home.modern.ModernHomeScreen
import org.jellyfin.androidtv.ui.home.modern.ModernHomeViewModel
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.androidtv.ui.collections.ImdbTop250
import org.jellyfin.androidtv.ui.library.modern.ModernLibraryRepository
import org.jellyfin.androidtv.ui.collections.CollectionCatalog
import org.jellyfin.androidtv.ui.collections.LanguageCollectionsRepository
import org.jellyfin.androidtv.ui.collections.ServiceCollectionsRepository
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()
	private val languageCollectionsRepository by inject<LanguageCollectionsRepository>()
	private val imdbTop250 by inject<ImdbTop250>()
	private val serviceCollectionsRepository by inject<ServiceCollectionsRepository>()
	private val collectionCatalog by inject<CollectionCatalog>()
	private val libraryRepository by inject<ModernLibraryRepository>()

	// Only created when the modern home screen is enabled
	private val modernHomeViewModel by viewModel<ModernHomeViewModel>()

	private var useModernHome = false
	private var firstResume = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		useModernHome = userPreferences[UserPreferences.modernHomeEnabled]
		// Collections by audio language: add the new titles once a day
		languageCollectionsRepository.updateInBackground()
		serviceCollectionsRepository.updateInBackground()
		// Which collections hold series: the Collections pages of Films and TV Shows open at once
		collectionCatalog.preloadInBackground()
		// "IMDb Top 250" collection: updated once a week
		imdbTop250.syncInBackground()
		// Genres for the search page, the server is slow to list them
		libraryRepository.preloadGenres()

		return content {
			if (useModernHome) ModernHomeScreen(viewModel = modernHomeViewModel)
			else LegacyHome()
		}
	}

	@Composable
	private fun LegacyHome() {
		val rowsFocusRequester = remember { FocusRequester() }
		LaunchedEffect(rowsFocusRequester) { rowsFocusRequester.requestFocus() }

		Column {
			MainToolbar(MainToolbarActiveButton.Home)

			// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
			// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
			// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
			var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }
			AndroidFragment<HomeRowsFragment>(
				modifier = Modifier
					.focusGroup()
					.focusRequester(rowsFocusRequester)
					.focusProperties {
						onExit = {
							val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
								cancelFocusChange()
							} else {
								rowsSupportFragment?.selectedPosition = 0
								rowsSupportFragment?.verticalGridView?.clearFocus()
							}
						}
					}
					.fillMaxSize(),
				maxLifecycle = Lifecycle.State.RESUMED,
				onUpdate = { fragment ->
					rowsSupportFragment = fragment
				}
			)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)

		if (useModernHome) {
			// Refresh rows when something was watched or added while the home screen is visible
			viewLifecycleOwner.lifecycleScope.launch {
				viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
					api.webSocket.subscribe<UserDataChangedMessage>()
						.onEach { modernHomeViewModel.refresh() }
						.launchIn(this)

					api.webSocket.subscribe<LibraryChangedMessage>()
						.onEach { modernHomeViewModel.refresh() }
						.launchIn(this)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()

		// Coming back from the player or details screen: update continue watching, new episodes...
		if (useModernHome && !firstResume) modernHomeViewModel.refresh()
		firstResume = false
	}
}
