package org.jellyfin.androidtv.ui.details.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.UUID

/**
 * Modern details screen for movies, series, seasons and people. Other item types are handed over
 * to the original details screen.
 */
class ModernDetailsFragment : Fragment() {
	companion object {
		const val ARGUMENT_ITEM_ID = "ItemId"

		/** Time the theme song keeps playing after leaving the page, so it continues on the season page */
		private const val THEME_MUSIC_HANDOVER_MS = 2500L
	}

	private val viewModel by viewModel<ModernDetailsViewModel>()
	private val navigationRepository by inject<NavigationRepository>()
	private val themeMusicPlayer by inject<ThemeMusicPlayer>()
	private var firstResume = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val itemId = arguments?.getString(ARGUMENT_ITEM_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
		if (itemId != null) viewModel.load(itemId)

		return content {
			ModernDetailsScreen(viewModel = viewModel)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// Unsupported item type (episode, album...): replace this screen with the original one
		viewLifecycleOwner.lifecycleScope.launch {
			val state = viewModel.state
				.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
				.first { !it.loading }

			val item = state.item
			if (item != null && state.kind == null) {
				// Albums, artists and music playlists: the page in the style of the music library
				val destination = if (Destinations.isModernMusicItem(item)) Destinations.modernMusicItem(item.id)
				else Destinations.legacyItemDetails(item.id)
				navigationRepository.navigate(destination, replace = true)
			}
		}

		// Theme song of the series while this page is visible (series and season pages)
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				viewModel.state
					.map { it.themeSeriesId }
					.distinctUntilChanged()
					.collect { seriesId -> if (seriesId != null) themeMusicPlayer.play(seriesId) }
			}
		}
	}

	override fun onPause() {
		super.onPause()

		themeMusicPlayer.stop(delayMs = THEME_MUSIC_HANDOVER_MS)
	}

	override fun onResume() {
		super.onResume()

		// Coming back from the player: update progress, next up and watched state
		if (!firstResume) viewModel.refresh()
		firstResume = false
	}
}
