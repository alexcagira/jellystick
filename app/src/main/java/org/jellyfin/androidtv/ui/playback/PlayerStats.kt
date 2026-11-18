package org.jellyfin.androidtv.ui.playback

/**
 * What the player on the device is doing with the stream, for the stream info panel (☰ in the
 * player). Sizes, rates and counts are -1 when unknown.
 */
data class PlayerStats(
	/** Video as it reaches the decoder: "video/hevc", "video/dolby-vision"... */
	val videoMimeType: String?,
	val videoCodecs: String?,
	val width: Int,
	val height: Int,
	val frameRate: Float,
	val videoBitrate: Int,
	val audioMimeType: String?,
	val audioChannels: Int,
	val audioSampleRate: Int,
	val audioBitrate: Int,
	/** Decoders the player opened ("c2.mtk.hevc.decoder"), null when there is none (audio passthrough) */
	val videoDecoder: String?,
	val audioDecoder: String?,
	/** Frames the device couldn't show in time since the stream started */
	val droppedFrames: Int,
	/** Seconds of video already downloaded ahead of the position, in milliseconds */
	val bufferedMs: Long,
)
