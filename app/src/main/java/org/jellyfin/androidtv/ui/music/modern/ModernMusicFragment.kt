package org.jellyfin.androidtv.ui.music.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Music library page in the style of the modern home screen.
 */
class ModernMusicFragment : Fragment() {
	private val viewModel by viewModel<ModernMusicViewModel>()
	private var firstResume = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val library = arguments?.getString(Extras.Folder)?.let { json ->
			runCatching { Json.decodeFromString<BaseItemDto>(json) }
				.onFailure { Timber.w(it, "Invalid music library argument") }
				.getOrNull()
		}

		if (library != null) {
			viewModel.load(
				library = library,
				labels = MusicLabels(
					yourMix = getString(R.string.modern_music_your_mix),
					radio = getString(R.string.modern_music_radio),
					recommended = getString(R.string.modern_music_recommended),
					becauseYouListen = { artist -> getString(R.string.modern_music_because_you_listen, artist) },
				),
			)
		}

		return content {
			if (library != null) ModernMusicScreen(viewModel = viewModel, library = library)
		}
	}

	override fun onResume() {
		super.onResume()

		// "Last played" changes after listening to something
		if (!firstResume) viewModel.refresh()
		firstResume = false
	}
}
