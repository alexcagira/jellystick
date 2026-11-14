package org.jellyfin.androidtv.ui.search.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.ModernHomeRepository
import org.jellyfin.androidtv.ui.music.modern.ModernMusicRepository
import org.jellyfin.androidtv.ui.music.modern.MusicCard
import org.jellyfin.androidtv.ui.search.SearchRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID

/** A row of results: posters (films, series...) or music cards */
sealed interface SearchGroup {
	val title: Int

	data class Posters(override val title: Int, val cards: List<HomeCard>) : SearchGroup
	data class Music(override val title: Int, val cards: List<MusicCard>) : SearchGroup
}

data class SearchState(
	val query: String = "",
	val searching: Boolean = false,
	val searched: Boolean = false,
	val groups: List<SearchGroup> = emptyList(),
)

class ModernSearchViewModel(
	private val searchRepository: SearchRepository,
	private val homeRepository: ModernHomeRepository,
	private val musicRepository: ModernMusicRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(SearchState())
	val state: StateFlow<SearchState> = _state.asStateFlow()

	var musicOnly = false
	var lastFocusKey: String? = null
	private var searchJob: Job? = null

	fun setQuery(query: String, immediate: Boolean = false) {
		_state.update { it.copy(query = query) }
		searchJob?.cancel()
		val term = query.trim()
		if (term.length < MIN_QUERY_LENGTH) {
			_state.update { it.copy(searching = false, searched = false, groups = emptyList()) }
			return
		}

		searchJob = viewModelScope.launch {
			if (!immediate) delay(DEBOUNCE_MS)
			_state.update { it.copy(searching = true) }
			val groups = try {
				search(term)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Search failed")
				emptyList()
			}
			_state.update { it.copy(searching = false, searched = true, groups = groups) }
		}
	}

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = homeRepository.resolvePlayableId(item)

	private suspend fun search(term: String): List<SearchGroup> = coroutineScope {
		suspend fun find(vararg types: BaseItemKind): List<BaseItemDto> =
			searchRepository.search(term, types.toList()).getOrDefault(emptyList())

		val video = if (musicOnly) null else listOf(
			R.string.lbl_movies to async { find(BaseItemKind.MOVIE) },
			R.string.lbl_tv_series to async { find(BaseItemKind.SERIES) },
			R.string.lbl_episodes to async { find(BaseItemKind.EPISODE) },
			R.string.lbl_collections to async { find(BaseItemKind.BOX_SET) },
			R.string.lbl_people to async { find(BaseItemKind.PERSON) },
		)
		val songs = async { find(BaseItemKind.AUDIO) }
		val albums = async { find(BaseItemKind.MUSIC_ALBUM) }
		val artists = async { find(BaseItemKind.MUSIC_ARTIST) }
		val playlists = async { if (musicOnly) find(BaseItemKind.PLAYLIST).filter { it.mediaType == org.jellyfin.sdk.model.api.MediaType.AUDIO } else emptyList() }

		val videoGroups = video.orEmpty().mapNotNull { (title, items) ->
			val cards = withContext(Dispatchers.IO) { homeRepository.posterCards(items.await()) }
			if (cards.isEmpty()) null else SearchGroup.Posters(title, cards)
		}
		val musicGroups = listOf(
			SearchGroup.Music(R.string.lbl_songs, songs.await().map { musicRepository.trackCard(it) }),
			SearchGroup.Music(R.string.lbl_albums, albums.await().map { musicRepository.albumCard(it) }),
			SearchGroup.Music(R.string.lbl_artists, artists.await().map { musicRepository.artistCard(it) }),
			SearchGroup.Music(R.string.lbl_playlists, playlists.await().map { musicRepository.playlistCard(it) }),
		).filter { it.cards.isNotEmpty() }

		// Music page: songs first. Everywhere else films and series first
		if (musicOnly) musicGroups else videoGroups + musicGroups
	}

	private companion object {
		const val MIN_QUERY_LENGTH = 2
		const val DEBOUNCE_MS = 450L
	}
}
