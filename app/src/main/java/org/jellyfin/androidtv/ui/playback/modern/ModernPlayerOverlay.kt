package org.jellyfin.androidtv.ui.playback.modern

import android.content.Context
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.livetv.modern.LiveTvClock
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.time.LocalDateTime
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackFallback
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.VideoQualityController
import org.jellyfin.androidtv.ui.playback.VideoSpeedController
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodingInfo
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
	/**
	 * Live TV: the guide or the channel changer (views of the fragment) is open, the controls leave
	 * the focus to it
	 */
	var panelOpen by mutableStateOf(false)
	/** Changes when the remote has to come back to the controls (the guide or the changer closed) */
	var focusRequests by mutableIntStateOf(0)
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

	/** Live TV: a channel plays. The bar is the programme on now, from its start to its end */
	var live by mutableStateOf(false)
	/** Live TV: the programme on now on the channel */
	var liveProgram by mutableStateOf<BaseItemDto?>(null)
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
			// Live TV too: the programme on now in the bar, the guide (button) and the channels on ↓
			return userPreferences[UserPreferences.modernPlayerEnabled] && !items.isNullOrEmpty()
		}

		/**
		 * The modern player is on screen: ☰ opens its stream info instead of stopping the music
		 * (MainActivity / MusicStopper)
		 */
		@Volatile
		@JvmStatic
		var isShowing: Boolean = false
	}

	private val playbackControllerContainer by inject<PlaybackControllerContainer>()
	private val imageHelper by inject<ImageHelper>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var liveJob: Job? = null
	private var composeView: View? = null

	private val qualityController by lazy { VideoQualityController(userPreferences[UserPreferences.maxBitrate], userPreferences) }
	private var speedController: VideoSpeedController? = null

	val state = ModernPlayerState()

	/** Called to close the player (back arrow) */
	var onClose: () -> Unit = {}

	/** Live TV: the guide of the player (its button), and the channel changer (↓) */
	var onShowGuide: () -> Unit = {}
	var onShowChannels: () -> Unit = {}

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
			// The focus goes into the controls, not to this frame (the keys wouldn't reach them)
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
			setContent { ModernPlayerScreen(overlay = this@ModernPlayerOverlay) }
		}
		root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		view.requestFocus()
		composeView = view
	}

	/** The remote goes back to the controls (after the guide or the channel changer closed) */
	fun focusControls() {
		state.panelOpen = false
		composeView?.requestFocus()
		// The view having the focus isn't enough: the screen takes it inside (ModernPlayerScreen)
		state.focusRequests++
	}

	/** The player closes */
	fun release() {
		scope.cancel()
		composeView = null
	}

	// Events from the fragment

	fun onProgress(positionMs: Long) {
		if (!state.live) state.positionMs = positionMs
		refreshPlayState()
	}

	fun onPlayStateChanged() = refreshPlayState()

	fun onItemChanged() {
		val controller = controller ?: return
		val item = controller.currentlyPlayingItem
		val previous = state.item
		state.item = item
		state.live = controller.isLiveTv
		state.logoUrl = if (state.live) item?.itemImages?.get(ImageType.PRIMARY)?.getUrl(api, maxHeight = CHANNEL_LOGO_HEIGHT, format = ImageFormat.PNG)
		else imageHelper.getLogoImageUrl(item, 600)
		if (state.live && (item?.id != previous?.id || state.liveProgram == null)) {
			if (item?.id != previous?.id) state.liveProgram = null
			watchLiveProgram()
		}
		refreshPlayState()
	}

	/** Live TV: the programme on now, read again when it ends */
	private fun watchLiveProgram() {
		val channelId = state.item?.id ?: return
		liveJob?.cancel()
		liveJob = scope.launch {
			while (true) {
				val program = try {
					withContext(Dispatchers.IO) { api.liveTvApi.getChannel(channelId).content.currentProgram }
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Unable to read the programme on now")
					null
				}
				if (state.item?.id != channelId) return@launch
				if (program != null) state.liveProgram = program
				refreshPlayState()
				// Once it ends, the next one (at most a few minutes, the guide can change)
				val untilEnd = program?.endDate?.let { java.time.Duration.between(LiveTvClock.now(), it).toMillis() } ?: 0L
				delay((untilEnd + LIVE_END_MARGIN_MS).coerceIn(LIVE_MIN_REFRESH_MS, LIVE_MAX_REFRESH_MS))
			}
		}
	}

	/** Live TV: where the programme on now is (from its start to its end, by the clock) */
	private fun refreshLivePosition(): Boolean {
		if (!state.live) return false
		val program = state.liveProgram
		val start = program?.startDate
		val end = program?.endDate
		if (start == null || end == null) {
			state.positionMs = 0
			state.durationMs = 0
			return true
		}
		// The server's time: the TV's clock may not match it (LiveTvClock)
		val now = LiveTvClock.now()
		state.durationMs = java.time.Duration.between(start, end).toMillis().coerceAtLeast(0)
		state.positionMs = java.time.Duration.between(start, now).toMillis().coerceIn(0, state.durationMs)
		return true
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
		if (!refreshLivePosition()) state.durationMs = controller.duration.coerceAtLeast(0)
		state.canSeek = controller.canSeek()
		state.hasNext = controller.hasNextItem()
		state.hasPrevious = controller.hasPreviousItem()
		state.mediaSourceId = controller.currentMediaSource?.id
	}

	// Actions from the controls

	fun refreshPosition() {
		val controller = controller ?: return
		if (!state.live) state.positionMs = controller.currentPosition
		refreshPlayState()
	}

	fun showGuide() {
		state.panelOpen = true
		onShowGuide()
	}

	fun showChannels() {
		state.panelOpen = true
		onShowChannels()
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

	/** The subtitles go up above the controls (top of the seek bar in pixels), back down with null */
	fun setControlsTop(topPx: Float?) {
		controller?.raiseSubtitlesAbove(topPx ?: -1f)
	}

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

	/** Direct play, remux, audio or full conversion by the server: kept for the whole series */
	fun playbackModeOptions(): List<PlayerOption> {
		val controller = controller ?: return emptyList()
		val current = controller.getPlaybackFallbackLevel()
		return listOf(
			PlaybackFallback.DIRECT to R.string.modern_player_mode_direct,
			PlaybackFallback.REMUX to R.string.modern_player_mode_remux,
			PlaybackFallback.TRANSCODE_AUDIO to R.string.modern_player_mode_audio,
			PlaybackFallback.TRANSCODE_ALL to R.string.modern_player_mode_all,
		).map { (level, label) ->
			PlayerOption(
				label = context.getString(label),
				selected = level == current,
				onSelect = { if (level != controller.getPlaybackFallbackLevel()) controller.setPlaybackFallbackLevel(level) },
			)
		} + listOfNotNull(
			// Dolby Vision files: their HDR10 layer instead (on/off, also kept for the series)
			if (controller.isDolbyVisionVideo()) {
				val enabled = controller.isDolbyVisionAsHdr10()
				PlayerOption(
					label = context.getString(R.string.modern_player_mode_dolby_vision),
					selected = enabled,
					onSelect = { controller.setDolbyVisionAsHdr10(!enabled) },
				)
			} else null
		)
	}

	/** How the current stream plays, null before it starts */
	fun playMethod(): PlayMethod? = controller?.currentStreamInfo?.playMethod

	/** The stream info panel (☰): [transcoding] is what the server says about the stream */
	fun streamInfo(transcoding: TranscodingInfo?): List<StreamInfoSection> {
		val controller = controller ?: return emptyList()
		val quality = getQualityProfiles(context)[qualityController.currentQuality]
		return StreamInfoBuilder(context).build(controller, transcoding, quality)
	}

	/** How the current stream plays */
	fun playMethodLabel(): String = context.getString(
		when (controller?.currentStreamInfo?.playMethod) {
			PlayMethod.DIRECT_PLAY -> R.string.modern_player_method_direct
			PlayMethod.DIRECT_STREAM -> R.string.modern_player_method_stream
			else -> R.string.modern_player_method_transcode
		}
	)

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
private const val CHANNEL_LOGO_HEIGHT = 120
/** Live TV: the next programme is read this long after the current one ends */
private const val LIVE_END_MARGIN_MS = 5_000L
private const val LIVE_MIN_REFRESH_MS = 15_000L
private const val LIVE_MAX_REFRESH_MS = 5 * 60_000L
