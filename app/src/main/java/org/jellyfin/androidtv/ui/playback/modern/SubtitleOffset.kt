package org.jellyfin.androidtv.ui.playback.modern

import android.content.Context
import android.os.Looper
import java.nio.ByteBuffer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.decoder.DecoderInputBuffer
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import org.jellyfin.androidtv.ui.playback.PlaybackFallback

/**
 * Subtitle timing offset of the current playback, changed from the player controls.
 * Positive values show the subtitles later, negative values earlier.
 */
object SubtitleOffset {
	@Volatile
	var offsetMs: Long = 0L

	fun reset() {
		offsetMs = 0L
	}
}

/**
 * Renderers factory that wraps the subtitle renderers so they follow [SubtitleOffset].
 */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderersFactory(
	context: Context,
	private val dolbyVisionAsHdr10: Boolean = false,
) : DefaultRenderersFactory(context) {
	init {
		// Dolby Vision profile 8 files have an HDR10 base layer: without Dolby Vision decoders the
		// player decodes them as HEVC HDR10 (fixes a black screen with some DV + HDR10+ files). The
		// setting, or the playback fallback for the current stream (read on every decoder choice)
		setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
			val hdr10 = dolbyVisionAsHdr10 || PlaybackFallback.decodeDolbyVisionAsHdr10
			if (hdr10 && mimeType == MimeTypes.VIDEO_DOLBY_VISION) emptyList()
			else MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
		}
	}

	/**
	 * The video renderer of the factory, replaced by one that also removes the Dolby Vision
	 * metadata from the frames while Dolby Vision plays as HDR10 (see [DolbyVisionAsHdr10VideoRenderer]).
	 */
	override fun buildVideoRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		eventHandler: Handler,
		eventListener: VideoRendererEventListener,
		allowedVideoJoiningTimeMs: Long,
		out: ArrayList<Renderer>,
	) {
		val start = out.size
		super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
		val index = (start until out.size).firstOrNull { out[it] is MediaCodecVideoRenderer } ?: return
		out[index] = DolbyVisionAsHdr10VideoRenderer(
			MediaCodecVideoRenderer.Builder(context)
				.setCodecAdapterFactory(codecAdapterFactory)
				.setMediaCodecSelector(mediaCodecSelector)
				.setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
				.setEnableDecoderFallback(enableDecoderFallback)
				.setEventHandler(eventHandler)
				.setEventListener(eventListener)
				.setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
			stripAlways = dolbyVisionAsHdr10,
		)
	}

	override fun buildTextRenderers(
		context: Context,
		output: TextOutput,
		outputLooper: Looper,
		extensionRendererMode: Int,
		out: ArrayList<Renderer>,
	) {
		val start = out.size
		super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
		for (index in start until out.size) out[index] = SubtitleOffsetRenderer(out[index])
	}
}

/**
 * Subtitle renderer that renders at a shifted position: with an offset of +500 ms the cue that
 * should show at 10.0 s shows at 10.5 s. Showing subtitles earlier works as long as the subtitle
 * samples are already buffered, which is the case for external subtitles and normal offsets.
 */
@OptIn(UnstableApi::class)
private class SubtitleOffsetRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
	override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
		val shiftedPositionUs = (positionUs - SubtitleOffset.offsetMs * 1000).coerceAtLeast(0)
		super.render(shiftedPositionUs, elapsedRealtimeUs)
	}
}

/**
 * Video renderer that, while Dolby Vision plays as HDR10 (the setting, [stripAlways], or the
 * playback fallback), removes the Dolby Vision metadata of each frame (the RPU, NAL unit type 62,
 * and an enhancement layer, 63) before the HEVC decoder gets it. Decoders should ignore those NAL
 * units, but on a Fire TV some frames came out with blocks of older frames.
 */
@OptIn(UnstableApi::class)
private class DolbyVisionAsHdr10VideoRenderer(
	builder: MediaCodecVideoRenderer.Builder,
	private val stripAlways: Boolean,
) : MediaCodecVideoRenderer(builder) {
	override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
		super.onQueueInputBuffer(buffer)
		val data = buffer.data ?: return
		if (!stripAlways && !PlaybackFallback.decodeDolbyVisionAsHdr10) return
		if (data.isReadOnly || codecInfo?.mimeType != MimeTypes.VIDEO_H265) return
		DolbyVisionNalUnits.stripTrailing(data)
	}
}

/** Dolby Vision NAL units in HEVC access units with start codes (what the extractors give) */
internal object DolbyVisionNalUnits {
	private const val NAL_UNIT_TYPE_RPU = 62
	private const val NAL_UNIT_TYPE_EL = 63
	private const val MAX_TRAILING_UNITS = 3

	/**
	 * How far back a NAL unit is looked for. An RPU is a few hundred bytes; the slices before it
	 * are much bigger and aren't read through (it runs for every frame).
	 */
	private const val MAX_UNIT_SIZE = 16 * 1024

	/**
	 * Cut the Dolby Vision NAL units at the end of the access unit (where the RPU always is) by
	 * moving the limit of [data] back. Only the end is read: no copy of the frame.
	 */
	fun stripTrailing(data: ByteBuffer) {
		val start = data.position()
		if (!startsWithStartCode(data, start)) return
		var end = data.limit()
		repeat(MAX_TRAILING_UNITS) {
			val startCode = lastStartCode(data, maxOf(start, end - MAX_UNIT_SIZE), end) ?: return
			val header = startCode + 3
			if (header >= end) return
			val type = (data.get(header).toInt() shr 1) and 0x3F
			if (type != NAL_UNIT_TYPE_RPU && type != NAL_UNIT_TYPE_EL) return
			// A 4-byte start code: its first zero goes too
			val cut = if (startCode > start && data.get(startCode - 1) == ZERO) startCode - 1 else startCode
			// Never the whole frame
			if (cut <= start) return
			end = cut
			data.limit(end)
		}
	}

	private fun startsWithStartCode(data: ByteBuffer, start: Int): Boolean {
		if (data.limit() - start < 4) return false
		if (data.get(start) != ZERO || data.get(start + 1) != ZERO) return false
		return data.get(start + 2) == ONE || (data.get(start + 2) == ZERO && data.get(start + 3) == ONE)
	}

	/** Index of the last 00 00 01 between [from] and [end] (payloads can't contain it: emulation prevention) */
	private fun lastStartCode(data: ByteBuffer, from: Int, end: Int): Int? {
		var index = end - 3
		while (index >= from) {
			if (data.get(index + 2) == ONE && data.get(index + 1) == ZERO && data.get(index) == ZERO) return index
			index--
		}
		return null
	}

	private const val ZERO: Byte = 0
	private const val ONE: Byte = 1
}
