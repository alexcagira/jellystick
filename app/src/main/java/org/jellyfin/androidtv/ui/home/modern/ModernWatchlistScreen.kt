package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import java.util.UUID

private const val WATCHLIST_COLUMNS = 6

/**
 * Grid with everything in the watchlist, same cards as the home screen.
 */
@Composable
fun ModernWatchlistScreen(
	viewModel: ModernWatchlistViewModel,
) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val imageLoader = koinInject<ImageLoader>()

	val gridState = rememberLazyGridState()
	val firstCardFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	val restoreKey = remember { viewModel.lastFocusKey }
	var initialFocusDone by remember { mutableStateOf(false) }
	var backgroundUrl by remember { mutableStateOf<String?>(null) }

	val topBarThresholdPx = with(density) { 40.dp.toPx() }
	val showTopBar by remember {
		derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < topBarThresholdPx }
	}

	fun openDetails(id: UUID) = navigationRepository.navigate(Destinations.itemDetails(id))

	fun play(card: HomeCard) {
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

	// Initial focus: the card that was focused before, otherwise the first one
	LaunchedEffect(state.loading, state.cards) {
		if (state.loading || initialFocusDone || state.cards.isEmpty()) return@LaunchedEffect

		val restoreIndex = state.cards.indexOfFirst { it.key == restoreKey }
		if (restoreIndex >= 0) {
			// + 1 for the title
			gridState.scrollToItem(restoreIndex + 1)
			awaitFrames(2)
			if (restoreFocusRequester.tryRequestFocus()) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}

		awaitFrames(1)
		initialFocusDone = firstCardFocusRequester.tryRequestFocus()
	}

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background),
	) {
		// Ambient background from the focused title
		Crossfade(
			targetState = backgroundUrl,
			animationSpec = tween(durationMillis = 800),
			label = "WatchlistBackground",
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

		LazyVerticalGrid(
			columns = GridCells.Fixed(WATCHLIST_COLUMNS),
			state = gridState,
			contentPadding = PaddingValues(
				start = ModernHomeDimens.ScreenPaddingHorizontal,
				end = ModernHomeDimens.ScreenPaddingHorizontal,
				top = 84.dp,
				bottom = 48.dp,
			),
			verticalArrangement = Arrangement.spacedBy(20.dp),
			horizontalArrangement = Arrangement.spacedBy(ModernHomeDimens.CardSpacing),
			modifier = Modifier.fillMaxSize(),
		) {
			item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
				WatchlistTitle(count = state.cards.size)
			}

			items(state.cards, key = { it.key }) { card ->
				Box(contentAlignment = Alignment.TopCenter) {
					WatchlistCard(
						card = card,
						focusRequester = when {
							card.key == restoreKey -> restoreFocusRequester
							card == state.cards.first() -> firstCardFocusRequester
							else -> null
						},
						onFocused = {
							viewModel.lastFocusKey = card.key
							card.backgroundUrl?.let { backgroundUrl = it }
						},
						onClick = { openDetails(card.targetId) },
						onPlay = { play(card) },
					)
				}
			}
		}

		when {
			state.loading -> CircularProgressIndicator(
				color = ModernHomeColors.Accent,
				modifier = Modifier
					.align(Alignment.Center)
					.size(48.dp),
			)

			state.cards.isEmpty() -> Column(
				modifier = Modifier.align(Alignment.Center),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Text(
					text = stringResource(R.string.modern_home_watchlist_empty),
					color = ModernHomeColors.TextPrimary,
					fontSize = 20.sp,
					fontWeight = FontWeight.Bold,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					text = stringResource(R.string.modern_home_watchlist_hint),
					color = ModernHomeColors.TextSecondary,
					fontSize = 14.sp,
				)
			}
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
				watchlistActive = true,
				onNavigateDown = { firstCardFocusRequester.tryRequestFocus() },
				onHideLibrary = viewModel::hideLibrary,
				onMoveLibrary = viewModel::moveLibrary,
			)
		}
	}
}

@Composable
private fun WatchlistTitle(count: Int) {
	Row(
		modifier = Modifier.padding(bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.width(3.dp)
				.height(34.dp)
				.background(ModernHomeColors.Accent, RoundedCornerShape(2.dp))
		)
		Spacer(Modifier.width(12.dp))
		Column {
			Text(
				text = stringResource(R.string.modern_home_watchlist),
				color = ModernHomeColors.TextPrimary,
				fontSize = 22.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
			)
			if (count > 0) {
				Text(
					text = LocalContext.current.resources.getQuantityString(R.plurals.modern_home_titles, count, count),
					color = ModernHomeColors.TextMuted,
					fontSize = 11.sp,
				)
			}
		}
	}
}

@Composable
private fun WatchlistCard(
	card: HomeCard,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onPlay: () -> Unit,
) {
	val typeLabel = when (card.item.type) {
		BaseItemKind.MOVIE -> stringResource(R.string.modern_home_movie)
		BaseItemKind.SERIES -> stringResource(R.string.modern_home_tv_show)
		else -> null
	}

	Column(modifier = Modifier.width(ModernHomeDimens.PosterWidth)) {
		PosterCard(
			card = card,
			onClick = onClick,
			onLongClick = onClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			onPlay = onPlay,
		)

		Spacer(Modifier.height(8.dp))

		Text(
			text = card.title,
			color = ModernHomeColors.TextPrimary,
			fontSize = 12.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		Text(
			text = listOfNotNull(card.year?.toString(), typeLabel).joinToString(" · "),
			color = ModernHomeColors.TextMuted,
			fontSize = 10.sp,
			maxLines = 1,
		)
	}
}
