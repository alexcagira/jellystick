package org.jellyfin.androidtv.ui.music.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.home.modern.isPlayKey
import org.jellyfin.androidtv.ui.playback.MediaManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LyricDto
import org.koin.compose.koinInject

/** Positions the content moves through, so nothing stays at the same place on the TV */
private val driftOffsets = listOf(0 to 0, 24 to -14, -20 to 12, 14 to 18, -26 to -10)
private const val DRIFT_MS = 25_000L
private const val BUTTON_HIGHLIGHT_MS = 3_000L

/**
 * Full screen view of the song: cover, name, album and artist over the blurred cover, and the
 * lyrics (bigger) when they are shown. OK shows or hides the lyrics (small button in the corner),
 * play/pause pauses, any other key goes back.
 */
@Composable
internal fun NowPlayingScreensaver(
	item: BaseItemDto?,
	coverUrl: String?,
	backgroundUrl: String?,
	lyrics: LyricDto?,
	playbackManager: PlaybackManager,
	onExit: () -> Unit,
) {
	val imageLoader = koinInject<ImageLoader>()
	val mediaManager = koinInject<MediaManager>()
	val focusRequester = remember { FocusRequester() }
	val progress by rememberPlayerProgress(playbackManager)
	var drift by remember { mutableIntStateOf(0) }
	val (driftX, driftY) = driftOffsets[drift % driftOffsets.size]
	val offsetX by animateDpAsState(driftX.dp, tween(6_000), label = "DriftX")
	val offsetY by animateDpAsState(driftY.dp, tween(6_000), label = "DriftY")
	val showLyrics = NowPlayingPreferences.showLyrics && lyrics != null
	// The lyrics button is barely visible, and lights up for a moment when used
	var buttonActive by remember { mutableStateOf(false) }
	var buttonTouch by remember { mutableIntStateOf(0) }
	val buttonAlpha by animateFloatAsState(if (buttonActive) 1f else 0.35f, tween(400), label = "LyricsButton")

	BackHandler(onBack = onExit)

	LaunchedEffect(Unit) {
		focusRequester.tryRequestFocus()
		while (true) {
			delay(DRIFT_MS)
			drift++
		}
	}
	LaunchedEffect(buttonTouch) {
		if (buttonTouch == 0) return@LaunchedEffect
		buttonActive = true
		delay(BUTTON_HIGHLIGHT_MS)
		buttonActive = false
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black)
			.focusRequester(focusRequester)
			.onPreviewKeyEvent { event ->
				val up = event.type == KeyEventType.KeyUp
				when {
					event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
						if (up) {
							NowPlayingPreferences.showLyrics = !NowPlayingPreferences.showLyrics
							buttonTouch++
						}
					}

					event.isPlayKey -> if (up) mediaManager.togglePlayPause()
					else -> if (up) onExit()
				}
				true
			}
			.focusable(),
	) {
		Crossfade(targetState = backgroundUrl, animationSpec = tween(1_200), label = "ScreensaverBackground") { url ->
			if (url != null) {
				AsyncImage(
					model = url,
					contentDescription = null,
					imageLoader = imageLoader,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Brush.verticalGradient(0f to Color(0x80000000), 1f to Color(0xD9000000)))
		)

		Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 48.dp)) {
			Clock(fontSize = 16, color = Color.White.copy(alpha = 0.7f))
		}

		BoxWithConstraints(
			modifier = Modifier
				.fillMaxSize()
				.offset(offsetX, offsetY)
				.padding(horizontal = 72.dp, vertical = 52.dp),
			contentAlignment = Alignment.Center,
		) {
			if (showLyrics) {
				val coverSize = minOf(320.dp, maxHeight - 130.dp)
				Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
					Column(modifier = Modifier.width(coverSize + 20.dp)) {
						Cover(coverUrl, item, size = coverSize)
						Spacer(Modifier.height(18.dp))
						SongInfo(item, align = TextAlign.Start, large = false)
					}
					Spacer(Modifier.width(56.dp))
					NowPlayingLyrics(
						lyrics = lyrics!!,
						playbackManager = playbackManager,
						fontSize = 30,
						modifier = Modifier
							.weight(1f)
							.fillMaxHeight(),
					)
				}
			} else {
				// Cover beside the text: everything fits whatever the height of the screen
				val coverSize = minOf(340.dp, maxHeight)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
					Cover(coverUrl, item, size = coverSize)
					Spacer(Modifier.width(48.dp))
					Box(modifier = Modifier.weight(1f, fill = false).width(420.dp)) {
						SongInfo(item, align = TextAlign.Start, large = true)
					}
				}
			}
		}

		// Lyrics on/off, subtle
		if (lyrics != null) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 40.dp, bottom = 20.dp)
					.graphicsLayer { alpha = buttonAlpha }
					.background(Color.White.copy(alpha = if (buttonActive) 0.18f else 0.08f), RoundedCornerShape(50))
					.padding(horizontal = 12.dp, vertical = 6.dp),
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_subtitles),
					contentDescription = null,
					tint = if (NowPlayingPreferences.showLyrics) ModernHomeColors.Accent else Color.White,
					modifier = Modifier.size(14.dp),
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = stringResource(if (NowPlayingPreferences.showLyrics) R.string.music_screensaver_hide_lyrics else R.string.music_screensaver_show_lyrics),
					color = Color.White,
					fontSize = 11.sp,
					fontWeight = FontWeight.SemiBold,
				)
			}
		}

		// Progress of the song, thin line at the bottom
		Box(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.fillMaxWidth()
				.height(3.dp)
				.background(Color.White.copy(alpha = 0.12f)),
		) {
			Box(
				modifier = Modifier
					.fillMaxHeight()
					.fillMaxWidth(progress.coerceIn(0f, 1f))
					.background(ModernHomeColors.Accent),
			)
		}
	}
}

@Composable
private fun Cover(url: String?, item: BaseItemDto?, size: Dp) {
	Box(
		modifier = Modifier
			.size(size)
			.clip(RoundedCornerShape(18.dp))
			.background(ModernHomeColors.CardSurface),
	) {
		Crossfade(targetState = url, animationSpec = tween(800), label = "ScreensaverCover") { coverUrl ->
			CardArtwork(url = coverUrl, fallbackTitle = item?.album ?: item?.name.orEmpty())
		}
	}
}

@Composable
private fun SongInfo(item: BaseItemDto?, align: TextAlign, large: Boolean) {
	Column(horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
		Text(
			text = item?.name.orEmpty(),
			color = Color.White,
			fontSize = if (large) 36.sp else 26.sp,
			lineHeight = if (large) 40.sp else 30.sp,
			fontWeight = FontWeight.Bold,
			textAlign = align,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Spacer(Modifier.height(6.dp))
		Text(
			text = item?.artistsText().orEmpty(),
			color = Color.White.copy(alpha = 0.85f),
			fontSize = if (large) 22.sp else 18.sp,
			fontWeight = FontWeight.SemiBold,
			textAlign = align,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		val album = item?.album
		if (album != null) {
			Spacer(Modifier.height(4.dp))
			Text(
				text = album,
				color = Color.White.copy(alpha = 0.6f),
				fontSize = if (large) 17.sp else 15.sp,
				textAlign = align,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}
