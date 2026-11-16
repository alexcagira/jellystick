package org.jellyfin.androidtv.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import java.util.UUID

/** The server has the position where a video was left (its playback stop was reported) */
data class PlaybackStopReported(val itemId: UUID, val time: Long = System.currentTimeMillis())

class DataRefreshService {
	private val _playbackStopReported = MutableStateFlow<PlaybackStopReported?>(null)

	/**
	 * Last video whose stop the server received: "Continue watching" can be asked again then (asked
	 * before, the server still has the previous position)
	 */
	val playbackStopReported: StateFlow<PlaybackStopReported?> = _playbackStopReported.asStateFlow()

	fun onPlaybackStopReported(itemId: UUID) {
		_playbackStopReported.value = PlaybackStopReported(itemId)
	}

	var lastDeletedItemId: UUID? = null
	var lastPlayback: Instant? = null
	var lastMoviePlayback: Instant? = null
	var lastTvPlayback: Instant? = null
	var lastLibraryChange: Instant? = null
	var lastFavoriteUpdate: Instant? = null
	var lastPlayedItem: BaseItemDto? = null
}
