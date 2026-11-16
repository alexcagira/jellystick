package org.jellyfin.androidtv.ui.collections

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.EditableField
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.LanguageBadge
import org.jellyfin.androidtv.ui.home.modern.LanguageFlag
import org.jellyfin.androidtv.ui.home.modern.MediaBadges
import org.jellyfin.androidtv.ui.home.modern.ModernDialog
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

private sealed interface Step {
	data class Scanning(val done: Int, val total: Int) : Step
	data class Languages(val scan: LanguageScan, val rules: Map<String, LanguageCollectionRule>) : Step
	data class Configure(val scan: LanguageScan, val rules: Map<String, LanguageCollectionRule>, val language: String) : Step
	data class Working(val update: Boolean) : Step
	data object Failed : Step
}

/**
 * "Filter" of the Films and TV Shows libraries: pick an audio language and make a collection with
 * every title that has it, with the generated flag poster or the poster of one of the titles.
 */
@Composable
fun LanguageCollectionsDialog(
	visible: Boolean,
	library: BaseItemDto,
	onDismissRequest: () -> Unit,
) {
	val repository = koinInject<LanguageCollectionsRepository>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var step by remember(visible) { mutableStateOf<Step>(Step.Scanning(0, 0)) }
	val kind = LanguageCollectionKind.of(library) ?: return

	val kindName = stringResource(if (kind == LanguageCollectionKind.MOVIES) R.string.language_collections_kind_movies else R.string.language_collections_kind_series)

	LaunchedEffect(visible) {
		if (!visible) return@LaunchedEffect
		step = try {
			val scan = repository.scan(library) { done, total -> step = Step.Scanning(done, total) }
			Step.Languages(scan, repository.rulesFor(library.id))
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to read the audio languages of ${library.name}")
			Step.Failed
		}
	}

	fun finish(
		configure: Step.Configure,
		name: String,
		poster: CollectionPoster,
	) {
		val rule = configure.rules[configure.language]
		step = Step.Working(update = rule != null)
		scope.launch {
			val message = try {
				if (rule != null) {
					val updated = repository.update(rule.copy(name = name), configure.scan, poster, kindName)
					if (updated) context.getString(R.string.language_collections_updated, rule.name)
					else repository.create(library, configure.scan, configure.language, name, poster, kindName)
						.let { context.getString(R.string.language_collections_created, name) }
				} else {
					repository.create(library, configure.scan, configure.language, name, poster, kindName)
					context.getString(R.string.language_collections_created, name)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: PosterUploadException) {
				context.getString(R.string.language_collections_poster_failed)
			} catch (err: Exception) {
				Timber.w(err, "Unable to make the language collection $name")
				context.getString(R.string.language_collections_failed)
			}
			Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			onDismissRequest()
		}
	}

	ModernDialog(
		visible = visible,
		title = stringResource(R.string.language_collections_title),
		// Back on the poster choice goes back to the languages
		onDismissRequest = {
			when (val current = step) {
				is Step.Configure -> step = Step.Languages(current.scan, current.rules)
				is Step.Working -> Unit
				else -> onDismissRequest()
			}
		},
		maxWidth = 860.dp,
	) {
		when (val current = step) {
			is Step.Scanning -> CollectionProgress(
				if (current.total == 0) stringResource(R.string.language_collections_reading)
				else stringResource(R.string.language_collections_scanning, current.done, current.total)
			)

			is Step.Working -> CollectionProgress(
				stringResource(if (current.update) R.string.language_collections_updating else R.string.language_collections_creating)
			)

			Step.Failed -> Text(
				text = stringResource(R.string.language_collections_failed),
				color = ModernHomeColors.TextSecondary,
				fontSize = 15.sp,
			)

			is Step.Languages -> LanguageList(
				step = current,
				kind = kind,
				onSelect = { language -> step = Step.Configure(current.scan, current.rules, language) },
			)

			is Step.Configure -> ConfigureCollection(
				step = current,
				kind = kind,
				kindName = kindName,
				onConfirm = { name, poster -> finish(current, name, poster) },
			)
		}
	}
}

@Composable
internal fun CollectionProgress(text: String) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.padding(vertical = 24.dp),
	) {
		CircularProgressIndicator(color = ModernHomeColors.Accent, modifier = Modifier.size(32.dp))
		Spacer(Modifier.width(16.dp))
		Text(text = text, color = ModernHomeColors.TextSecondary, fontSize = 15.sp)
	}
}

@Composable
private fun LanguageList(
	step: Step.Languages,
	kind: LanguageCollectionKind,
	onSelect: (String) -> Unit,
) {
	val languages = remember(step.scan) {
		step.scan.itemsByLanguage.entries.sortedByDescending { it.value.size }.map { it.key to it.value.size }
	}

	Text(
		text = stringResource(R.string.language_collections_hint),
		color = ModernHomeColors.TextSecondary,
		fontSize = 14.sp,
	)
	Spacer(Modifier.height(14.dp))

	if (languages.isEmpty()) {
		Text(text = stringResource(R.string.language_collections_none), color = ModernHomeColors.TextMuted, fontSize = 14.sp)
		return
	}

	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.heightIn(max = 430.dp),
	) {
		items(languages, key = { it.first }) { (language, count) ->
			LanguageRow(
				language = language,
				count = titleCount(kind, count),
				created = language in step.rules,
				onClick = { onSelect(language) },
			)
		}
	}
}

@Composable
private fun LanguageRow(
	language: String,
	count: String,
	created: Boolean,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(12.dp)
	val contentColor = if (focused) Color.Black else ModernHomeColors.TextPrimary

	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), shape)
			.padding(horizontal = 18.dp, vertical = 12.dp),
	) {
		LanguageFlag(LanguageBadge(language, MediaBadges.flagFor(language)), modifier = Modifier.graphicsLayer { scaleX = 1.6f; scaleY = 1.6f })
		Spacer(Modifier.width(20.dp))
		Text(
			text = MediaBadges.languageName(language),
			color = contentColor,
			fontSize = 16.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			modifier = Modifier.weight(1f),
		)
		if (created) {
			Text(
				text = stringResource(R.string.language_collections_created_badge),
				color = if (focused) Color.Black else ModernHomeColors.Accent,
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
			)
			Spacer(Modifier.width(14.dp))
		}
		Text(text = count, color = if (focused) Color.Black.copy(alpha = 0.7f) else ModernHomeColors.TextMuted, fontSize = 13.sp, maxLines = 1)
	}
}

@Composable
private fun ConfigureCollection(
	step: Step.Configure,
	kind: LanguageCollectionKind,
	kindName: String,
	onConfirm: (name: String, poster: CollectionPoster) -> Unit,
) {
	val api = koinInject<ApiClient>()
	val rule = step.rules[step.language]
	val languageName = MediaBadges.languageName(step.language)
	val defaultName = stringResource(
		if (kind == LanguageCollectionKind.MOVIES) R.string.language_collections_default_name_movies else R.string.language_collections_default_name_series,
		languageName,
	)
	var name by remember(step.language) { mutableStateOf(rule?.name ?: defaultName) }
	var poster by remember(step.language) { mutableStateOf(if (rule != null) CollectionPoster.Keep else CollectionPoster.Generated) }
	val confirmFocusRequester = remember { FocusRequester() }

	val generated = remember(step.language, kindName) {
		LanguagePoster.draw(MediaBadges.flagFor(step.language), languageName, kindName)
	}
	val itemPosters = remember(step.scan, step.language) {
		step.scan.itemsByLanguage[step.language].orEmpty()
			.mapNotNull { id -> step.scan.items[id] }
			.mapNotNull { item -> item.itemImages[ImageType.PRIMARY]?.let { image -> item.id to image.getUrl(api, maxWidth = 260) } }
			.take(MAX_POSTER_CHOICES)
	}

	LaunchedEffect(step.language) {
		awaitFrames(2)
		confirmFocusRequester.tryRequestFocus()
	}

	Row(verticalAlignment = Alignment.CenterVertically) {
		LanguageFlag(LanguageBadge(step.language, MediaBadges.flagFor(step.language)), modifier = Modifier.graphicsLayer { scaleX = 1.4f; scaleY = 1.4f })
		Spacer(Modifier.width(16.dp))
		Text(
			text = titleCount(kind, step.scan.itemsByLanguage[step.language].orEmpty().size),
			color = ModernHomeColors.TextSecondary,
			fontSize = 14.sp,
		)
	}
	Spacer(Modifier.height(14.dp))

	Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		if (rule == null) {
			EditableField(
				label = stringResource(R.string.language_collections_name),
				value = name,
				onValueChange = { name = it },
				onSubmit = {},
				modifier = Modifier.weight(1f),
			)
		} else {
			// Existing collection: renaming is done in the web app
			Text(
				text = rule.name,
				color = ModernHomeColors.TextPrimary,
				fontSize = 18.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
		}
		HeroButton(
			text = stringResource(if (rule != null) R.string.language_collections_update else R.string.language_collections_create),
			icon = R.drawable.ic_check,
			primary = true,
			onClick = { onConfirm(name.trim().ifEmpty { defaultName }, poster) },
			modifier = Modifier.focusRequester(confirmFocusRequester),
		)
	}

	Spacer(Modifier.height(18.dp))
	Text(
		text = stringResource(R.string.language_collections_poster),
		color = ModernHomeColors.TextMuted,
		fontSize = 11.sp,
		fontWeight = FontWeight.SemiBold,
	)
	Spacer(Modifier.height(6.dp))

	LazyRow(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
	) {
		if (rule != null) {
			item(key = "keep") {
				PosterChoice(
					label = stringResource(R.string.language_collections_poster_keep),
					selected = poster == CollectionPoster.Keep,
					onClick = { poster = CollectionPoster.Keep },
				) {
					AsyncImage(
						model = api.imageApi.getItemImageUrl(itemId = rule.collectionId, imageType = ImageType.PRIMARY, maxWidth = 260),
						contentDescription = null,
						imageLoader = koinInject<ImageLoader>(),
						contentScale = ContentScale.Crop,
						modifier = Modifier.fillMaxSize(),
					)
				}
			}
		}
		item(key = "generated") {
			PosterChoice(
				label = stringResource(R.string.language_collections_poster_generated),
				selected = poster == CollectionPoster.Generated,
				onClick = { poster = CollectionPoster.Generated },
			) {
				Image(bitmap = generated, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
			}
		}
		items(itemPosters, key = { it.first.toString() }) { (itemId, url) ->
			PosterChoice(
				label = step.scan.items[itemId]?.name.orEmpty(),
				selected = (poster as? CollectionPoster.FromItem)?.itemId == itemId,
				onClick = { poster = CollectionPoster.FromItem(itemId) },
			) {
				AsyncImage(
					model = url,
					contentDescription = null,
					imageLoader = koinInject<ImageLoader>(),
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}

@Composable
internal fun PosterChoice(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
	artwork: @Composable () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "PosterChoiceScale")
	val shape = RoundedCornerShape(ModernHomeDimens.CardCornerRadius)

	Column(
		modifier = Modifier
			.width(110.dp)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
	) {
		Box(
			modifier = Modifier
				.size(110.dp, 165.dp)
				.background(ModernHomeColors.CardSurface, shape)
				.border(
					width = if (focused || selected) ModernHomeDimens.FocusBorderWidth else 0.dp,
					color = when {
						focused -> ModernHomeColors.FocusRing
						selected -> ModernHomeColors.Accent
						else -> Color.Transparent
					},
					shape = shape,
				)
				.padding(ModernHomeDimens.FocusBorderWidth),
		) {
			Box(modifier = Modifier.fillMaxSize().background(Color.Transparent, shape)) { artwork() }
			if (selected) {
				Box(
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(6.dp)
						.size(24.dp)
						.background(ModernHomeColors.Accent, CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(ImageVector.vectorResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
				}
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(
			text = label,
			color = if (selected) ModernHomeColors.Accent else ModernHomeColors.TextSecondary,
			fontSize = 11.sp,
			fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun titleCount(kind: LanguageCollectionKind, count: Int): String {
	val resources = LocalContext.current.resources
	val plural = if (kind == LanguageCollectionKind.MOVIES) R.plurals.language_collections_count_movies else R.plurals.language_collections_count_series
	return resources.getQuantityString(plural, count, count)
}

private const val MAX_POSTER_CHOICES = 40
