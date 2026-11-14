package org.jellyfin.androidtv.ui.music.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.util.UUID

data class MusicItemState(
	val loading: Boolean = true,
	val failed: Boolean = false,
	val content: MusicItemContent? = null,
	val favorite: Boolean = false,
)

class ModernMusicItemViewModel(
	private val repository: MusicItemRepository,
	private val musicRepository: ModernMusicRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(MusicItemState())
	val state: StateFlow<MusicItemState> = _state.asStateFlow()

	/** Last focused element, restored when coming back to the page */
	var lastFocusKey: String? = null

	private var itemId: UUID? = null
	private var loadJob: Job? = null

	fun load(id: UUID) {
		if (itemId == id) return
		itemId = id
		refresh()
	}

	fun refresh() {
		val id = itemId ?: return
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			try {
				val content = repository.load(id)
				_state.update {
					it.copy(
						loading = false,
						failed = false,
						content = content,
						favorite = content.item.userData?.isFavorite == true,
					)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load music item $id")
				_state.update { it.copy(loading = false, failed = it.content == null) }
			}
		}
	}

	fun toggleFavorite() {
		val item = _state.value.content?.item ?: return
		val favorite = !_state.value.favorite
		_state.update { it.copy(favorite = favorite) }
		viewModelScope.launch {
			runCatching { repository.setFavorite(item.id, favorite) }
				.onFailure {
					Timber.w(it, "Unable to change the favorite state")
					_state.update { state -> state.copy(favorite = !favorite) }
				}
		}
	}

	suspend fun setSongFavorite(song: BaseItemDto, favorite: Boolean): Boolean =
		runCatching { repository.setFavorite(song.id, favorite) }.isSuccess

	suspend fun stationTracks(source: StationSource): List<BaseItemDto> =
		runCatching { musicRepository.stationTracks(source) }
			.onFailure { Timber.w(it, "Unable to start the radio") }
			.getOrDefault(emptyList())
}
