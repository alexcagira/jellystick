package org.jellyfin.androidtv.ui.home.modern

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

data class WatchlistState(
	val loading: Boolean = true,
	val cards: List<HomeCard> = emptyList(),
	val libraries: List<BaseItemDto> = emptyList(),
)

class ModernWatchlistViewModel(
	private val repository: ModernHomeRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(WatchlistState())
	val state: StateFlow<WatchlistState> = _state.asStateFlow()

	/** Last focused card, restored when coming back from a details page */
	var lastFocusKey: String? = null

	private var loadJob: Job? = null

	init {
		loadLibraries()
		refresh()
	}

	fun refresh() {
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			val cards = try {
				repository.loadWatchlist()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load the watchlist")
				null
			}

			_state.update { state -> state.copy(loading = false, cards = cards ?: state.cards) }
		}
	}

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = repository.resolvePlayableId(item)

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	fun moveLibrary(library: BaseItemDto, offset: Int) {
		viewModelScope.launch { libraryVisibilityRepository.moveLibrary(library.id, offset) }
	}

	private fun loadLibraries() {
		viewModelScope.launch {
			val libraries = try {
				libraryVisibilityRepository.libraries()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load libraries")
				emptyList()
			}

			var previousHidden: Set<UUID>? = null
			combine(libraryVisibilityRepository.hiddenLibraryIds, libraryVisibilityRepository.libraryOrder) { hidden, order -> hidden to order }
				.collect { (hiddenLibraryIds, order) ->
					_state.update { state ->
						state.copy(libraries = LibraryVisibilityRepository.arrange(libraries, order).filterNot { it.id in hiddenLibraryIds })
					}
					if (previousHidden != null && previousHidden != hiddenLibraryIds) refresh()
					previousHidden = hiddenLibraryIds
				}
		}
	}
}
