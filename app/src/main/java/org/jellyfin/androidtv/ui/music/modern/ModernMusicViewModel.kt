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
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

class ModernMusicViewModel(
	private val repository: ModernMusicRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
	private val userPreferences: UserPreferences,
	private val favouriteTracks: FavouriteTracks,
) : ViewModel() {
	private val _state = MutableStateFlow(ModernMusicState())
	val state: StateFlow<ModernMusicState> = _state.asStateFlow()

	/** Last focused card, restored when coming back to the page */
	var lastFocusKey: String? = null

	private var library: BaseItemDto? = null
	private var labels: MusicLabels? = null
	private var loadJob: Job? = null
	private var librariesJob: Job? = null

	fun load(library: BaseItemDto, labels: MusicLabels) {
		if (this.library?.id == library.id) return
		this.library = library
		this.labels = labels
		observeLibraries()
		refresh()
		// "Favourite Tracks" playlist up to date with the songs marked in other apps
		favouriteTracks.syncInBackground()
	}

	fun refresh() {
		val library = library ?: return
		val labels = labels ?: return
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			val content = try {
				repository.load(library.id, labels)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load the music library")
				null
			}

			val order = MusicSectionOrder.parse(userPreferences[UserPreferences.musicSectionOrder])
			_state.update { state ->
				if (content == null) state.copy(loading = false)
				else state.copy(
					loading = false,
					// The slides stay the same on refresh, they would jump under the user's eyes
					hero = state.hero.ifEmpty { content.hero },
					sections = content.sections.sortedBy { order.indexOf(it.type) },
				)
			}
		}
	}

	suspend fun stationTracks(source: StationSource): List<BaseItemDto> =
		runCatching { repository.stationTracks(source) }
			.onFailure { Timber.w(it, "Unable to start station") }
			.getOrDefault(emptyList())

	suspend fun shuffleTracks(): List<BaseItemDto> {
		val library = library ?: return emptyList()
		return runCatching { repository.shuffleTracks(library.id) }
			.onFailure { Timber.w(it, "Unable to shuffle the library") }
			.getOrDefault(emptyList())
	}

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
