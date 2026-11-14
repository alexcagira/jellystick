package org.jellyfin.androidtv.ui.home.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ModernHomeViewModel(
	private val repository: ModernHomeRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(ModernHomeState())
	val state: StateFlow<ModernHomeState> = _state.asStateFlow()

	/** Last focused card, used to restore focus when the home screen is shown again */
	var lastFocus: HomeFocusPosition? = null

	private var heroJob: Job? = null
	private var sectionsJob: Job? = null
	private var librariesJob: Job? = null

	init {
		loadLibraries()
		loadHero()
		loadSections(debounce = Duration.ZERO)
	}

	/**
	 * Reload the rows (continue watching, new episodes...). Calls are debounced because the server
	 * tends to send multiple change events in a row.
	 */
	fun refresh() = loadSections(debounce = REFRESH_DEBOUNCE)

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = repository.resolvePlayableId(item)

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	/**
	 * Mark an episode/movie as watched and reload the rows right away, so "Continue watching"
	 * shows the next episode.
	 */
	fun markWatched(item: BaseItemDto) {
		viewModelScope.launch {
			try {
				repository.setPlayed(item.id)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to mark ${item.id} as watched")
				return@launch
			}

			loadSections(debounce = Duration.ZERO)
		}
	}

	/**
	 * Libraries for the navigation bar, in the server order. Updates live when libraries are hidden
	 * or shown again in the settings.
	 */
	private fun loadLibraries() {
		librariesJob?.cancel()
		librariesJob = viewModelScope.launch {
			val libraries = try {
				libraryVisibilityRepository.libraries()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load libraries for the navigation bar")
				emptyList()
			}

			var firstValue = true
			libraryVisibilityRepository.hiddenLibraryIds.collect { hiddenLibraryIds ->
				_state.update { state -> state.copy(libraries = libraries.filterNot { it.id in hiddenLibraryIds }) }

				// Hiding or showing a library changes what the home screen may contain
				if (!firstValue) {
					loadHero()
					loadSections(debounce = Duration.ZERO)
				}
				firstValue = false
			}
		}
	}

	private fun loadHero() {
		heroJob?.cancel()
		heroJob = viewModelScope.launch {
			val hero = try {
				repository.loadHero()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load hero items")
				emptyList()
			}

			_state.update { it.copy(hero = hero, heroLoaded = true) }
		}
	}

	private fun loadSections(debounce: Duration) {
		sectionsJob?.cancel()
		sectionsJob = viewModelScope.launch {
			if (debounce > Duration.ZERO) delay(debounce)

			val sections = try {
				repository.loadSections()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load home sections")
				null
			}

			_state.update { state ->
				state.copy(
					loading = false,
					// Keep the old rows when a refresh fails (e.g. temporary network issue)
					sections = sections ?: state.sections,
				)
			}
		}
	}

	private companion object {
		val REFRESH_DEBOUNCE = 750.milliseconds
	}
}
