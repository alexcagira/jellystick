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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
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
	val heroFocusRequester = remember { FocusRequester() }
	val firstCardFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	// Snapshot of the last focused card when this screen was (re)created
	val restorePosition = remember { viewModel.lastFocus }
	var initialFocusDone by remember { mutableStateOf(false) }
	var screenHasFocus by remember { mutableStateOf(false) }
	var backgroundUrl by remember { mutableStateOf<String?>(null) }
	// Background of the focused card, applied once the focus rests a moment (no full screen
	// crossfade for every card while scrolling fast)
	var pendingBackgroundUrl by remember { mutableStateOf<String?>(null) }
	var heroIndex by rememberSaveable { mutableIntStateOf(0) }
	var menuCard by remember { mutableStateOf<HomeCard?>(null) }
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

	// Long press: menu on "Continue watching" (which plays on click), details screen everywhere else
	fun onCardLongClick(card: HomeCard) {
		if (card.playOnClick) menuCard = card
		else openDetails(card.targetId)
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

	LaunchedEffect(pendingBackgroundUrl) {
		val url = pendingBackgroundUrl ?: return@LaunchedEffect
		delay(BACKGROUND_DELAY_MS)
		backgroundUrl = url
	}

	// While the hero is on screen the ambient background follows the current slide
	LaunchedEffect(currentSlide, showTopBar) {
		if (showTopBar && currentSlide != null) backgroundUrl = currentSlide.backgroundUrl
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
		Crossfade(
			targetState = backgroundUrl,
			animationSpec = tween(durationMillis = 800),
			label = "HomeBackground",
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
							autoAdvance = showTopBar,
							playFocusRequester = heroFocusRequester,
							onPlay = { slide -> onHeroPlay(slide) },
							onInfo = { slide -> openDetails(slide.item.id) },
							modifier = Modifier.onFocusChanged { focusState ->
								if (focusState.hasFocus) {
									viewModel.lastFocus = null
									scope.launch { listState.animateScrollToItem(0) }
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
							pendingBackgroundUrl = card.backgroundUrl
							viewModel.lastFocus = HomeFocusPosition(section.type, card.key)
						},
						modifier = Modifier
							.padding(top = ModernHomeDimens.SectionSpacing)
							.onFocusChanged { focusState ->
								if (focusState.hasFocus) scope.launch { listState.animateScrollToItem(sectionIndex + 1) }
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

		ContinueWatchingMenu(
			card = menuCard,
			onDismissRequest = { menuCard = null },
			onMarkWatched = { card -> viewModel.markWatched(card.item) },
			onOpen = { id -> openDetails(id) },
		)
	}
}

/**
 * Menu shown when holding OK on a "Continue watching" card.
 */
@Composable
private fun ContinueWatchingMenu(
	card: HomeCard?,
	onDismissRequest: () -> Unit,
	onMarkWatched: (HomeCard) -> Unit,
	onOpen: (UUID) -> Unit,
) {
	val item = card?.item
	val seasonId = item?.seasonId
	val seriesId = item?.seriesId

	val options = buildList {
		if (card == null) return@buildList

		add(ModernMenuOption(stringResource(R.string.modern_home_mark_watched), R.drawable.ic_check) { onMarkWatched(card) })
		if (seasonId != null) add(ModernMenuOption(stringResource(R.string.modern_home_go_to_season), R.drawable.ic_folder) { onOpen(seasonId) })
		if (seriesId != null) add(ModernMenuOption(stringResource(R.string.modern_home_go_to_show), R.drawable.ic_tv) { onOpen(seriesId) })
		// Movies: their details page
		if (seriesId == null) add(ModernMenuOption(stringResource(R.string.modern_home_more_info), R.drawable.ic_info) { onOpen(card.targetId) })
	}

	ModernMenuDialog(
		visible = card != null,
		title = card?.title.orEmpty(),
		subtitle = card?.subtitle,
		options = options,
		onDismissRequest = onDismissRequest,
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
) {
	val density = LocalDensity.current
	val bringIntoViewSpec = remember(density) {
		KeepInViewBringIntoViewSpec(marginPx = with(density) { ModernHomeDimens.ScreenPaddingHorizontal.toPx() })
	}
	val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialCardIndex)

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
					.focusRestorer()
					.focusGroup(),
			) {
				itemsIndexed(
					items = section.cards,
					key = { _, card -> card.key },
				) { cardIndex, card ->
					val focusRequester = focusRequesterFor(cardIndex, card)
					val onClick = { onCardClick(card) }
					val onLongClick = { onCardLongClick(card) }
					val onFocused = { onCardFocused(card) }
					val onPlay = { onCardPlay(card) }

					when (section.style) {
						CardStyle.LANDSCAPE -> LandscapeCard(
							card = card,
							onClick = onClick,
							onLongClick = onLongClick,
							onFocused = onFocused,
							focusRequester = focusRequester,
						)

						CardStyle.POSTER -> PosterCard(
							card = card,
							onClick = onClick,
							onLongClick = onLongClick,
							onFocused = onFocused,
							focusRequester = focusRequester,
							onPlay = onPlay,
						)

						CardStyle.RANKED -> RankedPosterCard(
							rank = cardIndex + 1,
							card = card,
							onClick = onClick,
							onLongClick = onLongClick,
							onFocused = onFocused,
							focusRequester = focusRequester,
							onPlay = onPlay,
						)
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

private const val BACKGROUND_DELAY_MS = 300L
