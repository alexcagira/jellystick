// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.music.modern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.FocusableCardSurface
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.KeepInViewBringIntoViewSpec
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.ModernTopBar
import org.jellyfin.androidtv.ui.home.modern.NoAutoScrollBringIntoViewSpec
import org.jellyfin.androidtv.ui.home.modern.SectionHeader
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.rememberSmoothListScroller
import org.jellyfin.androidtv.ui.home.modern.rememberFocusMemory
import org.jellyfin.androidtv.ui.home.modern.restoreFocus
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.music.share.SharedQueueDialog
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import java.util.UUID
import kotlin.math.absoluteValue

private const val HERO_HEIGHT_FRACTION = 0.74f
private const val HERO_AUTO_ADVANCE_MS = 10_000L
private const val LINK_KEY_PREFIX = "link_"
private const val BACKGROUND_DELAY_MS = 300L
/** Size of the square music cards (also used to know how many fit in a grid line) */
internal val MusicCardSize = 150.dp
private val CardSize = MusicCardSize

/**
 * Music library in the style of the home screen: recommended albums in the media bar, quick links
 * (albums, artists, genres, shuffle), then the rows in the order chosen in the settings.
 */
@Composable
fun ModernMusicScreen(
	viewModel: ModernMusicViewModel,
	library: BaseItemDto,
) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val playbackLauncher = koinInject<PlaybackLauncher>()
	val imageLoader = koinInject<ImageLoader>()

	val listState = rememberLazyListState()
	val scroller = rememberSmoothListScroller(listState)
	val heroFocusRequester = remember { FocusRequester() }
	val linksFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	val linkRestoreFocusRequester = remember { FocusRequester() }
	// Quick link with the focus, to check it is still there when coming back from its page
	var focusedLinkKey by remember { mutableStateOf<String?>(null) }
	var linksFocused by remember { mutableStateOf(false) }
	var userMoved by remember { mutableStateOf(false) }
	val restoreKey = remember { viewModel.lastFocusKey }
	var initialFocusDone by remember { mutableStateOf(false) }
	var showSharedQueue by remember { mutableStateOf(false) }
	var heroIndex by remember { mutableIntStateOf(0) }
	// Out of the composition so focus moves don't recompose the whole page
	val backgroundUrlState = remember { mutableStateOf<String?>(null) }
	val pendingBackgroundUrl = remember { MutableStateFlow<String?>(null) }

	val currentSlide = state.hero.getOrNull(heroIndex.coerceIn(0, (state.hero.size - 1).coerceAtLeast(0)))
	var heroFocused by remember { mutableStateOf(false) }

	// Next recommendation every few seconds while the media bar isn't focused, also while browsing
	// the rows below (the media bar can be off screen, so the timer lives here)
	LaunchedEffect(heroIndex, heroFocused, state.hero.size) {
		if (heroFocused || state.hero.size < 2) return@LaunchedEffect
		delay(HERO_AUTO_ADVANCE_MS)
		heroIndex = (heroIndex + 1) % state.hero.size
	}
	val topBarThresholdPx = with(density) { 40.dp.toPx() }
	val showTopBar by remember {
		derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < topBarThresholdPx }
	}

	// Albums, playlists and artists open the music page of the item (with its songs)
	fun openDetails(id: UUID) = navigationRepository.navigate(Destinations.modernMusicItem(id))
	fun play(id: UUID) = playbackHelper.retrieveAndPlay(id, false, context)

	fun playStation(source: StationSource) {
		scope.launch {
			val tracks = viewModel.stationTracks(source)
			if (tracks.isNotEmpty()) playbackLauncher.launch(context, tracks)
		}
	}

	fun onCardClick(card: MusicCard) {
		val item = card.item
		when {
			card.kind == MusicCardKind.STATION -> card.station?.let(::playStation)
			card.kind == MusicCardKind.TRACK && item != null -> play(item.id)
			item != null -> openDetails(item.id)
		}
	}

	fun onCardPlay(card: MusicCard) {
		val item = card.item
		if (card.kind == MusicCardKind.STATION) card.station?.let(::playStation)
		else if (item != null) play(item.id)
	}

	fun onCardLongClick(card: MusicCard) {
		val item = card.item ?: return
		// Hold OK on a song: its album
		val target = if (card.kind == MusicCardKind.TRACK) item.albumId ?: item.id else item.id
		openDetails(target)
	}

	// Media bar images in the cache before their slide shows: no empty cover while it changes
	LaunchedEffect(state.hero) {
		for (slide in state.hero) {
			for (url in listOfNotNull(slide.coverUrl, slide.backgroundUrl)) {
				imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
			}
		}
	}

	// Ambient background: the current slide while the media bar is visible, otherwise the focused card
	LaunchedEffect(currentSlide, showTopBar) {
		if (showTopBar && currentSlide != null) {
			pendingBackgroundUrl.value = null
			backgroundUrlState.value = currentSlide.backgroundUrl
		}
	}
	LaunchedEffect(Unit) {
		pendingBackgroundUrl.collectLatest { url ->
			if (url == null) return@collectLatest
			delay(BACKGROUND_DELAY_MS)
			backgroundUrlState.value = url
		}
	}

	// Initial focus: the card or quick link that was focused before, otherwise "Play" in the media bar
	LaunchedEffect(state.loading) {
		if (state.loading || initialFocusDone) return@LaunchedEffect

		// Back from Genres, Stations...: that link again
		if (restoreKey?.startsWith(LINK_KEY_PREFIX) == true) {
			listState.scrollToItem(if (state.hero.isEmpty()) 0 else 1)
			val restored = linkRestoreFocusRequester.restoreFocus(
				hasFocus = { linksFocused && focusedLinkKey == restoreKey },
				interrupted = { userMoved },
			)
			if (restored || userMoved) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}

		val restoreIndex = state.sections.indexOfFirst { section -> section.cards.any { focusKey(section, it) == restoreKey } }
		if (restoreKey != null && restoreIndex >= 0) {
			// + hero + quick links
			listState.scrollToItem(restoreIndex + 2)
			awaitFrames(2)
			if (restoreFocusRequester.tryRequestFocus()) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}

		awaitFrames(1)
		// Without recommendations (nothing in the library yet): the quick links
		initialFocusDone = heroFocusRequester.tryRequestFocus() || linksFocusRequester.tryRequestFocus()
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

		AmbientBackground(urlState = backgroundUrlState, imageLoader = imageLoader)

		CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScrollBringIntoViewSpec) {
			LazyColumn(
				state = listState,
				contentPadding = PaddingValues(bottom = 96.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				item(key = "hero") {
					if (currentSlide != null) {
						MusicHero(
							slide = currentSlide,
							slideCount = state.hero.size,
							index = heroIndex,
							onIndexChange = { heroIndex = it },
							height = heroHeight,
							playFocusRequester = heroFocusRequester,
							onPlay = { play(currentSlide.album.id) },
							onRadio = { playStation(StationSource(albumId = currentSlide.album.id)) },
							onOpen = { openDetails(currentSlide.album.id) },
							modifier = Modifier.onFocusChanged {
								heroFocused = it.hasFocus
								if (it.hasFocus) {
									viewModel.lastFocusKey = null
									pendingBackgroundUrl.value = null
									scope.launch { scroller.scrollToItem(0) }
								}
							},
						)
					} else {
						Spacer(Modifier.height(96.dp))
					}
				}

				item(key = "links") {
					QuickLinks(
						library = library,
						firstFocusRequester = linksFocusRequester,
						restoreKey = restoreKey,
						restoreFocusRequester = linkRestoreFocusRequester,
						onLinkFocused = { key ->
							viewModel.lastFocusKey = key
							focusedLinkKey = key
						},
						onShuffle = {
							scope.launch {
								val tracks = viewModel.shuffleTracks()
								if (tracks.isNotEmpty()) playbackLauncher.launch(context, tracks)
							}
						},
						onSharedQueue = { showSharedQueue = true },
						modifier = Modifier.onFocusChanged {
							linksFocused = it.hasFocus
							if (it.hasFocus) scope.launch { scroller.scrollToItem(if (state.hero.isEmpty()) 0 else 1) }
						},
					)
				}

				state.sections.forEachIndexed { sectionIndex, section ->
					item(key = section.type.name) {
						MusicSectionRow(
							title = stringResource(section.type.titleRes),
							cards = section.cards,
							focusRequesterFor = { card -> if (focusKey(section, card) == restoreKey) restoreFocusRequester else null },
							onFocused = { card ->
								viewModel.lastFocusKey = focusKey(section, card)
								pendingBackgroundUrl.value = card.backgroundUrl
							},
							onClick = ::onCardClick,
							onLongClick = ::onCardLongClick,
							onPlay = ::onCardPlay,
							modifier = Modifier.onFocusChanged { focusState ->
								if (focusState.hasFocus) scope.launch { scroller.scrollToItem(sectionIndex + 2) }
							},
						)
					}
				}
			}
		}

		SharedQueueDialog(
			visible = showSharedQueue,
			onDismissRequest = { showSharedQueue = false },
		)

		if (state.loading) {
			CircularProgressIndicator(
				color = ModernHomeColors.Accent,
				modifier = Modifier
					.align(Alignment.Center)
					.size(48.dp),
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
				glassBackdrop = null,
				homeActive = false,
				onNavigateDown = { heroFocusRequester.tryRequestFocus() || linksFocusRequester.tryRequestFocus() },
				onHideLibrary = viewModel::hideLibrary,
				onMoveLibrary = viewModel::moveLibrary,
				activeLibraryId = library.id,
			)
		}
	}
}

// Media bar

@Composable
private fun MusicHero(
	slide: MusicHeroSlide,
	slideCount: Int,
	index: Int,
	onIndexChange: (Int) -> Unit,
	height: Dp,
	playFocusRequester: FocusRequester,
	onPlay: () -> Unit,
	onRadio: () -> Unit,
	onOpen: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var hasFocus by remember { mutableStateOf(false) }

	fun previous() = onIndexChange((index - 1 + slideCount) % slideCount)
	fun next() = onIndexChange((index + 1) % slideCount)

	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(height)
			.onFocusChanged { hasFocus = it.hasFocus },
	) {
		Row(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, end = ModernHomeDimens.ScreenPaddingHorizontal, bottom = 20.dp),
			verticalAlignment = Alignment.Bottom,
		) {
			Box(
				modifier = Modifier
					.size(230.dp)
					.clip(RoundedCornerShape(12.dp))
					.background(ModernHomeColors.CardSurface),
			) {
				Crossfade(targetState = slide, animationSpec = tween(durationMillis = 400), label = "MusicHeroCover") { shown ->
					CardArtwork(url = shown.coverUrl, fallbackTitle = shown.title)
				}
			}

			Spacer(Modifier.width(30.dp))

			Column(modifier = Modifier.weight(1f)) {
				slide.reason?.let { reason ->
					Text(
						text = reason.uppercase(),
						color = ModernHomeColors.Accent,
						fontSize = 12.sp,
						fontWeight = FontWeight.Bold,
						letterSpacing = 1.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(6.dp))
				}
				Text(
					text = slide.title,
					color = ModernHomeColors.TextPrimary,
					fontSize = 32.sp,
					lineHeight = 36.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				slide.artist?.let { artist ->
					Spacer(Modifier.height(4.dp))
					Text(text = artist, color = ModernHomeColors.TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
				}
				slide.details?.let { details ->
					Spacer(Modifier.height(4.dp))
					Text(text = details, color = ModernHomeColors.TextMuted, fontSize = 13.sp, maxLines = 1)
				}

				Spacer(Modifier.height(18.dp))

				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					HeroButton(
						text = stringResource(R.string.lbl_play),
						icon = R.drawable.ic_play,
						primary = true,
						onClick = onPlay,
						modifier = Modifier
							.focusRequester(playFocusRequester)
							// Left on the first button: previous recommendation
							.onPreviewKeyEvent { event ->
								if (event.key != Key.DirectionLeft || slideCount < 2) return@onPreviewKeyEvent false
								if (event.type == KeyEventType.KeyDown) previous()
								true
							},
					)
					HeroButton(
						text = stringResource(R.string.modern_music_radio),
						icon = R.drawable.ic_radio,
						primary = false,
						onClick = onRadio,
					)
					HeroButton(
						text = stringResource(R.string.modern_music_open_album),
						icon = R.drawable.ic_info,
						primary = false,
						onClick = onOpen,
						// Right on the last button: next recommendation
						modifier = Modifier.onPreviewKeyEvent { event ->
							if (event.key != Key.DirectionRight || slideCount < 2) return@onPreviewKeyEvent false
							if (event.type == KeyEventType.KeyDown) next()
							true
						},
					)
				}

				if (slideCount > 1) {
					Spacer(Modifier.height(14.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						repeat(slideCount) { dot ->
							Box(
								modifier = Modifier
									.size(width = if (dot == index) 18.dp else 6.dp, height = 6.dp)
									.background(if (dot == index) Color.White else Color.White.copy(alpha = 0.35f), CircleShape)
							)
						}
					}
				}
			}
		}
	}
}

// Quick links

@Composable
private fun QuickLinks(
	library: BaseItemDto,
	firstFocusRequester: FocusRequester,
	restoreKey: String?,
	restoreFocusRequester: FocusRequester,
	onLinkFocused: (String) -> Unit,
	onShuffle: () -> Unit,
	onSharedQueue: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val navigationRepository = koinInject<NavigationRepository>()
	// Moving into the bar from the media bar or a row: the link focused last. Not focusRestorer():
	// it sent the focus back to Genres when coming back from Stations, Albums...
	val focusMemory = rememberFocusMemory()

	// Each link remembers it was focused, to come back to it from its page
	fun link(name: String, first: Boolean = false): Modifier {
		val key = LINK_KEY_PREFIX + name
		return Modifier
			.then(if (first) Modifier.focusRequester(firstFocusRequester) else Modifier)
			.then(if (key == restoreKey) Modifier.focusRequester(restoreFocusRequester) else Modifier)
			.then(focusMemory.child(key))
			.onFocusChanged { if (it.isFocused) onLinkFocused(key) }
	}

	Row(
		modifier = modifier
			.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, top = 6.dp, bottom = 4.dp)
			.background(Color.Black.copy(alpha = 0.35f), CircleShape)
			.padding(4.dp)
			.then(focusMemory.group),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		QuickLink(stringResource(R.string.lbl_albums), link("albums", first = true)) {
			navigationRepository.navigate(Destinations.libraryBrowser(library.copy(displayPreferencesId = "${library.id}AL"), null))
		}
		QuickLink(stringResource(R.string.lbl_album_artists), link("album_artists")) {
			navigationRepository.navigate(Destinations.libraryBrowser(library.copy(displayPreferencesId = "${library.id}AR"), "AlbumArtist"))
		}
		QuickLink(stringResource(R.string.lbl_artists), link("artists")) {
			navigationRepository.navigate(Destinations.libraryBrowser(library.copy(displayPreferencesId = "${library.id}AR"), "Artist"))
		}
		QuickLink(stringResource(R.string.lbl_genres), link("genres")) {
			navigationRepository.navigate(Destinations.modernMusicBrowse(library, MusicBrowseMode.GENRES))
		}
		QuickLink(stringResource(R.string.modern_music_stations), link("stations")) {
			navigationRepository.navigate(Destinations.modernMusicBrowse(library, MusicBrowseMode.STATIONS))
		}
		QuickLink(stringResource(R.string.modern_music_shuffle), link("shuffle"), onClick = onShuffle)
		QuickLink(stringResource(R.string.shared_queue_title), link("shared_queue"), onClick = onSharedQueue)
		// Music only search, with voice search
		QuickLinkIcon(R.drawable.ic_search, stringResource(R.string.lbl_search), link("search")) {
			navigationRepository.navigate(Destinations.modernSearch(music = true))
		}
	}
}

@Composable
private fun QuickLinkIcon(
	icon: Int,
	contentDescription: String,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(if (focused) Color.White else Color.Transparent, CircleShape)
			.padding(horizontal = 14.dp, vertical = 7.dp),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = contentDescription,
			tint = if (focused) Color.Black else ModernHomeColors.TextPrimary,
			modifier = Modifier.size(18.dp),
		)
	}
}

@Composable
private fun QuickLink(
	text: String,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	Text(
		text = text,
		color = if (focused) Color.Black else ModernHomeColors.TextPrimary,
		fontSize = 13.sp,
		fontWeight = FontWeight.SemiBold,
		maxLines = 1,
		modifier = modifier
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(if (focused) Color.White else Color.Transparent, CircleShape)
			.padding(horizontal = 16.dp, vertical = 8.dp),
	)
}

// Rows

@Composable
internal fun MusicSectionRow(
	title: String,
	cards: List<MusicCard>,
	focusRequesterFor: (MusicCard) -> FocusRequester?,
	onFocused: (MusicCard) -> Unit,
	onClick: (MusicCard) -> Unit,
	onLongClick: (MusicCard) -> Unit,
	onPlay: (MusicCard) -> Unit,
	modifier: Modifier = Modifier,
) {
	val density = LocalDensity.current
	val bringIntoViewSpec = remember(density) {
		KeepInViewBringIntoViewSpec(marginPx = with(density) { ModernHomeDimens.ScreenPaddingHorizontal.toPx() })
	}
	val focusMemory = rememberFocusMemory()

	Column(modifier = modifier.fillMaxWidth().padding(top = ModernHomeDimens.SectionSpacing)) {
		SectionHeader(title, compact = true)

		CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
			LazyRow(
				contentPadding = PaddingValues(horizontal = ModernHomeDimens.ScreenPaddingHorizontal, vertical = 12.dp),
				horizontalArrangement = Arrangement.spacedBy(ModernHomeDimens.CardSpacing),
				modifier = Modifier
					.fillMaxWidth()
					.then(focusMemory.group),
			) {
				items(cards, key = { it.key }) { card ->
					MusicCardView(
						card = card,
						focusRequester = focusRequesterFor(card),
						onFocused = { onFocused(card) },
						onClick = { onClick(card) },
						onLongClick = { onLongClick(card) },
						onPlay = { onPlay(card) },
						modifier = focusMemory.child(card.key),
					)
				}
			}
		}
	}
}

@Composable
internal fun MusicCardView(
	card: MusicCard,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPlay: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier.width(CardSize)) {
		FocusableCardSurface(
			onClick = onClick,
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			onPlay = onPlay,
			modifier = Modifier.size(CardSize),
		) { focused ->
			if (card.kind == MusicCardKind.STATION) {
				StationArtwork(card)
			} else if (card.kind == MusicCardKind.GENRE) {
				GenreArtwork(card.title, card.key)
			} else {
				CardArtwork(url = card.imageUrl, fallbackTitle = card.title)
			}

			if (focused && (card.kind == MusicCardKind.STATION || card.kind == MusicCardKind.TRACK)) {
				Box(
					modifier = Modifier
						.align(Alignment.Center)
						.size(40.dp)
						.background(Color.White.copy(alpha = 0.92f), CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						imageVector = ImageVector.vectorResource(if (card.kind == MusicCardKind.STATION) R.drawable.ic_radio else R.drawable.ic_play),
						contentDescription = null,
						tint = Color.Black,
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}

		Spacer(Modifier.height(8.dp))
		Text(
			text = card.title,
			color = ModernHomeColors.TextPrimary,
			fontSize = 12.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		card.subtitle?.let { subtitle ->
			Text(
				text = subtitle,
				color = ModernHomeColors.TextMuted,
				fontSize = 11.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Stations: artist photo when there is one, otherwise a colored gradient with the name.
 */
@Composable
private fun StationArtwork(card: MusicCard) {
	// Colors from the name: a genre looks the same in Stations and in Genres
	val palette = stationPalettes[card.title.hashCode().absoluteValue % stationPalettes.size]

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Brush.linearGradient(palette)),
	) {
		if (card.imageUrl != null) {
			val imageLoader = koinInject<ImageLoader>()
			AsyncImage(
				model = card.imageUrl,
				contentDescription = null,
				imageLoader = imageLoader,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
			)
		}

		Column(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(10.dp),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_radio),
				contentDescription = null,
				tint = Color.White,
				modifier = Modifier.size(16.dp),
			)
			Spacer(Modifier.height(4.dp))
			Text(
				text = card.title,
				color = Color.White,
				fontSize = 15.sp,
				lineHeight = 17.sp,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Start,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Genre: drawn like the genre stations (gradient, small icon and the name), the images of genres
 * made by the server repeat the same album cover four times.
 */
@Composable
internal fun GenreArtwork(name: String, key: String, modifier: Modifier = Modifier, large: Boolean = false) {
	// Same colors as the station of the genre
	val palette = stationPalettes[name.hashCode().absoluteValue % stationPalettes.size]

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(Brush.linearGradient(palette)),
	) {
		Column(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(if (large) 20.dp else 10.dp),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_music_album),
				contentDescription = null,
				tint = Color.White,
				modifier = Modifier.size(if (large) 30.dp else 16.dp),
			)
			Spacer(Modifier.height(if (large) 8.dp else 4.dp))
			Text(
				text = name,
				color = Color.White,
				fontSize = if (large) 30.sp else 15.sp,
				lineHeight = if (large) 34.sp else 17.sp,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Start,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

private val stationPalettes = listOf(
	listOf(Color(0xFF7F00FF), Color(0xFFE100FF)),
	listOf(Color(0xFFFF512F), Color(0xFFDD2476)),
	listOf(Color(0xFF00B4DB), Color(0xFF0083B0)),
	listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
	listOf(Color(0xFFF7971E), Color(0xFFFFD200)),
	listOf(Color(0xFF41295A), Color(0xFF2F0743)),
)

/** Key of a card for focus restoration: the same album can show up in several rows */
private fun focusKey(section: MusicSection, card: MusicCard) = "${section.type.name}/${card.key}"

/** Title of a music row, also used in Settings › Music sections */
internal val MusicSectionType.titleRes: Int
	get() = when (this) {
		MusicSectionType.LATEST -> R.string.modern_music_latest
		MusicSectionType.LAST_PLAYED -> R.string.modern_music_last_played
		MusicSectionType.STATIONS -> R.string.modern_music_stations
		MusicSectionType.RECOMMENDED -> R.string.modern_music_recommended
		MusicSectionType.FAVORITES -> R.string.modern_music_favorites
		MusicSectionType.PLAYLISTS -> R.string.modern_music_playlists
	}
