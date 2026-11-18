package org.jellyfin.androidtv.ui.playback.modern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.BebasNeue
import org.jellyfin.androidtv.ui.home.modern.ImdbRatings
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.koin.compose.koinInject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val CONTROLS_TIMEOUT_MS = 5_000L
/** Seeking without OK for this long: the position is forgotten and the playback stays where it was */
private const val SCRUB_CANCEL_MS = 8_000L
private const val TICKS_PER_MS = 10_000L

/** Element focused when the controls appear */
private enum class ControlsFocus { DEFAULT, SEEK_BAR }

/** Menus opened from the buttons on the bottom right */
internal enum class PlayerMenu { AUDIO, SUBTITLES, CHAPTERS, QUALITY, SPEED, SUBTITLE_OFFSET, PLAYBACK_MODE }

/**
 * Streaming style player controls:
 * - Hidden while playing. OK pauses, left/right seek, any other direction shows the controls.
 * - Controls: title and logo at the top, rewind/play/fast forward in the middle, seek bar with the
 *   chapters marked, previous/next and "Ends at" on the bottom left, menus on the bottom right.
 * - Paused without controls: "You're watching" with the episode information.
 * - "Skip intro" / "Skip credits" button during media segments.
 */
@Composable
fun ModernPlayerScreen(
	overlay: ModernPlayerOverlay,
) {
	val state = overlay.state
	val rootFocusRequester = remember { FocusRequester() }
	val seekBarFocusRequester = remember { FocusRequester() }

	var controlsVisible by remember { mutableStateOf(false) }
	var controlsFocus by remember { mutableStateOf(ControlsFocus.DEFAULT) }
	// Changes on every key press, restarts the auto hide timer
	var lastInteraction by remember { mutableLongStateOf(0L) }
	var menu by remember { mutableStateOf<PlayerMenu?>(null) }
	// Stream info panel (☰)
	var infoVisible by remember { mutableStateOf(false) }
	// Position being sought: only applied with OK
	var scrubMs by remember { mutableStateOf<Long?>(null) }
	val scrubbing = scrubMs != null
	// Top of the seek bar on the screen: the subtitles go above it while the controls show
	var seekBarTop by remember { mutableStateOf<Float?>(null) }

	val skipTarget = state.skipTargetMs
	val skipVisible = skipTarget != null &&
		state.positionMs <= skipTarget - MediaSegmentRepository.SkipMinDuration.inWholeMilliseconds

	fun showControls(focus: ControlsFocus = ControlsFocus.DEFAULT) {
		controlsFocus = focus
		controlsVisible = true
		lastInteraction = System.currentTimeMillis()
	}

	fun scrub(event: KeyEvent, forward: Boolean) {
		// Faster when the button is held
		val repeat = event.nativeKeyEvent.repeatCount
		val stepMs = when {
			repeat > 20 -> 120_000L
			repeat > 8 -> 60_000L
			repeat > 2 -> 30_000L
			else -> 10_000L
		}
		val start = scrubMs ?: state.positionMs
		scrubMs = (start + if (forward) stepMs else -stepMs).coerceIn(0, state.durationMs.coerceAtLeast(0))
	}

	// Auto hide, paused while a menu is open. Seeking without pressing OK: the position is forgotten
	// (the playback never left where it was) and the controls hide
	LaunchedEffect(controlsVisible, lastInteraction, menu, scrubbing) {
		if (!controlsVisible || menu != null) return@LaunchedEffect
		delay(if (scrubbing) SCRUB_CANCEL_MS else CONTROLS_TIMEOUT_MS)
		scrubMs = null
		controlsVisible = false
	}

	// Subtitles above the controls while they show, back down when they hide
	LaunchedEffect(controlsVisible, seekBarTop) {
		overlay.setControlsTop(if (controlsVisible) seekBarTop else null)
	}
	DisposableEffect(Unit) {
		// ☰ is for the stream info while the player shows (not the music stop)
		ModernPlayerOverlay.isShowing = true
		onDispose {
			ModernPlayerOverlay.isShowing = false
			overlay.setControlsTop(null)
		}
	}

	// Focus: the controls when they appear, the screen itself (key handling) when they hide
	LaunchedEffect(controlsVisible, controlsFocus) {
		awaitFrames(1)
		when {
			!controlsVisible -> rootFocusRequester.tryRequestFocus()
			// OK on the seek bar plays/pauses, left/right seek
			else -> seekBarFocusRequester.tryRequestFocus()
		}
	}

	// Smooth position while the controls are visible
	LaunchedEffect(controlsVisible) {
		while (controlsVisible) {
			overlay.refreshPosition()
			delay(500)
		}
	}

	// The skip button goes away by itself after a few seconds
	LaunchedEffect(skipTarget) {
		if (skipTarget == null) return@LaunchedEffect
		delay(MediaSegmentRepository.AskToSkipAutoHideDuration)
		overlay.clearSkip()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.focusRequester(rootFocusRequester)
			.onPreviewKeyEvent { event ->
				val isDown = event.type == KeyEventType.KeyDown
				val isBack = event.key == Key.Back || event.key == Key.Escape

				// ☰ opens or closes the stream info (when released); the controls hide to make room
				if (event.key == Key.Menu) {
					if (event.type == KeyEventType.KeyUp) {
						if (infoVisible) infoVisible = false
						else {
							infoVisible = true
							scrubMs = null
							controlsVisible = false
						}
					}
					return@onPreviewKeyEvent true
				}

				if (controlsVisible) {
					if (isDown) lastInteraction = System.currentTimeMillis()
					// Back forgets the position being sought, otherwise hides the controls (it doesn't
					// close the player)
					if (isBack) {
						if (isDown) {
							if (scrubMs != null) scrubMs = null
							else controlsVisible = false
						}
						return@onPreviewKeyEvent true
					}
					return@onPreviewKeyEvent false
				}

				// Back closes the stream info first (the skip button, if any, goes before)
				if (isBack && infoVisible && !skipVisible) {
					if (isDown) infoVisible = false
					return@onPreviewKeyEvent true
				}

				when (event.key) {
					Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
						if (isDown && event.nativeKeyEvent.repeatCount == 0) {
							if (skipVisible) overlay.skipSegment()
							else {
								overlay.playPause()
								showControls()
							}
						}
						true
					}

					Key.DirectionLeft, Key.DirectionRight -> {
						if (isDown && state.canSeek) {
							scrub(event, forward = event.key == Key.DirectionRight)
							showControls(ControlsFocus.SEEK_BAR)
						} else if (isDown) showControls()
						true
					}

					Key.DirectionUp, Key.DirectionDown -> {
						if (isDown) showControls()
						true
					}

					// Back with the skip button: only hide the button
					Key.Back, Key.Escape -> if (skipVisible) {
						if (isDown) overlay.clearSkip()
						true
					} else false

					else -> false
				}
			}
			.focusable(),
	) {
		// "You're watching" while paused without controls
		AnimatedVisibility(
			visible = !controlsVisible && !state.playing && state.paused,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.fillMaxSize(),
		) {
			PausedInfo(state = state)
		}

		// Subtle pause bars in the middle of the screen
		AnimatedVisibility(
			visible = !state.playing && state.paused,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.align(Alignment.Center),
		) {
			PauseIndicator()
		}

		// Stream info on the right; out of the way while the controls show
		StreamInfoPanel(overlay = overlay, visible = infoVisible && !controlsVisible)

		AnimatedVisibility(
			visible = controlsVisible,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.fillMaxSize(),
		) {
			PlayerControls(
				overlay = overlay,
				scrubMs = scrubMs,
				seekBarFocusRequester = seekBarFocusRequester,
				onScrub = { event, forward -> scrub(event, forward) },
				onCommitScrub = {
					scrubMs?.let { overlay.seekTo(it) }
					scrubMs = null
					lastInteraction = System.currentTimeMillis()
				},
				onCancelScrub = { scrubMs = null },
				onSeekBarPositioned = { seekBarTop = it },
				onPlayPause = overlay::playPause,
				onMenu = { menu = it },
				skipVisible = skipVisible,
			)
		}

		// Skip button while the controls are hidden (OK skips)
		AnimatedVisibility(
			visible = skipVisible && !controlsVisible,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(end = 56.dp, bottom = 56.dp),
		) {
			SkipSegmentButton(type = state.skipType, focused = true, onClick = overlay::skipSegment)
		}
	}

	PlayerMenus(
		overlay = overlay,
		menu = menu,
		onDismissRequest = {
			menu = null
			lastInteraction = System.currentTimeMillis()
		},
	)
}

// Controls

@Composable
private fun PlayerControls(
	overlay: ModernPlayerOverlay,
	scrubMs: Long?,
	seekBarFocusRequester: FocusRequester,
	onScrub: (KeyEvent, Boolean) -> Unit,
	onCommitScrub: () -> Unit,
	onCancelScrub: () -> Unit,
	onSeekBarPositioned: (Float) -> Unit,
	onPlayPause: () -> Unit,
	onMenu: (PlayerMenu) -> Unit,
	skipVisible: Boolean,
) {
	val state = overlay.state
	val item = state.item
	val imageLoader = koinInject<ImageLoader>()

	Box(modifier = Modifier.fillMaxSize()) {
		// Readability gradients
		Box(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.fillMaxWidth()
				.height(150.dp)
				.background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.8f), 1f to Color.Transparent))
		)
		Box(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.height(210.dp)
				.background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f)))
		)

		// Top: back + title, logo underneath
		Column(
			modifier = Modifier
				.align(Alignment.TopStart)
				.padding(start = 28.dp, top = 18.dp, end = 48.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				PlayerIconButton(
					icon = R.drawable.ic_arrow_back,
					contentDescription = stringResource(R.string.modern_player_back),
					onClick = overlay::close,
					size = 36.dp,
				)
				Spacer(Modifier.width(10.dp))
				Text(
					text = item?.let { playerTitle(it) }.orEmpty(),
					color = ModernHomeColors.TextPrimary,
					fontSize = 14.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}

			if (state.logoUrl != null) {
				Spacer(Modifier.height(6.dp))
				AsyncImage(
					model = state.logoUrl,
					contentDescription = null,
					imageLoader = imageLoader,
					contentScale = ContentScale.Fit,
					alignment = Alignment.CenterStart,
					modifier = Modifier
						.padding(start = 8.dp)
						.height(54.dp)
						.widthIn(max = 230.dp),
				)
			}
		}

		// Bottom: seek bar and buttons
		Column(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.padding(start = 40.dp, end = 40.dp, bottom = 20.dp),
		) {
			if (skipVisible) {
				SkipSegmentButton(
					type = state.skipType,
					focused = null,
					onClick = overlay::skipSegment,
					modifier = Modifier
						.align(Alignment.End)
						.padding(bottom = 12.dp),
				)
			}

			SeekBarRow(
				state = state,
				scrubMs = scrubMs,
				focusRequester = seekBarFocusRequester,
				onScrub = onScrub,
				onCommit = onCommitScrub,
				onCancel = onCancelScrub,
				onPositioned = onSeekBarPositioned,
				onPlayPause = onPlayPause,
			)

			Spacer(Modifier.height(8.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (state.hasPrevious) {
					PlayerIconButton(
						icon = R.drawable.ic_previous,
						contentDescription = stringResource(R.string.modern_player_previous),
						onClick = { overlay.previous() },
					)
					Spacer(Modifier.width(6.dp))
				}
				if (state.hasNext) {
					PlayerIconButton(
						icon = R.drawable.ic_next,
						contentDescription = stringResource(R.string.modern_player_next),
						onClick = { overlay.next() },
					)
					Spacer(Modifier.width(10.dp))
				}

				val remainingMs = (state.durationMs - state.positionMs).coerceAtLeast(0)
				if (state.durationMs > 0) {
					val endTime = LocalTime.now()
						.plusSeconds((remainingMs / state.speed.coerceAtLeast(0.1f) / 1000f).toLong())
						.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
					Text(
						text = stringResource(R.string.modern_player_ends_at, endTime),
						color = ModernHomeColors.TextSecondary,
						fontSize = 12.sp,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
					)
				}

				Spacer(Modifier.weight(1f))

				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					PlayerIconButton(
						icon = R.drawable.ic_select_audio,
						contentDescription = stringResource(R.string.modern_player_audio),
						onClick = { onMenu(PlayerMenu.AUDIO) },
					)
					PlayerIconButton(
						icon = R.drawable.ic_select_subtitle,
						contentDescription = stringResource(R.string.modern_player_subtitles),
						onClick = { onMenu(PlayerMenu.SUBTITLES) },
					)
					if (!item?.chapters.isNullOrEmpty()) {
						PlayerIconButton(
							icon = R.drawable.ic_select_chapter,
							contentDescription = stringResource(R.string.modern_player_chapters),
							onClick = { onMenu(PlayerMenu.CHAPTERS) },
						)
					}
					PlayerIconButton(
						icon = R.drawable.ic_select_quality,
						contentDescription = stringResource(R.string.modern_player_quality),
						onClick = { onMenu(PlayerMenu.QUALITY) },
					)
					// Direct play, remux or transcoding: for files that don't play well as they are
					PlayerIconButton(
						icon = R.drawable.ic_playback_mode,
						contentDescription = stringResource(R.string.modern_player_playback_mode),
						onClick = { onMenu(PlayerMenu.PLAYBACK_MODE) },
					)
					PlayerIconButton(
						icon = R.drawable.ic_playback_speed,
						contentDescription = stringResource(R.string.modern_player_speed),
						onClick = { onMenu(PlayerMenu.SPEED) },
					)
					PlayerIconButton(
						icon = R.drawable.ic_subtitle_offset,
						contentDescription = stringResource(R.string.modern_player_subtitle_offset),
						onClick = { onMenu(PlayerMenu.SUBTITLE_OFFSET) },
					)
				}
			}
		}
	}
}

/**
 * "Rick and Morty - S9:E1 - There's Something About Morty (2026)" or "Movie (2024)".
 */
internal fun playerTitle(item: BaseItemDto): String {
	val year = (item.productionYear ?: item.premiereDate?.year)?.let { " ($it)" }.orEmpty()
	if (item.type != BaseItemKind.EPISODE) return item.name.orEmpty() + year

	val number = listOfNotNull(
		item.parentIndexNumber?.let { "S$it" },
		item.indexNumber?.let { "E$it" },
	).joinToString(":")
	return listOf(item.seriesName.orEmpty(), number, item.name.orEmpty() + year)
		.filter { it.isNotBlank() }
		.joinToString(" - ")
}

// Seek bar

@Composable
private fun SeekBarRow(
	state: ModernPlayerState,
	scrubMs: Long?,
	focusRequester: FocusRequester,
	onScrub: (KeyEvent, Boolean) -> Unit,
	onCommit: () -> Unit,
	onCancel: () -> Unit,
	onPositioned: (Float) -> Unit,
	onPlayPause: () -> Unit,
) {
	val shownPosition = scrubMs ?: state.positionMs
	val duration = state.durationMs.coerceAtLeast(1)
	val playedFraction = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
	val chapters = remember(state.item) {
		state.item?.chapters.orEmpty()
			.map { it.startPositionTicks / TICKS_PER_MS }
			.filter { it > 0 }
	}

	BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
		val timeWidth = 64.dp
		val barStart = timeWidth + 12.dp
		val barWidth = maxWidth - (timeWidth + 12.dp) * 2
		val previewWidth = 192.dp
		val containerWidth = maxWidth
		val fraction = (shownPosition.toFloat() / duration).coerceIn(0f, 1f)

		Column {
			// Thumbnail of the position being sought
			if (scrubMs != null) {
				val x = (barStart + barWidth * fraction - previewWidth / 2).coerceIn(0.dp, containerWidth - previewWidth)
				SeekPreview(
					item = state.item,
					mediaSourceId = state.mediaSourceId,
					positionMs = scrubMs,
					modifier = Modifier
						.offset(x = x)
						.padding(bottom = 10.dp)
						.width(previewWidth),
				)
			}

			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.onGloballyPositioned { onPositioned(it.boundsInWindow().top) },
			) {
				Text(
					text = formatTime(shownPosition),
					color = ModernHomeColors.TextPrimary,
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					modifier = Modifier.width(timeWidth),
				)
				Spacer(Modifier.width(12.dp))
				SeekBar(
					fraction = fraction,
					playedFraction = playedFraction,
					chapterFractions = chapters.map { it.toFloat() / duration },
					seeking = scrubMs != null,
					enabled = state.canSeek,
					focusRequester = focusRequester,
					onScrub = onScrub,
					onCommit = onCommit,
					onCancel = onCancel,
					onPlayPause = onPlayPause,
					modifier = Modifier.width(barWidth),
				)
				Spacer(Modifier.width(12.dp))
				Text(
					text = "-" + formatTime((state.durationMs - shownPosition).coerceAtLeast(0)),
					color = ModernHomeColors.TextPrimary,
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					modifier = Modifier.width(timeWidth),
				)
			}
		}
	}
}

@Composable
private fun SeekBar(
	/** Position shown (the one being sought while seeking) */
	fraction: Float,
	/** Position of the playback */
	playedFraction: Float,
	chapterFractions: List<Float>,
	seeking: Boolean,
	enabled: Boolean,
	focusRequester: FocusRequester,
	onScrub: (KeyEvent, Boolean) -> Unit,
	onCommit: () -> Unit,
	onCancel: () -> Unit,
	onPlayPause: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	var hadFocus by remember { mutableStateOf(false) }
	val trackHeight by animateFloatAsState(if (focused) 6f else 4f, label = "SeekBarHeight")
	val accent = ModernHomeColors.Accent

	Box(
		modifier = modifier
			.height(24.dp)
			.focusRequester(focusRequester)
			// Leaving the seek bar (the buttons) forgets the position being sought. Not the first
			// event, when it appears unfocused
			.onFocusChanged {
				if (it.isFocused) hadFocus = true
				else if (hadFocus) {
					hadFocus = false
					if (seeking) onCancel()
				}
			}
			.onPreviewKeyEvent { event ->
				if (!enabled) return@onPreviewKeyEvent false
				when (event.key) {
					Key.DirectionLeft, Key.DirectionRight -> {
						if (event.type == KeyEventType.KeyDown) onScrub(event, event.key == Key.DirectionRight)
						true
					}

					// OK applies the position being sought, otherwise plays/pauses
					Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
						if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
							if (seeking) onCommit() else onPlayPause()
						}
						true
					}

					else -> false
				}
			}
			.focusable(enabled = enabled, interactionSource = interactionSource)
			.drawBehind {
				val height = trackHeight.dp.toPx()
				val top = (size.height - height) / 2
				val radius = CornerRadius(height / 2)

				drawRoundRect(color = Color.White.copy(alpha = 0.28f), topLeft = Offset(0f, top), size = Size(size.width, height), cornerRadius = radius)
				drawRoundRect(color = accent, topLeft = Offset(0f, top), size = Size(size.width * playedFraction, height), cornerRadius = radius)
				// Seeking: from the playback to the position sought, which only applies with OK
				if (seeking) {
					val from = minOf(playedFraction, fraction)
					val to = maxOf(playedFraction, fraction)
					drawRoundRect(
						color = Color.White.copy(alpha = 0.6f),
						topLeft = Offset(size.width * from, top),
						size = Size(size.width * (to - from), height),
						cornerRadius = radius,
					)
				}

				// Chapters
				val tickWidth = 2.dp.toPx()
				val tickHeight = height + 6.dp.toPx()
				for (chapter in chapterFractions) {
					if (chapter <= 0f || chapter >= 1f) continue
					drawRect(
						color = Color.White.copy(alpha = 0.75f),
						topLeft = Offset(size.width * chapter - tickWidth / 2, (size.height - tickHeight) / 2),
						size = Size(tickWidth, tickHeight),
					)
				}

				if (focused) {
					drawCircle(color = Color.White, radius = 8.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
					drawCircle(color = accent, radius = 8.dp.toPx(), center = Offset(size.width * fraction, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
				}
			},
	)
}

@Composable
private fun SeekPreview(
	item: BaseItemDto?,
	mediaSourceId: String?,
	positionMs: Long,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		TrickplayThumbnail(
			item = item,
			mediaSourceId = mediaSourceId,
			positionMs = positionMs,
			modifier = Modifier
				.fillMaxWidth()
				.height(108.dp),
		)
		Spacer(Modifier.height(4.dp))
		Text(
			text = formatTime(positionMs),
			color = ModernHomeColors.TextPrimary,
			fontSize = 13.sp,
			fontWeight = FontWeight.Bold,
			maxLines = 1,
		)
		// The playback only jumps with OK
		Text(
			text = stringResource(R.string.modern_player_seek_hint),
			color = ModernHomeColors.TextSecondary,
			fontSize = 10.sp,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
		)
	}
}

// Paused screen

@Composable
private fun PausedInfo(state: ModernPlayerState) {
	val item = state.item ?: return
	val imageLoader = koinInject<ImageLoader>()
	val isEpisode = item.type == BaseItemKind.EPISODE

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(
				Brush.horizontalGradient(
					0f to Color.Black.copy(alpha = 0.85f),
					0.5f to Color.Black.copy(alpha = 0.45f),
					1f to Color.Black.copy(alpha = 0.15f),
				)
			),
	) {
		Column(
			modifier = Modifier
				.align(Alignment.CenterStart)
				.padding(start = 56.dp)
				.fillMaxWidth(0.48f),
		) {
			Text(
				text = stringResource(R.string.modern_player_you_are_watching),
				color = ModernHomeColors.TextSecondary,
				fontSize = 14.sp,
				fontWeight = FontWeight.Medium,
			)
			Spacer(Modifier.height(14.dp))

			if (state.logoUrl != null) {
				AsyncImage(
					model = state.logoUrl,
					contentDescription = null,
					imageLoader = imageLoader,
					contentScale = ContentScale.Fit,
					alignment = Alignment.CenterStart,
					modifier = Modifier
						.height(84.dp)
						.widthIn(max = 300.dp),
				)
			} else {
				Text(
					text = (if (isEpisode) item.seriesName else item.name).orEmpty(),
					color = ModernHomeColors.TextPrimary,
					fontSize = 32.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}

			Spacer(Modifier.height(18.dp))

			if (isEpisode) {
				val season = item.parentIndexNumber
				val episode = item.indexNumber
				if (season != null && episode != null) {
					Text(
						text = stringResource(R.string.modern_player_season_episode, season, episode),
						color = ModernHomeColors.TextSecondary,
						fontSize = 13.sp,
						fontWeight = FontWeight.SemiBold,
					)
				}
				Text(
					text = item.name.orEmpty(),
					color = ModernHomeColors.TextPrimary,
					fontSize = 20.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(6.dp))
			} else if (state.logoUrl != null) {
				Text(
					text = item.name.orEmpty(),
					color = ModernHomeColors.TextPrimary,
					fontSize = 20.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
				)
				Spacer(Modifier.height(6.dp))
			}

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				(item.productionYear ?: item.premiereDate?.year)?.let { year ->
					Text(text = year.toString(), color = ModernHomeColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
				}
				item.officialRating?.takeIf { it.isNotBlank() }?.let { rating ->
					Text(
						text = rating,
						color = ModernHomeColors.TextPrimary,
						fontSize = 10.sp,
						fontWeight = FontWeight.Bold,
						modifier = Modifier
							.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
							.padding(horizontal = 5.dp, vertical = 1.dp),
					)
				}
				item.runTimeTicks?.let { ticks ->
					Text(text = formatRuntimeShort(ticks / TICKS_PER_MS), color = ModernHomeColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
				}
				ImdbRatings.rating(item)?.let { rating ->
					Text(
						text = "★ " + String.format(Locale.getDefault(), "%.1f", rating),
						color = ModernHomeColors.TextPrimary,
						fontSize = 12.sp,
						fontWeight = FontWeight.SemiBold,
					)
				}
			}

			item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
				Spacer(Modifier.height(12.dp))
				Text(
					text = overview,
					color = ModernHomeColors.TextSecondary,
					fontSize = 13.sp,
					lineHeight = 19.sp,
					maxLines = 3,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}

		Text(
			text = stringResource(R.string.modern_player_paused),
			color = Color.White.copy(alpha = 0.75f),
			fontSize = 22.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(end = 48.dp, bottom = 40.dp),
		)
	}
}

// Buttons

@Composable
private fun PlayerIconButton(
	icon: Int,
	contentDescription: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	size: androidx.compose.ui.unit.Dp = 38.dp,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "PlayerButtonScale")

	Box(
		modifier = modifier
			.size(size)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(if (focused) Color.White else Color.Transparent, CircleShape),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = contentDescription,
			tint = if (focused) Color.Black else Color.White,
			modifier = Modifier.size(size * 0.5f),
		)
	}
}

/**
 * Two subtle bars shown in the middle of the screen while paused.
 */
@Composable
private fun PauseIndicator() {
	Row(
		modifier = Modifier
			.size(64.dp)
			.background(Color.Black.copy(alpha = 0.28f), CircleShape),
		horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
		verticalAlignment = Alignment.CenterVertically,
	) {
		repeat(2) {
			Box(
				modifier = Modifier
					.size(width = 7.dp, height = 26.dp)
					.background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
			)
		}
	}
}

/**
 * "SKIP INTRO" / "SKIP CREDITS". [focused] null means it takes focus like any button.
 */
@Composable
private fun SkipSegmentButton(
	type: MediaSegmentType?,
	focused: Boolean?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val hasFocus by interactionSource.collectIsFocusedAsState()
	val highlighted = focused ?: hasFocus
	val shape = RoundedCornerShape(8.dp)

	val text = stringResource(
		when (type) {
			MediaSegmentType.INTRO -> R.string.modern_player_skip_intro
			MediaSegmentType.OUTRO -> R.string.modern_player_skip_credits
			MediaSegmentType.RECAP -> R.string.modern_player_skip_recap
			MediaSegmentType.PREVIEW -> R.string.modern_player_skip_preview
			else -> R.string.modern_player_skip
		}
	)

	Row(
		modifier = modifier
			.then(
				if (focused == null) Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
				else Modifier
			)
			.background(if (highlighted && focused == null) Color.White else Color.Black.copy(alpha = 0.6f), shape)
			.border(1.5.dp, Color.White.copy(alpha = if (highlighted) 0.9f else 0.5f), shape)
			.padding(horizontal = 18.dp, vertical = 9.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		val contentColor = if (highlighted && focused == null) Color.Black else Color.White
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_next),
			contentDescription = null,
			tint = contentColor,
			modifier = Modifier.size(16.dp),
		)
		Spacer(Modifier.width(10.dp))
		Text(
			text = text.uppercase(Locale.getDefault()),
			color = contentColor,
			fontFamily = BebasNeue,
			fontSize = 22.sp,
			letterSpacing = 1.sp,
			maxLines = 1,
		)
	}
}

// Formatting

internal fun formatTime(ms: Long): String {
	val totalSeconds = ms / 1000
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60
	return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
	else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

private fun formatRuntimeShort(ms: Long): String {
	val minutes = (ms / 60_000).toInt()
	return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
