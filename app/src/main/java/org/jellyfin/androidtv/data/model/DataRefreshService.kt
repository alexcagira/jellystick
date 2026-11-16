package org.jellyfin.androidtv.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import java.util.UUID

/** The server has the position where a video was left (its playback stop was reported) */
data class PlaybackStopReported(val itemId: UUID, val time: Long = System.currentTimeMillis())

/**
 * A video stopped at [positionTicks]: the rows can change at once, before the server has it.
 * [next]: the next title of the player queue (the next episode).
 */
data class PlaybackStopped(
	val item: BaseItemDto,
	val positionTicks: Long,
	val next: BaseItemDto?,
	val time: Long = System.currentTimeMillis(),
)

class DataRefreshService {
	private val _playbackStopReported = MutableStateFlow<PlaybackStopReported?>(null)

	/**
	 * Last video whose stop the server received: "Continue watching" can be asked again then (asked
	 * before, the server still has the previous position)
	 */
	val playbackStopReported: StateFlow<PlaybackStopReported?> = _playbackStopReported.asStateFlow()

	fun onPlaybackStopReported(itemId: UUID) {
		stopReportPending = false
		_playbackStopReported.value = PlaybackStopReported(itemId)
	}

	private val _playbackStopped = MutableStateFlow<PlaybackStopped?>(null)

	/** Last video stopped (when the player stops, before the server knows) */
	val playbackStopped: StateFlow<PlaybackStopped?> = _playbackStopped.asStateFlow()

	/**
	 * The stop of a video is on its way to the server: rows asked now would still have the old
	 * position, so they wait for [playbackStopReported]
	 */
	@Volatile
	var stopReportPending: Boolean = false
		private set

	fun onPlaybackStopped(item: BaseItemDto, positionTicks: Long, next: BaseItemDto?) {
		stopReportPending = true
		_playbackStopped.value = PlaybackStopped(item, positionTicks, next)
	}

	var lastDeletedItemId: UUID? = null
	var lastPlayback: Instant? = null
	var lastMoviePlayback: Instant? = null
	var lastTvPlayback: Instant? = null
	var lastLibraryChange: Instant? = null
	var lastFavoriteUpdate: Instant? = null
	var lastPlayedItem: BaseItemDto? = null
}
