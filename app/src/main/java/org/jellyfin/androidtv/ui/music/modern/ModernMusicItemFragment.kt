package org.jellyfin.androidtv.ui.music.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.UUID

/**
 * Album, playlist, artist or genre in the style of the music page, with its songs.
 */
class ModernMusicItemFragment : Fragment() {
	companion object {
		const val ARGUMENT_ITEM_ID = "ItemId"
	}

	private val viewModel by viewModel<ModernMusicItemViewModel>()
	private var firstResume = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val itemId = arguments?.getString(ARGUMENT_ITEM_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
		if (itemId != null) viewModel.load(itemId)

		return content {
			ModernMusicItemScreen(viewModel = viewModel)
		}
	}

	override fun onResume() {
		super.onResume()

		// Playlists change after "Add to playlist", favorites on other pages
		if (!firstResume) viewModel.refresh()
		firstResume = false
	}
}
