package org.jellyfin.androidtv.ui.music.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.SectionHeader
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import timber.log.Timber

/** Music pages with a grid of cards */
enum class MusicBrowseMode {
	GENRES,
	STATIONS,
}

private const val COLUMNS = 7

/** A titled group of cards (stations page: for you, genres, artists) */
data class MusicBrowseGroup(
	val title: Int?,
	val cards: List<MusicCard>,
)

data class MusicBrowseState(
	val loading: Boolean = true,
	val groups: List<MusicBrowseGroup> = emptyList(),
)

class ModernMusicBrowseViewModel(
	private val repository: ModernMusicRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(MusicBrowseState())
	val state: StateFlow<MusicBrowseState> = _state.asStateFlow()

	/** Last focused card, restored when coming back to the page */
	var lastFocusKey: String? = null
	private var loaded = false

	fun load(mode: MusicBrowseMode, library: BaseItemDto, labels: MusicLabels) {
		if (loaded) return
		loaded = true
		viewModelScope.launch {
			val groups = try {
				when (mode) {
					MusicBrowseMode.GENRES -> listOf(MusicBrowseGroup(null, repository.genres(library.id)))
					MusicBrowseMode.STATIONS -> repository.allStations(library.id, labels).let { stations ->
						listOf(
							MusicBrowseGroup(R.string.music_stations_for_you, stations.forYou),
							MusicBrowseGroup(R.string.lbl_genres, stations.genres),
							MusicBrowseGroup(R.string.lbl_artists, stations.artists),
						).filter { it.cards.isNotEmpty() }
					}
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load the music $mode")
				emptyList()
			}
			_state.value = MusicBrowseState(loading = false, groups = groups)
		}
	}

	suspend fun stationTracks(source: StationSource): List<BaseItemDto> =
		runCatching { repository.stationTracks(source) }.getOrDefault(emptyList())
}

class ModernMusicBrowseFragment : Fragment() {
	companion object {
		const val ARGUMENT_MODE = "Mode"
	}

	private val viewModel by viewModel<ModernMusicBrowseViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val library = arguments?.getString(Extras.Folder)?.let { json -> runCatching { Json.decodeFromString<BaseItemDto>(json) }.getOrNull() }
		val mode = arguments?.getString(ARGUMENT_MODE)?.let { name -> MusicBrowseMode.entries.firstOrNull { it.name == name } } ?: MusicBrowseMode.GENRES

		if (library != null) {
			viewModel.load(
				mode = mode,
				library = library,
				labels = MusicLabels(
					yourMix = getString(R.string.modern_music_your_mix),
					radio = getString(R.string.modern_music_radio),
					recommended = getString(R.string.modern_music_recommended),
					becauseYouListen = { artist -> getString(R.string.modern_music_because_you_listen, artist) },
				),
			)
		}

		return content { ModernMusicBrowseScreen(viewModel, mode) }
	}
}

@Composable
fun ModernMusicBrowseScreen(
	viewModel: ModernMusicBrowseViewModel,
	mode: MusicBrowseMode,
) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackLauncher = koinInject<PlaybackLauncher>()
	val imageLoader = koinInject<ImageLoader>()
	val listState = rememberLazyListState()
	val firstFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	val restoreKey = remember { viewModel.lastFocusKey }
	val backgroundState = remember { mutableStateOf<String?>(null) }

	fun playStation(source: StationSource) {
		scope.launch {
			val tracks = viewModel.stationTracks(source)
			if (tracks.isNotEmpty()) playbackLauncher.launch(context, tracks)
		}
	}

	fun onClick(card: MusicCard) {
		when (card.kind) {
			MusicCardKind.STATION -> card.station?.let(::playStation)
			else -> card.item?.let { navigationRepository.navigate(Destinations.modernMusicItem(it.id)) }
		}
	}

	fun onPlay(card: MusicCard) {
		when (card.kind) {
			MusicCardKind.STATION -> card.station?.let(::playStation)
			MusicCardKind.GENRE -> card.item?.let { playStation(StationSource(genreId = it.id)) }
			else -> onClick(card)
		}
	}

	LaunchedEffect(state.loading) {
		if (state.loading) return@LaunchedEffect
		awaitFrames(2)
		if (!restoreFocusRequester.tryRequestFocus()) firstFocusRequester.tryRequestFocus()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background),
	) {
		AmbientBackground(urlState = backgroundState, imageLoader = imageLoader)

		LazyColumn(
			state = listState,
			contentPadding = PaddingValues(top = 40.dp, bottom = 80.dp),
			modifier = Modifier.fillMaxSize(),
		) {
			item(key = "title") {
				Text(
					text = stringResource(if (mode == MusicBrowseMode.GENRES) R.string.lbl_genres else R.string.modern_music_stations),
					color = ModernHomeColors.TextPrimary,
					fontSize = 30.sp,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, bottom = 6.dp),
				)
			}

			for ((groupIndex, group) in state.groups.withIndex()) {
				val firstGroup = groupIndex == 0
				if (group.title != null) {
					item(key = "group_$groupIndex") {
						SectionHeader(stringResource(group.title), compact = true, modifier = Modifier.padding(top = 14.dp))
					}
				}
				cardGrid(
					keyPrefix = "group_$groupIndex",
					cards = group.cards,
					focusRequesterFor = { card ->
						when {
							card.key == restoreKey -> restoreFocusRequester
							firstGroup && card == group.cards.first() -> firstFocusRequester
							else -> null
						}
					},
					onFocused = { card ->
						viewModel.lastFocusKey = card.key
						card.backgroundUrl?.let { backgroundState.value = it }
					},
					onClick = ::onClick,
					onPlay = ::onPlay,
				)
			}
		}

		if (state.loading) {
			CircularProgressIndicator(color = ModernHomeColors.Accent, modifier = Modifier.align(Alignment.Center).size(48.dp))
		} else if (state.groups.isEmpty()) {
			Text(
				text = stringResource(R.string.music_nothing_here),
				color = ModernHomeColors.TextSecondary,
				fontSize = 16.sp,
				modifier = Modifier.align(Alignment.Center),
			)
		}
	}
}

/** Cards in rows of [COLUMNS], as items of a lazy list */
internal fun LazyListScope.cardGrid(
	keyPrefix: String,
	cards: List<MusicCard>,
	focusRequesterFor: (MusicCard) -> FocusRequester?,
	onFocused: (MusicCard) -> Unit,
	onClick: (MusicCard) -> Unit,
	onPlay: (MusicCard) -> Unit,
	onLongClick: (MusicCard) -> Unit = onClick,
) {
	itemsIndexed(cards.chunked(COLUMNS), key = { index, _ -> "${keyPrefix}_row_$index" }) { _, row ->
		Row(
			horizontalArrangement = Arrangement.spacedBy(ModernHomeDimens.CardSpacing),
			modifier = Modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal, vertical = 10.dp),
		) {
			for (card in row) {
				MusicCardView(
					card = card,
					focusRequester = focusRequesterFor(card),
					onFocused = { onFocused(card) },
					onClick = { onClick(card) },
					onLongClick = { onLongClick(card) },
					onPlay = { onPlay(card) },
				)
			}
		}
	}
}
