package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.home.modern.TmdbTrendingClient
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

/**
 * The TMDB key of "Trending this week": typed here (the remote's keyboard, or the phone with the Fire
 * TV app, which can paste), checked with TMDB before it is kept. Without a key, or with it deleted,
 * the rows use the IMDb trend. Each person uses their own free key: it isn't inside the app.
 */
@Composable
fun SettingsTmdbKeyScreen() {
	val userPreferences = koinInject<UserPreferences>()
	val tmdb = koinInject<TmdbTrendingClient>()
	val scope = rememberCoroutineScope()

	var saved by remember { mutableStateOf(userPreferences[UserPreferences.tmdbApiKey]) }
	var text by remember { mutableStateOf(saved) }
	var status by remember { mutableStateOf<String?>(null) }
	var checking by remember { mutableStateOf(false) }

	val okMessage = stringResource(R.string.tmdb_key_ok)
	val failedMessage = stringResource(R.string.tmdb_key_failed)
	val deletedMessage = stringResource(R.string.tmdb_key_deleted)

	fun save() {
		val key = text.trim()
		if (checking) return
		if (key.isEmpty()) {
			userPreferences[UserPreferences.tmdbApiKey] = ""
			saved = ""
			status = deletedMessage
			return
		}
		checking = true
		status = null
		scope.launch {
			val count = tmdb.check(key)
			checking = false
			if (count != null && count > 0) {
				userPreferences[UserPreferences.tmdbApiKey] = key
				saved = key
				status = okMessage.format(count)
			} else {
				status = failedMessage
			}
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.tmdb_key)) },
				captionContent = { Text(stringResource(R.string.tmdb_key_help)) },
			)
		}

		item {
			KeyTextField(
				value = text,
				onValueChange = { text = it },
				onSubmit = ::save,
				modifier = Modifier
					.fillMaxWidth()
					.focusKey("tmdb_key_input", initialFocus = saved.isEmpty()),
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(if (text.isBlank() && saved.isNotEmpty()) R.string.tmdb_key_delete else R.string.tmdb_key_save)) },
				captionContent = {
					Text(
						when {
							checking -> stringResource(R.string.tmdb_key_checking)
							status != null -> status.orEmpty()
							saved.isNotEmpty() -> stringResource(R.string.tmdb_key_in_use)
							BuildConfig.TMDB_API_KEY.isNotBlank() -> stringResource(R.string.tmdb_key_build)
							else -> stringResource(R.string.tmdb_key_none)
						}
					)
				},
				onClick = ::save,
				modifier = Modifier.focusKey("tmdb_key_save", initialFocus = saved.isNotEmpty()),
			)
		}

		item {
			ListSection(
				headingContent = { Text(stringResource(R.string.tmdb_attribution)) },
			)
		}
	}
}

@Composable
private fun KeyTextField(
	value: String,
	onValueChange: (String) -> Unit,
	onSubmit: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val color = when {
		focused -> JellyfinTheme.colorScheme.inputFocused to JellyfinTheme.colorScheme.onInputFocused
		else -> JellyfinTheme.colorScheme.input to JellyfinTheme.colorScheme.onInput
	}

	ProvideTextStyle(LocalTextStyle.current.copy(color = color.second, fontSize = 16.sp)) {
		BasicTextField(
			value = value,
			onValueChange = { onValueChange(it.replace("\n", "").trim()) },
			singleLine = true,
			interactionSource = interactionSource,
			keyboardActions = KeyboardActions { onSubmit() },
			keyboardOptions = KeyboardOptions.Default.copy(
				keyboardType = KeyboardType.Ascii,
				imeAction = ImeAction.Done,
				autoCorrectEnabled = false,
				showKeyboardOnFocus = true,
			),
			textStyle = LocalTextStyle.current,
			cursorBrush = SolidColor(color.first),
			modifier = modifier,
			decorationBox = { innerTextField ->
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.border(2.dp, color.first, RoundedCornerShape(12.dp))
						.padding(horizontal = 16.dp, vertical = 14.dp),
				) {
					if (value.isEmpty()) Text(stringResource(R.string.tmdb_key_hint), color = color.second.copy(alpha = 0.6f))
					innerTextField()
				}
			},
		)
	}
}
