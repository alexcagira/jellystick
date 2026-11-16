// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.library.modern

import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
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
import org.jellyfin.androidtv.ui.home.modern.ModernHomeRepository
import org.jellyfin.androidtv.ui.home.modern.ModernTopBar
import org.jellyfin.androidtv.ui.home.modern.NoAutoScrollBringIntoViewSpec
import org.jellyfin.androidtv.ui.home.modern.PosterActionsDialogs
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.menuTarget
import org.jellyfin.androidtv.ui.home.modern.rememberPosterActionsState
import org.jellyfin.androidtv.ui.home.modern.rememberSmoothListScroller
import org.jellyfin.androidtv.ui.home.modern.restoreFocus
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
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
private val TOP_BAR_SPACE = 80.dp
private const val PILLS_SECTION = "pills"

/** Last focused card: row and card */
data class LibraryFocus(val section: String, val card: String)

class ModernLibraryViewModel(
	private val repository: ModernLibraryRepository,
	private val homeRepository: ModernHomeRepository,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(ModernLibraryState())
	val state: StateFlow<ModernLibraryState> = _state.asStateFlow()

	var lastFocus: LibraryFocus? = null
	private var library: BaseItemDto? = null
	/** The media bar comes from the page saved last time: replaced by fresh titles when they come */
	private var heroOutdated = false
	private var sectionsJob: Job? = null
	private var librariesJob: Job? = null

	fun load(library: BaseItemDto) {
		if (this.library?.id == library.id) return
		this.library = library
		observeLibraries(library)

		// Opened before in this session: shown at once while it refreshes
		val cached = repository.cached(library.id)
		if (cached != null) {
			val (hero, sections) = cached
			_state.update { it.copy(loading = false, heroLoaded = true, hero = hero, sections = sections) }
			refresh()
			return
		}

		// Otherwise what it showed the last time the app ran, unless the fresh rows are faster (a
		// fresh media bar replaces its one)
		viewModelScope.launch {
			val (hero, sections) = repository.savedPage(library.id) ?: return@launch
			val state = _state.value
			if (state.sections.isNotEmpty()) return@launch
			if (state.hero.isEmpty() && hero.isNotEmpty()) heroOutdated = true
			_state.value = state.copy(loading = false, heroLoaded = true, hero = state.hero.ifEmpty { hero }, sections = sections)
		}
		refresh()
	}

	fun refresh() {
		val library = library ?: return
		val kind = library.libraryKind ?: return
		val wantHero = _state.value.hero.isEmpty() || heroOutdated
		// On the main thread, like the saved page it can replace
		val onHero = { hero: List<HeroSlide> ->
			viewModelScope.launch { showHero(hero) }
			Unit
		}

		sectionsJob?.cancel()
		sectionsJob = viewModelScope.launch {
			try {
				val sections = repository.sections(
					libraryId = library.id,
					kind = kind,
					onPartial = { partial ->
						// The first rows, unless something is already on screen
						_state.update { if (it.sections.isEmpty()) it.copy(sections = partial, loading = false) else it }
					},
					// The media bar loads after the first rows: they show first
					onHero = onHero.takeIf { wantHero },
				)
				_state.update { it.copy(sections = keepFocusedRow(sections, it.sections), loading = false, heroLoaded = true) }

				// Then the badges of the series posters (one episode of each series is looked up)
				val withBadges = repository.withSeriesBadges(sections)
				if (withBadges !== sections) _state.update { it.copy(sections = keepFocusedRow(withBadges, it.sections)) }

				repository.store(library.id, _state.value.hero, withBadges)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load the rows of ${library.name}")
				_state.update { it.copy(loading = false, heroLoaded = true) }
			}
		}
	}

	/**
	 * Fresh rows over the ones on screen: the row with the focus stays as it is when its card is
	 * gone from the fresh one (rows picked at random change on every load), or the focus would be
	 * lost while browsing it.
	 */
	private fun keepFocusedRow(fresh: List<LibrarySection>, shown: List<LibrarySection>): List<LibrarySection> {
		val focus = lastFocus?.takeIf { it.section != PILLS_SECTION } ?: return fresh
		val focusedRow = shown.firstOrNull { it.key == focus.section } ?: return fresh
		return fresh.map { section ->
			if (section.key == focus.section && section.cards.none { it.key == focus.card }) focusedRow else section
		}
	}

	/**
	 * A fresh media bar. Over the one of the saved page, the slide on screen stays first so nothing
	 * jumps; an empty answer (server error) keeps what is there.
	 */
	private fun showHero(hero: List<HeroSlide>) {
		_state.update { state ->
			val shown = state.hero.firstOrNull()
			val merged = when {
				hero.isEmpty() -> state.hero
				shown == null || !heroOutdated -> state.hero.ifEmpty { hero }
				else -> listOf(shown) + hero.filter { it.item.id != shown.item.id }
			}
			state.copy(hero = merged, heroLoaded = true)
		}
		if (hero.isNotEmpty()) heroOutdated = false
	}

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = homeRepository.resolvePlayableId(item)

	fun hideLibrary(library: BaseItemDto) = libraryVisibilityRepository.setHidden(library.id, hidden = true)

	fun moveLibrary(library: BaseItemDto, offset: Int) {
		viewModelScope.launch { libraryVisibilityRepository.moveLibrary(library.id, offset) }
	}

	private fun observeLibraries(current: BaseItemDto) {
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
					_state.update { state ->
						state.copy(
							libraries = visible,
							siblings = visible.filter { it.collectionType == current.collectionType },
						)
					}
				}
		}
	}
}

class ModernLibraryFragment : Fragment() {
	private val viewModel by viewModel<ModernLibraryViewModel>()
	private var firstResume = true

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val library = arguments?.getString(Extras.Folder)?.let { json ->
			runCatching { Json.decodeFromString<BaseItemDto>(json) }
				.onFailure { Timber.w(it, "Invalid library argument") }
				.getOrNull()
		}
		if (library != null) viewModel.load(library)

		return content {
			if (library != null) ModernLibraryScreen(viewModel = viewModel, library = library)
		}
	}

	override fun onResume() {
		super.onResume()
		// "Continue watching" changes after watching something
		if (!firstResume) viewModel.refresh()
		firstResume = false
	}
}

/**
 * Movie or TV show library in the style of the home screen: media bar of the library, the other
 * libraries of the same kind as pills, and its rows. Hold OK on a poster for its menu.
 */
@Composable
fun ModernLibraryScreen(
	viewModel: ModernLibraryViewModel,
	library: BaseItemDto,
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
	val pillsFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	val pillRestoreFocusRequester = remember { FocusRequester() }
	// What has the focus in the rows, to check the restored element keeps it
	var currentFocus by remember { mutableStateOf<LibraryFocus?>(null) }
	var listFocused by remember { mutableStateOf(false) }
	var userMoved by remember { mutableStateOf(false) }
	val restoreFocus = remember { viewModel.lastFocus }
	var initialFocusDone by remember { mutableStateOf(false) }
	var focusHeroWhenLoaded by remember { mutableStateOf(false) }
	val backgroundUrl = remember { mutableStateOf<String?>(null) }
	var heroIndex by remember { mutableIntStateOf(0) }
	val posterActions = rememberPosterActionsState()
	val currentSlide = state.hero.getOrNull(heroIndex.coerceIn(0, (state.hero.size - 1).coerceAtLeast(0)))

	val topBarThresholdPx = with(density) { 40.dp.toPx() }
	val showTopBar by remember {
		derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < topBarThresholdPx }
	}
	val scrolledDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

	fun openDetails(id: UUID) = navigationRepository.navigate(Destinations.itemDetails(id))
	fun play(item: BaseItemDto) {
		scope.launch {
			val playableId = viewModel.resolvePlayableId(item)
			if (playableId != null) playbackHelper.retrieveAndPlay(playableId, false, context)
			else openDetails(item.id)
		}
	}
	fun onCardClick(card: HomeCard) {
		when {
			card.playOnClick -> playbackHelper.retrieveAndPlay(card.targetId, false, context)
			// Collections: the grid of their movies
			card.item.type == BaseItemKind.BOX_SET -> navigationRepository.navigate(Destinations.collectionBrowser(card.item))
			else -> openDetails(card.targetId)
		}
	}
	fun onCardPlay(card: HomeCard) {
		val playId = card.playId
		if (playId != null) playbackHelper.retrieveAndPlay(playId, false, context)
		else play(card.item)
	}
	fun focusTop() = heroFocusRequester.tryRequestFocus() || pillsFocusRequester.tryRequestFocus()

	// The media bar keeps changing while browsing the rows (its item can be off screen, so the
	// timer lives here and not in the media bar)
	LaunchedEffect(heroIndex, state.hero.size) {
		if (state.hero.size < 2) return@LaunchedEffect
		delay(HERO_AUTO_ADVANCE)
		heroIndex = (heroIndex + 1) % state.hero.size
	}

	// The ambient background is the current slide of the media bar
	LaunchedEffect(currentSlide) {
		if (currentSlide != null) backgroundUrl.value = currentSlide.backgroundUrl
	}
	// Media bar images in the cache from the start
	LaunchedEffect(state.hero) {
		for (slide in state.hero) {
			for (url in listOfNotNull(slide.backdropUrl, slide.logoUrl, slide.backgroundUrl)) {
				imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
			}
		}
	}

	BackHandler(enabled = scrolledDown) {
		scope.launch {
			listState.animateScrollToItem(0)
			focusTop()
		}
	}

	// Focus: the card focused before, otherwise "Play" in the media bar. The first rows come
	// before the media bar: the pills meanwhile
	LaunchedEffect(state.heroLoaded, state.sections.isNotEmpty()) {
		if (initialFocusDone || (!state.heroLoaded && state.sections.isEmpty())) return@LaunchedEffect

		// Back from Browse, Collections...: that pill again
		// The page fades in when coming back: the element is focused again until it keeps the focus
		fun restored() = listFocused && currentFocus == restoreFocus
		if (restoreFocus?.section == PILLS_SECTION) {
			listState.scrollToItem(1)
			if (pillRestoreFocusRequester.restoreFocus(hasFocus = ::restored, interrupted = { userMoved }) || userMoved) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}

		val restoreIndex = restoreFocus?.let { focus -> state.sections.indexOfFirst { it.key == focus.section } } ?: -1
		if (restoreIndex >= 0) {
			listState.scrollToItem(restoreIndex + 2)
			if (restoreFocusRequester.restoreFocus(hasFocus = ::restored, interrupted = { userMoved }) || userMoved) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}
		// Its row isn't there yet (one of the later ones)
		if (restoreFocus != null && restoreFocus.section != PILLS_SECTION && restoreIndex < 0 && !state.heroLoaded) return@LaunchedEffect

		awaitFrames(1)
		if (state.heroLoaded) {
			initialFocusDone = focusTop()
		} else if (pillsFocusRequester.tryRequestFocus()) {
			initialFocusDone = true
			focusHeroWhenLoaded = true
		}
	}

	// The media bar came after the first rows: its "Play" button, unless a key was pressed since
	LaunchedEffect(focusHeroWhenLoaded, state.heroLoaded) {
		if (!focusHeroWhenLoaded || !state.heroLoaded) return@LaunchedEffect
		focusHeroWhenLoaded = false
		if (!userMoved && state.hero.isNotEmpty()) {
			awaitFrames(2)
			if (!userMoved) heroFocusRequester.tryRequestFocus()
		}
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

		CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScrollBringIntoViewSpec) {
			LazyColumn(
				state = listState,
				contentPadding = PaddingValues(bottom = 120.dp),
				modifier = Modifier
					.fillMaxSize()
					.onFocusChanged { listFocused = it.hasFocus },
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
							onPlay = { slide: HeroSlide -> play(slide.item) },
							onInfo = { slide: HeroSlide -> openDetails(slide.item.id) },
							modifier = Modifier.onFocusChanged { focusState ->
								if (focusState.hasFocus) {
									viewModel.lastFocus = null
									currentFocus = null
									scope.launch { scroller.scrollToItem(0) }
								}
							},
						)
					} else if (!state.heroLoaded && state.sections.isNotEmpty()) {
						// Its place while it loads, so the rows don't jump when it comes
						HeroPlaceholder(height = heroHeight)
					} else {
						Spacer(Modifier.height(TOP_BAR_SPACE))
					}
				}

				item(key = "pills") {
					LibraryPills(
						libraries = state.siblings.ifEmpty { listOf(library) },
						currentId = library.id,
						firstFocusRequester = pillsFocusRequester,
						restoreKey = restoreFocus?.takeIf { it.section == PILLS_SECTION }?.card,
						restoreFocusRequester = pillRestoreFocusRequester,
						onPillFocused = { key ->
							viewModel.lastFocus = LibraryFocus(PILLS_SECTION, key)
							currentFocus = viewModel.lastFocus
						},
						onLibrary = { other ->
							if (other.id != library.id) navigationRepository.navigate(Destinations.modernLibrary(other), true)
						},
						onBrowse = { navigationRepository.navigate(Destinations.libraryBrowse(library)) },
						// The collections of this kind: of films in Films, of series in TV Shows
						onCollections = {
							val title = context.getString(R.string.library_collections)
							val kind = library.libraryKind
							navigationRepository.navigate(
								Destinations.modernBrowse(
									BrowseFilter(title = title, libraryId = library.id, kind = kind, collections = true, collectionsOf = kind)
								)
							)
						},
						modifier = Modifier
							.padding(top = 4.dp, bottom = 4.dp)
							// Not while the media bar loads: the page stays where it will be
							.onFocusChanged { if (it.hasFocus && state.heroLoaded) scope.launch { scroller.scrollToItem(1) } },
					)
				}

				itemsIndexed(
					items = state.sections,
					key = { _, section -> section.key },
				) { sectionIndex, section ->
					val restoreKey = restoreFocus?.takeIf { it.section == section.key }?.card
					val restoreCardIndex = restoreKey?.let { key -> section.cards.indexOfFirst { it.key == key } } ?: -1
					val seeAll = section.seeAll

					CardRow(
						style = section.style,
						cards = section.cards,
						title = section.title,
						initialCardIndex = (restoreCardIndex - 1).coerceAtLeast(0),
						focusRequesterFor = { _, card -> if (restoreKey != null && card.key == restoreKey) restoreFocusRequester else null },
						onCardClick = ::onCardClick,
						onCardLongClick = { card -> posterActions.open(card.menuTarget()) },
						onCardPlay = ::onCardPlay,
						onCardFocused = { card ->
							viewModel.lastFocus = LibraryFocus(section.key, card.key)
							currentFocus = viewModel.lastFocus
						},
						onSeeAll = seeAll?.let { filter -> { navigationRepository.navigate(Destinations.modernBrowse(filter)) } },
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
				text = stringResource(R.string.modern_home_empty),
				color = ModernHomeColors.TextSecondary,
				fontSize = 18.sp,
				modifier = Modifier.align(Alignment.Center),
			)
		}

		AnimatedVisibility(
			visible = showTopBar,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.align(Alignment.TopCenter),
		) {
			ModernTopBar(
				libraries = state.libraries,
				glassBackdrop = glassBackdrop,
				homeActive = false,
				activeLibraryId = library.id,
				onNavigateDown = { focusTop() },
				onHideLibrary = viewModel::hideLibrary,
				onMoveLibrary = viewModel::moveLibrary,
			)
		}

		PosterActionsDialogs(
			state = posterActions,
			onPlay = ::play,
			onOpen = ::openDetails,
			onChanged = viewModel::refresh,
		)
	}
}

/** Place of the media bar while its titles load */
@Composable
private fun HeroPlaceholder(height: Dp) {
	Box(
		contentAlignment = Alignment.Center,
		modifier = Modifier
			.fillMaxWidth()
			.height(height)
			.background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent)),
	) {
		CircularProgressIndicator(
			color = ModernHomeColors.Accent.copy(alpha = 0.7f),
			modifier = Modifier.size(30.dp),
		)
	}
}

/** The libraries of the same kind (Films, Películas...) and "Browse", under the media bar */
@Composable
private fun LibraryPills(
	libraries: List<BaseItemDto>,
	currentId: UUID,
	firstFocusRequester: FocusRequester,
	restoreKey: String?,
	restoreFocusRequester: FocusRequester,
	onPillFocused: (String) -> Unit,
	onLibrary: (BaseItemDto) -> Unit,
	onBrowse: () -> Unit,
	onCollections: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	// Each pill remembers it was focused, to come back to it from its page
	fun pill(key: String, first: Boolean = false): Modifier = Modifier
		.then(if (first) Modifier.focusRequester(firstFocusRequester) else Modifier)
		.then(if (key == restoreKey) Modifier.focusRequester(restoreFocusRequester) else Modifier)
		.onFocusChanged { if (it.isFocused) onPillFocused(key) }

	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(2.dp),
			modifier = Modifier
				.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
				.padding(3.dp),
		) {
			libraries.forEachIndexed { index, other ->
				FilterPill(
					text = other.name.orEmpty(),
					selected = other.id == currentId,
					onClick = { onLibrary(other) },
					modifier = pill(other.id.toString(), first = index == 0),
				)
			}
			if (onCollections != null) {
				FilterPill(
					text = stringResource(R.string.library_collections),
					selected = false,
					onClick = onCollections,
					modifier = pill("collections"),
				)
			}
		}
		FilterPill(
			text = stringResource(R.string.library_browse),
			icon = R.drawable.ic_grid,
			selected = false,
			onClick = onBrowse,
			outlined = true,
			modifier = pill("browse"),
		)
	}
}
