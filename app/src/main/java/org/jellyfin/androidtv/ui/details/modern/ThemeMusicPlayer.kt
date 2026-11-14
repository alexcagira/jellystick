package org.jellyfin.androidtv.ui.details.modern

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import timber.log.Timber
import java.util.UUID

/**
 * Plays the theme song of a series (the "theme.mp3" next to the series in the library) while its
 * page or one of its seasons is open. The music fades in and out, and keeps playing when going
 * from the series to a season and back.
 *
 * Everything runs on the main thread.
 */
class ThemeMusicPlayer(
	private val context: Context,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val mediaManager: MediaManager,
	appLifecycle: Lifecycle,
) {
	private companion object {
		const val MAX_VOLUME = 0.45f
		const val FADE_IN_MS = 1500L
		const val FADE_OUT_MS = 900L
		const val FADE_STEP_MS = 50L
	}

	private val scope = MainScope()
	private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
	private val audioAttributes = AudioAttributes.Builder()
		.setUsage(AudioAttributes.USAGE_MEDIA)
		.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
		.build()

	// Stop when something else (e.g. the video player) takes over the audio
	private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
		if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
	}
	private var focusRequest: AudioFocusRequest? = null

	private var player: MediaPlayer? = null
	private var volume = 0f
	/** Series of the song that is playing, finished playing, or being loaded */
	private var currentSeriesId: UUID? = null
	private var startJob: Job? = null
	private var fadeJob: Job? = null
	private var stopJob: Job? = null

	/** Theme song of each series, null when it has none */
	private val themeSongs = HashMap<UUID, UUID?>()
	private var themeSongsServer: String? = null

	init {
		// Stop right away when the app goes to the background
		scope.launch {
			appLifecycle.addObserver(LifecycleEventObserver { _, event ->
				if (event == Lifecycle.Event.ON_STOP) stop()
			})
		}
	}

	/**
	 * Play the theme song of [seriesId]. Nothing happens when it's already playing, or when it
	 * already played completely since the series was opened.
	 */
	fun play(seriesId: UUID) {
		stopJob?.cancel()
		stopJob = null

		if (!userPreferences[UserPreferences.themeMusicEnabled] || mediaManager.isPlayingAudio) {
			stop()
			return
		}

		if (seriesId == currentSeriesId) {
			// Cancel a fade out that already started
			if (player != null) fadeTo(MAX_VOLUME, FADE_IN_MS)
			return
		}

		release()
		currentSeriesId = seriesId
		startJob = scope.launch {
			val songId = themeSongId(seriesId) ?: return@launch
			start(songId)
		}
	}

	/**
	 * Fade out and stop. With [delayMs] the music keeps playing for a moment, so it continues when
	 * the next page is the same series (series → season) and [play] is called again in time.
	 */
	fun stop(delayMs: Long = 0) {
		if (delayMs > 0) {
			if (stopJob?.isActive != true) {
				stopJob = scope.launch {
					delay(delayMs)
					stopNow()
				}
			}
			return
		}

		stopNow()
	}

	private fun stopNow() {
		stopJob?.cancel()
		stopJob = null
		startJob?.cancel()
		startJob = null

		val current = player
		if (current == null) {
			currentSeriesId = null
			return
		}

		fadeTo(0f, FADE_OUT_MS) {
			if (player === current) release()
			currentSeriesId = null
		}
	}

	private fun start(songId: UUID) {
		if (!requestAudioFocus()) return

		val mediaPlayer = MediaPlayer()
		player = mediaPlayer
		volume = 0f

		try {
			mediaPlayer.setAudioAttributes(audioAttributes)
			mediaPlayer.setDataSource(
				context,
				Uri.parse(api.audioApi.getAudioStreamUrl(itemId = songId, static = true)),
				mapOf("Authorization" to authorizationHeader()),
			)
			mediaPlayer.setVolume(0f, 0f)
			mediaPlayer.setOnPreparedListener { prepared ->
				if (player === prepared) {
					prepared.start()
					fadeTo(MAX_VOLUME, FADE_IN_MS)
				}
			}
			// Played once: stays quiet until another series is opened
			mediaPlayer.setOnCompletionListener { completed ->
				if (player === completed) release()
			}
			mediaPlayer.setOnErrorListener { failed, what, extra ->
				Timber.w("Theme song playback failed ($what, $extra)")
				if (player === failed) release()
				true
			}
			mediaPlayer.prepareAsync()
		} catch (err: Exception) {
			Timber.w(err, "Unable to play theme song")
			release()
		}
	}

	private fun release() {
		fadeJob?.cancel()
		fadeJob = null

		player?.let { mediaPlayer -> runCatching { mediaPlayer.release() } }
		player = null
		volume = 0f
		abandonAudioFocus()
	}

	private fun fadeTo(target: Float, durationMs: Long, onDone: () -> Unit = {}) {
		fadeJob?.cancel()
		fadeJob = scope.launch {
			val start = volume
			val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
			for (step in 1..steps) {
				delay(FADE_STEP_MS)
				setVolume(start + (target - start) * step / steps)
			}
			onDone()
		}
	}

	private fun setVolume(value: Float) {
		volume = value
		player?.let { mediaPlayer -> runCatching { mediaPlayer.setVolume(value, value) } }
	}

	private suspend fun themeSongId(seriesId: UUID): UUID? {
		if (themeSongsServer != api.baseUrl) {
			themeSongs.clear()
			themeSongsServer = api.baseUrl
		}
		if (themeSongs.containsKey(seriesId)) return themeSongs[seriesId]

		val songId = try {
			withContext(Dispatchers.IO) {
				api.libraryApi.getThemeSongs(itemId = seriesId, inheritFromParent = true).content.items.firstOrNull()?.id
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// Not cached, tried again next time
			Timber.w(err, "Unable to load theme song of $seriesId")
			return null
		}

		themeSongs[seriesId] = songId
		return songId
	}

	private fun authorizationHeader() = AuthorizationHeaderBuilder.buildHeader(
		api.clientInfo.name,
		api.clientInfo.version,
		api.deviceInfo.id,
		api.deviceInfo.name,
		api.accessToken,
	)

	private fun requestAudioFocus(): Boolean {
		// "May duck": music from other apps gets quieter instead of stopping
		val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
				.setAudioAttributes(audioAttributes)
				.setOnAudioFocusChangeListener(focusListener)
				.build()
			focusRequest = request
			audioManager.requestAudioFocus(request)
		} else {
			@Suppress("DEPRECATION")
			audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
		}

		return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
	}

	private fun abandonAudioFocus() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			focusRequest?.let { request -> audioManager.abandonAudioFocusRequest(request) }
		} else {
			@Suppress("DEPRECATION")
			audioManager.abandonAudioFocus(focusListener)
		}
		focusRequest = null
	}
}
