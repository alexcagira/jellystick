package org.jellyfin.androidtv.ui.home.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Watchlist page, opened with the bookmark button of the navigation bar.
 */
class ModernWatchlistFragment : Fragment() {
	private val viewModel by viewModel<ModernWatchlistViewModel>()
	private var firstResume = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = content {
		ModernWatchlistScreen(viewModel = viewModel)
	}

	override fun onResume() {
		super.onResume()

		// Something may have been added or removed on a details page
		if (!firstResume) viewModel.refresh()
		firstResume = false
	}
}
