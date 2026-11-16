package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.BaseItemDto

class VideoQueueManager {
	private var _currentVideoQueue: List<BaseItemDto> = emptyList()
	private var _currentMediaPosition = -1
	private var _lastPlayedAudioLanguageIsoCode: String? = null
	private var _lastPlayedSubtitleLanguageIsoCode: String? = null

	fun setCurrentVideoQueue(items: List<BaseItemDto>?) {
		if (items.isNullOrEmpty()) return clearVideoQueue()

		_currentVideoQueue = items.toMutableList()
		_currentMediaPosition = 0
	}

	fun getCurrentVideoQueue(): List<BaseItemDto> = _currentVideoQueue

	/**
	 * More titles at the end of the queue being played: the next episodes, asked once the playback
	 * started (the player shares this list). Only when the queue still ends with [afterId].
	 */
	fun appendToVideoQueue(afterId: java.util.UUID, items: List<BaseItemDto>) {
		val queue = _currentVideoQueue as? MutableList<BaseItemDto> ?: return
		if (items.isEmpty() || queue.lastOrNull()?.id != afterId) return
		queue.addAll(items)
	}

	fun setCurrentMediaPosition(currentMediaPosition: Int) {
		if (currentMediaPosition !in 0.._currentVideoQueue.size) return

		_currentMediaPosition = currentMediaPosition
	}

	fun getCurrentMediaPosition() = _currentMediaPosition

	fun getLastPlayedAudioLanguageIsoCode(): String? {
		return _lastPlayedAudioLanguageIsoCode
	}

	fun setLastPlayedAudioLanguageIsoCode(isoCode: String) {
		_lastPlayedAudioLanguageIsoCode = isoCode
	}

	fun getLastPlayedSubtitleLanguageIsoCode(): String? {
		return _lastPlayedSubtitleLanguageIsoCode
	}

	fun setLastPlayedSubtitleLanguageIsoCode(isoCode: String?) {
		_lastPlayedSubtitleLanguageIsoCode = isoCode
	}

	fun clearVideoQueue() {
		_currentVideoQueue = emptyList()
		_currentMediaPosition = -1
		_lastPlayedAudioLanguageIsoCode = null
		_lastPlayedSubtitleLanguageIsoCode = null
	}
}
