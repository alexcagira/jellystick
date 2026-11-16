package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.update.AppUpdater
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsMainScreen() {
	val router = LocalRouter.current

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.app_name).uppercase()) },
				headingContent = { Text(stringResource(R.string.settings)) },
				captionContent = { Text(stringResource(R.string.settings_description)) },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_users), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_login)) },
				onClick = { router.push(Routes.AUTHENTICATION) },
				modifier = Modifier.focusKey(Routes.AUTHENTICATION),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_adjust), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_customization)) },
				onClick = { router.push(Routes.CUSTOMIZATION) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.modern_home_libraries)) },
				onClick = { router.push(Routes.HOME_LIBRARIES) },
				modifier = Modifier.focusKey(Routes.HOME_LIBRARIES),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_house), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.modern_home_sections)) },
				onClick = { router.push(Routes.HOME_SECTIONS_ORDER) },
				modifier = Modifier.focusKey(Routes.HOME_SECTIONS_ORDER),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_music_album), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.modern_music_sections)) },
				onClick = { router.push(Routes.MUSIC_SECTIONS_ORDER) },
				modifier = Modifier.focusKey(Routes.MUSIC_SECTIONS_ORDER),
			)
		}

		// TODO: Temporarily added to root - should be accessed via customization screen instead
		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photos), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_screensaver)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_next), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback)) },
				onClick = { router.push(Routes.PLAYBACK) },
				modifier = Modifier.focusKey(Routes.PLAYBACK),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_error), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_telemetry_category)) },
				onClick = { router.push(Routes.TELEMETRY) },
				modifier = Modifier.focusKey(Routes.TELEMETRY),
			)

		}

		// Update from the latest GitHub release
		item {
			AppUpdateButton()
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_jellyfin), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
				onClick = { router.push(Routes.ABOUT) },
				modifier = Modifier.focusKey(Routes.ABOUT),
			)
		}
	}
}

@Composable
private fun AppUpdateButton() {
	val updater = koinInject<AppUpdater>()
	val state by updater.state.collectAsState()
	val version = updater.currentVersion

	val caption = when (val current = state) {
		AppUpdater.State.Idle -> stringResource(R.string.app_update_idle, version)
		AppUpdater.State.Checking -> stringResource(R.string.app_update_checking)
		is AppUpdater.State.UpToDate -> stringResource(R.string.app_update_up_to_date, current.version)
		is AppUpdater.State.Available -> stringResource(R.string.app_update_available, current.release.version, megabytes(current.release.size))
		is AppUpdater.State.Downloading -> current.progress
			?.let { stringResource(R.string.app_update_downloading_progress, current.release.version, (it * 100).roundToInt()) }
			?: stringResource(R.string.app_update_downloading, current.release.version)
		is AppUpdater.State.Ready -> stringResource(R.string.app_update_ready, current.release.version)
		is AppUpdater.State.NeedsPermission -> stringResource(R.string.app_update_permission)
		is AppUpdater.State.Failed -> stringResource(
			when (current.reason) {
				AppUpdater.Reason.CHECK -> R.string.app_update_failed_check
				AppUpdater.Reason.NO_APK -> R.string.app_update_failed_no_apk
				AppUpdater.Reason.DOWNLOAD -> R.string.app_update_failed_download
				AppUpdater.Reason.INSTALL -> R.string.app_update_failed_install
			}
		)
	}

	ListButton(
		leadingContent = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
		headingContent = { Text(stringResource(R.string.app_update_title)) },
		captionContent = {
			// The debug build has its own app id: the release installs next to it
			Text(if (BuildConfig.DEBUG) caption + " " + stringResource(R.string.app_update_debug_note) else caption)
		},
		onClick = updater::onClick,
		modifier = Modifier.focusKey(UPDATE_FOCUS_KEY),
	)
}

private fun megabytes(bytes: Long): String =
	if (bytes <= 0) "?" else String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)

private const val UPDATE_FOCUS_KEY = "app_update"
