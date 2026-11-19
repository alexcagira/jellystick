// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.details.modern

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import kotlin.math.roundToInt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.BebasNeue
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.FocusableCardSurface
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.KeepInViewBringIntoViewSpec
import org.jellyfin.androidtv.ui.home.modern.LandscapeCard
import org.jellyfin.androidtv.ui.home.modern.LanguageFlags
import org.jellyfin.androidtv.ui.home.modern.ModernDialog
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.PosterCard
import org.jellyfin.androidtv.ui.home.modern.QualityBadges
import org.jellyfin.androidtv.ui.home.modern.SectionHeader
import org.jellyfin.androidtv.ui.home.modern.WatchProgressBar
import org.jellyfin.androidtv.ui.home.modern.fadeOutBottom
import org.jellyfin.androidtv.ui.home.modern.formatRuntime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.compose.koinInject
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Hero

@Composable
internal fun DetailsHero(
	header: DetailsHeader,
	kind: DetailsKind,
	height: Dp,
	playLabel: String,
	isFavorite: Boolean,
	isPlayed: Boolean,
	isInWatchlist: Boolean,
	hasTrailer: Boolean,
	hasMediaInfo: Boolean,
	playFocusRequester: FocusRequester,
	restoreFocusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onPlay: () -> Unit,
	/** Play from the beginning: only when the title (or the episode of the Play button) was started */
	onRestart: (() -> Unit)?,
	onFavorite: () -> Unit,
	onTrailer: () -> Unit,
	onPlayed: () -> Unit,
	onWatchlist: () -> Unit,
	onMediaInfo: () -> Unit,
	onMore: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val imageLoader = koinInject<ImageLoader>()

	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(height)
			.onFocusChanged { if (it.hasFocus) onFocused() },
	) {
		// Backdrop with readability gradients, faded into the ambient background at the bottom
		Box(
			modifier = Modifier
				.fillMaxSize()
				.fadeOutBottom(fadeStart = 0.55f),
		) {
			if (header.backdropUrl != null) {
				AsyncImage(
					model = header.backdropUrl,
					contentDescription = null,
					imageLoader = imageLoader,
					contentScale = ContentScale.Crop,
					alignment = Alignment.TopCenter,
					modifier = Modifier.fillMaxSize(),
				)
			}

			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.horizontalGradient(
							0f to Color.Black.copy(alpha = 0.85f),
							0.4f to Color.Black.copy(alpha = 0.55f),
							0.7f to Color.Transparent,
						)
					)
			)
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.verticalGradient(
							0f to Color.Black.copy(alpha = 0.55f),
							0.22f to Color.Transparent,
						)
					)
			)
		}

		Column(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, bottom = 24.dp)
				.fillMaxWidth(0.56f),
		) {
			if (header.logoUrl != null) {
				AsyncImage(
					model = header.logoUrl,
					contentDescription = header.title,
					imageLoader = imageLoader,
					contentScale = ContentScale.Fit,
					alignment = Alignment.BottomStart,
					modifier = Modifier
						.widthIn(max = 360.dp)
						.fillMaxWidth()
						.height(if (header.subtitle != null) 80.dp else 104.dp),
				)
			} else {
				Text(
					text = header.title,
					color = ModernHomeColors.TextPrimary,
					fontSize = 38.sp,
					lineHeight = 40.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}

			header.secondaryTitle?.let { secondaryTitle ->
				Spacer(Modifier.height(6.dp))
				Text(
					text = secondaryTitle,
					color = ModernHomeColors.TextSecondary,
					fontSize = if (header.subtitle != null) 11.sp else 13.sp,
					fontWeight = if (header.subtitle != null) FontWeight.Bold else FontWeight.Normal,
					letterSpacing = if (header.subtitle != null) 0.8.sp else 0.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}

			header.subtitle?.let { subtitle ->
				Spacer(Modifier.height(2.dp))
				Text(
					text = subtitle,
					color = ModernHomeColors.TextPrimary,
					fontSize = 24.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
				)
			}

			Spacer(Modifier.height(10.dp))
			DetailsMetadata(header)

			if (header.genres.isNotEmpty()) {
				Spacer(Modifier.height(4.dp))
				Text(
					text = header.genres.joinToString("  ·  "),
					color = ModernHomeColors.TextSecondary,
					fontSize = 13.sp,
					fontWeight = FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}

			header.overview?.let { overview ->
				Spacer(Modifier.height(12.dp))
				Text(
					text = overview,
					color = ModernHomeColors.TextSecondary,
					fontSize = 13.sp,
					lineHeight = 19.sp,
					maxLines = 4,
					overflow = TextOverflow.Ellipsis,
				)
			}

			Spacer(Modifier.height(18.dp))

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				HeroButton(
					text = playLabel,
					icon = R.drawable.ic_play,
					primary = true,
					onClick = onPlay,
					modifier = Modifier
						.focusRequester(playFocusRequester)
						.then(if (restoreFocusRequester != null) Modifier.focusRequester(restoreFocusRequester) else Modifier),
				)

				// ⏮: from the beginning
				if (onRestart != null) {
					CircleActionButton(
						icon = R.drawable.ic_previous,
						contentDescription = stringResource(R.string.modern_details_restart),
						onClick = onRestart,
					)
				}

				HeroButton(
					text = stringResource(if (isFavorite) R.string.modern_details_favorite else R.string.modern_details_add_favorite),
					icon = if (isFavorite) R.drawable.ic_heart_red else R.drawable.ic_heart,
					primary = false,
					onClick = onFavorite,
				)

				if (hasTrailer) {
					CircleActionButton(
						icon = R.drawable.ic_trailer,
						contentDescription = stringResource(R.string.modern_details_trailer),
						onClick = onTrailer,
					)
				}
			}
		}

		// Secondary actions on the right, like the web client
		Row(
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(end = ModernHomeDimens.ScreenPaddingHorizontal, bottom = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			if (kind != DetailsKind.PERSON) {
				CircleActionButton(
					icon = R.drawable.ic_check,
					contentDescription = stringResource(if (isPlayed) R.string.lbl_mark_unplayed else R.string.lbl_mark_played),
					active = isPlayed,
					onClick = onPlayed,
				)
			}

			if (hasMediaInfo) {
				CircleActionButton(
					icon = R.drawable.ic_info,
					contentDescription = stringResource(R.string.modern_details_media_info),
					onClick = onMediaInfo,
				)
			}

			if (kind != DetailsKind.PERSON) {
				CircleActionButton(
					icon = if (isInWatchlist) R.drawable.ic_watchlist_added else R.drawable.ic_watchlist,
					contentDescription = stringResource(if (isInWatchlist) R.string.modern_details_watchlist_remove else R.string.modern_details_watchlist_add),
					active = isInWatchlist,
					onClick = onWatchlist,
				)
			}

			CircleActionButton(
				icon = R.drawable.ic_more,
				contentDescription = stringResource(R.string.modern_details_more_options),
				onClick = onMore,
			)
		}
	}
}

/**
 * Where a rating comes from: its name in a plain outlined tag, like the age rating (only the name,
 * not the logos or colours of those sites)
 */
@Composable
private fun RatingSource(name: String) {
	Text(
		text = name,
		color = ModernHomeColors.TextSecondary,
		fontSize = 10.sp,
		fontWeight = FontWeight.Bold,
		maxLines = 1,
		modifier = Modifier
			.border(1.dp, ModernHomeColors.BadgeBorder, RoundedCornerShape(3.dp))
			.padding(horizontal = 4.dp, vertical = 1.dp),
	)
}

@Composable
private fun DetailsMetadata(header: DetailsHeader) {
	val facts = listOfNotNull(
		header.year?.toString(),
		header.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime),
		header.seasonCount?.let { quantityString(R.plurals.modern_details_seasons, it) },
		header.episodeCount?.let { quantityString(R.plurals.modern_details_episodes, it) },
	)

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		if (header.rating != null) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_star),
				contentDescription = null,
				tint = ModernHomeColors.Accent,
				modifier = Modifier.size(14.dp),
			)
			Text(
				text = String.format(Locale.getDefault(), "%.1f", header.rating),
				color = ModernHomeColors.TextPrimary,
				fontSize = 14.sp,
				fontWeight = FontWeight.Bold,
			)
			if (header.ratingFromImdb) RatingSource("IMDb")
			if (header.letterboxdRating == null && facts.isNotEmpty()) Text(text = "·", color = ModernHomeColors.TextSecondary, fontSize = 14.sp)
		}

		// Films: the average on Letterboxd (out of 5)
		if (header.letterboxdRating != null) {
			if (header.rating != null) Spacer(Modifier.width(2.dp))
			Text(
				text = String.format(Locale.getDefault(), "%.1f", header.letterboxdRating),
				color = ModernHomeColors.TextPrimary,
				fontSize = 14.sp,
				fontWeight = FontWeight.Bold,
			)
			RatingSource("Letterboxd")
			if (facts.isNotEmpty()) Text(text = "·", color = ModernHomeColors.TextSecondary, fontSize = 14.sp)
		}

		if (facts.isNotEmpty()) {
			Text(
				text = facts.joinToString("  ·  "),
				color = ModernHomeColors.TextPrimary,
				fontSize = 14.sp,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
			)
		}

		if (!header.officialRating.isNullOrBlank()) {
			Text(
				text = header.officialRating,
				color = ModernHomeColors.TextPrimary,
				fontSize = 10.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				modifier = Modifier
					.border(1.dp, ModernHomeColors.BadgeBorder, RoundedCornerShape(3.dp))
					.padding(horizontal = 4.dp, vertical = 1.dp),
			)
		}

		QualityBadges(header.quality, scale = 1.3f)
		LanguageFlags(header.languages)
	}
}

/**
 * Round icon button (watched, info, more...). [active] highlights it, e.g. when already watched.
 */
@Composable
internal fun CircleActionButton(
	icon: Int,
	contentDescription: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	active: Boolean = false,
	size: Dp = 44.dp,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "CircleActionScale")

	val containerColor = when {
		focused -> Color.White
		active -> ModernHomeColors.Accent
		else -> Color.Black.copy(alpha = 0.45f)
	}
	val contentColor = if (focused) Color.Black else Color.White

	Box(
		modifier = modifier
			.size(size)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.background(containerColor, CircleShape)
			.border(1.dp, if (focused) ModernHomeColors.Accent else Color.White.copy(alpha = 0.25f), CircleShape),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = contentDescription,
			tint = contentColor,
			modifier = Modifier.size(size * 0.45f),
		)
	}
}

// Person

@Composable
internal fun PersonHeader(
	person: PersonInfo,
	focusRequester: FocusRequester,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(
				start = ModernHomeDimens.ScreenPaddingHorizontal,
				end = ModernHomeDimens.ScreenPaddingHorizontal,
				top = 88.dp,
				bottom = 16.dp,
			),
	) {
		Box(
			modifier = Modifier
				.size(width = 210.dp, height = 280.dp)
				.clip(RoundedCornerShape(14.dp))
				.background(ModernHomeColors.CardSurface),
		) {
			CardArtwork(url = person.imageUrl, fallbackTitle = person.name)
		}

		Spacer(Modifier.width(32.dp))

		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = person.name,
				color = ModernHomeColors.TextPrimary,
				fontSize = 32.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)

			person.overview?.let { overview ->
				Spacer(Modifier.height(14.dp))
				Text(
					text = overview,
					color = ModernHomeColors.TextSecondary,
					fontSize = 13.sp,
					lineHeight = 19.sp,
					maxLines = if (expanded) 14 else 5,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(10.dp))
				HeroButton(
					text = stringResource(if (expanded) R.string.modern_details_show_less else R.string.modern_details_show_more),
					icon = null,
					primary = false,
					onClick = { expanded = !expanded },
					modifier = Modifier.focusRequester(focusRequester),
				)
			}

			Spacer(Modifier.height(14.dp))

			person.birthDate?.let { birthDate ->
				val age = Period.between(birthDate, person.deathDate ?: LocalDate.now()).years
				PersonFact(
					text = stringResource(R.string.modern_details_born, birthDate.format(dateFormatter)) +
						if (person.deathDate == null) " " + stringResource(R.string.modern_details_age, age) else "",
				)
			}

			person.deathDate?.let { deathDate ->
				PersonFact(text = stringResource(R.string.modern_details_died, deathDate.format(dateFormatter)))
			}

			person.birthPlace?.let { birthPlace ->
				PersonFact(text = stringResource(R.string.modern_details_birth_place, birthPlace))
			}
		}
	}
}

@Composable
private fun PersonFact(text: String) {
	Text(
		text = text,
		color = ModernHomeColors.TextSecondary,
		fontSize = 13.sp,
		fontWeight = FontWeight.Medium,
		modifier = Modifier.padding(vertical = 4.dp),
	)
}

// Rows

/**
 * Section title + horizontal list, focus is restored to the last focused card when coming back.
 */
@Composable
private fun <T> DetailsRow(
	title: String,
	items: List<T>,
	key: (T) -> Any,
	modifier: Modifier = Modifier,
	listState: LazyListState = rememberLazyListState(),
	header: @Composable () -> Unit = { SectionHeader(title, compact = true) },
	itemContent: @Composable (item: T) -> Unit,
) {
	val density = LocalDensity.current
	val bringIntoViewSpec = remember(density) {
		KeepInViewBringIntoViewSpec(marginPx = with(density) { ModernHomeDimens.ScreenPaddingHorizontal.toPx() })
	}

	Column(modifier = modifier.fillMaxWidth().padding(top = ModernHomeDimens.SectionSpacing)) {
		header()

		CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
			LazyRow(
				state = listState,
				contentPadding = PaddingValues(horizontal = ModernHomeDimens.ScreenPaddingHorizontal, vertical = 12.dp),
				horizontalArrangement = Arrangement.spacedBy(ModernHomeDimens.CardSpacing),
				verticalAlignment = Alignment.Top,
				modifier = Modifier
					.fillMaxWidth()
					.focusRestorer()
					.focusGroup(),
			) {
				items(items, key = key) { item -> itemContent(item) }
			}
		}
	}
}

@Composable
internal fun NextUpSection(
	card: HomeCard,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	DetailsRow(
		title = stringResource(R.string.lbl_next_up),
		items = listOf(card),
		key = { it.key },
		modifier = modifier,
	) {
		LandscapeCard(
			card = card,
			onClick = onClick,
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
		)
	}
}

@Composable
internal fun PosterRow(
	title: String,
	cards: List<HomeCard>,
	showCaptionDetails: Boolean,
	focusRequesterFor: (HomeCard) -> FocusRequester?,
	onFocused: (HomeCard) -> Unit,
	onClick: (HomeCard) -> Unit,
	onPlay: ((HomeCard) -> Unit)?,
	modifier: Modifier = Modifier,
	/** Hold OK; null opens the item like a click */
	onLongClick: ((HomeCard) -> Unit)? = null,
) {
	DetailsRow(
		title = title,
		items = cards,
		key = { it.key },
		modifier = modifier,
	) { card ->
		CaptionedPoster(
			card = card,
			showDetails = showCaptionDetails,
			focusRequester = focusRequesterFor(card),
			onFocused = { onFocused(card) },
			onClick = { onClick(card) },
			onLongClick = { (onLongClick ?: onClick)(card) },
			onPlay = onPlay?.let { play -> { play(card) } },
		)
	}
}

/**
 * Poster with the title and "★ 7.1 · 2010 · Movie" underneath, like "More like this" on the web.
 */
@Composable
private fun CaptionedPoster(
	card: HomeCard,
	showDetails: Boolean,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPlay: (() -> Unit)?,
) {
	val typeLabel = when (card.item.type) {
		BaseItemKind.MOVIE -> stringResource(R.string.modern_home_movie)
		BaseItemKind.SERIES -> stringResource(R.string.modern_home_tv_show)
		else -> null
	}
	val facts = listOfNotNull(card.year?.toString(), typeLabel).joinToString(" · ")

	Column(modifier = Modifier.width(ModernHomeDimens.PosterWidth)) {
		PosterCard(
			card = card,
			onClick = onClick,
			onLongClick = onLongClick,
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

		if (showDetails && (card.rating != null || facts.isNotEmpty())) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (card.rating != null) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_star),
						contentDescription = null,
						tint = ModernHomeColors.Accent,
						modifier = Modifier.size(10.dp),
					)
					Spacer(Modifier.width(3.dp))
					Text(
						text = String.format(Locale.getDefault(), "%.1f", card.rating) + if (facts.isNotEmpty()) " · " else "",
						color = ModernHomeColors.TextSecondary,
						fontSize = 10.sp,
						maxLines = 1,
					)
				}
				Text(
					text = facts,
					color = ModernHomeColors.TextMuted,
					fontSize = 10.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
internal fun CastRow(
	cast: List<CastEntry>,
	focusRequesterFor: (CastEntry) -> FocusRequester?,
	onFocused: (CastEntry) -> Unit,
	onClick: (CastEntry) -> Unit,
	modifier: Modifier = Modifier,
) {
	DetailsRow(
		title = stringResource(R.string.lbl_cast_crew),
		items = cast,
		key = { it.id },
		modifier = modifier,
	) { member ->
		Column(modifier = Modifier.width(CAST_CARD_WIDTH)) {
			FocusableCardSurface(
				onClick = { onClick(member) },
				onLongClick = null,
				onFocused = { onFocused(member) },
				focusRequester = focusRequesterFor(member),
				modifier = Modifier.size(CAST_CARD_WIDTH, CAST_CARD_HEIGHT),
			) {
				if (member.imageUrl != null) {
					CardArtwork(url = member.imageUrl, fallbackTitle = member.name)
				} else {
					// Same placeholder as the web client
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Color(0xFF0B4F6C)),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_user),
							contentDescription = null,
							tint = Color.White,
							modifier = Modifier.size(40.dp),
						)
					}
				}
			}

			Spacer(Modifier.height(8.dp))

			Text(
				text = member.name,
				color = ModernHomeColors.TextPrimary,
				fontSize = 12.sp,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			member.role?.let { role ->
				Text(
					text = if (member.isCharacter) stringResource(R.string.modern_details_as_role, role) else role,
					color = ModernHomeColors.TextMuted,
					fontSize = 11.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

private val CAST_CARD_WIDTH = 96.dp
private val CAST_CARD_HEIGHT = 128.dp

// Episodes

@Composable
internal fun EpisodesHeader(
	title: String,
	count: Int,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, top = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.width(3.dp)
				.height(30.dp)
				.background(ModernHomeColors.Accent, RoundedCornerShape(2.dp))
		)
		Spacer(Modifier.width(10.dp))
		Column {
			Text(
				text = title,
				color = ModernHomeColors.TextPrimary,
				fontSize = 18.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
			)
			Text(
				text = quantityString(R.plurals.modern_details_episodes, count),
				color = ModernHomeColors.TextMuted,
				fontSize = 11.sp,
			)
		}
	}
}

/** Room kept between the focused season pill and the edges, so the next seasons show */
private val SEASON_PILLS_FOCUS_MARGIN = 96.dp

/**
 * Season switcher in the style of the navigation bar: "Season 1 | Season 2 | Season 3".
 * Moving down from the buttons above lands on the current season. With many seasons the pills
 * scroll so the current one is in view (season 19 of a long series was off to the right, only up
 * to season 8 showed).
 */
@Composable
private fun SeasonTabs(
	tabs: List<SeasonTab>,
	selectedId: java.util.UUID?,
	episodeCount: Int,
	onSelected: (SeasonTab) -> Unit,
) {
	val selectedFocusRequester = remember { FocusRequester() }
	val hasSelected = tabs.any { it.id == selectedId }
	val scrollState = rememberScrollState()
	// Where each pill is in the scrolled content (start, width in pixels) and the width of the visible part
	val pillBounds = remember(tabs) { HashMap<java.util.UUID, Pair<Int, Int>>() }
	var viewportWidth by remember { mutableIntStateOf(0) }
	var scrolledOnce by remember { mutableStateOf(false) }
	// The page doesn't scroll by itself on focus (NoAutoScrollBringIntoViewSpec), and the pills got
	// that too: moving through them, the one with the focus went off screen. Here they follow the
	// focus, with room to see the next ones
	val density = LocalDensity.current
	val followFocus = remember(density) { KeepInViewBringIntoViewSpec(marginPx = with(density) { SEASON_PILLS_FOCUS_MARGIN.toPx() }) }

	// The current season in the middle of the visible part (at once the first time, smoothly after)
	LaunchedEffect(selectedId, tabs, viewportWidth) {
		if (viewportWidth == 0 || selectedId == null) return@LaunchedEffect
		awaitFrames(1)
		val (start, width) = pillBounds[selectedId] ?: return@LaunchedEffect
		val target = (start - (viewportWidth - width) / 2).coerceIn(0, scrollState.maxValue)
		if (scrolledOnce) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
		scrolledOnce = true
	}

	Row(
		modifier = Modifier.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, top = 4.dp, end = ModernHomeDimens.ScreenPaddingHorizontal),
		verticalAlignment = Alignment.CenterVertically,
	) {
		CompositionLocalProvider(LocalBringIntoViewSpec provides followFocus) {
			Row(
				modifier = Modifier
					// Measured after the episode count, so the count always has room
					.weight(1f, fill = false)
					.background(Color.Black.copy(alpha = 0.35f), CircleShape)
					.border(1.dp, ModernHomeColors.GlassBorder, CircleShape)
					.onSizeChanged { viewportWidth = it.width }
					.horizontalScroll(scrollState)
					.padding(4.dp)
					.then(if (hasSelected) Modifier.focusRestorer(selectedFocusRequester) else Modifier.focusRestorer())
					.focusGroup(),
				horizontalArrangement = Arrangement.spacedBy(2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				for (tab in tabs) {
					SeasonTabButton(
						text = tab.name,
						selected = tab.id == selectedId,
						onClick = { onSelected(tab) },
						modifier = Modifier
							.onPlaced { coordinates -> pillBounds[tab.id] = coordinates.positionInParent().x.roundToInt() to coordinates.size.width }
							.then(if (tab.id == selectedId) Modifier.focusRequester(selectedFocusRequester) else Modifier),
					)
				}
			}
		}

		Spacer(Modifier.width(14.dp))

		Text(
			text = quantityString(R.plurals.modern_details_episodes, episodeCount),
			color = ModernHomeColors.TextMuted,
			fontSize = 12.sp,
			maxLines = 1,
		)
	}
}

@Composable
private fun SeasonTabButton(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	val containerColor = when {
		focused -> Color.White
		selected -> ModernHomeColors.PillActive
		else -> Color.Transparent
	}

	Text(
		text = text,
		color = if (focused) Color.Black else if (selected) ModernHomeColors.TextPrimary else ModernHomeColors.TextSecondary,
		fontSize = 13.sp,
		fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold,
		maxLines = 1,
		modifier = modifier
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.background(containerColor, CircleShape)
			.padding(horizontal = 14.dp, vertical = 7.dp),
	)
}

/**
 * Episodes of a season as a horizontal carousel, starting at the next episode to watch.
 * With [seasonTabs] the title is replaced by a season switcher.
 */
@Composable
internal fun EpisodesCarousel(
	title: String,
	entries: List<EpisodeEntry>,
	seasonTabs: List<SeasonTab>,
	selectedSeasonId: java.util.UUID?,
	focusRequesterFor: (EpisodeEntry) -> FocusRequester?,
	onFocused: (EpisodeEntry) -> Unit,
	onClick: (EpisodeEntry) -> Unit,
	onLongClick: (EpisodeEntry) -> Unit,
	onTogglePlayed: (EpisodeEntry) -> Unit,
	onSeasonSelected: (SeasonTab) -> Unit,
	modifier: Modifier = Modifier,
	/** Episode to scroll to when the page opens, the next episode by default */
	startEntry: EpisodeEntry? = null,
) {
	val startIndex = remember(entries, startEntry) {
		entries.indexOf(startEntry ?: entries.firstOrNull { it.isNext }).coerceAtLeast(0)
	}
	// The first visible episode gets the focus when moving down. A new list per season, so switching
	// season starts at its next episode again
	val listState = key(selectedSeasonId) { rememberLazyListState(initialFirstVisibleItemIndex = startIndex) }

	DetailsRow(
		title = title,
		items = entries,
		key = { it.item.id },
		listState = listState,
		header = {
			if (seasonTabs.size > 1) {
				SeasonTabs(
					tabs = seasonTabs,
					selectedId = selectedSeasonId,
					episodeCount = entries.size,
					onSelected = onSeasonSelected,
				)
			} else {
				EpisodesHeader(title = title, count = entries.size)
			}
		},
		modifier = modifier,
	) { entry ->
		EpisodeCard(
			entry = entry,
			focusRequester = focusRequesterFor(entry),
			onFocused = { onFocused(entry) },
			onClick = { onClick(entry) },
			onLongClick = { onLongClick(entry) },
			onTogglePlayed = { onTogglePlayed(entry) },
		)
	}
}

@Composable
private fun EpisodeCard(
	entry: EpisodeEntry,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onTogglePlayed: () -> Unit,
) {
	// The quick "watched" button only shows on the focused episode
	var hasFocus by remember { mutableStateOf(false) }

	Column(
		modifier = Modifier
			.width(EPISODE_CARD_WIDTH)
			.onFocusChanged { hasFocus = it.hasFocus },
	) {
		FocusableCardSurface(
			onClick = onClick,
			// Hold OK: file details
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			// Play/pause on the remote plays the episode too
			onPlay = onClick,
			modifier = Modifier.size(EPISODE_CARD_WIDTH, EPISODE_CARD_HEIGHT),
		) { focused ->
			CardArtwork(url = entry.imageUrl, fallbackTitle = entry.title)

			// Quality top left (bottom left under "Next up", above the progress bar), audio flags
			// and the watched check top right, like the other 16:9 cards
			val qualityOnTop = entry.quality != null && !entry.isNext
			val qualityBelow = entry.quality != null && entry.isNext
			if (qualityOnTop || entry.languages.isNotEmpty()) {
				Box(
					modifier = Modifier
						.align(Alignment.TopCenter)
						.fillMaxWidth()
						.height(40.dp)
						.background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent))
				)
			}
			if (qualityOnTop) {
				QualityBadges(
					quality = entry.quality,
					modifier = Modifier
						.align(Alignment.TopStart)
						.padding(start = 6.dp, top = 6.dp, end = 90.dp),
				)
			}

			// Readability of the progress bar (and the quality above it)
			if (entry.progress != null || qualityBelow) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.fillMaxWidth()
						.height(36.dp)
						.background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)))
				)
			}

			if (entry.isNext) {
				Text(
					text = stringResource(R.string.lbl_next_up).uppercase(Locale.getDefault()),
					color = Color.White,
					fontSize = 9.sp,
					lineHeight = 11.sp,
					fontWeight = FontWeight.Bold,
					letterSpacing = 0.6.sp,
					maxLines = 1,
					modifier = Modifier
						.align(Alignment.TopStart)
						.background(ModernHomeColors.Accent, RoundedCornerShape(bottomEnd = 6.dp))
						.padding(horizontal = 7.dp, vertical = 3.dp),
				)
			}

			Row(
				horizontalArrangement = Arrangement.spacedBy(5.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(6.dp),
			) {
				LanguageFlags(languages = entry.languages)

				if (entry.played) {
					Box(
						modifier = Modifier
							.size(20.dp)
							.background(ModernHomeColors.Accent, CircleShape),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_check),
							contentDescription = null,
							tint = Color.White,
							modifier = Modifier.size(12.dp),
						)
					}
				}
			}

			if (qualityBelow) {
				QualityBadges(
					quality = entry.quality,
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(start = 10.dp, bottom = if (entry.progress != null) 18.dp else 8.dp),
				)
			}

			if (focused) {
				Box(
					modifier = Modifier
						.align(Alignment.Center)
						.size(42.dp)
						.background(Color.White.copy(alpha = 0.92f), CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_play),
						contentDescription = null,
						tint = Color.Black,
						modifier = Modifier.size(19.dp),
					)
				}
			}

			entry.progress?.let { progress ->
				WatchProgressBar(
					progress = progress,
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.padding(horizontal = 10.dp, vertical = 8.dp),
				)
			}
		}

		Spacer(Modifier.height(8.dp))

		Row(
			modifier = Modifier.height(26.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = entry.number,
				color = if (entry.isNext) ModernHomeColors.Accent else ModernHomeColors.TextMuted,
				fontFamily = BebasNeue,
				fontSize = 20.sp,
				lineHeight = 20.sp,
				maxLines = 1,
			)
			Spacer(Modifier.width(7.dp))
			Text(
				text = entry.title,
				color = ModernHomeColors.TextPrimary,
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)

			// Press down from the episode to reach it
			if (hasFocus) {
				Spacer(Modifier.width(6.dp))
				CircleActionButton(
					icon = R.drawable.ic_check,
					contentDescription = stringResource(if (entry.played) R.string.lbl_mark_unplayed else R.string.lbl_mark_played),
					active = entry.played,
					onClick = onTogglePlayed,
					size = 26.dp,
				)
			}
		}

		if (entry.runtimeMinutes != null || entry.rating != null) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				entry.runtimeMinutes?.let { minutes ->
					Text(text = formatRuntime(minutes), color = ModernHomeColors.TextMuted, fontSize = 10.sp)
					Spacer(Modifier.width(10.dp))
				}

				entry.rating?.let { rating ->
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_star),
						contentDescription = null,
						tint = ModernHomeColors.RatingStar,
						modifier = Modifier.size(9.dp),
					)
					Spacer(Modifier.width(3.dp))
					Text(
						text = String.format(Locale.getDefault(), "%.1f", rating),
						color = ModernHomeColors.TextSecondary,
						fontSize = 10.sp,
						fontWeight = FontWeight.Bold,
					)
				}
			}
		}

		entry.overview?.let { overview ->
			Spacer(Modifier.height(4.dp))
			Text(
				text = overview,
				color = ModernHomeColors.TextSecondary,
				fontSize = 10.sp,
				lineHeight = 14.sp,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

private val EPISODE_CARD_WIDTH = 236.dp
private val EPISODE_CARD_HEIGHT = 133.dp

// Media info

/**
 * File details: where it is, size, container, and every video/audio/subtitle track.
 * Up/down scroll when it doesn't fit on screen.
 */
@Composable
internal fun MediaInfoDialog(
	visible: Boolean,
	item: BaseItemDto?,
	onDismissRequest: () -> Unit,
) {
	val scrollState = rememberScrollState()
	val scope = rememberCoroutineScope()
	val source = item?.mediaSources?.firstOrNull()
	val streams = source?.mediaStreams ?: item?.mediaStreams.orEmpty()
	val path = source?.path ?: item?.path

	val fileRows = listOfNotNull(
		path?.substringAfterLast('/')?.substringAfterLast('\\')?.let { stringResource(R.string.modern_details_file_name) to it },
		path?.let { stringResource(R.string.modern_details_file_path) to it },
		source?.size?.takeIf { it > 0 }?.let { stringResource(R.string.modern_details_file_size) to formatFileSize(it) },
		source?.container?.let { stringResource(R.string.modern_details_container) to it.uppercase(Locale.getDefault()) },
		(source?.runTimeTicks ?: item?.runTimeTicks)?.let { stringResource(R.string.modern_details_duration) to formatRuntime((it / 600_000_000L).toInt()) },
		source?.bitrate?.takeIf { it > 0 }?.let { stringResource(R.string.modern_details_bitrate) to formatBitrate(it) },
	)

	val videoRows = streams.filter { it.type == MediaStreamType.VIDEO }.map { stream ->
		listOfNotNull(
			listOfNotNull(stream.codec?.uppercase(Locale.getDefault()), stream.profile).joinToString(" "),
			if (stream.width != null && stream.height != null) "${stream.width}×${stream.height}" else null,
			stream.videoRangeType.serialName.takeIf { it != "Unknown" },
			stream.bitDepth?.let { "$it-bit" },
			(stream.realFrameRate ?: stream.averageFrameRate)?.let { "${String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.')} fps" },
			stream.bitRate?.takeIf { it > 0 }?.let(::formatBitrate),
		).filter { it.isNotBlank() }.joinToString("  ·  ")
	}

	val audioRows = streams.filter { it.type == MediaStreamType.AUDIO }.map { stream ->
		listOfNotNull(
			stream.displayTitle ?: stream.language,
			stream.codec?.uppercase(Locale.getDefault()),
			stream.channelLayout ?: stream.channels?.let { "${it}ch" },
			stream.bitRate?.takeIf { it > 0 }?.let(::formatBitrate),
			if (stream.isDefault) stringResource(R.string.modern_details_default) else null,
		).distinct().joinToString("  ·  ")
	}

	val subtitleRows = streams.filter { it.type == MediaStreamType.SUBTITLE }.map { stream ->
		listOfNotNull(
			stream.displayTitle ?: stream.language,
			stream.codec?.uppercase(Locale.getDefault()),
			if (stream.isForced) stringResource(R.string.modern_details_forced) else null,
			if (stream.isExternal) stringResource(R.string.modern_details_external) else null,
		).distinct().joinToString("  ·  ")
	}

	ModernDialog(
		visible = visible,
		title = item?.name ?: stringResource(R.string.modern_details_media_info),
		onDismissRequest = onDismissRequest,
		maxWidth = 820.dp,
	) {
		Column(
			modifier = Modifier
				.heightIn(max = 330.dp)
				.verticalScroll(scrollState),
		) {
			MediaInfoSection(stringResource(R.string.modern_details_file)) {
				for ((label, value) in fileRows) MediaInfoRow(label, value)
			}

			Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
				Column(modifier = Modifier.weight(1f)) {
					if (videoRows.isNotEmpty()) {
						MediaInfoSection(stringResource(R.string.modern_details_video)) {
							for (row in videoRows) MediaInfoLine(row)
						}
					}
					if (subtitleRows.isNotEmpty()) {
						MediaInfoSection(stringResource(R.string.modern_details_subtitles)) {
							for (row in subtitleRows.take(MAX_STREAMS_PER_GROUP)) MediaInfoLine(row)
							if (subtitleRows.size > MAX_STREAMS_PER_GROUP) MediaInfoLine("+${subtitleRows.size - MAX_STREAMS_PER_GROUP}")
						}
					}
				}

				Column(modifier = Modifier.weight(1f)) {
					if (audioRows.isNotEmpty()) {
						MediaInfoSection(stringResource(R.string.modern_details_audio)) {
							for (row in audioRows.take(MAX_STREAMS_PER_GROUP)) MediaInfoLine(row)
							if (audioRows.size > MAX_STREAMS_PER_GROUP) MediaInfoLine("+${audioRows.size - MAX_STREAMS_PER_GROUP}")
						}
					}
				}
			}
		}

		Spacer(Modifier.height(16.dp))

		HeroButton(
			text = stringResource(R.string.modern_details_close),
			icon = null,
			primary = true,
			onClick = onDismissRequest,
			// Only the close button can be focused: up/down scroll the details instead
			modifier = Modifier.onPreviewKeyEvent { event ->
				val distance = when (event.key) {
					Key.DirectionDown -> 140f
					Key.DirectionUp -> -140f
					else -> return@onPreviewKeyEvent false
				}
				if (event.type == KeyEventType.KeyDown) scope.launch { scrollState.animateScrollBy(distance) }
				true
			},
		)
	}
}

@Composable
private fun MediaInfoSection(
	title: String,
	content: @Composable () -> Unit,
) {
	Text(
		text = title.uppercase(Locale.getDefault()),
		color = ModernHomeColors.Accent,
		fontSize = 11.sp,
		fontWeight = FontWeight.Bold,
		letterSpacing = 0.8.sp,
	)
	Spacer(Modifier.height(4.dp))
	content()
	Spacer(Modifier.height(14.dp))
}

@Composable
private fun MediaInfoRow(label: String, value: String) {
	Row(modifier = Modifier.padding(vertical = 1.dp)) {
		Text(
			text = label,
			color = ModernHomeColors.TextMuted,
			fontSize = 12.sp,
			maxLines = 1,
			modifier = Modifier.width(110.dp),
		)
		Text(
			text = value,
			color = ModernHomeColors.TextPrimary,
			fontSize = 12.sp,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun MediaInfoLine(text: String) {
	Text(
		text = text,
		color = ModernHomeColors.TextSecondary,
		fontSize = 12.sp,
		lineHeight = 17.sp,
		maxLines = 2,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier.padding(vertical = 1.dp),
	)
}

private fun formatFileSize(bytes: Long): String {
	val gigabytes = bytes / 1_073_741_824.0
	return if (gigabytes >= 1) String.format(Locale.getDefault(), "%.2f GB", gigabytes)
	else String.format(Locale.getDefault(), "%.0f MB", bytes / 1_048_576.0)
}

private fun formatBitrate(bitsPerSecond: Int): String =
	if (bitsPerSecond >= 1_000_000) String.format(Locale.getDefault(), "%.1f Mbps", bitsPerSecond / 1_000_000.0)
	else String.format(Locale.getDefault(), "%d kbps", bitsPerSecond / 1000)

private const val MAX_STREAMS_PER_GROUP = 8

@Composable
private fun quantityString(id: Int, quantity: Int): String =
	LocalContext.current.resources.getQuantityString(id, quantity, quantity)
