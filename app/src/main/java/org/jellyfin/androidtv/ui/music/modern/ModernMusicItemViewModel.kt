package org.jellyfin.androidtv.ui.music.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.util.UUID

data class MusicItemState(
	val loading: Boolean = true,
	val failed: Boolean = false,
	val content: MusicItemContent? = null,
	val favorite: Boolean = false,
	/** Libraries of the navigation bar */
	val libraries: List<BaseItemDto> = emptyList(),
)

class ModernMusicItemViewModel(
	private val repository: MusicItemRepository,
	private val musicRepository: ModernMusicRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
	private val favouriteTracks: FavouriteTracks,
) : ViewModel() {
	private val _state = MutableStateFlow(MusicItemState())
	val state: StateFlow<MusicItemState> = _state.asStateFlow()

	/** Favourite songs changed in this session */
	val favouriteChanges: StateFlow<Map<UUID, Boolean>> = favouriteTracks.changes

	/** Last focused element, restored when coming back to the page */
	var lastFocusKey: String? = null

	private var itemId: UUID? = null
	private var loadJob: Job? = null
	private var librariesJob: Job? = null

	fun load(id: UUID) {
		if (itemId == id) return
		itemId = id
		observeLibraries()
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

	/** Favourite album, playlist or artist (the heart of the header) */
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

	fun isSongFavorite(song: BaseItemDto, changes: Map<UUID, Boolean>) = favouriteTracks.isFavorite(song, changes)

	/** Favourite song: also adds it to (or takes it out of) the "Favourite Tracks" playlist */
	fun toggleSongFavorite(song: BaseItemDto) {
		val favorite = !favouriteTracks.isFavorite(song)
		viewModelScope.launch {
			runCatching { favouriteTracks.setFavorite(song, favorite) }
				.onFailure { Timber.w(it, "Unable to change the favourite state of ${song.id}") }
		}
	}

	suspend fun stationTracks(source: StationSource): List<BaseItemDto> =
		runCatching { musicRepository.stationTracks(source) }
			.onFailure { Timber.w(it, "Unable to start the radio") }
			.getOrDefault(emptyList())

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	fun moveLibrary(library: BaseItemDto, offset: Int) {
		viewModelScope.launch { libraryVisibilityRepository.moveLibrary(library.id, offset) }
	}

	private fun observeLibraries() {
		if (librariesJob != null) return
		librariesJob = viewModelScope.launch {
			val libraries = try {
				libraryVisibilityRepository.libraries()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load libraries")
				emptyList()
			}

			combine(libraryVisibilityRepository.hiddenLibraryIds, libraryVisibilityRepository.libraryOrder) { hidden, order -> hidden to order }
				.collect { (hidden, order) ->
					_state.update { state ->
						state.copy(libraries = LibraryVisibilityRepository.arrange(libraries, order).filterNot { it.id in hidden })
					}
				}
		}
	}
}
