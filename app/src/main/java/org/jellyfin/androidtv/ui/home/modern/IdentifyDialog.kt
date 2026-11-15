package org.jellyfin.androidtv.ui.home.modern

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.RemoteSearchResult
import org.koin.compose.koinInject
import timber.log.Timber

private val fieldShape = RoundedCornerShape(12.dp)

/**
 * "Identify" like in the web app: search the metadata providers for the right movie or series
 * (by name and year, or by its IMDb/TMDb id) and apply the chosen match.
 */
@Composable
internal fun IdentifyDialog(
	visible: Boolean,
	item: BaseItemDto?,
	onDismissRequest: () -> Unit,
	onIdentified: (name: String) -> Unit,
) {
	val actions = koinInject<ItemActions>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var name by remember(item?.id, visible) { mutableStateOf(item?.name.orEmpty()) }
	var year by remember(item?.id, visible) { mutableStateOf(item?.productionYear?.toString().orEmpty()) }
	var externalId by remember(item?.id, visible) { mutableStateOf("") }
	var results by remember(item?.id, visible) { mutableStateOf<List<RemoteSearchResult>?>(null) }
	var searching by remember(item?.id, visible) { mutableStateOf(false) }
	var applying by remember(item?.id, visible) { mutableStateOf(false) }
	val firstResultFocusRequester = remember { FocusRequester() }

	fun search(focusResults: Boolean) {
		val target = item ?: return
		searching = true
		scope.launch {
			results = try {
				actions.searchMatches(target, name.trim(), year.trim().toIntOrNull(), externalId)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Identify search failed")
				emptyList()
			}
			searching = false
			if (focusResults && results.orEmpty().isNotEmpty()) {
				awaitFrames(2)
				firstResultFocusRequester.tryRequestFocus()
			}
		}
	}

	fun apply(match: RemoteSearchResult) {
		val target = item ?: return
		if (applying) return
		applying = true
		scope.launch {
			try {
				actions.applyMatch(target.id, match)
				onIdentified(match.name ?: target.name.orEmpty())
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Identify failed")
				Toast.makeText(context, R.string.modern_identify_failed, Toast.LENGTH_LONG).show()
			}
			applying = false
		}
	}

	// Search right away with the current name and year: usually the right match is among the first
	LaunchedEffect(visible, item?.id) {
		if (visible && item != null) search(focusResults = true)
	}

	ModernDialog(
		visible = visible,
		title = stringResource(R.string.modern_identify_title),
		onDismissRequest = onDismissRequest,
		maxWidth = 860.dp,
	) {
		Text(
			text = item?.name.orEmpty(),
			color = ModernHomeColors.TextSecondary,
			fontSize = 14.sp,
			maxLines = 1,
		)
		Spacer(Modifier.height(16.dp))

		Row(
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			EditableField(
				label = stringResource(R.string.modern_identify_name),
				value = name,
				onValueChange = { name = it },
				onSubmit = { search(focusResults = true) },
				modifier = Modifier.weight(1f),
			)
			EditableField(
				label = stringResource(R.string.modern_identify_year),
				value = year,
				onValueChange = { value -> year = value.filter { it.isDigit() }.take(4) },
				onSubmit = { search(focusResults = true) },
				keyboardType = KeyboardType.Number,
				modifier = Modifier.width(110.dp),
			)
			EditableField(
				label = stringResource(R.string.modern_identify_id),
				value = externalId,
				onValueChange = { externalId = it.trim() },
				onSubmit = { search(focusResults = true) },
				modifier = Modifier.width(170.dp),
			)
			HeroButton(
				text = stringResource(R.string.modern_identify_search),
				icon = R.drawable.ic_search,
				primary = true,
				onClick = { search(focusResults = true) },
			)
		}

		Spacer(Modifier.height(18.dp))

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(270.dp),
			contentAlignment = Alignment.Center,
		) {
			val list = results
			when {
				searching || applying -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
					CircularProgressIndicator(color = ModernHomeColors.Accent, modifier = Modifier.size(40.dp))
					Spacer(Modifier.height(10.dp))
					Text(
						text = stringResource(if (applying) R.string.modern_identify_applying else R.string.modern_identify_searching),
						color = ModernHomeColors.TextSecondary,
						fontSize = 14.sp,
					)
				}

				list.isNullOrEmpty() -> Text(
					text = stringResource(R.string.modern_identify_no_results),
					color = ModernHomeColors.TextSecondary,
					fontSize = 14.sp,
				)

				else -> LazyRow(
					horizontalArrangement = Arrangement.spacedBy(14.dp),
					contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					itemsIndexed(list) { index, match ->
						MatchCard(
							match = match,
							onClick = { apply(match) },
							modifier = if (index == 0) Modifier.focusRequester(firstResultFocusRequester) else Modifier,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun MatchCard(
	match: RemoteSearchResult,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val imageLoader = koinInject<ImageLoader>()
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "MatchScale")
	val shape = RoundedCornerShape(ModernHomeDimens.CardCornerRadius)

	Column(
		modifier = modifier
			.width(130.dp)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
	) {
		Box(
			modifier = Modifier
				.size(130.dp, 195.dp)
				.background(ModernHomeColors.CardSurface, shape)
				.then(if (focused) Modifier.border(ModernHomeDimens.FocusBorderWidth, ModernHomeColors.FocusRing, shape) else Modifier)
				.padding(if (focused) ModernHomeDimens.FocusBorderWidth else 0.dp),
		) {
			CardArtwork(url = null, fallbackTitle = match.name.orEmpty())
			if (match.imageUrl != null) {
				AsyncImage(
					model = match.imageUrl,
					contentDescription = null,
					imageLoader = imageLoader,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.fillMaxSize()
						.background(Color.Transparent, shape),
				)
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(
			text = match.name.orEmpty(),
			color = ModernHomeColors.TextPrimary,
			fontSize = 12.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = listOfNotNull(match.productionYear?.toString(), match.searchProviderName).joinToString(" · "),
			color = ModernHomeColors.TextMuted,
			fontSize = 11.sp,
			maxLines = 1,
		)
	}
}

/**
 * Field that only becomes a text box (and opens the keyboard) when clicked: with a remote the
 * keyboard would otherwise pop up every time the focus passes over the field.
 */
@Composable
internal fun EditableField(
	label: String,
	value: String,
	onValueChange: (String) -> Unit,
	onSubmit: () -> Unit,
	modifier: Modifier = Modifier,
	keyboardType: KeyboardType = KeyboardType.Text,
) {
	val keyboard = LocalSoftwareKeyboardController.current
	var editing by remember { mutableStateOf(false) }
	// The text box reports "not focused" as soon as it appears: only leaving it after it had the
	// focus ends the editing
	var textHadFocus by remember { mutableStateOf(false) }
	// Back on the button after typing (not when the focus left the text box on its own)
	var refocusButton by remember { mutableStateOf(false) }
	val buttonFocusRequester = remember { FocusRequester() }
	val textFocusRequester = remember { FocusRequester() }
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	fun finish(refocus: Boolean) {
		keyboard?.hide()
		refocusButton = refocus
		textHadFocus = false
		editing = false
	}

	Column(modifier = modifier) {
		Text(
			text = label,
			color = ModernHomeColors.TextMuted,
			fontSize = 11.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
		)
		Spacer(Modifier.height(4.dp))

		if (editing) {
			BasicTextField(
				value = value,
				onValueChange = onValueChange,
				singleLine = true,
				textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
				cursorBrush = SolidColor(Color.White),
				keyboardOptions = KeyboardOptions.Default.copy(
					keyboardType = keyboardType,
					imeAction = ImeAction.Search,
					showKeyboardOnFocus = true,
				),
				keyboardActions = KeyboardActions {
					finish(refocus = true)
					onSubmit()
				},
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(textFocusRequester)
					.onFocusChanged { state ->
						if (state.isFocused) textHadFocus = true
						else if (textHadFocus && editing) finish(refocus = false)
					}
					// Back with the keyboard closed: stop typing, without closing the whole dialog
					.onPreviewKeyEvent { event ->
						if (event.key != Key.Back) return@onPreviewKeyEvent false
						if (event.type == KeyEventType.KeyUp) finish(refocus = true)
						true
					}
					.border(2.dp, ModernHomeColors.Accent, fieldShape)
					.background(Color.White.copy(alpha = 0.08f), fieldShape)
					.padding(horizontal = 14.dp, vertical = 11.dp),
			)
			LaunchedEffect(Unit) {
				awaitFrames(1)
				textFocusRequester.tryRequestFocus()
				// The Fire TV keyboard does not always open by itself
				keyboard?.show()
			}
		} else {
			Text(
				text = value.ifEmpty { "—" },
				color = if (focused) Color.Black else ModernHomeColors.TextPrimary,
				fontSize = 15.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(buttonFocusRequester)
					.clickable(interactionSource = interactionSource, indication = null, onClick = { editing = true })
					.background(if (focused) Color.White else Color.White.copy(alpha = 0.08f), fieldShape)
					.border(1.dp, ModernHomeColors.GlassBorder, fieldShape)
					.padding(horizontal = 14.dp, vertical = 11.dp),
			)
		}
	}

	// Back on the button after typing, so the remote keeps working where it was
	LaunchedEffect(editing, refocusButton) {
		if (!editing && refocusButton) {
			refocusButton = false
			awaitFrames(1)
			buttonFocusRequester.tryRequestFocus()
		}
	}
}
