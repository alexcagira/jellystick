package org.jellyfin.androidtv.ui.browsing

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.ui.collections.LanguageCollectionKind
import org.jellyfin.androidtv.ui.collections.LanguageCollectionsDialog
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

fun BrowseGridFragment.createSettingsVisibility() = MutableStateFlow(false)

fun BrowseGridFragment.addSettings(
	view: ComposeView,
	itemId: UUID,
	displayPreferencesId: String,
	visible: MutableStateFlow<Boolean>,
) {
	view.setContent {
		val isVisible by visible.collectAsState(false)

		ProvideRouter(
			routes,
			Routes.LIBRARIES_DISPLAY,
			mapOf("itemId" to itemId.toString(), "displayPreferencesId" to displayPreferencesId)
		) {
			SettingsDialog(
				visible = isVisible,
				onDismissRequest = {
					visible.value = false
					onResume()
				}
			) {
				SettingsRouterContent()
			}
		}
	}
}

/** Films and TV Shows libraries get the "Filter" button (collections by audio language) */
fun supportsLanguageCollections(folder: BaseItemDto): Boolean = LanguageCollectionKind.of(folder) != null

fun BrowseGridFragment.addLanguageCollections(
	view: ComposeView,
	folder: BaseItemDto,
	visible: MutableStateFlow<Boolean>,
) {
	view.setContent {
		val isVisible by visible.collectAsState(false)

		LanguageCollectionsDialog(
			visible = isVisible,
			library = folder,
			onDismissRequest = { visible.value = false },
		)
	}
}
