package org.jellyfin.androidtv.ui.music.nowplaying

import androidx.activity.compose.BackHandler
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

/**
 * Full screen view of the song: cover, name, album and artist over the blurred cover, and the
 * lyrics (bigger) when they are shown. Any key goes back.
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
	val focusRequester = remember { FocusRequester() }
	val progress by rememberPlayerProgress(playbackManager)
	var drift by remember { mutableIntStateOf(0) }
	val (driftX, driftY) = driftOffsets[drift % driftOffsets.size]
	val offsetX by animateDpAsState(driftX.dp, tween(6_000), label = "DriftX")
	val offsetY by animateDpAsState(driftY.dp, tween(6_000), label = "DriftY")

	BackHandler(onBack = onExit)

	LaunchedEffect(Unit) {
		focusRequester.tryRequestFocus()
		while (true) {
			delay(DRIFT_MS)
			drift++
		}
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black)
			.focusRequester(focusRequester)
			// Any key (released) closes it, Back included
			.onPreviewKeyEvent { event ->
				if (event.type == KeyEventType.KeyUp) onExit()
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

		Box(
			modifier = Modifier
				.fillMaxSize()
				.offset(offsetX, offsetY)
				.padding(horizontal = 72.dp, vertical = 56.dp),
			contentAlignment = Alignment.Center,
		) {
			if (lyrics != null) {
				Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
					Column(modifier = Modifier.width(340.dp)) {
						Cover(coverUrl, item, size = 320)
						Spacer(Modifier.height(20.dp))
						SongInfo(item, align = TextAlign.Start, large = false)
					}
					Spacer(Modifier.width(56.dp))
					NowPlayingLyrics(
						lyrics = lyrics,
						playbackManager = playbackManager,
						fontSize = 30,
						modifier = Modifier
							.weight(1f)
							.fillMaxHeight(),
					)
				}
			} else {
				Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
					Cover(coverUrl, item, size = 340)
					Spacer(Modifier.height(28.dp))
					SongInfo(item, align = TextAlign.Center, large = true)
				}
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
private fun Cover(url: String?, item: BaseItemDto?, size: Int) {
	Box(
		modifier = Modifier
			.size(size.dp)
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
