// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.home.modern

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import org.jellyfin.androidtv.ui.base.Icon
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.koin.compose.koinInject
import java.util.Locale
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Height reserved for the top bar when there is no hero carousel */
private val TOP_BAR_SPACE = 80.dp

/** Fraction of the screen height used by the hero carousel, the first row peeks out below it */
private const val HERO_HEIGHT_FRACTION = 0.8f

/**
 * Streaming-service style home screen: floating navigation pill, hero carousel and rows with badges.
 */
@Composable
fun ModernHomeScreen(
	viewModel: ModernHomeViewModel,
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
	val firstCardFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	// Snapshot of the last focused card when this screen was (re)created
	val restorePosition = remember { viewModel.lastFocus }
	var initialFocusDone by remember { mutableStateOf(false) }
	var screenHasFocus by remember { mutableStateOf(false) }
	// Kept out of the composition: changing them must not recompose the whole screen at every
	// focus move (only the background reads them)
	val backgroundUrl = remember { mutableStateOf<String?>(null) }
	var heroIndex by rememberSaveable { mutableIntStateOf(0) }
	val posterActions = rememberPosterActionsState()
	val currentSlide = state.hero.getOrNull(heroIndex.coerceIn(0, (state.hero.size - 1).coerceAtLeast(0)))

	val topBarThresholdPx = with(density) { 40.dp.toPx() }
	val showTopBar by remember {
		derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < topBarThresholdPx }
	}
	val scrolledDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

	fun openDetails(id: UUID) = navigationRepository.navigate(Destinations.itemDetails(id))

	fun onCardClick(card: HomeCard) {
		if (card.playOnClick) playbackHelper.retrieveAndPlay(card.targetId, false, context)
		else openDetails(card.targetId)
	}

	// Hold OK on a card: menu with play, watched, identify, file info...
	fun onCardLongClick(card: HomeCard) = posterActions.open(card.menuTarget())

	fun playItem(item: BaseItemDto) {
		scope.launch {
			val playableId = viewModel.resolvePlayableId(item)
			if (playableId != null) playbackHelper.retrieveAndPlay(playableId, false, context)
			else openDetails(item.id)
		}
	}

	// Play/pause button of the remote on a poster: play the episode/movie shown on the card
	fun onCardPlay(card: HomeCard) {
		val playId = card.playId
		if (playId != null) {
			playbackHelper.retrieveAndPlay(playId, false, context)
			return
		}

		scope.launch {
			val resolvedId = viewModel.resolvePlayableId(card.item)
			if (resolvedId != null) playbackHelper.retrieveAndPlay(resolvedId, false, context)
			else openDetails(card.targetId)
		}
	}

	fun onHeroPlay(slide: HeroSlide) {
		scope.launch {
			val playableId = viewModel.resolvePlayableId(slide.item)
			if (playableId != null) playbackHelper.retrieveAndPlay(playableId, false, context)
			else openDetails(slide.item.id)
		}
	}

	// Back button while scrolled down: go back to the top instead of leaving the app
	BackHandler(enabled = scrolledDown) {
		scope.launch {
			listState.animateScrollToItem(0)
			if (state.hero.isNotEmpty()) heroFocusRequester.tryRequestFocus()
			else firstCardFocusRequester.tryRequestFocus()
		}
	}

	// Initial focus: restore the last focused card, otherwise focus the "Play" button of the hero
	LaunchedEffect(state.heroLoaded, state.sections.isNotEmpty()) {
		if (initialFocusDone || !state.heroLoaded) return@LaunchedEffect
		if (state.hero.isEmpty() && state.sections.isEmpty()) return@LaunchedEffect

		val restoreSectionIndex = restorePosition?.let { position ->
			state.sections.indexOfFirst { section ->
				section.type == position.section && section.cards.any { it.key == position.cardKey }
			}
		} ?: -1

		if (restoreSectionIndex >= 0) {
			listState.scrollToItem(restoreSectionIndex + 1)
			awaitFrames(2)
			if (restoreFocusRequester.tryRequestFocus()) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}

		awaitFrames(1)
		initialFocusDone = when {
			state.hero.isNotEmpty() -> heroFocusRequester.tryRequestFocus()
			else -> firstCardFocusRequester.tryRequestFocus()
		}
	}

	// The media bar keeps changing while browsing the rows (its item can be off screen, so the
	// timer lives here and not in the media bar)
	LaunchedEffect(heroIndex, state.hero.size) {
		if (state.hero.size < 2) return@LaunchedEffect
		delay(HERO_AUTO_ADVANCE)
		heroIndex = (heroIndex + 1) % state.hero.size
	}

	// The ambient background is always the current slide of the media bar (not the focused card);
	// the details page shows the item's own
	LaunchedEffect(currentSlide) {
		if (currentSlide != null) backgroundUrl.value = currentSlide.backgroundUrl
	}

	// Media bar images in the cache from the start, so every slide shows at once
	LaunchedEffect(state.hero) {
		for (slide in state.hero) {
			for (url in listOfNotNull(slide.backdropUrl, slide.logoUrl, slide.backgroundUrl)) {
				imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
			}
		}
	}

	// When a refresh removes the focused card (e.g. an episode finished and left "continue watching")
	// focus would be lost and the remote would stop responding, so move it back to the top
	LaunchedEffect(state.sections) {
		if (!initialFocusDone) return@LaunchedEffect
		val position = viewModel.lastFocus ?: return@LaunchedEffect
		val stillExists = state.sections.any { section ->
			section.type == position.section && section.cards.any { it.key == position.cardKey }
		}
		if (stillExists) return@LaunchedEffect

		awaitFrames(2)
		if (!screenHasFocus) {
			viewModel.lastFocus = null
			listState.scrollToItem(0)
			awaitFrames(1)
			if (!heroFocusRequester.tryRequestFocus()) firstCardFocusRequester.tryRequestFocus()
		}
	}

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background)
			.onFocusChanged { screenHasFocus = it.hasFocus },
	) {
		val heroHeight = maxHeight * HERO_HEIGHT_FRACTION
		val glassBackdrop = currentSlide?.let { slide ->
			with(density) { GlassBackdrop(slide.glassUrl, maxWidth.toPx(), heroHeight.toPx()) }
		}

		// Ambient background: heavily blurred backdrop of the hero slide or the focused card. The hero
		// fades out into it, so its colors flow through the whole page
		AmbientBackground(urlState = backgroundUrl, imageLoader = imageLoader)

		// Rows scroll into place by themselves (see onFocusChanged below), so automatic scrolling is disabled
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
							onPlay = { slide -> onHeroPlay(slide) },
							onInfo = { slide -> openDetails(slide.item.id) },
							modifier = Modifier.onFocusChanged { focusState ->
								if (focusState.hasFocus) {
									viewModel.lastFocus = null
									scope.launch { scroller.scrollToItem(0) }
								}
							},
						)
					} else {
						Spacer(Modifier.height(TOP_BAR_SPACE))
					}
				}

				itemsIndexed(
					items = state.sections,
					key = { _, section -> section.type.name },
				) { sectionIndex, section ->
					val restoreKey = restorePosition?.takeIf { it.section == section.type }?.cardKey
					val restoreCardIndex = restoreKey?.let { key -> section.cards.indexOfFirst { it.key == key } } ?: -1

					HomeSectionRow(
						section = section,
						title = sectionTitle(section.type),
						initialCardIndex = (restoreCardIndex - 1).coerceAtLeast(0),
						focusRequesterFor = { cardIndex, card ->
							when {
								restoreKey != null && card.key == restoreKey -> restoreFocusRequester
								sectionIndex == 0 && cardIndex == 0 -> firstCardFocusRequester
								else -> null
							}
						},
						onCardClick = { card -> onCardClick(card) },
						onCardLongClick = { card -> onCardLongClick(card) },
						onCardPlay = { card -> onCardPlay(card) },
						onCardFocused = { card ->
							viewModel.lastFocus = HomeFocusPosition(section.type, card.key)
						},
						modifier = Modifier
							.padding(top = ModernHomeDimens.SectionSpacing)
							.onFocusChanged { focusState ->
								if (focusState.hasFocus) scope.launch { scroller.scrollToItem(sectionIndex + 1) }
							},
					)
				}
			}
		}

		if (!state.heroLoaded && state.loading) {
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
				onNavigateDown = {
					if (state.hero.isNotEmpty()) heroFocusRequester.tryRequestFocus()
					else firstCardFocusRequester.tryRequestFocus()
				},
				onHideLibrary = { library -> viewModel.hideLibrary(library) },
				onMoveLibrary = { library, offset -> viewModel.moveLibrary(library, offset) },
			)
		}

		PosterActionsDialogs(
			state = posterActions,
			onPlay = ::playItem,
			onOpen = ::openDetails,
			onChanged = viewModel::refreshNow,
		)
	}
}

/**
 * Full screen blurred backdrop with a dark gradient on top. Reads the url itself so a new
 * background only recomposes this part of the screen.
 */
@Composable
internal fun AmbientBackground(
	urlState: State<String?>,
	imageLoader: ImageLoader,
) {
	Crossfade(
		targetState = urlState.value,
		animationSpec = tween(durationMillis = 700),
		label = "AmbientBackground",
		modifier = Modifier.fillMaxSize(),
	) { url ->
		if (url != null) {
			AsyncImage(
				model = url,
				contentDescription = null,
				imageLoader = imageLoader,
				contentScale = ContentScale.Crop,
				alignment = Alignment.TopCenter,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(
				Brush.verticalGradient(
					0f to ModernHomeColors.AmbientScrimTop,
					0.45f to ModernHomeColors.AmbientScrimMiddle,
					1f to ModernHomeColors.AmbientScrimBottom,
				)
			)
	)
}

@Composable
private fun sectionTitle(type: HomeSectionType): String = stringResource(type.titleRes)

/** Title of a home row, also used in Settings › Home sections */
internal val HomeSectionType.titleRes: Int
	get() = when (this) {
		HomeSectionType.CONTINUE_WATCHING -> R.string.modern_home_continue_watching
		HomeSectionType.WATCHLIST -> R.string.modern_home_watchlist
		HomeSectionType.TRENDING -> R.string.modern_home_trending
		HomeSectionType.LATEST_EPISODES -> R.string.modern_home_latest_episodes
		HomeSectionType.LATEST_FILMS -> R.string.modern_home_latest_films
		HomeSectionType.RECENT_MOVIES -> R.string.modern_home_recent_movies
		HomeSectionType.RECENT_SHOWS -> R.string.modern_home_recent_shows
		HomeSectionType.FAVORITES -> R.string.modern_home_favorites
	}

/**
 * A titled horizontal row of cards.
 */
@Composable
internal fun HomeSectionRow(
	section: HomeSection,
	title: String,
	initialCardIndex: Int,
	focusRequesterFor: (cardIndex: Int, card: HomeCard) -> FocusRequester?,
	onCardClick: (HomeCard) -> Unit,
	onCardLongClick: (HomeCard) -> Unit,
	onCardPlay: (HomeCard) -> Unit,
	onCardFocused: (HomeCard) -> Unit,
	modifier: Modifier = Modifier,
) = CardRow(
	style = section.style,
	cards = section.cards,
	title = title,
	initialCardIndex = initialCardIndex,
	focusRequesterFor = focusRequesterFor,
	onCardClick = onCardClick,
	onCardLongClick = onCardLongClick,
	onCardPlay = onCardPlay,
	onCardFocused = onCardFocused,
	modifier = modifier,
)

/**
 * A titled horizontal row of cards, with a "See all" card at the end when [onSeeAll] is set.
 */
@Composable
internal fun CardRow(
	style: CardStyle,
	cards: List<HomeCard>,
	title: String,
	initialCardIndex: Int,
	focusRequesterFor: (cardIndex: Int, card: HomeCard) -> FocusRequester?,
	onCardClick: (HomeCard) -> Unit,
	onCardLongClick: (HomeCard) -> Unit,
	onCardPlay: (HomeCard) -> Unit,
	onCardFocused: (HomeCard) -> Unit,
	modifier: Modifier = Modifier,
	onSeeAll: (() -> Unit)? = null,
) {
	val density = LocalDensity.current
	val bringIntoViewSpec = remember(density) {
		KeepInViewBringIntoViewSpec(marginPx = with(density) { ModernHomeDimens.ScreenPaddingHorizontal.toPx() })
	}
	val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialCardIndex)
	val focusMemory = rememberFocusMemory()

	Column(modifier = modifier.fillMaxWidth()) {
		SectionHeader(title)

		CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
			LazyRow(
				state = rowState,
				contentPadding = PaddingValues(
					horizontal = ModernHomeDimens.ScreenPaddingHorizontal,
					vertical = 12.dp,
				),
				horizontalArrangement = Arrangement.spacedBy(ModernHomeDimens.CardSpacing),
				verticalAlignment = Alignment.Bottom,
				modifier = Modifier
					.fillMaxWidth()
					.then(focusMemory.group),
			) {
				itemsIndexed(
					items = cards,
					key = { _, card -> card.key },
				) { cardIndex, card ->
					val focusRequester = focusRequesterFor(cardIndex, card)
					val memory = focusMemory.child(card.key)
					val onClick = { onCardClick(card) }
					val onLongClick = { onCardLongClick(card) }
					val onFocused = { onCardFocused(card) }
					val onPlay = { onCardPlay(card) }

					when (style) {
						CardStyle.LANDSCAPE -> LandscapeCard(
							card = card,
							onClick = onClick,
							onLongClick = onLongClick,
							onFocused = onFocused,
							focusRequester = focusRequester,
							modifier = memory,
						)

						CardStyle.POSTER -> PosterCard(
							card = card,
							onClick = onClick,
							onLongClick = onLongClick,
							onFocused = onFocused,
							focusRequester = focusRequester,
							modifier = memory,
							onPlay = onPlay,
						)

						CardStyle.RANKED -> RankedPosterCard(
							rank = cardIndex + 1,
							card = card,
							onClick = onClick,
							onLongClick = onLongClick,
							onFocused = onFocused,
							focusRequester = focusRequester,
							modifier = memory,
							onPlay = onPlay,
						)
					}
				}

				if (onSeeAll != null) {
					item(key = "see_all") {
						SeeAllCard(landscape = style == CardStyle.LANDSCAPE, onClick = onSeeAll, modifier = focusMemory.child("see_all"))
					}
				}
			}
		}
	}
}

@Composable
internal fun SectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	/** Smaller title, used on the details pages so more rows fit on screen */
	compact: Boolean = false,
) {
	Row(
		modifier = modifier.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, top = if (compact) 4.dp else 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.width(3.dp)
				.height(if (compact) 17.dp else 22.dp)
				.background(ModernHomeColors.Accent, RoundedCornerShape(2.dp))
		)
		Spacer(Modifier.width(10.dp))
		Text(
			text = title.uppercase(Locale.getDefault()),
			color = ModernHomeColors.TextPrimary,
			fontFamily = BebasNeue,
			fontSize = if (compact) 20.sp else 26.sp,
			fontWeight = FontWeight.Normal,
			letterSpacing = 0.5.sp,
			maxLines = 1,
		)
	}
}

/**
 * Request focus without crashing when the target is not composed (yet).
 */
internal fun FocusRequester.tryRequestFocus(): Boolean = try {
	val result: Any = requestFocus()
	result != false
} catch (err: IllegalStateException) {
	false
}

internal suspend fun awaitFrames(count: Int) = repeat(count) { withFrameNanos { } }

/**
 * Puts the focus back on an element when a page shows again, and keeps checking for a moment:
 * the page fades in and the system can give the focus to its first element a bit later. Stops as
 * soon as [interrupted] (a key was pressed). Returns true when the element has the focus.
 */
internal suspend fun FocusRequester.restoreFocus(
	hasFocus: () -> Boolean,
	interrupted: () -> Boolean,
	attempts: Int = 15,
): Boolean {
	var restored = false
	for (attempt in 0 until attempts) {
		awaitFrames(2)
		if (interrupted()) break
		restored = hasFocus() || tryRequestFocus()
	}
	return restored
}


/** Last card of a row: opens every title of it in a grid */
@Composable
internal fun SeeAllCard(
	landscape: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	FocusableCardSurface(
		onClick = onClick,
		onLongClick = null,
		onFocused = {},
		modifier = modifier.then(
			if (landscape) Modifier.size(ModernHomeDimens.LandscapeWidth, ModernHomeDimens.LandscapeHeight)
			else Modifier.size(ModernHomeDimens.PosterWidth, ModernHomeDimens.PosterHeight)
		),
	) { focused ->
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center,
			modifier = Modifier
				.fillMaxSize()
				.background(if (focused) Color.White else Color.White.copy(alpha = 0.06f)),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_grid),
				contentDescription = null,
				tint = if (focused) Color.Black else ModernHomeColors.TextPrimary,
				modifier = Modifier.size(26.dp),
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = stringResource(R.string.browse_see_all),
				color = if (focused) Color.Black else ModernHomeColors.TextPrimary,
				fontSize = 13.sp,
				fontWeight = FontWeight.Bold,
			)
		}
	}
}
