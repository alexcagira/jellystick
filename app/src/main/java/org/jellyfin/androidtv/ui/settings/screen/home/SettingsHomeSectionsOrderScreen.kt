package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.home.modern.HomeSectionOrder
import org.jellyfin.androidtv.ui.home.modern.titleRes
import org.jellyfin.androidtv.ui.music.modern.MusicSectionOrder
import org.jellyfin.androidtv.ui.music.modern.MusicSectionType
import org.jellyfin.androidtv.ui.music.modern.titleRes as musicTitleRes
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

/**
 * "Home sections": order of the rows of the modern home screen. OK picks a section up, up/down
 * move it and OK (or back) drops it.
 */
@Composable
fun SettingsHomeSectionsOrderScreen() {
	val userPreferences = koinInject<UserPreferences>()

	SectionsOrderScreen(
		title = stringResource(R.string.modern_home_sections),
		keyPrefix = "home_sections_order",
		initialOrder = remember { HomeSectionOrder.parse(userPreferences[UserPreferences.homeSectionOrder]) },
		defaultOrder = HomeSectionOrder.default,
		name = { it.name },
		titleRes = { it.titleRes },
		move = HomeSectionOrder::move,
		onSave = { userPreferences[UserPreferences.homeSectionOrder] = HomeSectionOrder.serialize(it) },
	)
}

/**
 * "Music sections": order of the rows of the modern music page.
 */
@Composable
fun SettingsMusicSectionsOrderScreen() {
	val userPreferences = koinInject<UserPreferences>()

	SectionsOrderScreen<MusicSectionType>(
		title = stringResource(R.string.modern_music_sections),
		keyPrefix = "music_sections_order",
		initialOrder = remember { MusicSectionOrder.parse(userPreferences[UserPreferences.musicSectionOrder]) },
		defaultOrder = MusicSectionOrder.default,
		name = { it.name },
		titleRes = { it.musicTitleRes },
		move = MusicSectionOrder::move,
		onSave = { userPreferences[UserPreferences.musicSectionOrder] = MusicSectionOrder.serialize(it) },
	)
}

@Composable
private fun <T : Any> SectionsOrderScreen(
	title: String,
	keyPrefix: String,
	initialOrder: List<T>,
	defaultOrder: List<T>,
	name: (T) -> String,
	titleRes: (T) -> Int,
	move: (order: List<T>, section: T, offset: Int) -> List<T>,
	onSave: (List<T>) -> Unit,
) {
	var order by remember { mutableStateOf(initialOrder) }
	var moving by remember { mutableStateOf<T?>(null) }

	fun save(newOrder: List<T>) {
		order = newOrder
		onSave(newOrder)
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(title) },
				captionContent = { Text(stringResource(R.string.modern_home_sections_hint)) },
			)
		}

		items(order, key = { name(it) }) { section ->
			val isMoving = moving == section

			ListButton(
				leadingContent = { Text("${order.indexOf(section) + 1}") },
				headingContent = { Text(stringResource(titleRes(section))) },
				captionContent = if (isMoving) {
					{ Text(stringResource(R.string.modern_home_sections_moving)) }
				} else null,
				trailingContent = if (isMoving) {
					{ Icon(painterResource(R.drawable.ic_move_vertical), contentDescription = null) }
				} else null,
				onClick = { moving = if (isMoving) null else section },
				modifier = Modifier
					.focusKey("${keyPrefix}_${name(section)}")
					.onPreviewKeyEvent { event ->
						if (!isMoving) return@onPreviewKeyEvent false
						val isDown = event.type == KeyEventType.KeyDown
						when (event.key) {
							Key.DirectionUp -> {
								if (isDown) save(move(order, section, -1))
								true
							}

							Key.DirectionDown -> {
								if (isDown) save(move(order, section, 1))
								true
							}

							// Back drops the section instead of leaving the screen
							Key.Back, Key.Escape -> {
								if (event.type == KeyEventType.KeyUp) moving = null
								true
							}

							else -> false
						}
					},
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.modern_home_sections_reset)) },
				onClick = {
					moving = null
					save(defaultOrder)
				},
				modifier = Modifier.focusKey("${keyPrefix}_reset"),
			)
		}
	}
}
