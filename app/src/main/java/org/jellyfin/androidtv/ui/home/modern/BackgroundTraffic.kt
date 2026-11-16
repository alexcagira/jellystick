package org.jellyfin.androidtv.ui.home.modern

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Requests the app makes by itself in the background (badges of every series, what each collection
 * holds, collection updates...) wait while a video starts and plays. The app only talks to a server
 * through a few connections at a time, and the playback requests queued behind dozens of those:
 * the player took seconds to open.
 */
object BackgroundTraffic {
	@Volatile
	private var quietUntil = 0L

	@Volatile
	private var playing = false

	/** The user asked to play something: the background waits a moment */
	@JvmStatic
	fun quiet() {
		quietUntil = maxOf(quietUntil, System.currentTimeMillis() + QUIET_MS)
	}

	/** A video is playing (true from its start until the player closes) */
	@JvmStatic
	fun setPlaying(value: Boolean) {
		playing = value
		// Leaving the player: its stop report and "Continue watching" go first
		if (!value) quietUntil = System.currentTimeMillis() + AFTER_PLAYBACK_QUIET_MS
	}

	private val _playRequests = MutableStateFlow(0)

	/** Grows each time something is played: screens stop loading rows so the player goes first */
	val playRequests: StateFlow<Int> = _playRequests.asStateFlow()

	/** Something is going to play: quiet, and the screens stop what they load */
	@JvmStatic
	fun playRequested() {
		quiet()
		_playRequests.value += 1
	}

	val isQuiet: Boolean
		get() = playing || System.currentTimeMillis() < quietUntil

	/** Wait for the background's turn: call it before each background request */
	suspend fun awaitTurn() {
		while (isQuiet) delay(POLL_MS)
	}

	private const val QUIET_MS = 20_000L
	private const val AFTER_PLAYBACK_QUIET_MS = 10_000L
	private const val POLL_MS = 500L
}
