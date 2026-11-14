package org.jellyfin.androidtv.ui.home.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ModernHomeViewModel(
	private val repository: ModernHomeRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
	private val userPreferences: UserPreferences,
	private val homeCache: HomeCache,
) : ViewModel() {
	private val _state = MutableStateFlow(ModernHomeState())
	val state: StateFlow<ModernHomeState> = _state.asStateFlow()

	/** Last focused card, used to restore focus when the home screen is shown again */
	var lastFocus: HomeFocusPosition? = null

	private var heroJob: Job? = null
	private var sectionsJob: Job? = null
	private var librariesJob: Job? = null

	/** The hero shown comes from the cache: the new random slides are kept for the next start */
	private var heroFromCache = false
	private var nextHero: List<HeroSlide>? = null

	init {
		loadLibraries()
		showCachedHome()
		loadHero()
		loadSections(debounce = Duration.ZERO)
	}

	/**
	 * Show what was on screen last time right away, the fresh content replaces it when loaded.
	 */
	private fun showCachedHome() {
		viewModelScope.launch {
			val cached = homeCache.read(repository.cacheKey()) ?: return@launch
			_state.update { state ->
				val useHero = !state.heroLoaded && cached.hero.isNotEmpty()
				if (useHero) heroFromCache = true
				state.copy(
					hero = if (useHero) cached.hero else state.hero,
					heroLoaded = state.heroLoaded || useHero,
					sections = state.sections.ifEmpty { orderSections(cached.sections) },
					loading = false,
				)
			}
		}
	}

	private fun saveCache() {
		val state = _state.value
		val hero = nextHero ?: state.hero
		if (hero.isEmpty() && state.sections.isEmpty()) return
		viewModelScope.launch {
			homeCache.write(CachedHome(key = repository.cacheKey(), hero = hero, sections = state.sections))
		}
	}

	/**
	 * Reload the rows (continue watching, new episodes...). Calls are debounced because the server
	 * tends to send multiple change events in a row.
	 */
	fun refresh() = loadSections(debounce = REFRESH_DEBOUNCE)

	/** Reload the rows right away, e.g. after marking something as watched */
	fun refreshNow() = loadSections(debounce = Duration.ZERO)

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = repository.resolvePlayableId(item)

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	fun moveLibrary(library: BaseItemDto, offset: Int) {
		viewModelScope.launch { libraryVisibilityRepository.moveLibrary(library.id, offset) }
	}

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

			var previousHidden: Set<UUID>? = null
			combine(libraryVisibilityRepository.hiddenLibraryIds, libraryVisibilityRepository.libraryOrder) { hidden, order -> hidden to order }
				.collect { (hiddenLibraryIds, order) ->
					_state.update { state ->
						state.copy(libraries = LibraryVisibilityRepository.arrange(libraries, order).filterNot { it.id in hiddenLibraryIds })
					}

					// Hiding or showing a library changes what the home screen may contain
					if (previousHidden != null && previousHidden != hiddenLibraryIds) {
						heroFromCache = false
						loadHero()
						loadSections(debounce = Duration.ZERO)
					}
					previousHidden = hiddenLibraryIds
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

			if (heroFromCache) {
				// Don't change the slides under the user's eyes: they show on the next start
				nextHero = hero
			} else {
				_state.update { it.copy(hero = hero, heroLoaded = true) }
			}
			saveCache()
		}
	}

	private fun loadSections(debounce: Duration) {
		sectionsJob?.cancel()
		sectionsJob = viewModelScope.launch {
			if (debounce > Duration.ZERO) delay(debounce)

			// First load without cache: show the quick rows right away. Otherwise the rows on screen
			// stay until the complete ones are loaded
			val showPartial = _state.value.sections.isEmpty()
			val sections = try {
				repository.loadSections(
					onPartial = if (showPartial) { partial -> applySections(partial) } else null,
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load home sections")
				null
			}

			// Keep the old rows when a refresh fails (e.g. temporary network issue)
			if (sections != null) {
				applySections(sections)
				saveCache()
			} else {
				_state.update { it.copy(loading = false) }
			}
		}
	}

	/** Rows in the order chosen in the settings */
	private fun applySections(sections: List<HomeSection>) {
		val ordered = orderSections(sections)
		_state.update { state -> state.copy(loading = false, sections = ordered) }
	}

	private fun orderSections(sections: List<HomeSection>): List<HomeSection> {
		val order = HomeSectionOrder.parse(userPreferences[UserPreferences.homeSectionOrder])
		return sections.sortedBy { section -> order.indexOf(section.type) }
	}

	private companion object {
		val REFRESH_DEBOUNCE = 750.milliseconds
	}
}
