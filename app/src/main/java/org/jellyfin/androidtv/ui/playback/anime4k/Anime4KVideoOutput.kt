package org.jellyfin.androidtv.ui.playback.anime4k

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import timber.log.Timber

/**
 * Anime4K in the player: while a mode is on, the video goes to a GLSurfaceView of
 * [Anime4KRenderer] put where the player's own SurfaceView is (inside the frame that keeps the
 * aspect ratio, under the subtitles); off, it goes back to the SurfaceView.
 *
 * Only SDR video (HDR and Dolby Vision keep the normal output, the TV does their colours), and the
 * heavier modes only up to the size they are for. The renderer reports when the TV can't keep up
 * (a lighter mode then) or OpenGL doesn't do what it needs (off for this playback).
 *
 * Experimental 4K ([uhd]): the GLSurfaceView's buffer gets the TV's resolution (3840×2160 instead
 * of the 1920×1080 of the app's screen), so a 1080p video goes through Anime4K's x2 too. Whether the
 * Fire TV sends it as 4K or reduces it to 1080p on the way, the test pattern ([showTestPattern])
 * shows. Too slow in 4K: back to 1080p first.
 */
class Anime4KVideoOutput(
	private val context: Context,
	private val playerView: PlayerView,
	private val player: ExoPlayer,
	/** A message for the screen (toast) */
	private val onMessage: (String) -> Unit,
) : Player.Listener {
	private val mainHandler = Handler(Looper.getMainLooper())
	private var glView: GLSurfaceView? = null
	private var renderer: Anime4KRenderer? = null
	private var surface: Surface? = null

	/** The mode chosen for the video */
	var requestedMode: Anime4KMode = Anime4KMode.OFF
		private set

	/** The mode drawing now: lighter than the chosen one for a big video or a slow TV, OFF for HDR */
	var activeMode: Anime4KMode = Anime4KMode.OFF
		private set

	/** The heaviest mode this TV kept up with in this playback */
	private var ceiling: Anime4KMode = Anime4KMode.CNN

	/** OpenGL failed: off until the player is opened again */
	private var failure: String? = null

	/** Experimental: draw at the TV's 4K */
	var uhd: Boolean = false
		set(value) {
			field = value
			if (value) uhdTooSlow = false
			applyBufferSize()
			update()
		}

	/** 4K was too slow in this playback: 1080p until the player opens again */
	private var uhdTooSlow = false

	/** The test pattern is on the screen */
	private var testing = false

	/** Drawing at 4K now */
	val uhdActive: Boolean
		get() = glView != null && wants4K() && uhdScale() != null

	private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyBufferSize() }

	/** The last message, not shown twice for the same video */
	private var lastMessage: String? = null

	init {
		player.addListener(this)
	}

	fun setMode(mode: Anime4KMode) {
		if (mode != requestedMode) lastMessage = null
		requestedMode = mode
		update()
	}

	/** Why the chosen mode isn't the one drawing, for the menu */
	val statusMessage: String?
		get() = lastMessage

	fun release() {
		mainHandler.removeCallbacks(endTest)
		testing = false
		player.removeListener(this)
		detach()
	}

	// Player.Listener

	override fun onVideoSizeChanged(videoSize: VideoSize) = update()

	override fun onTracksChanged(tracks: Tracks) {
		lastMessage = null
		update()
	}

	// Output

	private fun update() {
		val format = player.videoFormat
		val size = player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { TextureSize(it.width, it.height) }
			?: format?.takeIf { it.width > 0 && it.height > 0 }?.let { TextureSize(it.width, it.height) }

		val mode = when {
			requestedMode == Anime4KMode.OFF -> Anime4KMode.OFF
			failure != null -> Anime4KMode.OFF
			// The video isn't known yet: what draws now stays
			format == null || size == null -> return
			isHdr(format) -> {
				message(context.getString(org.jellyfin.androidtv.R.string.anime4k_hdr))
				Anime4KMode.OFF
			}

			else -> {
				var mode = requestedMode.forSourceHeight(size.height)
				while (mode.ordinal > ceiling.ordinal) mode = mode.fallback
				if (mode != requestedMode && mode != Anime4KMode.OFF && requestedMode.maxSourceHeight < size.height) {
					message(context.getString(org.jellyfin.androidtv.R.string.anime4k_reduced, label(requestedMode), label(mode)))
				}
				mode
			}
		}

		activeMode = mode
		// The test pattern needs the view even without a mode
		if (mode == Anime4KMode.OFF && !testing) detach() else attach(mode, size)
	}

	private fun attach(mode: Anime4KMode, size: TextureSize?) {
		val existing = renderer
		if (existing != null) {
			existing.mode = mode
			if (size != null) existing.videoSize = size
			glView?.requestRender()
			return
		}

		val videoSurfaceView = playerView.videoSurfaceView as? SurfaceView ?: return
		val frame = videoSurfaceView.parent as? ViewGroup ?: return
		val view = GLSurfaceView(context)
		val newRenderer = Anime4KRenderer(
			readShader = { name -> context.assets.open("anime4k/$name").bufferedReader().use { it.readText() } },
			listener = object : Anime4KRenderer.Listener {
				override fun onSurfaceReady(surface: Surface) {
					mainHandler.post {
						if (renderer == null) return@post
						this@Anime4KVideoOutput.surface = surface
						player.setVideoSurface(surface)
					}
				}

				override fun onFailure(reason: String) {
					mainHandler.post {
						failure = reason
						message(context.getString(org.jellyfin.androidtv.R.string.anime4k_failed, reason.take(MAX_REASON)))
						update()
					}
				}

				override fun onTooSlow() {
					mainHandler.post {
						// In 4K: 1080p first, the same mode
						if (uhdActive && !testing) {
							uhdTooSlow = true
							message(context.getString(org.jellyfin.androidtv.R.string.anime4k_uhd_too_slow, label(activeMode)))
							applyBufferSize()
							return@post
						}
						val slow = activeMode
						val lighter = slow.fallback
						ceiling = lighter
						message(context.getString(org.jellyfin.androidtv.R.string.anime4k_too_slow, label(slow), label(lighter)))
						update()
					}
				}

				override fun requestRender() {
					glView?.requestRender()
				}
			},
		)
		newRenderer.mode = mode
		newRenderer.videoSize = size

		view.setEGLContextClientVersion(3)
		view.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
		view.preserveEGLContextOnPause = true
		view.setRenderer(newRenderer)
		view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

		if (size != null) newRenderer.videoSize = size

		renderer = newRenderer
		glView = view
		// Right above the player's SurfaceView, under the shutter and the subtitles
		val index = frame.indexOfChild(videoSurfaceView) + 1
		frame.addView(view, index, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		view.addOnLayoutChangeListener(layoutListener)
		videoSurfaceView.visibility = View.GONE
		applyBufferSize()
		Timber.i("Anime4K on: %s for %s", mode, size)
	}

	/**
	 * The 4K test: lines 1 and 2 pixels wide drawn at the TV's resolution for [TEST_PATTERN_MS].
	 * With 4K reaching the TV every band shows lines; reduced to 1080p, the 1-pixel ones turn grey.
	 */
	fun showTestPattern() {
		if (failure != null) return message(context.getString(org.jellyfin.androidtv.R.string.anime4k_failed, failure.orEmpty().take(MAX_REASON)))
		testing = true
		attach(activeMode, null)
		val scale = uhdScale()
		if (scale == null) {
			testing = false
			update()
			return message(context.getString(org.jellyfin.androidtv.R.string.anime4k_uhd_no_4k))
		}
		applyBufferSize()
		renderer?.testPatternUntil = System.nanoTime() + TEST_PATTERN_MS * 1_000_000L
		glView?.requestRender()
		lastMessage = null
		val instructions = context.getString(org.jellyfin.androidtv.R.string.anime4k_uhd_test)
		message(instructions)
		// A toast lasts a few seconds: the instructions stay while the pattern does
		for (delay in listOf(TOAST_MS, 2 * TOAST_MS)) mainHandler.postDelayed({ if (testing) onMessage(instructions) }, delay)
		mainHandler.removeCallbacks(endTest)
		mainHandler.postDelayed(endTest, TEST_PATTERN_MS)
	}

	private val endTest = Runnable {
		testing = false
		renderer?.testPatternUntil = 0L
		applyBufferSize()
		glView?.requestRender()
		// Without a mode the video goes back to its own output
		if (activeMode == Anime4KMode.OFF) detach() else update()
	}

	private fun wants4K() = testing || (uhd && !uhdTooSlow)

	/**
	 * How much bigger the TV's resolution is than the app's screen (2 for a 4K TV with the usual
	 * 1080p screen of the apps), null when it isn't 4K
	 */
	private fun uhdScale(): Float? {
		val view = glView ?: return null
		val mode = view.display?.mode ?: return null
		val screenWidth = view.rootView.width.takeIf { it > 0 } ?: return null
		val scale = maxOf(mode.physicalWidth, mode.physicalHeight).toFloat() / maxOf(screenWidth, view.rootView.height)
		return scale.takeIf { it >= MIN_UHD_SCALE && mode.physicalWidth >= UHD_WIDTH }
	}

	/** The buffer at 4K (the view's size times [uhdScale]) or, normally, the view's size */
	private fun applyBufferSize() {
		val view = glView ?: return
		val scale = if (wants4K()) uhdScale() else null
		if (scale == null || view.width <= 0 || view.height <= 0) {
			view.holder.setSizeFromLayout()
		} else {
			view.holder.setFixedSize(Math.round(view.width * scale), Math.round(view.height * scale))
		}
	}

	private fun detach() {
		val view = glView ?: return
		view.removeOnLayoutChangeListener(layoutListener)
		val oldRenderer = renderer
		renderer = null
		glView = null

		// The video back to the player's own SurfaceView
		val videoSurfaceView = playerView.videoSurfaceView as? SurfaceView
		videoSurfaceView?.visibility = View.VISIBLE
		surface?.let { player.clearVideoSurface(it) }
		surface = null
		if (videoSurfaceView != null) player.setVideoSurfaceView(videoSurfaceView)

		view.queueEvent { oldRenderer?.release() }
		view.onPause()
		(view.parent as? ViewGroup)?.removeView(view)
		Timber.i("Anime4K off")
	}

	private fun message(text: String) {
		if (text == lastMessage) return
		lastMessage = text
		onMessage(text)
	}

	private fun label(mode: Anime4KMode): String = context.getString(labelOf(mode))

	companion object {
		private const val MAX_REASON = 80
		private const val TEST_PATTERN_MS = 20_000L
		private const val TOAST_MS = 3_600L
		private const val UHD_WIDTH = 3840
		private const val MIN_UHD_SCALE = 1.5f

		/** Name of [mode] in the menu */
		fun labelOf(mode: Anime4KMode): Int = when (mode) {
			Anime4KMode.OFF -> org.jellyfin.androidtv.R.string.anime4k_off
			Anime4KMode.SHARP -> org.jellyfin.androidtv.R.string.anime4k_sharp
			Anime4KMode.SHARP_LINES -> org.jellyfin.androidtv.R.string.anime4k_sharp_lines
			Anime4KMode.CNN -> org.jellyfin.androidtv.R.string.anime4k_cnn
		}

		/** HDR10, HDR10+, HLG and Dolby Vision keep the normal output */
		fun isHdr(format: Format): Boolean {
			val transfer = format.colorInfo?.colorTransfer
			return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG ||
				format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
		}
	}
}
