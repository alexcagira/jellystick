package org.jellyfin.androidtv.ui.livetv.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.FocusableCardSurface
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.WatchProgressBar
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.search.modern.SearchField
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

private const val CHANNEL_COLUMNS = 4
private const val FAVOURITE_COLUMNS = 4
private val SIDE_PANEL_WIDTH = 236.dp
/** The time of the programmes and their progress move on */
private const val CLOCK_TICK_MS = 30_000L

/**
 * Channels tab: every channel in a grid (logo, name, what's on and until when) with a search and a
 * favourites filter on the side. Favourites tab: the favourite channels with the picture of what
 * they show now. OK tunes in, holding OK adds the channel to the favourites or takes it out.
 */
@Composable
internal fun LiveChannelsTab(
	state: ModernLiveTvState,
	favouritesTab: Boolean,
	restoreChannelId: UUID?,
	onRestoreDone: (restored: Boolean) -> Unit,
	onQueryChange: (String) -> Unit,
	onFavouritesOnly: (Boolean) -> Unit,
	onChannelFocused: (LiveChannel) -> Unit,
	onTuneIn: (LiveChannel) -> Unit,
	onToggleFavourite: (LiveChannel) -> Unit,
	modifier: Modifier = Modifier,
) {
	// The server's time (LiveTvClock): the TV's clock may not match it
	var now by remember { mutableStateOf(LiveTvClock.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(CLOCK_TICK_MS)
			now = LiveTvClock.now()
		}
	}
	val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()) }

	val favourites = remember(state.channels) { state.channels.filter { it.favorite } }
	val words = remember(state.query) { foldForSearch(state.query).split(' ').filter { it.isNotBlank() } }
	val shown = remember(state.channels, favouritesTab, state.favouritesOnly, words) {
		state.channels.filter { channel ->
			(!(favouritesTab || state.favouritesOnly) || channel.favorite) &&
				words.all { channel.searchText.contains(it) }
		}
	}

	// Nothing to give the focus back to (no favourites left...): the pill takes it
	val loaded = state.channelsLoaded && !state.channelsLoading
	LaunchedEffect(restoreChannelId, loaded, shown.isEmpty()) {
		if (restoreChannelId != null && loaded && shown.isEmpty()) onRestoreDone(false)
	}

	Row(modifier = modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal)) {
		if (!favouritesTab) {
			ChannelsSidePanel(
				query = state.query,
				total = state.channels.size,
				favourites = favourites.size,
				favouritesOnly = state.favouritesOnly,
				onQueryChange = onQueryChange,
				onFavouritesOnly = onFavouritesOnly,
				modifier = Modifier
					.width(SIDE_PANEL_WIDTH)
					.fillMaxHeight(),
			)
			Spacer(Modifier.width(28.dp))
		}

		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight(),
		) {
			when {
				shown.isNotEmpty() -> ChannelGrid(
					channels = shown,
					columns = if (favouritesTab) FAVOURITE_COLUMNS else CHANNEL_COLUMNS,
					programImages = favouritesTab,
					now = now,
					timeFormatter = timeFormatter,
					restoreChannelId = restoreChannelId,
					onRestoreDone = onRestoreDone,
					onChannelFocused = onChannelFocused,
					onTuneIn = onTuneIn,
					onToggleFavourite = onToggleFavourite,
				)

				state.channelsLoading || !state.channelsLoaded -> CircularProgressIndicator(
					color = ModernHomeColors.Accent,
					modifier = Modifier
						.align(Alignment.Center)
						.size(42.dp),
				)

				state.channelsError != null && state.channels.isEmpty() -> LiveTvMessage(
					text = stringResource(R.string.live_tv_load_failed, state.channelsError),
					modifier = Modifier.fillMaxSize(),
				)

				words.isNotEmpty() -> LiveTvMessage(
					text = stringResource(R.string.live_tv_no_match, state.query),
					modifier = Modifier.fillMaxSize(),
				)

				else -> LiveTvMessage(
					text = stringResource(if (favouritesTab || state.favouritesOnly) R.string.live_tv_no_favourites else R.string.live_tv_empty),
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}

@Composable
private fun ChannelGrid(
	channels: List<LiveChannel>,
	columns: Int,
	programImages: Boolean,
	now: LocalDateTime,
	timeFormatter: DateTimeFormatter,
	restoreChannelId: UUID?,
	onRestoreDone: (restored: Boolean) -> Unit,
	onChannelFocused: (LiveChannel) -> Unit,
	onTuneIn: (LiveChannel) -> Unit,
	onToggleFavourite: (LiveChannel) -> Unit,
) {
	val gridState = rememberLazyGridState()
	val restoreRequester = remember { FocusRequester() }
	// Only once: the channel watched before (back from the player)
	val restoreId = remember { restoreChannelId }
	var restored by remember { mutableStateOf(false) }

	LaunchedEffect(channels.isNotEmpty()) {
		if (restored || restoreId == null) return@LaunchedEffect
		restored = true
		val index = channels.indexOfFirst { it.id == restoreId }
		if (index < 0) {
			onRestoreDone(false)
			return@LaunchedEffect
		}
		gridState.scrollToItem((index - columns).coerceAtLeast(0))
		awaitFrames(2)
		onRestoreDone(restoreRequester.tryRequestFocus())
	}

	LazyVerticalGrid(
		columns = GridCells.Fixed(columns),
		state = gridState,
		contentPadding = PaddingValues(top = 6.dp, bottom = 60.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
		horizontalArrangement = Arrangement.spacedBy(16.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		items(channels, key = { it.id }) { channel ->
			ChannelTile(
				channel = channel,
				now = now,
				timeFormatter = timeFormatter,
				programImage = programImages,
				onClick = { onTuneIn(channel) },
				onLongClick = { onToggleFavourite(channel) },
				onFocused = { onChannelFocused(channel) },
				focusRequester = if (channel.id == restoreId) restoreRequester else null,
			)
		}
	}
}

/**
 * A channel: its logo (or the picture of what it shows, in Favourites), the number and name, what
 * it shows and until when, and how far the programme is
 */
@Composable
private fun ChannelTile(
	channel: LiveChannel,
	now: LocalDateTime,
	timeFormatter: DateTimeFormatter,
	programImage: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onFocused: () -> Unit,
	focusRequester: FocusRequester?,
) {
	// The programme the server gave as current may be over already
	val program = channel.program?.takeIf { it.endDate?.isAfter(now) != false }
	val progress = program?.progressAt(now)

	Column(modifier = Modifier.fillMaxWidth()) {
		FocusableCardSurface(
			onClick = onClick,
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			onPlay = onClick,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(16f / 9f),
		) {
			if (programImage && program != null && channel.programImageUrl != null) {
				CardArtwork(url = channel.programImageUrl, fallbackTitle = program.name.orEmpty())
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.45f to Color.Transparent)),
				)
				if (channel.logoUrl != null) {
					val imageLoader = koinInject<ImageLoader>()
					AsyncImage(
						model = channel.logoUrl,
						contentDescription = null,
						imageLoader = imageLoader,
						contentScale = ContentScale.Fit,
						alignment = Alignment.TopStart,
						modifier = Modifier
							.align(Alignment.TopStart)
							.padding(10.dp)
							.height(28.dp)
							.widthIn(max = 96.dp),
					)
				}
			} else {
				ChannelLogo(channel)
			}

			if (channel.favorite) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_heart),
					contentDescription = null,
					tint = Color.White,
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(8.dp)
						.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
						.padding(5.dp)
						.size(12.dp),
				)
			}

			if (progress != null) {
				WatchProgressBar(
					progress = progress,
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.padding(horizontal = 10.dp, vertical = 8.dp),
				)
			}
		}

		Spacer(Modifier.height(8.dp))
		Text(
			text = listOfNotNull(channel.number, channel.name).joinToString("  "),
			color = ModernHomeColors.TextPrimary,
			fontSize = 13.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = program?.name.orEmpty(),
			color = ModernHomeColors.TextSecondary,
			fontSize = 11.sp,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		val start = program?.startDate
		val end = program?.endDate
		Text(
			text = if (start != null && end != null) "${start.format(timeFormatter)} – ${end.format(timeFormatter)}" else "",
			color = ModernHomeColors.TextMuted,
			fontSize = 11.sp,
			maxLines = 1,
		)
	}
}

/** The logo whole on a dark card; the name of the channel when it has no logo */
@Composable
private fun ChannelLogo(channel: LiveChannel) {
	var failed by remember(channel.logoUrl) { mutableStateOf(false) }
	Box(
		contentAlignment = Alignment.Center,
		modifier = Modifier
			.fillMaxSize()
			.background(Brush.verticalGradient(listOf(Color(0xFF1C2433), Color(0xFF111722)))),
	) {
		if (channel.logoUrl == null || failed) {
			Text(
				text = channel.name,
				color = ModernHomeColors.TextPrimary,
				fontSize = 17.sp,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(12.dp),
			)
		} else {
			val imageLoader = koinInject<ImageLoader>()
			AsyncImage(
				model = channel.logoUrl,
				contentDescription = null,
				imageLoader = imageLoader,
				onError = { failed = true },
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.fillMaxSize()
					.padding(horizontal = 28.dp, vertical = 20.dp),
			)
		}
	}
}

/** Search, all channels / favourites, and how to add a favourite */
@Composable
private fun ChannelsSidePanel(
	query: String,
	total: Int,
	favourites: Int,
	favouritesOnly: Boolean,
	onQueryChange: (String) -> Unit,
	onFavouritesOnly: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	val searchFocusRequester = remember { FocusRequester() }
	Column(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = modifier.padding(top = 6.dp),
	) {
		SearchField(
			query = query,
			hint = stringResource(R.string.live_tv_search_channels),
			startEditing = false,
			onEditingStarted = {},
			onQueryChange = onQueryChange,
			onSubmit = {},
			focusRequester = searchFocusRequester,
		)
		Spacer(Modifier.height(6.dp))
		SideOption(
			text = stringResource(R.string.live_tv_all_channels),
			count = total,
			icon = R.drawable.ic_grid,
			selected = !favouritesOnly,
			onClick = { onFavouritesOnly(false) },
		)
		SideOption(
			text = stringResource(R.string.live_tv_favourites),
			count = favourites,
			icon = R.drawable.ic_heart,
			selected = favouritesOnly,
			onClick = { onFavouritesOnly(true) },
		)
		Spacer(Modifier.height(6.dp))
		Text(
			text = stringResource(R.string.live_tv_favourite_hint),
			color = ModernHomeColors.TextMuted,
			fontSize = 11.sp,
			modifier = Modifier.padding(horizontal = 6.dp),
		)
	}
}

/** A filter of the side panel: white when focused, lighter when it is the one in use */
@Composable
private fun SideOption(
	text: String,
	count: Int,
	icon: Int,
	selected: Boolean,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(12.dp)
	val content = when {
		focused -> Color.Black
		selected -> ModernHomeColors.TextPrimary
		else -> ModernHomeColors.TextSecondary
	}

	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(
				when {
					focused -> Color.White
					selected -> Color.White.copy(alpha = 0.16f)
					else -> Color.White.copy(alpha = 0.04f)
				},
				shape,
			)
			.padding(horizontal = 16.dp, vertical = 12.dp),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = null,
			tint = if (selected || focused) content else ModernHomeColors.TextMuted,
			modifier = Modifier.size(16.dp),
		)
		Spacer(Modifier.width(12.dp))
		Text(
			text = text,
			color = content,
			fontSize = 15.sp,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = count.toString(),
			color = if (focused) Color.Black.copy(alpha = 0.6f) else ModernHomeColors.TextMuted,
			fontSize = 13.sp,
		)
	}
}
