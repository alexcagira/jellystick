package org.jellyfin.androidtv.ui.playback.modern

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.ModernDialog
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import java.util.Locale

private const val TICKS_PER_MS = 10_000L

/**
 * The menus of the bottom right buttons. Only one is open at a time.
 */
@Composable
internal fun PlayerMenus(
	overlay: ModernPlayerOverlay,
	menu: PlayerMenu?,
	onDismissRequest: () -> Unit,
) {
	val noneLabel = stringResource(R.string.modern_player_subtitles_off)
	// Options are read when the menu opens (the tracks can change with the item)
	val options = remember(menu, overlay.state.item) {
		when (menu) {
			PlayerMenu.AUDIO -> overlay.audioOptions()
			PlayerMenu.SUBTITLES -> overlay.subtitleOptions(noneLabel)
			PlayerMenu.QUALITY -> overlay.qualityOptions()
			PlayerMenu.SPEED -> overlay.speedOptions()
			else -> emptyList()
		}
	}

	val title = when (menu) {
		PlayerMenu.AUDIO -> stringResource(R.string.modern_player_audio)
		PlayerMenu.SUBTITLES -> stringResource(R.string.modern_player_subtitles)
		PlayerMenu.QUALITY -> stringResource(R.string.modern_player_quality)
		PlayerMenu.SPEED -> stringResource(R.string.modern_player_speed)
		PlayerMenu.CHAPTERS -> stringResource(R.string.modern_player_chapters)
		PlayerMenu.SUBTITLE_OFFSET -> stringResource(R.string.modern_player_subtitle_offset)
		null -> ""
	}

	OptionsDialog(
		visible = menu in setOf(PlayerMenu.AUDIO, PlayerMenu.SUBTITLES, PlayerMenu.QUALITY, PlayerMenu.SPEED),
		title = title,
		options = options,
		onDismissRequest = onDismissRequest,
	)

	ChaptersDialog(
		visible = menu == PlayerMenu.CHAPTERS,
		title = title,
		overlay = overlay,
		onDismissRequest = onDismissRequest,
	)

	SubtitleOffsetDialog(
		visible = menu == PlayerMenu.SUBTITLE_OFFSET,
		title = title,
		overlay = overlay,
		onDismissRequest = onDismissRequest,
	)
}

/**
 * List of options with the current one checked and focused.
 */
@Composable
private fun OptionsDialog(
	visible: Boolean,
	title: String,
	options: List<PlayerOption>,
	onDismissRequest: () -> Unit,
) {
	val selectedFocusRequester = remember { FocusRequester() }

	LaunchedEffect(visible, options) {
		if (!visible) return@LaunchedEffect
		awaitFrames(2)
		selectedFocusRequester.tryRequestFocus()
	}

	ModernDialog(
		visible = visible,
		title = title,
		onDismissRequest = onDismissRequest,
	) {
		Column(
			modifier = Modifier
				.heightIn(max = 340.dp)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
			options.forEachIndexed { index, option ->
				OptionRow(
					option = option,
					onClick = {
						option.onSelect()
						onDismissRequest()
					},
					modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocusRequester) else Modifier,
				)
			}
		}
	}
}

@Composable
private fun OptionRow(
	option: PlayerOption,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val contentColor = if (focused) Color.Black else ModernHomeColors.TextPrimary

	Row(
		modifier = modifier
			.fillMaxWidth()
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
			.padding(horizontal = 16.dp, vertical = 11.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
			if (option.selected) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_check),
					contentDescription = null,
					tint = if (focused) Color.Black else ModernHomeColors.Accent,
					modifier = Modifier.size(18.dp),
				)
			}
		}
		Spacer(Modifier.width(12.dp))
		Text(
			text = option.label,
			color = contentColor,
			fontSize = 15.sp,
			fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Medium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * Scenes of the video with their image, OK jumps to the scene.
 */
@Composable
private fun ChaptersDialog(
	visible: Boolean,
	title: String,
	overlay: ModernPlayerOverlay,
	onDismissRequest: () -> Unit,
) {
	val api = koinInject<ApiClient>()
	val imageLoader = koinInject<ImageLoader>()
	val item = overlay.state.item
	val chapters = item?.chapters.orEmpty()
	val positionMs = overlay.state.positionMs
	val currentIndex = chapters.indexOfLast { it.startPositionTicks / TICKS_PER_MS <= positionMs }.coerceAtLeast(0)
	val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
	val currentFocusRequester = remember { FocusRequester() }

	LaunchedEffect(visible) {
		if (!visible) return@LaunchedEffect
		listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
		awaitFrames(2)
		currentFocusRequester.tryRequestFocus()
	}

	ModernDialog(
		visible = visible,
		title = title,
		onDismissRequest = onDismissRequest,
		maxWidth = 860.dp,
	) {
		LazyRow(
			state = listState,
			contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			itemsIndexed(chapters) { index, chapter ->
				val interactionSource = remember { MutableInteractionSource() }
				val focused by interactionSource.collectIsFocusedAsState()
				val imageUrl = remember(item?.id, index, chapter.imageTag) {
					val itemId = item?.id
					if (itemId == null || chapter.imageTag == null) null
					else api.imageApi.getItemImageUrl(
						itemId = itemId,
						imageType = ImageType.CHAPTER,
						imageIndex = index,
						tag = chapter.imageTag,
						maxWidth = 360,
					)
				}

				Column(
					modifier = Modifier
						.width(176.dp)
						.then(if (index == currentIndex) Modifier.focusRequester(currentFocusRequester) else Modifier)
						.clickable(interactionSource = interactionSource, indication = null) {
							overlay.seekTo(chapter.startPositionTicks / TICKS_PER_MS)
							onDismissRequest()
						},
				) {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.height(99.dp)
							.clip(RoundedCornerShape(8.dp))
							.background(ModernHomeColors.CardSurface)
							.border(
								width = if (focused) 2.5.dp else 0.dp,
								color = if (focused) Color.White else Color.Transparent,
								shape = RoundedCornerShape(8.dp),
							),
					) {
						if (imageUrl != null) {
							AsyncImage(
								model = imageUrl,
								contentDescription = null,
								imageLoader = imageLoader,
								contentScale = ContentScale.Crop,
								modifier = Modifier.fillMaxSize(),
							)
						}
						Text(
							text = formatTime(chapter.startPositionTicks / TICKS_PER_MS),
							color = Color.White,
							fontSize = 11.sp,
							fontWeight = FontWeight.Bold,
							modifier = Modifier
								.align(Alignment.BottomEnd)
								.padding(6.dp)
								.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
								.padding(horizontal = 5.dp, vertical = 1.dp),
						)
					}
					Spacer(Modifier.height(6.dp))
					Text(
						text = chapter.name?.takeIf { it.isNotBlank() } ?: "${index + 1}",
						color = if (index == currentIndex) ModernHomeColors.Accent else ModernHomeColors.TextPrimary,
						fontSize = 12.sp,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

/**
 * Move the subtitles earlier or later when they are out of sync.
 */
@Composable
private fun SubtitleOffsetDialog(
	visible: Boolean,
	title: String,
	overlay: ModernPlayerOverlay,
	onDismissRequest: () -> Unit,
) {
	val offsetMs = overlay.state.subtitleOffsetMs
	val resetFocusRequester = remember { FocusRequester() }

	LaunchedEffect(visible) {
		if (!visible) return@LaunchedEffect
		awaitFrames(2)
		resetFocusRequester.tryRequestFocus()
	}

	ModernDialog(
		visible = visible,
		title = title,
		onDismissRequest = onDismissRequest,
		maxWidth = 640.dp,
	) {
		Text(
			text = String.format(Locale.getDefault(), "%+.2f s", offsetMs / 1000f),
			color = if (offsetMs == 0L) ModernHomeColors.TextPrimary else ModernHomeColors.Accent,
			fontSize = 34.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.align(Alignment.CenterHorizontally),
		)
		Spacer(Modifier.height(4.dp))
		Text(
			text = stringResource(R.string.modern_player_subtitle_offset_hint),
			color = ModernHomeColors.TextSecondary,
			fontSize = 13.sp,
			modifier = Modifier.align(Alignment.CenterHorizontally),
		)
		Spacer(Modifier.height(20.dp))
		Row(
			modifier = Modifier.align(Alignment.CenterHorizontally),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			HeroButton(text = "−0.5 s", icon = null, primary = false, onClick = { overlay.changeSubtitleOffset(-500) })
			HeroButton(text = "−0.1 s", icon = null, primary = false, onClick = { overlay.changeSubtitleOffset(-100) })
			HeroButton(
				text = stringResource(R.string.modern_player_reset),
				icon = null,
				primary = true,
				onClick = { overlay.setSubtitleOffset(0) },
				modifier = Modifier.focusRequester(resetFocusRequester),
			)
			HeroButton(text = "+0.1 s", icon = null, primary = false, onClick = { overlay.changeSubtitleOffset(100) })
			HeroButton(text = "+0.5 s", icon = null, primary = false, onClick = { overlay.changeSubtitleOffset(500) })
		}
	}
}
