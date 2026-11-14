package org.jellyfin.androidtv.ui.playback.modern

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput

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
	dolbyVisionAsHdr10: Boolean = false,
) : DefaultRenderersFactory(context) {
	init {
		// Dolby Vision profile 8 files have an HDR10 base layer: without Dolby Vision decoders the
		// player decodes them as HEVC HDR10 (fixes a black screen with some DV + HDR10+ files)
		if (dolbyVisionAsHdr10) {
			setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
				if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) emptyList()
				else MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
			}
		}
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
