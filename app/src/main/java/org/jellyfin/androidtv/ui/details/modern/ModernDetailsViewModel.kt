package org.jellyfin.androidtv.ui.details.modern

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.model.PlaybackStopped
import org.jellyfin.androidtv.ui.home.modern.BackgroundTraffic
import org.jellyfin.androidtv.ui.home.modern.MAX_RESUME_FRACTION
import org.jellyfin.androidtv.ui.home.modern.MIN_RESUME_FRACTION
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.util.UUID

class ModernDetailsViewModel(
	private val context: Context,
	private val repository: ModernDetailsRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
	private val dataRefreshService: DataRefreshService,
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
	private var letterboxdJob: Job? = null
	/** A refresh asked while the stop of a video was on its way to the server: done once it arrives */
	private var refreshAfterStopReport = false

	init {
		// Back from the player: "Resume from…" / "Resume S01:E05" and ⏮ at once, before the server has it
		viewModelScope.launch {
			dataRefreshService.playbackStopped.drop(1).filterNotNull().collect { stopped -> showStopped(stopped) }
		}
		// New episodes or seasons (or new metadata) on the server: the page again
		viewModelScope.launch {
			dataRefreshService.libraryChanged.drop(1).collectLatest {
				delay(LIBRARY_CHANGE_DEBOUNCE_MS)
				refresh()
			}
		}
		viewModelScope.launch {
			dataRefreshService.playbackStopReported.drop(1).filterNotNull().collect {
				if (refreshAfterStopReport) {
					refreshAfterStopReport = false
					reload(showLoading = false)
				}
			}
		}

		// Something is going to play while the page still loads ("More like this" is a slow request):
		// it stops, the player gets the server first. The page loads again when coming back
		viewModelScope.launch {
			BackgroundTraffic.playRequests.drop(1).collect {
				if (_state.value.partial) loadJob?.cancel()
			}
		}
	}

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

	/** Season (or any item) marked as watched / unwatched from its menu */
	fun setItemPlayed(id: UUID, played: Boolean) {
		viewModelScope.launch {
			runCatching { repository.setPlayed(id, played) }
				.onFailure { Timber.w(it, "Unable to change played state of $id") }
			refresh()
		}
	}

	/** Episode "Play now" starts for a season: the first one not watched yet */
	suspend fun seasonPlayableId(season: BaseItemDto): UUID? = runCatching { repository.seasonPlayableId(season) }
		.onFailure { Timber.w(it, "Unable to find an episode of ${season.id}") }
		.getOrNull()

	suspend fun fullItem(id: UUID): BaseItemDto? = runCatching { repository.fullItem(id) }
		.onFailure { Timber.w(it, "Unable to load media info of $id") }
		.getOrNull()

	/**
	 * Reload quietly, e.g. after playback to update the progress and watched state.
	 */
	fun refresh() {
		// The server doesn't have where the video was left yet: the page would come back with the old position
		if (dataRefreshService.stopReportPending) {
			refreshAfterStopReport = true
			return
		}
		reload(showLoading = false)
	}

	/**
	 * The page as it will be once the server has the stop: the movie with its new position, or the
	 * episode of the Play button (the next one when it was watched). Same thresholds as the server:
	 * under 5 % the position isn't kept, from 90 % on it counts as watched.
	 */
	private fun showStopped(stopped: PlaybackStopped) {
		val item = stopped.item
		val runtime = item.runTimeTicks?.takeIf { it > 0 } ?: return
		val fraction = stopped.positionTicks.toDouble() / runtime
		val watched = fraction >= MAX_RESUME_FRACTION
		val kept = fraction >= MIN_RESUME_FRACTION && !watched

		_state.update { state ->
			val shown = state.item ?: return@update state
			when {
				state.kind == DetailsKind.MOVIE && shown.id == item.id -> {
					val userData = shown.userData ?: return@update state
					state.copy(
						item = shown.copy(userData = userData.copy(playbackPositionTicks = if (kept) stopped.positionTicks else 0L)),
						isPlayed = if (watched) true else state.isPlayed,
					)
				}

				(state.kind == DetailsKind.SERIES || state.kind == DetailsKind.SEASON) &&
					item.seriesId != null && (shown.id == item.seriesId || shown.seriesId == item.seriesId) -> {
					val next = stopped.next
					val target = when {
						kept -> PlayTarget(item.id, item.parentIndexNumber, item.indexNumber, resume = true)
						watched && next != null -> PlayTarget(next.id, next.parentIndexNumber, next.indexNumber, resume = false)
						else -> state.playTarget
					}
					state.copy(playTarget = target)
				}

				else -> state
			}
		}
	}

	private fun reload(showLoading: Boolean) {
		val id = itemId ?: return
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			if (showLoading) _state.update { it.copy(loading = true, failed = false) }

			val loaded = try {
				repository.load(
					itemId = id,
					// Opening the page: the header at once, the rows as they come. A refresh (after
					// playing) replaces the whole page at the end instead, so nothing jumps
					onPartial = if (showLoading) { partial ->
						_state.update { current ->
							if (itemId == id && (current.loading || current.partial)) partial.copy(libraries = current.libraries) else current
						}
					} else null,
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load details of $id")
				null
			}

			_state.update { current ->
				when {
					loaded != null -> {
						// The Letterboxd rating read before stays (the page loads again after playing)
						val letterboxd = current.header?.letterboxdRating?.takeIf { current.item?.id == loaded.item?.id }
						loaded.copy(libraries = current.libraries, header = loaded.header?.copy(letterboxdRating = letterboxd))
					}
					// Keep what is on screen when a refresh fails
					current.item != null -> current.copy(loading = false)
					else -> current.copy(loading = false, failed = true)
				}
			}

			val item = loaded?.item
			if (loaded?.kind == DetailsKind.MOVIE && item != null && _state.value.header?.letterboxdRating == null) loadLetterboxd(item)
		}
	}

	/** Films: their average on Letterboxd, beside the IMDb rating once it comes */
	private fun loadLetterboxd(item: BaseItemDto) {
		letterboxdJob?.cancel()
		letterboxdJob = viewModelScope.launch {
			val rating = LetterboxdRatings.rating(context, item) ?: return@launch
			_state.update { state ->
				if (state.item?.id == item.id) state.copy(header = state.header?.copy(letterboxdRating = rating)) else state
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

			combine(libraryVisibilityRepository.hiddenLibraryIds, libraryVisibilityRepository.libraryOrder) { hidden, order -> hidden to order }
				.collect { (hiddenLibraryIds, order) ->
					_state.update { state ->
						state.copy(libraries = LibraryVisibilityRepository.arrange(libraries, order).filterNot { it.id in hiddenLibraryIds })
					}
				}
		}
	}

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	fun moveLibrary(library: BaseItemDto, offset: Int) {
		viewModelScope.launch { libraryVisibilityRepository.moveLibrary(library.id, offset) }
	}

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
			// Before the next episode is known (page still loading): asked now
			DetailsKind.SERIES -> state.playTarget?.id ?: runCatching { repository.resolvePlayableId(item) }.getOrNull()
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

/** Notices of new titles close together: the page loads once */
private const val LIBRARY_CHANGE_DEBOUNCE_MS = 1_500L
