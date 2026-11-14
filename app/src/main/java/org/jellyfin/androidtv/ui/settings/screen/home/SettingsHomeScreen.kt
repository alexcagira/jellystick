package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsHomeScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_prefs)) },
			)
		}

		item {
			var modernHomeEnabled by rememberPreference(userPreferences, UserPreferences.modernHomeEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_home_enabled)) },
				captionContent = { Text(stringResource(R.string.modern_home_enabled_description)) },
				trailingContent = { Checkbox(checked = modernHomeEnabled) },
				onClick = { modernHomeEnabled = !modernHomeEnabled },
				modifier = Modifier.focusKey("modern_home_enabled")
			)
		}

		item {
			var modernDetailsEnabled by rememberPreference(userPreferences, UserPreferences.modernDetailsEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_details_enabled)) },
				captionContent = { Text(stringResource(R.string.modern_details_enabled_description)) },
				trailingContent = { Checkbox(checked = modernDetailsEnabled) },
				onClick = { modernDetailsEnabled = !modernDetailsEnabled },
				modifier = Modifier.focusKey("modern_details_enabled")
			)
		}

		item {
			var modernPlayerEnabled by rememberPreference(userPreferences, UserPreferences.modernPlayerEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_player_enabled)) },
				captionContent = { Text(stringResource(R.string.modern_player_enabled_description)) },
				trailingContent = { Checkbox(checked = modernPlayerEnabled) },
				onClick = { modernPlayerEnabled = !modernPlayerEnabled },
				modifier = Modifier.focusKey("modern_player_enabled")
			)
		}

		item {
			var dolbyVisionAsHdr10 by rememberPreference(userPreferences, UserPreferences.dolbyVisionAsHdr10)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_player_dv_as_hdr10)) },
				captionContent = { Text(stringResource(R.string.modern_player_dv_as_hdr10_description)) },
				trailingContent = { Checkbox(checked = dolbyVisionAsHdr10) },
				onClick = { dolbyVisionAsHdr10 = !dolbyVisionAsHdr10 },
				modifier = Modifier.focusKey("dolby_vision_as_hdr10")
			)
		}

		item {
			var audioOffloadEnabled by rememberPreference(userPreferences, UserPreferences.audioOffloadEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_player_audio_offload)) },
				captionContent = { Text(stringResource(R.string.modern_player_audio_offload_description)) },
				trailingContent = { Checkbox(checked = audioOffloadEnabled) },
				onClick = { audioOffloadEnabled = !audioOffloadEnabled },
				modifier = Modifier.focusKey("audio_offload_enabled")
			)
		}

		item {
			var themeMusicEnabled by rememberPreference(userPreferences, UserPreferences.themeMusicEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_details_theme_music)) },
				captionContent = { Text(stringResource(R.string.modern_details_theme_music_description)) },
				trailingContent = { Checkbox(checked = themeMusicEnabled) },
				onClick = { themeMusicEnabled = !themeMusicEnabled },
				modifier = Modifier.focusKey("theme_music_enabled")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.modern_home_sections)) },
				captionContent = { Text(stringResource(R.string.modern_home_sections_description)) },
				onClick = { router.push(Routes.HOME_SECTIONS_ORDER) },
				modifier = Modifier.focusKey(Routes.HOME_SECTIONS_ORDER)
			)
		}

		item {
			var modernMusicEnabled by rememberPreference(userPreferences, UserPreferences.modernMusicEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.modern_music_enabled)) },
				captionContent = { Text(stringResource(R.string.modern_music_enabled_description)) },
				trailingContent = { Checkbox(checked = modernMusicEnabled) },
				onClick = { modernMusicEnabled = !modernMusicEnabled },
				modifier = Modifier.focusKey("modern_music_enabled")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.modern_music_sections)) },
				captionContent = { Text(stringResource(R.string.modern_music_sections_description)) },
				onClick = { router.push(Routes.MUSIC_SECTIONS_ORDER) },
				modifier = Modifier.focusKey(Routes.MUSIC_SECTIONS_ORDER)
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.modern_home_libraries)) },
				captionContent = { Text(stringResource(R.string.modern_home_libraries_description)) },
				onClick = { router.push(Routes.HOME_LIBRARIES) },
				modifier = Modifier.focusKey(Routes.HOME_LIBRARIES)
			)
		}

		itemsIndexed(userSettingPreferences.homesections) { index, section ->
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_i, index + 1)) },
				captionContent = { Text(stringResource(userSettingPreferences[section].nameRes)) },
				onClick = { router.push(Routes.HOME_SECTION, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("home_section_$index")
			)
		}

		item {
			ListSection(
				headingContent = { Text(stringResource(R.string.lbl_next_up)) },
			)
		}

		item {
			val homeNextUpMaxDays by rememberPreference(userPreferences, UserPreferences.homeNextUpMaxDays)
			val options = getNextUpCutoffOptions()
			val selectedCaption = options.firstOrNull { it.first == homeNextUpMaxDays }?.second
				?: stringResource(R.string.home_next_up_max_days_disabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.home_next_up_max_days)) },
				captionContent = { Text(selectedCaption) },
				onClick = { router.push(Routes.HOME_NEXT_UP_CUTOFF) },
				modifier = Modifier.focusKey(Routes.HOME_NEXT_UP_CUTOFF)
			)
		}
	}
}
