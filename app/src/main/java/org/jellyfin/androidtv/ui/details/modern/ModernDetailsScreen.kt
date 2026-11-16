// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.details.modern

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.rememberSmoothListScroller
import org.jellyfin.androidtv.ui.home.modern.GlassBackdrop
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.ModernMenuDialog
import org.jellyfin.androidtv.ui.home.modern.ModernMenuOption
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernTopBar
import org.jellyfin.androidtv.ui.home.modern.NoAutoScrollBringIntoViewSpec
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.sdk.TrailerUtils
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

/** Fraction of the screen height used by the hero, the first section peeks out below it */
private const val HERO_HEIGHT_FRACTION = 0.86f

/**
 * Blocks of the details page, one per row of the vertical list.
 */
private sealed interface DetailsBlock {
	val key: String

	data object Hero : DetailsBlock {
		override val key = "hero"
	}

	data object Person : DetailsBlock {
		override val key = "person"
	}

	data class NextUp(val card: HomeCard) : DetailsBlock {
		override val key = "next_up"
	}

	data class Seasons(val cards: List<HomeCard>) : DetailsBlock {
		override val key = "seasons"
	}

	data class Episodes(val title: String, val entries: List<EpisodeEntry>) : DetailsBlock {
		override val key = "episodes"
	}

	data class Cast(val cast: List<CastEntry>) : DetailsBlock {
		override val key = "cast"
	}

	data class Posters(val id: String, val titleRes: Int, val cards: List<HomeCard>) : DetailsBlock {
		override val key = "posters_$id"
	}
}

private fun buildBlocks(state: DetailsState): List<DetailsBlock> = buildList {
	when (state.kind) {
		DetailsKind.PERSON -> add(DetailsBlock.Person)
		null -> return@buildList
		else -> add(DetailsBlock.Hero)
	}

	state.nextUp?.let { add(DetailsBlock.NextUp(it)) }
	if (state.seasons.isNotEmpty()) add(DetailsBlock.Seasons(state.seasons))

	if (state.episodes.isNotEmpty()) add(DetailsBlock.Episodes(state.header?.subtitle ?: state.item?.name.orEmpty(), state.episodes))

	if (state.personMovies.isNotEmpty()) add(DetailsBlock.Posters("movies", R.string.lbl_movies, state.personMovies))
	if (state.personShows.isNotEmpty()) add(DetailsBlock.Posters("shows", R.string.lbl_tv_series, state.personShows))
	if (state.cast.isNotEmpty()) add(DetailsBlock.Cast(state.cast))
	if (state.similar.isNotEmpty()) add(DetailsBlock.Posters("similar", R.string.lbl_more_like_this, state.similar))
}

private fun episodeKey(entry: EpisodeEntry) = "episode_${entry.item.id}"

/**
 * Label of the "Play" button: "Resume S01:E05" / "Play S01:E01" on series and season pages.
 */
@Composable
private fun playLabel(state: DetailsState): String {
	val target = state.playTarget
	if (target == null || (state.kind != DetailsKind.SERIES && state.kind != DetailsKind.SEASON)) {
		return stringResource(R.string.lbl_play)
	}

	val season = target.seasonNumber
	val episode = target.episodeNumber
	if (season == null || episode == null) return stringResource(R.string.lbl_play)

	val code = stringResource(R.string.modern_details_episode_code, season, episode)
	return stringResource(if (target.resume) R.string.modern_details_resume_episode else R.string.modern_details_play_episode, code)
}

/**
 * Details page of a movie, series, season or person, styled like the web client examples:
 * full-bleed backdrop with logo and actions, then next up, seasons, episodes, cast and similar titles.
 */
@Composable
fun ModernDetailsScreen(
	viewModel: ModernDetailsViewModel,
) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val imageLoader = koinInject<ImageLoader>()
	val themeMusicPlayer = koinInject<ThemeMusicPlayer>()

	val listState = rememberLazyListState()
	val scroller = rememberSmoothListScroller(listState)
	val primaryFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	// Snapshot of the last focused element when this screen was (re)created
	val restoreIndex = remember { viewModel.lastFocusIndex }
	val restoreKey = remember { viewModel.lastFocusKey }
	var initialFocusDone by remember { mutableStateOf(false) }
	// Out of the composition so focus moves don't recompose the whole page
	val backgroundUrlState = remember { mutableStateOf<String?>(null) }
	var showMediaInfo by remember { mutableStateOf(false) }
	// Item shown in the media info dialog (the page item, or an episode held with OK)
	var mediaInfoItem by remember { mutableStateOf<BaseItemDto?>(null) }
	// Season held with OK: play / watched menu
	var seasonMenu by remember { mutableStateOf<HomeCard?>(null) }
	var lastSeasonMenu by remember { mutableStateOf<HomeCard?>(null) }

	val blocks = remember(state) { buildBlocks(state) }

	val topBarThresholdPx = with(density) { 40.dp.toPx() }
	val showTopBar by remember {
		derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < topBarThresholdPx }
	}

	fun openDetails(id: UUID) = navigationRepository.navigate(Destinations.itemDetails(id))
	fun play(id: UUID) {
		themeMusicPlayer.stop()
		playbackHelper.retrieveAndPlay(id, false, context)
	}

	fun onPlay() {
		scope.launch { viewModel.resolvePlayId()?.let { play(it) } }
	}

	fun onTrailer() {
		val item = state.item ?: return
		scope.launch {
			val localTrailers = if ((item.localTrailerCount ?: 0) > 0) viewModel.localTrailerIds() else emptyList()
			themeMusicPlayer.stop()
			if (localTrailers.isNotEmpty()) {
				playbackHelper.retrieveAndPlay(localTrailers, false, null, 0, context)
				return@launch
			}

			try {
				TrailerUtils.getExternalTrailerIntent(context, item)?.let { intent -> context.startActivity(intent) }
			} catch (err: ActivityNotFoundException) {
				Timber.w(err, "Unable to open external trailer")
				Toast.makeText(context, context.getString(R.string.no_player_message), Toast.LENGTH_LONG).show()
			}
		}
	}

	fun onWatchlist() {
		val added = viewModel.toggleWatchlist()
		Toast.makeText(
			context,
			context.getString(if (added) R.string.modern_details_watchlist_added else R.string.modern_details_watchlist_removed),
			Toast.LENGTH_SHORT,
		).show()
	}

	// Background of the focused card, applied once the focus rests a moment
	val pendingBackgroundUrl = remember { MutableStateFlow<String?>(null) }
	LaunchedEffect(Unit) {
		pendingBackgroundUrl.collectLatest { url ->
			if (url == null) return@collectLatest
			delay(300)
			backgroundUrlState.value = url
		}
	}

	fun rememberFocus(index: Int, key: String, card: HomeCard?) {
		viewModel.lastFocusIndex = index
		viewModel.lastFocusKey = key
		card?.backgroundUrl?.let { pendingBackgroundUrl.value = it }
	}

	LaunchedEffect(state.backgroundUrl) {
		if (backgroundUrlState.value == null) backgroundUrlState.value = state.backgroundUrl
	}

	// Initial focus: restore the last focused element, otherwise the play button (or first card)
	LaunchedEffect(state.loading, blocks.size, state.partial) {
		if (state.loading || initialFocusDone || blocks.isEmpty()) return@LaunchedEffect

		if (restoreKey != null && restoreIndex in blocks.indices) {
			listState.scrollToItem(restoreIndex)
			awaitFrames(2)
			if (restoreFocusRequester.tryRequestFocus()) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}
		// The element to focus again can be in a row still loading
		if (restoreKey != null && restoreKey != "hero" && state.partial) return@LaunchedEffect

		awaitFrames(1)
		initialFocusDone = primaryFocusRequester.tryRequestFocus()
	}

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background),
	) {
		val heroHeight = maxHeight * HERO_HEIGHT_FRACTION
		val glassBackdrop = state.header?.let { header ->
			with(density) { GlassBackdrop(header.glassUrl, maxWidth.toPx(), heroHeight.toPx()) }
		}

		// Ambient background, same as the home screen
		AmbientBackground(urlState = backgroundUrlState, imageLoader = imageLoader)

		CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScrollBringIntoViewSpec) {
			LazyColumn(
				state = listState,
				contentPadding = PaddingValues(bottom = 96.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				itemsIndexed(
					items = blocks,
					key = { _, block -> block.key },
				) { index, block ->
					// Scroll the focused block into place
					val scrollModifier = Modifier.onFocusChanged { focusState ->
						if (focusState.hasFocus) scope.launch { scroller.scrollToItem(index) }
					}

					fun requesterFor(key: String): FocusRequester? =
						if (restoreKey == key && restoreIndex == index) restoreFocusRequester else null

					when (block) {
						DetailsBlock.Hero -> state.header?.let { header ->
							DetailsHero(
								header = header,
								kind = state.kind ?: DetailsKind.MOVIE,
								height = heroHeight,
								playLabel = playLabel(state),
								isFavorite = state.isFavorite,
								isPlayed = state.isPlayed,
								isInWatchlist = state.isInWatchlist,
								hasTrailer = remember(state.item) {
									val item = state.item
									item != null && ((item.localTrailerCount ?: 0) > 0 || TrailerUtils.hasPlayableTrailers(context, item))
								},
								hasMediaInfo = !state.item?.mediaStreams.isNullOrEmpty(),
								playFocusRequester = primaryFocusRequester,
								restoreFocusRequester = requesterFor("hero"),
								onFocused = { rememberFocus(index, "hero", null) },
								onPlay = ::onPlay,
								onFavorite = viewModel::toggleFavorite,
								onTrailer = ::onTrailer,
								onPlayed = viewModel::togglePlayed,
								onWatchlist = ::onWatchlist,
								onMediaInfo = {
									mediaInfoItem = state.item
									showMediaInfo = true
								},
								onMore = { state.item?.let { navigationRepository.navigate(Destinations.legacyItemDetails(it.id)) } },
								modifier = scrollModifier,
							)
						}

						DetailsBlock.Person -> state.person?.let { person ->
							PersonHeader(
								person = person,
								focusRequester = primaryFocusRequester,
								modifier = scrollModifier,
							)
						}

						is DetailsBlock.NextUp -> NextUpSection(
							card = block.card,
							focusRequester = requesterFor(block.card.key),
							onFocused = { rememberFocus(index, block.card.key, block.card) },
							onClick = { play(block.card.targetId) },
							onLongClick = { navigationRepository.navigate(Destinations.legacyItemDetails(block.card.targetId)) },
							modifier = scrollModifier,
						)

						is DetailsBlock.Seasons -> PosterRow(
							title = stringResource(R.string.lbl_seasons),
							cards = block.cards,
							showCaptionDetails = false,
							focusRequesterFor = { card -> requesterFor(card.key) },
							onFocused = { card -> rememberFocus(index, card.key, card) },
							onClick = { card -> openDetails(card.targetId) },
							onLongClick = { card ->
								lastSeasonMenu = card
								seasonMenu = card
							},
							onPlay = null,
							modifier = scrollModifier,
						)

						is DetailsBlock.Episodes -> EpisodesCarousel(
							title = block.title,
							entries = block.entries,
							seasonTabs = state.seasonTabs,
							selectedSeasonId = state.item?.id,
							onSeasonSelected = { tab -> viewModel.switchSeason(tab.id) },
							onTogglePlayed = viewModel::toggleEpisodePlayed,
							// Coming back from the player: start at the episode that was focused
							startEntry = remember(block.entries) {
								block.entries.firstOrNull { episodeKey(it) == restoreKey && restoreIndex == index }
							},
							focusRequesterFor = { entry -> requesterFor(episodeKey(entry)) },
							onFocused = { entry -> rememberFocus(index, episodeKey(entry), null) },
							onClick = { entry -> play(entry.item.id) },
							// Hold OK: file details of the episode
							onLongClick = { entry ->
								scope.launch {
									mediaInfoItem = viewModel.fullItem(entry.item.id) ?: entry.item
									showMediaInfo = true
								}
							},
							modifier = scrollModifier,
						)

						is DetailsBlock.Cast -> CastRow(
							cast = block.cast,
							focusRequesterFor = { member -> requesterFor(member.id.toString()) },
							onFocused = { member -> rememberFocus(index, member.id.toString(), null) },
							onClick = { member -> openDetails(member.id) },
							modifier = scrollModifier,
						)

						is DetailsBlock.Posters -> PosterRow(
							title = stringResource(block.titleRes),
							cards = block.cards,
							showCaptionDetails = true,
							// On a person page without biography the first poster gets the initial focus
							focusRequesterFor = { card ->
								requesterFor(card.key)
									?: if (state.kind == DetailsKind.PERSON && state.person?.overview == null && index == 1 && card == block.cards.first()) primaryFocusRequester else null
							},
							onFocused = { card -> rememberFocus(index, card.key, card) },
							onClick = { card -> openDetails(card.targetId) },
							onPlay = { card ->
								val playId = card.playId
								if (playId != null) play(playId)
								else scope.launch {
									val resolvedId = viewModel.resolvePlayableId(card.item)
									if (resolvedId != null) play(resolvedId)
									else openDetails(card.targetId)
								}
							},
							modifier = scrollModifier,
						)
					}
				}
			}
		}

		if (state.loading && state.item == null) {
			CircularProgressIndicator(
				color = ModernHomeColors.Accent,
				modifier = Modifier
					.align(Alignment.Center)
					.size(48.dp),
			)
		} else if (state.failed) {
			Text(
				text = stringResource(R.string.modern_details_failed),
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
				onNavigateDown = { primaryFocusRequester.tryRequestFocus() },
				onHideLibrary = viewModel::hideLibrary,
				onMoveLibrary = viewModel::moveLibrary,
			)
		}

		MediaInfoDialog(
			visible = showMediaInfo,
			item = mediaInfoItem,
			onDismissRequest = { showMediaInfo = false },
		)

		val season = lastSeasonMenu
		ModernMenuDialog(
			visible = seasonMenu != null,
			title = season?.title.orEmpty(),
			subtitle = season?.item?.seriesName,
			options = buildList {
				val item = season?.item ?: return@buildList
				add(ModernMenuOption(stringResource(R.string.modern_menu_play_now), R.drawable.ic_play) {
					scope.launch { viewModel.seasonPlayableId(item)?.let(::play) }
				})
				add(ModernMenuOption(stringResource(R.string.modern_home_mark_watched), R.drawable.ic_watch) { viewModel.setItemPlayed(item.id, true) })
				add(ModernMenuOption(stringResource(R.string.modern_menu_mark_unwatched), R.drawable.ic_unwatch) { viewModel.setItemPlayed(item.id, false) })
			},
			onDismissRequest = { seasonMenu = null },
		)
	}
}
