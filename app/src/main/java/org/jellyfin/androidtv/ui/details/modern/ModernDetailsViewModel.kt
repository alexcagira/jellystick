package org.jellyfin.androidtv.ui.details.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.util.UUID

class ModernDetailsViewModel(
	private val repository: ModernDetailsRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(DetailsState())
	val state: StateFlow<DetailsState> = _state.asStateFlow()

	/** Last focused element (list position + key), restored when coming back to this screen */
	var lastFocusIndex: Int = -1
	var lastFocusKey: String? = null

	/** Item of the page (the fragment argument) */
	private var requestedId: UUID? = null
	/** Item shown, differs from [requestedId] after switching season */
	private var itemId: UUID? = null
	private var loadJob: Job? = null
	private var librariesJob: Job? = null

	fun load(id: UUID) {
		if (requestedId == id) return
		requestedId = id
		itemId = id
		observeLibraries()
		reload(showLoading = true)
	}

	/**
	 * Show another season of the same series on this page (season switcher).
	 */
	fun switchSeason(seasonId: UUID) {
		if (itemId == seasonId) return
		itemId = seasonId
		reload(showLoading = false)
	}

	/**
	 * Quick "watched" button under an episode.
	 */
	fun toggleEpisodePlayed(entry: EpisodeEntry) {
		val played = !entry.played
		_state.update { state ->
			state.copy(
				episodes = state.episodes.map { episode ->
					if (episode.item.id == entry.item.id) episode.copy(played = played, progress = if (played) null else episode.progress)
					else episode
				}
			)
		}

		viewModelScope.launch {
			runCatching { repository.setPlayed(entry.item.id, played) }
				.onFailure { Timber.w(it, "Unable to change played state of ${entry.item.id}") }
			// Next episode, progress and counts change with it
			refresh()
		}
	}

	suspend fun fullItem(id: UUID): BaseItemDto? = runCatching { repository.fullItem(id) }
		.onFailure { Timber.w(it, "Unable to load media info of $id") }
		.getOrNull()

	/**
	 * Reload quietly, e.g. after playback to update the progress and watched state.
	 */
	fun refresh() = reload(showLoading = false)

	private fun reload(showLoading: Boolean) {
		val id = itemId ?: return
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			if (showLoading) _state.update { it.copy(loading = true, failed = false) }

			val loaded = try {
				repository.load(id)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load details of $id")
				null
			}

			_state.update { current ->
				when {
					loaded != null -> loaded.copy(libraries = current.libraries)
					// Keep what is on screen when a refresh fails
					current.item != null -> current.copy(loading = false)
					else -> current.copy(loading = false, failed = true)
				}
			}
		}
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

			libraryVisibilityRepository.hiddenLibraryIds.collect { hiddenLibraryIds ->
				_state.update { state -> state.copy(libraries = libraries.filterNot { it.id in hiddenLibraryIds }) }
			}
		}
	}

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	fun toggleFavorite() {
		val item = _state.value.item ?: return
		val favorite = !_state.value.isFavorite
		_state.update { it.copy(isFavorite = favorite) }

		viewModelScope.launch {
			val result = runCatching { repository.setFavorite(item.id, favorite) }
				.onFailure { Timber.w(it, "Unable to change favorite state") }
				.getOrElse { !favorite }
			_state.update { it.copy(isFavorite = result) }
		}
	}

	fun togglePlayed() {
		val item = _state.value.item ?: return
		val played = !_state.value.isPlayed
		_state.update { it.copy(isPlayed = played) }

		viewModelScope.launch {
			val result = runCatching { repository.setPlayed(item.id, played) }
				.onFailure { Timber.w(it, "Unable to change played state") }
				.getOrElse { !played }
			_state.update { it.copy(isPlayed = result) }
			// Episodes, next up and progress change with it
			refresh()
		}
	}

	/**
	 * Add to or remove from the watchlist (the series on a season page).
	 *
	 * @return true when the item is being added
	 */
	fun toggleWatchlist(): Boolean {
		val itemId = _state.value.watchlistItemId ?: return false
		val inWatchlist = !_state.value.isInWatchlist
		_state.update { it.copy(isInWatchlist = inWatchlist) }

		viewModelScope.launch {
			val result = runCatching { repository.setInWatchlist(itemId, inWatchlist) }
				.onFailure { Timber.w(it, "Unable to change watchlist state") }
				.getOrElse { !inWatchlist }
			_state.update { it.copy(isInWatchlist = result) }
		}

		return inWatchlist
	}

	/**
	 * What the "Play" button starts: the movie, the next episode of a series or season.
	 */
	suspend fun resolvePlayId(): UUID? {
		val state = _state.value
		val item = state.item ?: return null

		return when (state.kind) {
			DetailsKind.MOVIE -> item.id
			DetailsKind.SERIES -> state.playTarget?.id ?: runCatching { repository.firstEpisodeId(item.id) }.getOrNull()
			DetailsKind.SEASON -> state.playTarget?.id ?: state.episodes.firstOrNull()?.item?.id

			DetailsKind.PERSON, null -> null
		}
	}

	/**
	 * What the play/pause button starts on a poster: movies directly, series at the next episode.
	 */
	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = runCatching { repository.resolvePlayableId(item) }
		.onFailure { Timber.w(it, "Unable to resolve playable item") }
		.getOrNull()

	suspend fun localTrailerIds(): List<UUID> {
		val item = _state.value.item ?: return emptyList()
		return runCatching { repository.localTrailerIds(item.id) }.getOrDefault(emptyList())
	}
}
