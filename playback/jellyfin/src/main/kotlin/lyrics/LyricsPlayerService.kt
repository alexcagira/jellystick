package org.jellyfin.playback.jellyfin.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import timber.log.Timber

class LyricsPlayerService(
	private val api: ApiClient,
) : PlayerService() {
	override suspend fun onInitialize() {
		// Load lyrics for an item as soon as it becomes the currently playing item, and the lyrics of
		// the next one already, so they show the moment it starts
		manager.queue.entry
			.filterNotNull()
			.onEach { entry ->
				fetchLyrics(entry)
				val next = try {
					manager.queue.peekNext()
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					null
				}
				if (next != null) fetchLyrics(next)
			}
			.launchIn(coroutineScope)
	}

	private suspend fun fetchLyrics(entry: QueueEntry) {
		// Already has lyrics!
		if (entry.lyrics != null) return

		// BaseItem doesn't exist or doesn't have lyrics
		val baseItem = entry.baseItem ?: return
		if (baseItem.hasLyrics != true) return

		// Get via API. A failure (network...) must not stop the lyrics of the next songs
		try {
			val lyrics by api.lyricsApi.getLyrics(baseItem.id)
			entry.lyrics = lyrics
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to load the lyrics of ${baseItem.id}")
		}
	}
}
