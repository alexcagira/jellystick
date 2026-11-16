package org.jellyfin.androidtv.ui.settings.screen.language

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.AppLanguage

/**
 * Language of the app's texts: the one of the TV, or any translation of the app. English and
 * Spanish first (Jellystick's own screens are only in those), then the others by name.
 */
@Composable
fun SettingsLanguageScreen() {
	val context = LocalContext.current
	val activity = LocalActivity.current
	val selected = remember { AppLanguage.selected(context) }
	val options = remember {
		val first = listOf("en", "es")
		AppLanguage.options.sortedWith(
			compareBy<AppLanguage.Option> { first.indexOf(it.tag).takeIf { index -> index >= 0 } ?: first.size }
				.thenBy { AppLanguage.displayName(it.tag).lowercase() }
		)
	}

	fun choose(tag: String) {
		if (tag == selected) return
		AppLanguage.select(context, tag)
		// The screens load their texts again in the new language
		activity?.recreate()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.app_language)) },
				captionContent = { Text(stringResource(R.string.app_language_description)) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.app_language_system)) },
				captionContent = { Text(AppLanguage.displayName(AppLanguage.systemLocale.toLanguageTag())) },
				trailingContent = { RadioButton(checked = selected.isEmpty()) },
				onClick = { choose("") },
				modifier = Modifier.focusKey("app_language_system", initialFocus = selected.isEmpty()),
			)
		}

		items(options) { option ->
			ListButton(
				headingContent = { Text(AppLanguage.displayName(option.tag)) },
				captionContent = {
					Text(
						when {
							option.tag in AppLanguage.complete -> stringResource(R.string.app_language_complete)
							else -> stringResource(R.string.app_language_partial, option.translated)
						}
					)
				},
				trailingContent = { RadioButton(checked = selected == option.tag) },
				onClick = { choose(option.tag) },
				modifier = Modifier.focusKey("app_language_${option.tag}", initialFocus = selected == option.tag),
			)
		}
	}
}
