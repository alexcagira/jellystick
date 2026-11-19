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
import org.jellyfin.androidtv.util.profile.KnownDefects

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
			hideHdr10PlusFromDolbyVision = KnownDefects.hevcDoviHdr10PlusHandledByPlayer,
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
 * Video renderer that fixes the frames of Dolby Vision files before the decoder gets them:
 * - While Dolby Vision plays as HDR10 (the setting, [stripAlways], or the playback fallback), it
 *   removes the Dolby Vision metadata of each frame (the RPU, NAL unit type 62, and an enhancement
 *   layer, 63) before the HEVC decoder gets it. Decoders should ignore those NAL units, but on a
 *   Fire TV some frames came out with blocks of older frames.
 * - While Dolby Vision plays as Dolby Vision on a device that gives a black screen with Dolby
 *   Vision + HDR10+ files ([hideHdr10PlusFromDolbyVision], Fire TV 4K / 4K Max), it hides the HDR10+
 *   metadata of each frame from the Dolby Vision decoder (see [Hdr10PlusSei]).
 */
@OptIn(UnstableApi::class)
private class DolbyVisionAsHdr10VideoRenderer(
	builder: MediaCodecVideoRenderer.Builder,
	private val stripAlways: Boolean,
	private val hideHdr10PlusFromDolbyVision: Boolean,
) : MediaCodecVideoRenderer(builder) {
	private val hdr10PlusSei = Hdr10PlusSei()

	override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
		super.onQueueInputBuffer(buffer)
		val data = buffer.data ?: return
		if (data.isReadOnly) return
		when (codecInfo?.mimeType) {
			MimeTypes.VIDEO_H265 -> if (stripAlways || PlaybackFallback.decodeDolbyVisionAsHdr10) DolbyVisionNalUnits.stripTrailing(data)
			MimeTypes.VIDEO_DOLBY_VISION -> if (hideHdr10PlusFromDolbyVision) hdr10PlusSei.hide(data)
		}
	}
}

/**
 * HDR10+ metadata in HEVC frames: an SEI message (user data registered by ITU-T T.35, payload type
 * 4) with the HDR10+ signature (country 0xB5, provider 0x003C, oriented code 0x0001, application 4).
 *
 * Files with both Dolby Vision (profile 8) and HDR10+ give a black screen on the Fire TV 4K / 4K
 * Max when the Dolby Vision decoder gets the HDR10+ messages; without them it plays Dolby Vision
 * (what Plex does). Each HDR10+ message is turned into an unregistered user data message (payload
 * type 5), which decoders skip: one byte changed in place, the frame isn't copied or resized.
 * Dolby Vision doesn't use HDR10+, nothing is lost.
 */
internal class Hdr10PlusSei {
	// The SEI unit without its emulation prevention bytes, and where each byte is in the frame
	private var rbsp = ByteArray(INITIAL_SIZE)
	private var positions = IntArray(INITIAL_SIZE)

	/** Hide the HDR10+ messages of the access unit in [data] (Annex B, with start codes). Returns how many */
	fun hide(data: ByteBuffer): Int {
		val start = data.position()
		val end = data.limit()
		val scanEnd = minOf(end, start + MAX_SCAN)
		var hidden = 0
		var index = start
		while (true) {
			val startCode = nextStartCode(data, index, scanEnd) ?: break
			val header = startCode + 3
			if (header + 2 >= end) break
			val type = (data.get(header).toInt() shr 1) and 0x3F
			// The SEI messages come before the first slice of the frame
			if (type < FIRST_NON_VCL_TYPE) break
			val unitEnd = nextStartCode(data, header + 2, end) ?: end
			if (type == NAL_UNIT_TYPE_PREFIX_SEI) hidden += hideInUnit(data, header + 2, unitEnd)
			index = unitEnd
		}
		return hidden
	}

	/** The SEI messages of one NAL unit (payload from [from] to [to]) */
	private fun hideInUnit(data: ByteBuffer, from: Int, to: Int): Int {
		val length = unescape(data, from, to)
		var hidden = 0
		var p = 0
		// Until the trailing bits (0x80) of the unit
		while (p < length - 1) {
			var payloadType = 0
			while (p < length && rbsp[p] == FF) { payloadType += 255; p++ }
			if (p >= length) break
			val typeIndex = p
			payloadType += rbsp[p].toInt() and 0xFF
			p++
			var payloadSize = 0
			while (p < length && rbsp[p] == FF) { payloadSize += 255; p++ }
			if (p >= length) break
			payloadSize += rbsp[p].toInt() and 0xFF
			p++
			if (p + payloadSize > length) break
			if (payloadType == PAYLOAD_TYPE_T35 && payloadSize >= MIN_UNREGISTERED_SIZE && isHdr10Plus(p)) {
				data.put(positions[typeIndex], PAYLOAD_TYPE_UNREGISTERED)
				hidden++
			}
			p += payloadSize
		}
		return hidden
	}

	private fun isHdr10Plus(at: Int): Boolean {
		for (i in SIGNATURE.indices) if (rbsp[at + i] != SIGNATURE[i]) return false
		return true
	}

	/** Copy the unit without its emulation prevention bytes (00 00 03), keeping where each byte was */
	private fun unescape(data: ByteBuffer, from: Int, to: Int): Int {
		val size = to - from
		if (rbsp.size < size) {
			rbsp = ByteArray(size)
			positions = IntArray(size)
		}
		var length = 0
		var zeros = 0
		for (i in from until to) {
			val byte = data.get(i)
			if (zeros >= 2 && byte == THREE) {
				zeros = 0
				continue
			}
			zeros = if (byte == ZERO) zeros + 1 else 0
			rbsp[length] = byte
			positions[length] = i
			length++
		}
		return length
	}

	/** Index of the next 00 00 01 from [from] (before [end]) */
	private fun nextStartCode(data: ByteBuffer, from: Int, end: Int): Int? {
		var index = from
		while (index + 2 < end) {
			if (data.get(index + 2) == ONE && data.get(index + 1) == ZERO && data.get(index) == ZERO) return index
			index++
		}
		return null
	}

	private companion object {
		const val NAL_UNIT_TYPE_PREFIX_SEI = 39
		/** NAL unit types below this one are slices */
		const val FIRST_NON_VCL_TYPE = 32
		const val PAYLOAD_TYPE_T35 = 4
		const val PAYLOAD_TYPE_UNREGISTERED: Byte = 5
		/** A user data unregistered message has a 16 byte UUID */
		const val MIN_UNREGISTERED_SIZE = 16
		/** How far into a frame the SEI messages are looked for (they come before the slices) */
		const val MAX_SCAN = 64 * 1024
		const val INITIAL_SIZE = 1024
		val SIGNATURE = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04)
		const val FF: Byte = -1
		const val ZERO: Byte = 0
		const val ONE: Byte = 1
		const val THREE: Byte = 3
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
