package org.jellyfin.androidtv.ui.playback.modern

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.VideoQualityController
import org.jellyfin.androidtv.ui.playback.VideoSpeedController
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration

/**
 * One option of a player menu (audio track, subtitle, quality, speed).
 */
data class PlayerOption(
	val label: String,
	val selected: Boolean,
	val onSelect: () -> Unit,
)

/**
 * State shown by the modern player controls, updated by [CustomPlaybackOverlayFragment] through
 * [ModernPlayerOverlay]. Only touched from the main thread.
 */
class ModernPlayerState {
	var item by mutableStateOf<BaseItemDto?>(null)
	var logoUrl by mutableStateOf<String?>(null)
	/** Media source being played, for the trickplay thumbnails */
	var mediaSourceId by mutableStateOf<String?>(null)
	var positionMs by mutableLongStateOf(0L)
	var durationMs by mutableLongStateOf(0L)
	var playing by mutableStateOf(false)
	var paused by mutableStateOf(false)
	var canSeek by mutableStateOf(true)
	var hasNext by mutableStateOf(false)
	var hasPrevious by mutableStateOf(false)
	var speed by mutableStateOf(1f)
	var subtitleOffsetMs by mutableLongStateOf(0L)

	/** "Skip intro" / "Skip credits": where to jump to, null when there is nothing to skip */
	var skipTargetMs by mutableStateOf<Long?>(null)
	var skipType by mutableStateOf<MediaSegmentType?>(null)
}

/**
 * Streaming style player controls for video playback (not used for live TV, which keeps the
 * original controls with the guide). Replaces the top panel, the Leanback transport controls and
 * the skip overlay of [CustomPlaybackOverlayFragment].
 */
class ModernPlayerOverlay(
	private val context: Context,
) : KoinComponent {
	companion object {
		/**
		 * Whether the modern controls are used for [items]: enabled in the settings and not live TV.
		 */
		@JvmStatic
		fun isEnabledFor(items: List<BaseItemDto>?): Boolean {
			val userPreferences = org.koin.java.KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)
			if (!userPreferences[UserPreferences.modernPlayerEnabled]) return false
			return items.orEmpty().none { it.type == BaseItemKind.TV_CHANNEL || it.type == BaseItemKind.PROGRAM }
		}
	}

	private val playbackControllerContainer by inject<PlaybackControllerContainer>()
	private val imageHelper by inject<ImageHelper>()
	private val userPreferences by inject<UserPreferences>()

	private val qualityController by lazy { VideoQualityController(userPreferences[UserPreferences.maxBitrate], userPreferences) }
	private var speedController: VideoSpeedController? = null

	val state = ModernPlayerState()

	/** Called to close the player (back arrow) */
	var onClose: () -> Unit = {}

	private val controller: PlaybackController?
		get() = playbackControllerContainer.playbackController

	init {
		// Every playback starts without subtitle offset
		SubtitleOffset.reset()
	}

	/**
	 * Add the controls on top of the player.
	 */
	fun attach(root: ViewGroup) {
		val view = ComposeView(root.context).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			isFocusable = true
			setContent { ModernPlayerScreen(overlay = this@ModernPlayerOverlay) }
		}
		root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		view.requestFocus()
	}

	// Events from the fragment

	fun onProgress(positionMs: Long) {
		state.positionMs = positionMs
		refreshPlayState()
	}

	fun onPlayStateChanged() = refreshPlayState()

	fun onItemChanged() {
		val controller = controller ?: return
		val item = controller.currentlyPlayingItem
		state.item = item
		state.logoUrl = imageHelper.getLogoImageUrl(item, 600)
		refreshPlayState()
	}

	fun askToSkip(target: Duration, type: MediaSegmentType?) {
		state.skipTargetMs = target.inWholeMilliseconds
		state.skipType = type
	}

	fun clearSkip() {
		state.skipTargetMs = null
		state.skipType = null
	}

	private fun refreshPlayState() {
		val controller = controller ?: return
		state.playing = controller.isPlaying
		state.paused = controller.isPaused
		state.durationMs = controller.duration.coerceAtLeast(0)
		state.canSeek = controller.canSeek()
		state.hasNext = controller.hasNextItem()
		state.hasPrevious = controller.hasPreviousItem()
		state.mediaSourceId = controller.currentMediaSource?.id
	}

	// Actions from the controls

	fun refreshPosition() {
		val controller = controller ?: return
		state.positionMs = controller.currentPosition
		refreshPlayState()
	}

	fun playPause() {
		controller?.playPause()
		refreshPlayState()
	}

	fun rewind() {
		controller?.rewind()
		refreshPosition()
	}

	fun fastForward() {
		controller?.fastForward()
		refreshPosition()
	}

	fun seekTo(positionMs: Long) {
		val controller = controller ?: return
		controller.seek(positionMs.coerceIn(0, state.durationMs.coerceAtLeast(0)))
		state.positionMs = positionMs
	}

	fun next() = controller?.next()

	fun previous() = controller?.prev()

	fun skipSegment() {
		val target = state.skipTargetMs ?: return
		controller?.seek(target, true)
		clearSkip()
	}

	fun close() = onClose()

	// Menus

	fun audioOptions(): List<PlayerOption> {
		val controller = controller ?: return emptyList()
		val current = controller.audioStreamIndex
		return controller.currentStreamInfo?.selectableAudioStreams.orEmpty().map { stream ->
			PlayerOption(
				label = stream.displayTitle ?: stream.language ?: "#${stream.index}",
				selected = stream.index == current,
				onSelect = { controller.switchAudioStream(stream.index) },
			)
		}
	}

	fun subtitleOptions(noneLabel: String): List<PlayerOption> {
		val controller = controller ?: return emptyList()
		val current = controller.subtitleStreamIndex
		val subtitles = controller.currentMediaSource?.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }

		return listOf(PlayerOption(noneLabel, current == -1) { controller.setSubtitleIndex(-1) }) +
			subtitles.map { stream ->
				PlayerOption(
					label = stream.displayTitle ?: stream.language ?: "#${stream.index}",
					selected = stream.index == current,
					onSelect = { controller.setSubtitleIndex(stream.index) },
				)
			}
	}

	fun qualityOptions(): List<PlayerOption> {
		val controller = controller ?: return emptyList()
		return getQualityProfiles(context).map { (key, label) ->
			PlayerOption(
				label = label,
				selected = key == qualityController.currentQuality,
				onSelect = {
					if (key != qualityController.currentQuality) {
						qualityController.currentQuality = key
						controller.refreshStream()
					}
				},
			)
		}
	}

	fun speedOptions(): List<PlayerOption> {
		val controller = controller ?: return emptyList()
		val speedController = speedController ?: VideoSpeedController(controller).also { speedController = it }
		return VideoSpeedController.SpeedSteps.entries.map { step ->
			PlayerOption(
				label = if (step.speed == 1f) "1×" else "${step.speed.toString().removeSuffix("0").removeSuffix(".")}×",
				selected = step == speedController.currentSpeed,
				onSelect = {
					speedController.currentSpeed = step
					state.speed = step.speed
				},
			)
		}
	}

	fun changeSubtitleOffset(deltaMs: Long) = setSubtitleOffset(state.subtitleOffsetMs + deltaMs)

	fun setSubtitleOffset(offsetMs: Long) {
		val clamped = offsetMs.coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
		SubtitleOffset.offsetMs = clamped
		state.subtitleOffsetMs = clamped
	}
}

private const val MAX_SUBTITLE_OFFSET_MS = 30_000L
