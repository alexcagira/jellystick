package org.jellyfin.androidtv.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.queue
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun rememberQueueEntry(
	playbackManager: PlaybackManager = koinInject(),
) = remember(playbackManager) {
	playbackManager.queue.entry
}.collectAsState()

@Composable
fun rememberPlayerPositionInfo(
	playbackManager: PlaybackManager = koinInject(),
	precision: Duration = 1.seconds
): MutableState<PositionInfo> {
	val playState by playbackManager.state.playState.collectAsState()
	val playing = playState == PlayState.PLAYING

	val positionInfo = remember { mutableStateOf(playbackManager.state.positionInfo) }

	LaunchedEffect(playing, precision) {
		val precisionMs = precision.inWholeMilliseconds

		while (playing) {
			positionInfo.value = playbackManager.state.positionInfo

			delay(precisionMs - (positionInfo.value.active.inWholeMilliseconds % precisionMs))
		}
	}

	return positionInfo
}

/**
 * Progress of the song or video playing (0 to 1), smooth. The real position is read again every
 * second (and when the song or the play state changes) and the bar moves to where it will be a
 * second later. It used to read it once and animate to the end of the song: after buffering, a
 * seek or a next song of the same length the bar reached the end before the song did, and nothing
 * set it right while the screensaver showed (leaving it did, a new bar read the position).
 */
@Composable
fun rememberPlayerProgress(
	playbackManager: PlaybackManager = koinInject(),
): State<Float> {
	val playState by playbackManager.state.playState.collectAsState()
	val entry by rememberQueueEntry(playbackManager)
	val playing = playState == PlayState.PLAYING
	val animatable = remember { Animatable(0f, 0f) }

	LaunchedEffect(playing, entry) {
		while (true) {
			val info = playbackManager.state.positionInfo
			val durationMs = info.duration.inWholeMilliseconds
			val activeMs = info.active.inWholeMilliseconds.coerceAtLeast(0)
			animatable.snapTo(if (durationMs > 0) (activeMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f)

			// Paused or stopped: where it is
			if (!playing) break
			// Still loading the song: its length comes in a moment
			if (durationMs <= 0) {
				delay(PROGRESS_SYNC_MS)
				continue
			}

			val stepMs = PROGRESS_SYNC_MS.coerceAtMost(durationMs - activeMs).coerceAtLeast(MIN_STEP_MS)
			val target = ((activeMs + stepMs).toFloat() / durationMs).coerceIn(0f, 1f)
			withContext(FixedMotionDurationScale) {
				animatable.animateTo(
					targetValue = target,
					animationSpec = tween(durationMillis = stepMs.toInt(), easing = LinearEasing),
				)
			}
		}
	}

	return animatable.asState()
}

/** How often the progress bars read the real position */
private const val PROGRESS_SYNC_MS = 1_000L
private const val MIN_STEP_MS = 50L

@Composable
fun rememberPlayerProgress(
	playing: Boolean,
	active: Duration,
	duration: Duration,
): State<Float> {
	val animatable = remember { Animatable(0f, 0f) }

	LaunchedEffect(playing, duration) {
		val activeMs = active.inWholeMilliseconds.toFloat()
		val durationMs = duration.inWholeMilliseconds.toFloat()

		if (active == Duration.ZERO) animatable.snapTo(0f)
		else animatable.snapTo((activeMs / durationMs).coerceIn(0f, 1f))

		if (playing) withContext(FixedMotionDurationScale) {
			animatable.animateTo(
				targetValue = 1f,
				animationSpec = tween(
					durationMillis = (durationMs - activeMs).roundToInt(),
					easing = LinearEasing,
				)
			)
		}
	}

	return animatable.asState()
}
