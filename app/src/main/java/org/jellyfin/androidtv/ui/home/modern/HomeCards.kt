package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.vectorResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import java.util.Locale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.ui.base.Text
import org.koin.compose.koinInject

private val cardShape = RoundedCornerShape(ModernHomeDimens.CardCornerRadius)

/**
 * Focusable surface shared by all cards: scales up and gets a white ring when focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FocusableCardSurface(
	onClick: () -> Unit,
	onLongClick: (() -> Unit)?,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
	onPlay: (() -> Unit)? = null,
	content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(
		targetValue = if (focused) ModernHomeDimens.FocusScale else 1f,
		label = "CardFocusScale",
	)

	Box(
		modifier = modifier
			.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
			.onFocusChanged { state -> if (state.isFocused) onFocused() }
			.onPreviewKeyEvent { event ->
				// Play/pause button of the remote starts playback directly
				if (onPlay == null || !event.isPlayKey) return@onPreviewKeyEvent false
				if (event.type == KeyEventType.KeyDown) onPlay()
				// Also consume the key up, otherwise Android forwards it to the active media session
				true
			}
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.combinedClickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
				onLongClick = onLongClick,
			)
			.clip(cardShape)
			.background(ModernHomeColors.CardSurface, cardShape)
			.then(
				if (focused) Modifier.border(ModernHomeDimens.FocusBorderWidth, ModernHomeColors.FocusRing, cardShape)
				else Modifier
			),
	) {
		content(focused)
	}
}

/**
 * Card artwork. The title is drawn underneath so something readable shows up when an item has no image.
 */
@Composable
internal fun CardArtwork(
	url: String?,
	fallbackTitle: String,
	modifier: Modifier = Modifier,
) {
	// The title is only laid out when there is no image: measuring it under every poster cost time
	// on each new card while scrolling
	var failed by remember(url) { mutableStateOf(false) }

	Box(
		modifier = modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		if (url == null || failed) {
			Text(
				text = fallbackTitle,
				color = ModernHomeColors.TextSecondary,
				fontSize = 12.sp,
				fontWeight = FontWeight.SemiBold,
				textAlign = TextAlign.Center,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(8.dp),
			)
		} else {
			val imageLoader = koinInject<ImageLoader>()
			AsyncImage(
				model = url,
				contentDescription = null,
				imageLoader = imageLoader,
				onError = { failed = true },
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

@Composable
internal fun PosterCard(
	card: HomeCard,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
	onPlay: (() -> Unit)? = null,
) {
	FocusableCardSurface(
		onClick = onClick,
		onLongClick = onLongClick,
		onFocused = onFocused,
		focusRequester = focusRequester,
		onPlay = onPlay,
		modifier = modifier.size(ModernHomeDimens.PosterWidth, ModernHomeDimens.PosterHeight),
	) { focused ->
		CardArtwork(url = card.imageUrl, fallbackTitle = card.title)

		// Focused: darken the poster and show title, episode, rating and a play button
		val overlayAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "PosterFocusOverlay")
		if (overlayAlpha > 0f) {
			PosterFocusOverlay(
				card = card,
				modifier = Modifier
					.fillMaxSize()
					.graphicsLayer { alpha = overlayAlpha },
			)
		}

		// Darken the top of the poster a little so the white badges are readable on bright artwork
		val hasQuality = card.quality?.isEmpty == false
		if (hasQuality || card.releaseDate != null || card.unplayedCount != null) {
			Box(
				modifier = Modifier
					.align(Alignment.TopCenter)
					.fillMaxWidth()
					.height(46.dp)
					.background(
						Brush.verticalGradient(
							0f to Color.Black.copy(alpha = 0.55f),
							1f to Color.Transparent,
						)
					)
			)
		}

		// Top left: resolution, Dolby, HDR
		QualityBadges(
			quality = card.quality,
			modifier = Modifier
				.align(Alignment.TopStart)
				.padding(start = 6.dp, top = 6.dp, end = 28.dp),
		)

		// Top right: unwatched episodes, or the release date in "Latest releases"
		Row(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(5.dp),
			horizontalArrangement = Arrangement.spacedBy(3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			card.releaseDate?.let { ReleaseDateBadge(it) }
			card.unplayedCount?.let { UnplayedCountBadge(it) }
		}

		// "New episode" footer, replaced by the info overlay while focused
		val showNewLabel = !focused && card.newLabel != null
		if (showNewLabel) {
			card.newLabel?.let { label ->
				NewContentBadge(
					label = label,
					modifier = Modifier.align(Alignment.BottomCenter),
				)
			}
		}

		// Audio languages in the bottom left corner, above the "New episode" footer when there is one
		LanguageFlags(
			languages = card.languages,
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(start = 5.dp, bottom = if (showNewLabel) 23.dp else 5.dp),
		)
	}
}

@Composable
private fun PosterFocusOverlay(
	card: HomeCard,
	modifier: Modifier = Modifier,
) {
	val details = card.episodeLabel ?: card.year?.toString()

	Box(
		modifier = modifier.background(
			Brush.verticalGradient(
				0f to Color.Black.copy(alpha = 0.35f),
				0.45f to Color.Black.copy(alpha = 0.6f),
				1f to Color.Black.copy(alpha = 0.85f),
			)
		),
	) {
		Column(
			modifier = Modifier
				.align(Alignment.Center)
				.padding(start = 6.dp, end = 6.dp, top = 20.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.background(Color.White.copy(alpha = 0.92f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_play),
					contentDescription = null,
					tint = Color.Black,
					modifier = Modifier.size(18.dp),
				)
			}

			Spacer(Modifier.height(8.dp))

			Text(
				text = card.title,
				color = ModernHomeColors.TextPrimary,
				fontSize = 12.sp,
				lineHeight = 14.sp,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)

			if (details != null || card.rating != null) {
				Spacer(Modifier.height(3.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					if (details != null) {
						Text(
							text = details,
							color = ModernHomeColors.TextSecondary,
							fontSize = 10.sp,
							fontWeight = FontWeight.SemiBold,
							maxLines = 1,
						)
					}

					if (card.rating != null) {
						if (details != null) Spacer(Modifier.width(6.dp))
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_star),
							contentDescription = null,
							tint = ModernHomeColors.RatingStar,
							modifier = Modifier.size(10.dp),
						)
						Spacer(Modifier.width(2.dp))
						Text(
							text = String.format(Locale.getDefault(), "%.1f", card.rating),
							color = ModernHomeColors.TextPrimary,
							fontSize = 10.sp,
							fontWeight = FontWeight.Bold,
							maxLines = 1,
						)
					}
				}
			}
		}
	}
}

@Composable
internal fun LandscapeCard(
	card: HomeCard,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
) {
	Column(modifier = modifier.width(ModernHomeDimens.LandscapeWidth)) {
		FocusableCardSurface(
			onClick = onClick,
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			onPlay = onClick,
			modifier = Modifier.size(ModernHomeDimens.LandscapeWidth, ModernHomeDimens.LandscapeHeight),
		) {
			CardArtwork(url = card.imageUrl, fallbackTitle = card.title)

			card.remainingMinutes?.let { minutes ->
				TimeLeftBadge(
					minutes = minutes,
					modifier = Modifier.align(Alignment.TopStart),
				)
			}

			card.progress?.let { progress ->
				WatchProgressBar(
					progress = progress,
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.padding(horizontal = 12.dp, vertical = 9.dp),
				)
			}
		}

		Spacer(Modifier.height(8.dp))

		Text(
			text = card.title,
			color = ModernHomeColors.TextPrimary,
			fontSize = 13.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		card.subtitle?.let { subtitle ->
			Text(
				text = subtitle,
				color = ModernHomeColors.TextMuted,
				fontSize = 11.sp,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Poster with a big outlined rank number behind it ("Trending this week").
 */
@Composable
internal fun RankedPosterCard(
	rank: Int,
	card: HomeCard,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
	onPlay: (() -> Unit)? = null,
) {
	val density = LocalDensity.current
	val numberWidth = if (rank >= 10) ModernHomeDimens.RankNumberWidthWide else ModernHomeDimens.RankNumberWidth
	// Sized in dp so the number always matches the poster, regardless of the system font scale
	val numberFontSize = with(density) { RANK_FONT_SIZE.toSp() }
	val numberStyle = remember(numberFontSize) {
		TextStyle(
			fontFamily = BebasNeue,
			fontSize = numberFontSize,
			drawStyle = Stroke(width = with(density) { 2.dp.toPx() }),
		)
	}

	Box(
		modifier = modifier
			.width(numberWidth + ModernHomeDimens.PosterWidth - ModernHomeDimens.RankNumberOverlap)
			.height(ModernHomeDimens.PosterHeight),
	) {
		Text(
			text = rank.toString(),
			style = numberStyle,
			color = ModernHomeColors.RankOutline,
			maxLines = 1,
			softWrap = false,
			modifier = Modifier
				.align(Alignment.BottomStart)
				// Sit the digits on the bottom edge of the poster: the baseline is placed on it, so
				// nothing is cut off whatever the font metrics are
				.layout { measurable, constraints ->
					val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
					val baseline = placeable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
					val height = constraints.maxHeight
					layout(placeable.width, height) { placeable.place(0, height - baseline) }
				},
		)

		PosterCard(
			card = card,
			onClick = onClick,
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			onPlay = onPlay,
			modifier = Modifier.align(Alignment.BottomEnd),
		)
	}
}

private val RANK_FONT_SIZE = 190.dp

internal val KeyEvent.isPlayKey: Boolean
	get() = key == Key.MediaPlayPause || key == Key.MediaPlay
