// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.livetv.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.CardRow
import org.jellyfin.androidtv.ui.home.modern.GlassBackdrop
import org.jellyfin.androidtv.ui.home.modern.HERO_AUTO_ADVANCE
import org.jellyfin.androidtv.ui.home.modern.HeroCarousel
import org.jellyfin.androidtv.ui.home.modern.HeroSlide
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.ModernTopBar
import org.jellyfin.androidtv.ui.home.modern.NoAutoScrollBringIntoViewSpec
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.rememberSmoothListScroller
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.library.modern.FilterPill
import org.jellyfin.androidtv.ui.library.modern.LibrarySection
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

private const val HERO_HEIGHT_FRACTION = 0.7f
/** What is on changes: the page is read again this often while it shows */
private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
private val TOP_BAR_SPACE = 80.dp
private const val HERO_PRELOAD = 3

/** The tabs of Live TV. The guide is a page of its own: its pill opens it */
enum class LiveTvTab { PROGRAMMES, GUIDE, CHANNELS, FAVOURITES }

data class ModernLiveTvState(
	val tab: LiveTvTab = LiveTvTab.PROGRAMMES,
	val loading: Boolean = true,
	val heroLoaded: Boolean = false,
	val hero: List<HeroSlide> = emptyList(),
	val sections: List<LibrarySection> = emptyList(),
	/** Why the programmes are missing */
	val error: String? = null,
	val channelsLoading: Boolean = false,
	val channelsLoaded: Boolean = false,
	val channels: List<LiveChannel> = emptyList(),
	val channelsError: String? = null,
	/** Channels tab: search and favourites filter */
	val query: String = "",
	val favouritesOnly: Boolean = false,
	/** Libraries of the navigation bar */
	val libraries: List<BaseItemDto> = emptyList(),
)

class ModernLiveTvViewModel(
	private val repository: ModernLiveTvRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(ModernLiveTvState())
	val state: StateFlow<ModernLiveTvState> = _state.asStateFlow()

	/** Last focused card of the Programmes tab (row key, card key), to come back to it */
	var lastFocus: Pair<String, String>? = null
	/** Last focused channel of the Channels and Favourites tabs */
	var lastChannelId: UUID? = null
	/** The pill that opened another page (the guide), focused again on the way back */
	var lastPill: LiveTvTab? = null

	private var librariesJob: Job? = null
	private var pageJob: Job? = null
	private var channelsJob: Job? = null
	private var pageLoadedAt = 0L
	private var channelsLoadedAt = 0L

	init {
		// What the page showed this session: it opens at once and refreshes
		memory?.let { page ->
			_state.value = ModernLiveTvState(loading = false, heroLoaded = true, hero = page.hero, sections = page.sections)
		}
		channelsMemory?.let { channels -> _state.update { it.copy(channels = channels, channelsLoaded = true) } }
		observeLibraries()
		refresh(force = true)
	}

	fun selectTab(tab: LiveTvTab) {
		if (tab == LiveTvTab.GUIDE) return
		_state.update { it.copy(tab = tab) }
		if (tab != LiveTvTab.PROGRAMMES && (!_state.value.channelsLoaded || channelsAge() > STALE_MS)) loadChannels()
	}

	/**
	 * Reads the page again. Not again within a minute (back from a channel at once), and the
	 * channels only for their tabs: the server's guide queries are heavy
	 */
	fun refresh(force: Boolean = false) {
		val now = System.currentTimeMillis()
		if (force || now - pageLoadedAt > MIN_REFRESH_MS) loadProgrammes()
		if (_state.value.tab != LiveTvTab.PROGRAMMES && (force || channelsAge() > MIN_REFRESH_MS)) loadChannels()
	}

	/**
	 * Leaving the page (a channel tuned in, another page): what is still loading stops, so the server
	 * and the connections are free for the stream
	 */
	fun stopLoading() {
		pageJob?.cancel()
		channelsJob?.cancel()
		_state.update { it.copy(loading = false, channelsLoading = false) }
	}

	private fun channelsAge() = System.currentTimeMillis() - channelsLoadedAt

	private fun loadProgrammes() {
		pageJob?.cancel()
		pageJob = viewModelScope.launch {
			try {
				val page = repository.programmes { partial -> show(partial, complete = false) }
				show(page, complete = true)
				if (page.sections.isNotEmpty() || page.hero.isNotEmpty()) memory = page
				pageLoadedAt = System.currentTimeMillis()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load Live TV")
				_state.update { it.copy(loading = false, heroLoaded = true, error = LiveTvProgramLoader.describe(err)) }
			}
		}
	}

	/** The rows that came; what the page showed stays until its row is read again */
	private fun show(page: ModernLiveTvRepository.ProgrammesPage, complete: Boolean) = _state.update { state ->
		val fresh = page.sections.associateBy { it.key }
		val sections = if (complete) page.sections
		else (state.sections.map { fresh[it.key] ?: it } + page.sections.filter { new -> state.sections.none { it.key == new.key } })
		// The slide on screen stays first, so the media bar doesn't jump
		val shown = state.hero.firstOrNull()
		val hero = when {
			page.hero.isEmpty() && !complete -> state.hero
			shown == null || page.hero.none { it.item.id == shown.item.id } -> page.hero
			else -> listOf(page.hero.first { it.item.id == shown.item.id }) + page.hero.filter { it.item.id != shown.item.id }
		}
		state.copy(
			loading = !complete && sections.isEmpty(),
			heroLoaded = complete || hero.isNotEmpty(),
			hero = hero,
			sections = sections,
			error = page.error,
		)
	}

	private fun loadChannels() {
		channelsJob?.cancel()
		_state.update { it.copy(channelsLoading = true) }
		channelsJob = viewModelScope.launch {
			try {
				val channels = repository.channels()
				channelsMemory = channels
				channelsLoadedAt = System.currentTimeMillis()
				_state.update { it.copy(channels = channels, channelsLoading = false, channelsLoaded = true, channelsError = null) }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load the channels")
				_state.update { it.copy(channelsLoading = false, channelsLoaded = true, channelsError = LiveTvProgramLoader.describe(err)) }
			}
		}
	}

	fun setQuery(query: String) = _state.update { it.copy(query = query) }

	fun setFavouritesOnly(favouritesOnly: Boolean) = _state.update { it.copy(favouritesOnly = favouritesOnly) }

	/** Adds the channel to the favourites or takes it out; [onDone] with whether it is one now (null: failed) */
	fun toggleFavourite(channel: LiveChannel, onDone: (Boolean?) -> Unit) {
		val favourite = !channel.favorite
		updateChannel(channel.id) { it.copy(favorite = favourite) }
		viewModelScope.launch {
			val result = try {
				repository.setFavorite(channel.id, favourite)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to change the favourite ${channel.name}")
				null
			}
			updateChannel(channel.id) { it.copy(favorite = result ?: channel.favorite) }
			channelsMemory = _state.value.channels
			onDone(result)
		}
	}

	private fun updateChannel(id: UUID, change: (LiveChannel) -> LiveChannel) = _state.update { state ->
		state.copy(channels = state.channels.map { if (it.id == id) change(it) else it })
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
					val visible = LibraryVisibilityRepository.arrange(libraries, order).filterNot { it.id in hidden }
					_state.update { it.copy(libraries = visible) }
				}
		}
	}

	private companion object {
		/** The page of this session */
		var memory: ModernLiveTvRepository.ProgrammesPage? = null
		var channelsMemory: List<LiveChannel>? = null

		const val MIN_REFRESH_MS = 60_000L
		const val STALE_MS = 5 * 60_000L
	}
}

class ModernLiveTvFragment : Fragment() {
	private val viewModel by viewModel<ModernLiveTvViewModel>()
	private var firstResume = true

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val library = arguments?.getString(Extras.Folder)?.let { json ->
			runCatching { Json.decodeFromString<BaseItemDto>(json) }
				.onFailure { Timber.w(it, "Invalid library argument") }
				.getOrNull()
		}
		return content {
			ModernLiveTvScreen(viewModel = viewModel, libraryId = library?.id)
		}
	}

	override fun onResume() {
		super.onResume()
		// Back from a channel or the guide: what is on now changed
		if (!firstResume) viewModel.refresh()
		firstResume = false
	}

	override fun onStop() {
		super.onStop()
		// A channel is starting (or another page opened): the guide queries make way for it
		viewModel.stopLoading()
	}
}

/**
 * Live TV in the style of the libraries: the navigation bar and the tabs Programmes (the media bar
 * with the highlights of the guide and the rows of Jellyfin web: on now, shows, movies, sports,
 * for kids, news), Guide, Channels (every channel, with a search and a favourites filter) and
 * Favourites. OK on a programme opens its page, ⏯ (or OK on a channel) tunes in, holding OK on a
 * channel adds it to the favourites or takes it out.
 */
@Composable
fun ModernLiveTvScreen(
	viewModel: ModernLiveTvViewModel,
	libraryId: UUID?,
) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val imageLoader = koinInject<ImageLoader>()

	val listState = rememberLazyListState()
	val scroller = rememberSmoothListScroller(listState)
	val heroFocusRequester = remember { FocusRequester() }
	val pillRequesters = remember { LiveTvTab.entries.associateWith { FocusRequester() } }
	val restoreFocusRequester = remember { FocusRequester() }
	val restoreFocus = remember { viewModel.lastFocus }
	val restorePill = remember { viewModel.lastPill }
	var initialFocusDone by remember { mutableStateOf(false) }
	var userMoved by remember { mutableStateOf(false) }
	var pillsFocused by remember { mutableStateOf(false) }
	// Back from a channel on the Channels or Favourites tab: the focus goes back to that channel
	var channelRestorePending by remember { mutableStateOf(state.tab != LiveTvTab.PROGRAMMES && viewModel.lastChannelId != null && viewModel.lastPill == null) }
	// A pill changed the tab: the focus goes to the pill of the new tab once it is laid out
	var focusPillOf by remember { mutableStateOf<LiveTvTab?>(null) }
	val backgroundUrl = remember { mutableStateOf<String?>(null) }
	var heroIndex by remember { mutableIntStateOf(0) }
	val currentSlide = state.hero.getOrNull(heroIndex.coerceIn(0, (state.hero.size - 1).coerceAtLeast(0)))
	val programmesTab = state.tab == LiveTvTab.PROGRAMMES

	val topBarThresholdPx = with(density) { 40.dp.toPx() }
	val atTop by remember {
		derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < topBarThresholdPx }
	}
	val scrolledDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

	fun tuneIn(channelId: UUID?) {
		if (channelId == null) return
		// The stream first: the page stops loading
		viewModel.stopLoading()
		playbackHelper.retrieveAndPlay(channelId, false, context)
	}
	fun openProgram(program: BaseItemDto) = navigationRepository.navigate(Destinations.liveProgram(program))
	fun onCardClick(card: HomeCard) {
		val item = card.item
		when {
			item.isLiveTvProgram -> openProgram(item)
			item.type == BaseItemKind.TV_CHANNEL || card.playOnClick -> tuneIn(item.id)
			else -> navigationRepository.navigate(Destinations.itemDetails(item.id))
		}
	}
	fun onCardPlay(card: HomeCard) = tuneIn(card.playId)
	fun focusPill(tab: LiveTvTab = state.tab) = pillRequesters.getValue(tab).tryRequestFocus()
	fun focusTop() = (programmesTab && heroFocusRequester.tryRequestFocus()) || focusPill()
	fun onTab(tab: LiveTvTab) {
		if (tab == LiveTvTab.GUIDE) {
			viewModel.lastPill = LiveTvTab.GUIDE
			navigationRepository.navigate(Destinations.liveTvGuide)
			return
		}
		viewModel.lastPill = null
		if (tab == state.tab) return
		channelRestorePending = false
		focusPillOf = tab
		viewModel.selectTab(tab)
	}
	fun toggleFavourite(channel: LiveChannel) {
		viewModel.toggleFavourite(channel) { favourite ->
			val message = when (favourite) {
				null -> context.getString(R.string.live_tv_favourite_failed)
				true -> context.getString(R.string.live_tv_favourite_added, channel.name)
				false -> context.getString(R.string.live_tv_favourite_removed, channel.name)
			}
			Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
		}
	}

	// The media bar keeps changing while browsing the rows
	LaunchedEffect(heroIndex, state.hero.size, programmesTab) {
		if (!programmesTab || state.hero.size < 2) return@LaunchedEffect
		delay(HERO_AUTO_ADVANCE)
		heroIndex = (heroIndex + 1) % state.hero.size
	}

	// The ambient background is the current slide of the media bar
	LaunchedEffect(currentSlide) {
		if (currentSlide != null) backgroundUrl.value = currentSlide.backgroundUrl
	}
	// The next slides ready in advance (not all: the pictures of the guide are slow for the server)
	LaunchedEffect(state.hero) {
		for (slide in state.hero.take(HERO_PRELOAD)) {
			for (url in listOfNotNull(slide.backdropUrl, slide.backgroundUrl, slide.channelLogoUrl)) {
				imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
			}
		}
	}

	// What is on now, read again while the page shows
	LaunchedEffect(Unit) {
		while (true) {
			delay(REFRESH_INTERVAL_MS)
			viewModel.refresh(force = true)
		}
	}

	// The pill of the new tab
	LaunchedEffect(focusPillOf, state.tab) {
		val tab = focusPillOf ?: return@LaunchedEffect
		if (tab != state.tab) return@LaunchedEffect
		if (tab == LiveTvTab.PROGRAMMES) listState.scrollToItem(1)
		awaitFrames(2)
		focusPill(tab)
		focusPillOf = null
	}

	// Back: the top of the programmes, the pills, then the Programmes tab, then out
	BackHandler(enabled = (programmesTab && scrolledDown) || (!programmesTab)) {
		when {
			programmesTab -> scope.launch {
				listState.animateScrollToItem(0)
				focusTop()
			}

			!pillsFocused -> focusPill()
			else -> onTab(LiveTvTab.PROGRAMMES)
		}
	}

	// Focus: the card or pill focused before, otherwise the first button of the media bar
	LaunchedEffect(state.heroLoaded, state.sections.isNotEmpty()) {
		if (initialFocusDone) return@LaunchedEffect
		if (!programmesTab) {
			// The channel tabs: the pill, unless the grid gives the focus back to the last channel
			awaitFrames(2)
			if (!channelRestorePending) focusPill(restorePill ?: state.tab)
			initialFocusDone = true
			return@LaunchedEffect
		}
		if (!state.heroLoaded && state.sections.isEmpty()) return@LaunchedEffect
		if (restorePill != null) {
			listState.scrollToItem(1)
			awaitFrames(2)
			if (focusPill(restorePill)) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}
		val restoreIndex = restoreFocus?.let { (section, _) -> state.sections.indexOfFirst { it.key == section } } ?: -1
		if (restoreIndex >= 0) {
			listState.scrollToItem(restoreIndex + 2)
			awaitFrames(2)
			if (restoreFocusRequester.tryRequestFocus()) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}
		awaitFrames(1)
		if (!userMoved) initialFocusDone = focusTop()
	}

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background)
			.onPreviewKeyEvent { event ->
				if (event.type == KeyEventType.KeyDown) userMoved = true
				false
			},
	) {
		val heroHeight = maxHeight * HERO_HEIGHT_FRACTION
		val glassBackdrop = currentSlide?.let { slide ->
			with(density) { GlassBackdrop(slide.glassUrl, maxWidth.toPx(), heroHeight.toPx()) }
		}

		AmbientBackground(urlState = backgroundUrl, imageLoader = imageLoader)

		val tabs = @Composable { modifier: Modifier ->
			LiveTvTabs(
				selected = state.tab,
				focusRequesters = pillRequesters,
				onTab = ::onTab,
				onFocusChanged = { pillsFocused = it },
				modifier = modifier,
			)
		}

		if (programmesTab) {
			CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScrollBringIntoViewSpec) {
				LazyColumn(
					state = listState,
					contentPadding = PaddingValues(bottom = 120.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					item(key = "hero") {
						if (state.hero.isNotEmpty()) {
							HeroCarousel(
								slides = state.hero,
								index = heroIndex,
								onIndexChange = { heroIndex = it },
								height = heroHeight,
								autoAdvance = false,
								playFocusRequester = heroFocusRequester,
								onPlay = { slide: HeroSlide -> tuneIn(slide.item.channelId) },
								onInfo = { slide: HeroSlide -> openProgram(slide.item) },
								modifier = Modifier.onFocusChanged { focusState ->
									if (focusState.hasFocus) {
										viewModel.lastFocus = null
										viewModel.lastPill = null
										scope.launch { scroller.scrollToItem(0) }
									}
								},
							)
						} else {
							Spacer(Modifier.height(TOP_BAR_SPACE))
						}
					}

					item(key = "pills") {
						tabs(
							Modifier
								.padding(top = 4.dp, bottom = 4.dp)
								.onFocusChanged { if (it.hasFocus) scope.launch { scroller.scrollToItem(1) } },
						)
					}

					itemsIndexed(
						items = state.sections,
						key = { _, section -> section.key },
					) { sectionIndex, section ->
						val restoreKey = restoreFocus?.takeIf { it.first == section.key }?.second
						val restoreCardIndex = restoreKey?.let { key -> section.cards.indexOfFirst { it.key == key } } ?: -1

						CardRow(
							style = section.style,
							cards = section.cards,
							title = section.title,
							initialCardIndex = (restoreCardIndex - 1).coerceAtLeast(0),
							focusRequesterFor = { _, card -> if (restoreKey != null && card.key == restoreKey) restoreFocusRequester else null },
							onCardClick = ::onCardClick,
							// Hold OK: the programme page too
							onCardLongClick = ::onCardClick,
							onCardPlay = ::onCardPlay,
							onCardFocused = { card ->
								viewModel.lastFocus = section.key to card.key
								viewModel.lastPill = null
							},
							onSeeAll = null,
							modifier = Modifier
								.padding(top = ModernHomeDimens.SectionSpacing)
								.onFocusChanged { focusState ->
									if (focusState.hasFocus) scope.launch { scroller.scrollToItem(sectionIndex + 2) }
								},
						)
					}
				}
			}

			if (state.loading && state.sections.isEmpty()) {
				CircularProgressIndicator(
					color = ModernHomeColors.Accent,
					modifier = Modifier
						.align(Alignment.Center)
						.size(48.dp),
				)
			} else if (!state.loading && state.hero.isEmpty() && state.sections.isEmpty()) {
				Text(
					text = state.error?.let { stringResource(R.string.live_tv_load_failed, it) } ?: stringResource(R.string.live_tv_empty),
					color = ModernHomeColors.TextSecondary,
					fontSize = 18.sp,
					textAlign = TextAlign.Center,
					modifier = Modifier
						.align(Alignment.Center)
						.padding(horizontal = 80.dp),
				)
			}
		} else {
			Column(modifier = Modifier.fillMaxSize()) {
				Spacer(Modifier.height(TOP_BAR_SPACE))
				tabs(Modifier.padding(top = 4.dp, bottom = 12.dp))
				LiveChannelsTab(
					state = state,
					favouritesTab = state.tab == LiveTvTab.FAVOURITES,
					restoreChannelId = if (channelRestorePending) viewModel.lastChannelId else null,
					onRestoreDone = { restored ->
						channelRestorePending = false
						if (!restored && !pillsFocused) focusPill()
					},
					onQueryChange = viewModel::setQuery,
					onFavouritesOnly = viewModel::setFavouritesOnly,
					onChannelFocused = {
						viewModel.lastChannelId = it.id
						viewModel.lastPill = null
					},
					onTuneIn = { tuneIn(it.id) },
					onToggleFavourite = ::toggleFavourite,
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f),
				)
			}
		}

		AnimatedVisibility(
			visible = !programmesTab || atTop,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.align(Alignment.TopCenter),
		) {
			ModernTopBar(
				libraries = state.libraries,
				glassBackdrop = if (programmesTab) glassBackdrop else null,
				homeActive = false,
				activeLibraryId = libraryId,
				onNavigateDown = { focusTop() },
				onHideLibrary = viewModel::hideLibrary,
				onMoveLibrary = viewModel::moveLibrary,
			)
		}
	}
}

/** Programmes, Guide, Channels and Favourites, in the glass pill of the libraries */
@Composable
private fun LiveTvTabs(
	selected: LiveTvTab,
	focusRequesters: Map<LiveTvTab, FocusRequester>,
	onTab: (LiveTvTab) -> Unit,
	onFocusChanged: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		modifier = modifier
			.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal)
			.onFocusChanged { onFocusChanged(it.hasFocus) },
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(2.dp),
			modifier = Modifier
				.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
				.padding(3.dp),
		) {
			for (tab in LiveTvTab.entries) {
				val (text, icon) = when (tab) {
					LiveTvTab.PROGRAMMES -> R.string.live_tv_tab_programmes to R.drawable.ic_tv
					LiveTvTab.GUIDE -> R.string.live_tv_tab_guide to R.drawable.ic_tv_guide
					LiveTvTab.CHANNELS -> R.string.live_tv_tab_channels to R.drawable.ic_grid
					LiveTvTab.FAVOURITES -> R.string.live_tv_tab_favourites to R.drawable.ic_heart
				}
				FilterPill(
					text = stringResource(text),
					icon = icon,
					selected = tab == selected,
					onClick = { onTab(tab) },
					modifier = Modifier.focusRequester(focusRequesters.getValue(tab)),
				)
			}
		}
	}
}

/** Message in the middle of an empty tab */
@Composable
internal fun LiveTvMessage(text: String, modifier: Modifier = Modifier) {
	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		Text(
			text = text,
			color = ModernHomeColors.TextSecondary,
			fontSize = 16.sp,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(horizontal = 48.dp),
		)
	}
}
