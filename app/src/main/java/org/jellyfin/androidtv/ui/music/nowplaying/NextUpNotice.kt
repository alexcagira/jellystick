package org.jellyfin.androidtv.ui.music.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long before the end of the song the next one is announced */
private val NEXT_UP_LEAD = 10.seconds

/** Songs shorter than this aren't announced (the card would cover most of them) */
private val MIN_SONG_LENGTH = 30.seconds

/** The "Next up" card: the next song, shown during the last seconds of the current one */
@Stable
internal class NextUpState {
	var visible by mutableStateOf(false)
		internal set

	/** Stays while the card fades out (the next song is then the current one) */
	var song by mutableStateOf<BaseItemDto?>(null)
		internal set
}

/** Follows the queue and the position of the song: when to show the card and with which song */
@Composable
internal fun rememberNextUp(playbackManager: PlaybackManager): NextUpState {
	val state = remember { NextUpState() }
	val entry by rememberQueueEntry(playbackManager)
	val entries by playbackManager.queue.entries.collectAsState()
	val playbackOrder by playbackManager.state.playbackOrder.collectAsState()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)

	// The next song: known once peeked, again when the queue or its order change
	var next by remember { mutableStateOf<BaseItemDto?>(null) }
	LaunchedEffect(entry, entries, playbackOrder) {
		next = runCatching { playbackManager.queue.peekNext(1).firstOrNull()?.baseItem }.getOrNull()
	}

	val duration = positionInfo.duration
	val remaining = duration - positionInfo.active
	val visible = next != null && duration >= MIN_SONG_LENGTH && remaining > Duration.ZERO && remaining <= NEXT_UP_LEAD
	val song = next
	// After the composition: the screensaver reads it to make room
	SideEffect {
		state.visible = visible
		if (visible && song != null) state.song = song
	}
	return state
}

/**
 * "Next up" card for the screensavers: the cover, name and album of the next song, in the last
 * seconds of the current one. Nothing when the queue ends with this song. Compact (68 dp high):
 * the screensaver of Now Playing makes room for it at the top while it shows.
 */
@Composable
internal fun NextUpNotice(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) = NextUpNotice(rememberNextUp(playbackManager), modifier)

@Composable
internal fun NextUpNotice(
	state: NextUpState,
	modifier: Modifier = Modifier,
) {
	val api = koinInject<ApiClient>()

	AnimatedVisibility(
		visible = state.visible,
		enter = fadeIn(tween(500)) + slideInHorizontally(tween(500)) { -it / 3 },
		exit = fadeOut(tween(500)) + slideOutHorizontally(tween(500)) { -it / 3 },
		modifier = modifier,
	) {
		val song = state.song ?: return@AnimatedVisibility
		val coverUrl = song.songImage()?.let { api.songCoverUrl(it) }
		val shape = RoundedCornerShape(12.dp)

		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.widthIn(max = 380.dp)
				.clip(shape)
				.background(Color.Black.copy(alpha = 0.66f))
				.border(1.dp, Color.White.copy(alpha = 0.14f), shape)
				.padding(8.dp),
		) {
			Box(
				modifier = Modifier
					.size(52.dp)
					.clip(RoundedCornerShape(6.dp))
					.background(ModernHomeColors.CardSurface),
			) {
				CardArtwork(url = coverUrl, fallbackTitle = song.album ?: song.name.orEmpty())
			}
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.padding(end = 6.dp)) {
				Text(
					text = stringResource(R.string.music_next_up).uppercase(),
					color = ModernHomeColors.Accent,
					fontSize = 10.sp,
					fontWeight = FontWeight.Bold,
					letterSpacing = 1.1.sp,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = song.name.orEmpty(),
					color = Color.White,
					fontSize = 15.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				val details = listOfNotNull(song.album, song.artistsText()?.takeIf { it.isNotBlank() }).joinToString(" · ")
				if (details.isNotEmpty()) {
					Text(
						text = details,
						color = Color.White.copy(alpha = 0.7f),
						fontSize = 12.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}
