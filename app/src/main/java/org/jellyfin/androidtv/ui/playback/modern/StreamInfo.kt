package org.jellyfin.androidtv.ui.playback.modern

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackFallback
import org.jellyfin.androidtv.ui.playback.PlayerStats
import org.jellyfin.sdk.model.api.HardwareAccelerationType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.TranscodingInfo
import org.jellyfin.sdk.model.api.VideoRangeType
import java.net.URLDecoder
import java.util.Locale

/** A block of the stream info panel ("Video", "Audio"...) */
data class StreamInfoSection(val title: String, val rows: List<StreamInfoRow>)

/** One line: what ("Original") and its value; [warning] for what costs the server work */
data class StreamInfoRow(val label: String, val value: String, val warning: Boolean = false)

/**
 * What the stream info panel shows (☰ in the player): how the file plays (direct, remux,
 * transcoding and why), the file, its video, audio and subtitles as they are and as they reach
 * the device, the decoders of the device and the buffer. Like the "playback info" of the Jellyfin
 * web client and Plex's stats.
 */
internal class StreamInfoBuilder(private val context: Context) {
	fun build(
		controller: PlaybackController,
		transcoding: TranscodingInfo?,
		qualityLabel: String?,
	): List<StreamInfoSection> {
		val streamInfo = controller.currentStreamInfo
		val source = controller.currentMediaSource
		val streams = source?.mediaStreams.orEmpty()
		val stats = controller.playerStats
		val method = streamInfo?.playMethod
		// What the server was asked to make: the transcoding URL carries it (and why)
		val params = if (method == PlayMethod.DIRECT_PLAY) emptyMap() else queryParameters(streamInfo?.mediaUrl)

		val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
		val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == controller.audioStreamIndex }
			?: streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault }
			?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
		val subtitleIndex = controller.subtitleStreamIndex
		val subtitle = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == subtitleIndex }

		return listOfNotNull(
			playback(controller, method, transcoding, params),
			file(source?.path, source?.container, source?.size, source?.bitrate),
			video(video, transcoding, params, method, stats),
			audio(audio, transcoding, params, method, stats),
			subtitles(subtitle, subtitleIndex, streamInfo?.subtitleDeliveryMethod),
			connection(stats, qualityLabel),
		).filter { it.rows.isNotEmpty() }
	}

	// Sections

	private fun playback(
		controller: PlaybackController,
		method: PlayMethod?,
		transcoding: TranscodingInfo?,
		params: Map<String, String>,
	): StreamInfoSection {
		val rows = mutableListOf<StreamInfoRow>()
		rows += StreamInfoRow(
			label = string(R.string.stream_info_method),
			value = string(
				when (method) {
					PlayMethod.DIRECT_PLAY -> R.string.stream_info_method_direct
					PlayMethod.DIRECT_STREAM -> R.string.stream_info_method_stream
					null -> R.string.stream_info_unknown
					else -> R.string.stream_info_method_transcode
				}
			),
			warning = method == PlayMethod.TRANSCODE,
		)

		val reasons = transcoding?.transcodeReasons?.map { it.serialName }?.takeIf { it.isNotEmpty() }
			?: params["transcodereasons"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
		if (method != PlayMethod.DIRECT_PLAY && !reasons.isNullOrEmpty()) {
			rows += StreamInfoRow(string(R.string.stream_info_reason), reasons.map(::reasonLabel).distinct().joinToString(", "), warning = true)
		}

		// What the app asks for after a file failed (Playback mode), kept for the whole series
		val level = controller.getPlaybackFallbackLevel()
		val mode = listOfNotNull(
			when (level) {
				PlaybackFallback.REMUX -> string(R.string.modern_player_mode_remux)
				PlaybackFallback.TRANSCODE_AUDIO -> string(R.string.modern_player_mode_audio)
				PlaybackFallback.TRANSCODE_ALL -> string(R.string.modern_player_mode_all)
				else -> null
			},
			if (controller.isDolbyVisionAsHdr10()) string(R.string.modern_player_mode_dolby_vision) else null,
		)
		if (mode.isNotEmpty()) rows += StreamInfoRow(string(R.string.stream_info_mode), mode.joinToString(" · "))

		// The server converting: how far ahead it is, how fast and with what
		if (transcoding != null && method != PlayMethod.DIRECT_PLAY) {
			val server = listOfNotNull(
				transcoding.completionPercentage?.let { String.format(locale(), "%.0f %%", it) },
				transcoding.framerate?.takeIf { it > 0f && !(transcoding.isVideoDirect) }?.let { String.format(locale(), "%.0f fps", it) },
				if (transcoding.isVideoDirect) null else hardwareLabel(transcoding.hardwareAccelerationType),
			)
			if (server.isNotEmpty()) rows += StreamInfoRow(string(R.string.stream_info_server), server.joinToString(" · "))
		}

		return StreamInfoSection(string(R.string.stream_info_playback), rows)
	}

	private fun file(path: String?, container: String?, size: Long?, bitrate: Int?): StreamInfoSection {
		val name = path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
		val format = listOfNotNull(
			container?.substringBefore(',')?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() },
			size?.takeIf { it > 0 }?.let(::formatSize),
			bitrate?.takeIf { it > 0 }?.let(::formatBitrate),
		)
		return StreamInfoSection(
			string(R.string.stream_info_file),
			listOfNotNull(
				name?.let { StreamInfoRow(string(R.string.stream_info_name), it) },
				format.takeIf { it.isNotEmpty() }?.let { StreamInfoRow(string(R.string.stream_info_format), it.joinToString(" · ")) },
			),
		)
	}

	private fun video(
		stream: MediaStream?,
		transcoding: TranscodingInfo?,
		params: Map<String, String>,
		method: PlayMethod?,
		stats: PlayerStats?,
	): StreamInfoSection {
		val rows = mutableListOf<StreamInfoRow>()
		if (stream != null) {
			rows += StreamInfoRow(
				string(R.string.stream_info_original),
				listOfNotNull(
					codecName(stream.codec, stream.profile),
					stream.bitDepth?.takeIf { it > 0 }?.let { "$it bit" },
					rangeLabel(stream),
				).joinToString(" · "),
			)
			val picture = listOfNotNull(
				resolution(stream.width, stream.height),
				(stream.realFrameRate ?: stream.averageFrameRate)?.takeIf { it > 0f }?.let(::formatFrameRate),
				stream.bitRate?.takeIf { it > 0 }?.let(::formatBitrate),
				if (stream.isInterlaced) string(R.string.stream_info_interlaced) else null,
			)
			if (picture.isNotEmpty()) rows += StreamInfoRow(string(R.string.stream_info_picture), picture.joinToString(" · "))
		}

		// Converted by the server: what it sends instead (without the session, from what was asked)
		val converted = method == PlayMethod.TRANSCODE && when {
			transcoding != null -> !transcoding.isVideoDirect
			else -> params["videocodec"] != null && params["allowvideostreamcopy"] != "true"
		}
		if (converted) {
			val sent = listOfNotNull(
				codecName(transcoding?.videoCodec ?: params["videocodec"]?.substringBefore(','), null),
				resolution(transcoding?.width, transcoding?.height)
					?: params["maxheight"]?.let { "≤ ${it}p" },
				(transcoding?.bitrate ?: params["videobitrate"]?.toIntOrNull())?.takeIf { it > 0 }?.let(::formatBitrate),
			)
			if (sent.isNotEmpty()) rows += StreamInfoRow(string(R.string.stream_info_sent), sent.joinToString(" · "), warning = true)
		}

		if (stats != null && (stats.videoDecoder != null || stats.width > 0)) {
			val device = listOfNotNull(
				stats.videoDecoder,
				resolution(stats.width, stats.height),
				stats.frameRate.takeIf { it > 0f }?.let(::formatFrameRate),
			)
			if (device.isNotEmpty()) rows += StreamInfoRow(string(R.string.stream_info_device), device.joinToString(" · "))
			rows += StreamInfoRow(string(R.string.stream_info_dropped), stats.droppedFrames.coerceAtLeast(0).toString(), warning = stats.droppedFrames > DROPPED_FRAMES_WARNING)
		}

		return StreamInfoSection(string(R.string.stream_info_video), rows)
	}

	private fun audio(
		stream: MediaStream?,
		transcoding: TranscodingInfo?,
		params: Map<String, String>,
		method: PlayMethod?,
		stats: PlayerStats?,
	): StreamInfoSection {
		val rows = mutableListOf<StreamInfoRow>()
		if (stream != null) {
			rows += StreamInfoRow(
				string(R.string.stream_info_track),
				listOfNotNull(
					languageName(stream.language),
					stream.title?.takeIf { it.isNotBlank() && !it.equals(stream.language, ignoreCase = true) },
				).joinToString(" · ").ifEmpty { stream.displayTitle.orEmpty() },
			)
			rows += StreamInfoRow(
				string(R.string.stream_info_original),
				listOfNotNull(
					codecName(stream.codec, stream.profile),
					stream.channelLayout?.takeIf { it.isNotBlank() } ?: stream.channels?.let(::channelsLabel),
					stream.bitRate?.takeIf { it > 0 }?.let(::formatBitrate),
					stream.sampleRate?.takeIf { it > 0 }?.let(::formatSampleRate),
				).joinToString(" · "),
			)
		}

		val audioConverted = method == PlayMethod.TRANSCODE && when {
			transcoding != null -> !transcoding.isAudioDirect
			else -> params["audiocodec"] != null && params["allowaudiostreamcopy"] != "true"
		}
		if (audioConverted) {
			val sent = listOfNotNull(
				codecName(transcoding?.audioCodec ?: params["audiocodec"]?.substringBefore(','), null),
				(transcoding?.audioChannels ?: params["transcodingmaxaudiochannels"]?.toIntOrNull() ?: params["maxaudiochannels"]?.toIntOrNull())?.let(::channelsLabel),
			)
			if (sent.isNotEmpty()) rows += StreamInfoRow(string(R.string.stream_info_sent), sent.joinToString(" · "), warning = true)
		}

		if (stats != null && stats.audioMimeType != null) {
			// No decoder opened: the audio goes as it is to the TV or the sound bar
			val device = stats.audioDecoder ?: string(R.string.stream_info_passthrough)
			val format = listOfNotNull(
				device,
				stats.audioChannels.takeIf { it > 0 }?.let(::channelsLabel),
			)
			rows += StreamInfoRow(string(R.string.stream_info_device), format.joinToString(" · "))
		}

		return StreamInfoSection(string(R.string.stream_info_audio), rows)
	}

	private fun subtitles(stream: MediaStream?, index: Int, deliveryMethod: SubtitleDeliveryMethod?): StreamInfoSection {
		if (index < 0 || stream == null) {
			return StreamInfoSection(string(R.string.stream_info_subtitles), listOf(StreamInfoRow(string(R.string.stream_info_track), string(R.string.stream_info_subtitles_off))))
		}
		val delivery = stream.deliveryMethod ?: deliveryMethod
		val how = when {
			delivery == SubtitleDeliveryMethod.ENCODE -> string(R.string.stream_info_subtitles_burned)
			stream.isExternal || delivery == SubtitleDeliveryMethod.EXTERNAL -> string(R.string.stream_info_subtitles_external)
			else -> string(R.string.stream_info_subtitles_embedded)
		}
		return StreamInfoSection(
			string(R.string.stream_info_subtitles),
			listOf(
				StreamInfoRow(
					string(R.string.stream_info_track),
					listOfNotNull(
						languageName(stream.language),
						stream.title?.takeIf { it.isNotBlank() && !it.equals(stream.language, ignoreCase = true) },
						if (stream.isForced) string(R.string.stream_info_forced) else null,
					).joinToString(" · ").ifEmpty { stream.displayTitle.orEmpty() },
				),
				StreamInfoRow(
					string(R.string.stream_info_format),
					listOfNotNull(stream.codec?.uppercase(Locale.ROOT), how).joinToString(" · "),
					warning = delivery == SubtitleDeliveryMethod.ENCODE,
				),
			),
		)
	}

	private fun connection(stats: PlayerStats?, qualityLabel: String?): StreamInfoSection = StreamInfoSection(
		string(R.string.stream_info_connection),
		listOfNotNull(
			stats?.let { StreamInfoRow(string(R.string.stream_info_buffer), String.format(locale(), "%.0f s", it.bufferedMs / 1000f)) },
			qualityLabel?.let { StreamInfoRow(string(R.string.stream_info_quality), it) },
		),
	)

	// Formatting

	private fun string(id: Int): String = context.getString(id)

	private fun locale(): Locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()

	private fun codecName(codec: String?, profile: String?): String? {
		val name = when (val lower = codec?.lowercase(Locale.ROOT)?.trim()) {
			null, "" -> return null
			"h264", "avc", "avc1" -> "H.264"
			"hevc", "h265", "hvc1", "hev1" -> "HEVC"
			"av1" -> "AV1"
			"vp9" -> "VP9"
			"mpeg2video" -> "MPEG-2"
			"eac3" -> "E-AC3"
			"ac3" -> "AC3"
			"truehd" -> "TrueHD"
			"dts" -> "DTS"
			"aac" -> "AAC"
			"flac" -> "FLAC"
			"opus" -> "Opus"
			"mp3" -> "MP3"
			else -> lower.uppercase(Locale.ROOT)
		}
		val cleanProfile = profile?.trim()?.takeIf { it.isNotEmpty() && !it.equals(codec, ignoreCase = true) }
		return if (cleanProfile != null) "$name $cleanProfile" else name
	}

	private fun rangeLabel(stream: MediaStream): String? {
		val type = stream.videoRangeType ?: return null
		val name = type.serialName
		return when {
			name.startsWith("DOVI", ignoreCase = true) -> listOfNotNull(
				"Dolby Vision",
				stream.dvProfile?.let { profile -> stream.dvBlSignalCompatibilityId?.let { "$profile.$it" } ?: "$profile" },
				when (type) {
					VideoRangeType.DOVI_WITH_HDR10 -> "HDR10"
					VideoRangeType.DOVI_WITH_HDR10_PLUS, VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> "HDR10+"
					VideoRangeType.DOVI_WITH_HLG -> "HLG"
					VideoRangeType.DOVI_WITH_SDR -> "SDR"
					else -> null
				}?.let { "+ $it" },
			).joinToString(" ")
			name.equals("HDR10Plus", ignoreCase = true) -> "HDR10+"
			name.equals("Unknown", ignoreCase = true) -> null
			else -> name
		}
	}

	private fun resolution(width: Int?, height: Int?): String? =
		if (width != null && height != null && width > 0 && height > 0) "$width×$height" else null

	/** "24 fps", "23.976 fps" (with the decimal separator of the language) */
	private fun formatFrameRate(fps: Float): String {
		val text = String.format(locale(), "%.3f", fps).trimEnd('0').trimEnd('.', ',')
		return "$text fps"
	}

	private fun formatSampleRate(hertz: Int): String =
		if (hertz % 1000 == 0) "${hertz / 1000} kHz" else String.format(locale(), "%.1f kHz", hertz / 1000f)

	private fun formatBitrate(bitsPerSecond: Int): String = when {
		bitsPerSecond >= 1_000_000 -> String.format(locale(), "%.1f Mbps", bitsPerSecond / 1_000_000f)
		else -> String.format(locale(), "%d kbps", bitsPerSecond / 1000)
	}

	private fun formatSize(bytes: Long): String = when {
		bytes >= 1L shl 30 -> String.format(locale(), "%.2f GB", bytes / (1L shl 30).toDouble())
		else -> String.format(locale(), "%.0f MB", bytes / (1L shl 20).toDouble())
	}

	private fun channelsLabel(channels: Int): String = when (channels) {
		1 -> "1.0"
		2 -> "2.0"
		6 -> "5.1"
		7 -> "6.1"
		8 -> "7.1"
		else -> "$channels ch"
	}

	private fun languageName(code: String?): String? {
		val tag = code?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) } ?: return null
		val name = Locale.forLanguageTag(tag).getDisplayLanguage(locale())
		return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
			?.replaceFirstChar { it.titlecase(locale()) }
			?: tag
	}

	private fun hardwareLabel(type: HardwareAccelerationType?): String = when (type) {
		null, HardwareAccelerationType.NONE -> string(R.string.stream_info_software)
		HardwareAccelerationType.V_4L_2M_2M -> "V4L2"
		else -> type.serialName.uppercase(Locale.ROOT)
	}

	/** Why the server converts, in words (TranscodeReason names) */
	private fun reasonLabel(reason: String): String = when (reason) {
		"ContainerNotSupported" -> string(R.string.stream_info_reason_container)
		"VideoCodecNotSupported", "VideoCodecTagNotSupported" -> string(R.string.stream_info_reason_video_codec)
		"AudioCodecNotSupported", "SecondaryAudioNotSupported", "AudioIsExternal" -> string(R.string.stream_info_reason_audio_codec)
		"SubtitleCodecNotSupported" -> string(R.string.stream_info_reason_subtitles)
		"AudioChannelsNotSupported" -> string(R.string.stream_info_reason_audio_channels)
		"ContainerBitrateExceedsLimit", "VideoBitrateNotSupported", "AudioBitrateNotSupported" -> string(R.string.stream_info_reason_bitrate)
		"VideoProfileNotSupported", "VideoLevelNotSupported", "RefFramesNotSupported" -> string(R.string.stream_info_reason_video_profile)
		"VideoResolutionNotSupported" -> string(R.string.stream_info_reason_resolution)
		"VideoBitDepthNotSupported" -> string(R.string.stream_info_reason_bit_depth)
		"VideoRangeTypeNotSupported" -> string(R.string.stream_info_reason_video_range)
		"AudioProfileNotSupported", "AudioSampleRateNotSupported", "AudioBitDepthNotSupported" -> string(R.string.stream_info_reason_audio_format)
		"DirectPlayError" -> string(R.string.stream_info_reason_direct_play_error)
		// "InterlacedVideoNotSupported" → "interlaced video not supported"
		else -> reason.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase(Locale.ROOT)
	}

	/** The query of a URL, keys in lowercase */
	private fun queryParameters(url: String?): Map<String, String> {
		val query = url?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return emptyMap()
		return query.split('&').mapNotNull { part ->
			val key = part.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
			key.lowercase(Locale.ROOT) to value
		}.toMap()
	}
}

/** More dropped frames than this since the stream started are shown in red */
private const val DROPPED_FRAMES_WARNING = 50
