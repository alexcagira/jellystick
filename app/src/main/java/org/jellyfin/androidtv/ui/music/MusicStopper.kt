package org.jellyfin.androidtv.ui.music

import android.view.KeyEvent
import org.jellyfin.androidtv.ui.music.share.SharedQueueServer
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.MediaType

/**
 * Stop of the music: what Stop does on Now Playing and the ☰ button of the remote anywhere in the
 * app. The music stops, its queue (with the songs already played) is emptied and the shared queue
 * closes (a new one has to be started).
 */
class MusicStopper(
	private val mediaManager: MediaManager,
	private val playbackManager: PlaybackManager,
	private val sharedQueueServer: SharedQueueServer,
) {
	/** There is music: playing, paused, or a finished queue kept to play again, or a shared queue */
	val hasMusic: Boolean
		get() = playbackManager.queue.entries.value.any { it.baseItem?.mediaType == MediaType.AUDIO } ||
			playbackManager.queue.entry.value?.baseItem?.mediaType == MediaType.AUDIO ||
			sharedQueueServer.state.value.running

	fun stop() {
		mediaManager.stopAudio(true)
		sharedQueueServer.stop()
	}

	/**
	 * The ☰ button while there is music: stops it (when released). Returns true when the event was
	 * used, so nothing else gets it (it used to open menus in some screens).
	 */
	fun handleMenuKey(event: KeyEvent): Boolean {
		if (event.keyCode != KeyEvent.KEYCODE_MENU || !hasMusic) return false
		if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) stop()
		return true
	}
}
