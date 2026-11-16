package org.jellyfin.androidtv.ui.playback

import android.content.Context
import android.os.Handler
import androidx.core.content.edit
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue

/**
 * Some files the server says this device plays as they are don't work on it (a codec the Fire TV
 * says it supports but can't decode, an audio format the TV or sound bar refuses...). They are
 * played again with more of the work done by the server, level by level, and the level that
 * worked is kept for the whole series (or the movie) so the next episodes start with it.
 */
class PlaybackFallback(context: Context) {
	companion object {
		/** Direct play when the device can */
		const val DIRECT = 0

		/** No direct play: the server repackages the file, the streams stay as they are */
		const val REMUX = 1

		/** The video as it is, the audio converted by the server */
		const val TRANSCODE_AUDIO = 2

		/** Video and audio converted by the server */
		const val TRANSCODE_ALL = 3

		@JvmStatic
		fun messageOf(level: Int): Int = when (level) {
			REMUX -> R.string.playback_fallback_remux
			TRANSCODE_AUDIO -> R.string.playback_fallback_audio
			else -> R.string.playback_fallback_all
		}

		/**
		 * Next level after a failure. The remux alone rarely fixes anything, and converting the
		 * audio (what Plex does with E-AC3 Atmos on a Fire TV) is cheap for the server: it comes
		 * before converting the video too.
		 */
		@JvmStatic
		fun nextLevel(level: Int): Int = if (level < TRANSCODE_AUDIO) TRANSCODE_AUDIO else TRANSCODE_ALL

		/**
		 * Decode Dolby Vision video as HEVC HDR10 in the current stream: profile 8 files carry an
		 * HDR10 base layer, and some DV (+ HDR10+) files stay black on a Fire TV. Read by the player
		 * each time it picks its decoders, so it applies to the next stream.
		 */
		@Volatile
		@JvmStatic
		var decodeDolbyVisionAsHdr10: Boolean = false

		/** The video of [source] is Dolby Vision (any profile, with or without HDR10/HDR10+) */
		@JvmStatic
		fun isDolbyVision(source: MediaSourceInfo?): Boolean = source?.mediaStreams.orEmpty().any { stream ->
			stream.type == MediaStreamType.VIDEO && stream.videoRangeType?.serialName?.startsWith("DOVI", ignoreCase = true) == true
		}
	}

	private val preferences = context.getSharedPreferences("playback_fallback", Context.MODE_PRIVATE)

	private fun key(item: BaseItemDto) = (item.seriesId ?: item.id).toString()

	/** Level that worked before for the series of [item] (or the item) */
	fun rememberedLevel(item: BaseItemDto?): Int =
		if (item == null) DIRECT else preferences.getInt(key(item), DIRECT).coerceIn(DIRECT, TRANSCODE_ALL)

	/** Keep [level] for the series of [item]; [DIRECT] forgets it */
	fun remember(item: BaseItemDto?, level: Int) {
		if (item == null) return
		preferences.edit {
			if (level <= DIRECT) remove(key(item)) else putInt(key(item), level.coerceAtMost(TRANSCODE_ALL))
		}
	}

	/** Dolby Vision as HDR10 worked for the series of [item] */
	fun rememberedDolbyVision(item: BaseItemDto?): Boolean =
		item != null && preferences.getBoolean(DOLBY_VISION_PREFIX + key(item), false)

	fun rememberDolbyVision(item: BaseItemDto?, enabled: Boolean) {
		if (item == null) return
		preferences.edit {
			if (enabled) putBoolean(DOLBY_VISION_PREFIX + key(item), true) else remove(DOLBY_VISION_PREFIX + key(item))
		}
	}

	/** What the server is asked for at [level] (after the device profile is set) */
	fun apply(options: VideoOptions, level: Int) {
		if (level >= REMUX) options.enableDirectPlay = false
		if (level >= TRANSCODE_AUDIO) options.allowAudioStreamCopy = false
		if (level >= TRANSCODE_ALL) {
			options.enableDirectStream = false
			options.allowVideoStreamCopy = false
			options.profile = options.profile?.let(::compatibilityProfile)
		}
	}

	/**
	 * The last try: what every device plays, H.264 8-bit up to 1080p with AAC audio. Otherwise
	 * the server keeps the size and the codec family the device claims to support: a 4K HEVC
	 * 10-bit file became a 4K stream the device couldn't play either (and too heavy for the
	 * server to make in time).
	 */
	private fun compatibilityProfile(profile: DeviceProfile): DeviceProfile {
		fun lowerThanOrEquals(property: ProfileConditionValue, value: Int) = ProfileCondition(
			condition = ProfileConditionType.LESS_THAN_EQUAL,
			property = property,
			value = value.toString(),
			isRequired = false,
		)

		val compatibleH264 = CodecProfile(
			type = CodecType.VIDEO,
			codec = Codec.Video.H264,
			conditions = listOf(
				lowerThanOrEquals(ProfileConditionValue.WIDTH, MAX_WIDTH),
				lowerThanOrEquals(ProfileConditionValue.HEIGHT, MAX_HEIGHT),
				lowerThanOrEquals(ProfileConditionValue.VIDEO_BIT_DEPTH, 8),
			),
			applyConditions = emptyList(),
		)

		return profile.copy(
			maxStreamingBitrate = minOf(profile.maxStreamingBitrate ?: MAX_BITRATE, MAX_BITRATE),
			transcodingProfiles = profile.transcodingProfiles.map { transcoding ->
				if (transcoding.type != DlnaProfileType.VIDEO) transcoding
				else transcoding.copy(videoCodec = Codec.Video.H264, audioCodec = Codec.Audio.AAC)
			},
			// The size limits of H.264 replaced by the ones above, its profile and level limits stay
			codecProfiles = profile.codecProfiles.filterNot { codecProfile ->
				codecProfile.type == CodecType.VIDEO &&
					codecProfile.codec.orEmpty().split(',').any { it.trim().equals(Codec.Video.H264, ignoreCase = true) } &&
					codecProfile.conditions.any { it.property == ProfileConditionValue.WIDTH || it.property == ProfileConditionValue.HEIGHT }
			} + compatibleH264,
		)
	}
}

private const val DOLBY_VISION_PREFIX = "dv:"
private const val MAX_WIDTH = 1920
private const val MAX_HEIGHT = 1080
private const val MAX_BITRATE = 20_000_000

/**
 * Watches the first seconds of a stream. Some files fail without any error from the player: a black
 * screen (the video decoder never gives a frame) or a still picture with the time not moving (the
 * audio output blocks the clock). [onFailure] says which one, [onStarted] is called once
 * the video plays.
 */
class PlaybackStartWatchdog(
	private val handler: Handler,
	private val videoManager: VideoManager,
	private val hasVideo: Boolean,
	private val isPausedByUser: () -> Boolean,
	private val onStarted: () -> Unit,
	/** true: no picture at all, false: a picture but the time doesn't move */
	private val onFailure: (noPicture: Boolean) -> Unit,
) {
	private var running = false
	private var activeMs = 0L
	private var stalledMs = 0L
	private var progressMs = 0L
	private var lastPosition = -1L

	private val tick = object : Runnable {
		override fun run() {
			if (!running) return
			check()
			if (running) handler.postDelayed(this, TICK_MS)
		}
	}

	fun start() {
		stop()
		activeMs = 0
		stalledMs = 0
		progressMs = 0
		lastPosition = -1
		running = true
		handler.postDelayed(tick, TICK_MS)
	}

	fun stop() {
		running = false
		handler.removeCallbacks(tick)
	}

	private fun check() {
		// Paused by the user: nothing is expected to move
		if (isPausedByUser()) {
			lastPosition = -1
			stalledMs = 0
			return
		}

		activeMs += TICK_MS
		val position = videoManager.playerPosition
		val waiting = videoManager.isWaitingForData
		if (lastPosition >= 0) {
			val delta = position - lastPosition
			// Bigger jumps are seeks (to the resume position), not playback
			if (delta in 1..MAX_TICK_PROGRESS_MS) progressMs += delta
			stalledMs = if (delta != 0L || waiting) 0 else stalledMs + TICK_MS
		}
		lastPosition = position
		val hasFrame = !hasVideo || videoManager.hasRenderedFirstFrame()

		when {
			hasFrame && progressMs >= STARTED_PROGRESS_MS -> {
				stop()
				onStarted()
			}

			// No picture at all: the video can't be decoded here
			!hasFrame && activeMs >= (if (waiting) NO_FRAME_BUFFERING_TIMEOUT_MS else NO_FRAME_TIMEOUT_MS) -> {
				stop()
				onFailure(true)
			}

			// A picture but the time doesn't move while the player is ready: the audio blocks it
			hasFrame && stalledMs >= STALL_TIMEOUT_MS && videoManager.isReadyToPlay -> {
				stop()
				onFailure(false)
			}
		}
	}

	private companion object {
		const val TICK_MS = 1_000L
		const val MAX_TICK_PROGRESS_MS = 2_500L
		const val STARTED_PROGRESS_MS = 3_000L
		const val NO_FRAME_TIMEOUT_MS = 14_000L
		const val NO_FRAME_BUFFERING_TIMEOUT_MS = 30_000L
		const val STALL_TIMEOUT_MS = 10_000L
	}
}
