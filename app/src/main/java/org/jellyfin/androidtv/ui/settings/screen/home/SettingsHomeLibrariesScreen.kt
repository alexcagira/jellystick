package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * "Hidden libraries": show again the libraries hidden from the navigation bar (hold OK on a library
 * in the bar to hide it), or hide more of them. Hidden libraries are removed from the navigation
 * bar, the home screen and search results.
 */
@Composable
fun SettingsHomeLibrariesScreen() {
	val libraryVisibilityRepository = koinInject<LibraryVisibilityRepository>()
	val hiddenLibraryIds by libraryVisibilityRepository.hiddenLibraryIds.collectAsState()
	val libraries by produceState(initialValue = emptyList<BaseItemDto>()) {
		value = runCatching { libraryVisibilityRepository.libraries(forceRefresh = true) }
			.onFailure { Timber.w(it, "Unable to load libraries") }
			.getOrDefault(emptyList())
	}

	val hiddenLibraries = libraries.filter { it.id in hiddenLibraryIds }
	val visibleLibraries = libraries.filterNot { it.id in hiddenLibraryIds }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.modern_home_libraries)) },
				captionContent = { Text(stringResource(R.string.modern_home_libraries_description)) },
			)
		}

		item {
			ListSection(headingContent = { Text(stringResource(R.string.modern_home_libraries_hidden)) })
		}

		if (hiddenLibraries.isEmpty()) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.modern_home_libraries_none_hidden)) },
					captionContent = { Text(stringResource(R.string.modern_home_libraries_hint)) },
					enabled = false,
					onClick = {},
				)
			}
		}

		items(hiddenLibraries, key = { "hidden_${it.id}" }) { library ->
			ListButton(
				headingContent = { Text(library.name.orEmpty()) },
				captionContent = { Text(stringResource(R.string.modern_home_show_again)) },
				trailingContent = { Checkbox(checked = false) },
				onClick = { libraryVisibilityRepository.setHidden(library.id, hidden = false) },
				modifier = Modifier.focusKey("library_visibility_${library.id}")
			)
		}

		item {
			ListSection(headingContent = { Text(stringResource(R.string.modern_home_libraries_visible)) })
		}

		items(visibleLibraries, key = { "visible_${it.id}" }) { library ->
			ListButton(
				headingContent = { Text(library.name.orEmpty()) },
				trailingContent = { Checkbox(checked = true) },
				onClick = { libraryVisibilityRepository.setHidden(library.id, hidden = true) },
				modifier = Modifier.focusKey("library_visibility_${library.id}")
			)
		}
	}
}
