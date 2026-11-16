package org.jellyfin.androidtv.ui.home.modern

import kotlinx.coroutines.delay

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
		if (!value) quietUntil = 0L
	}

	val isQuiet: Boolean
		get() = playing || System.currentTimeMillis() < quietUntil

	/** Wait for the background's turn: call it before each background request */
	suspend fun awaitTurn() {
		while (isQuiet) delay(POLL_MS)
	}

	private const val QUIET_MS = 20_000L
	private const val POLL_MS = 500L
}
