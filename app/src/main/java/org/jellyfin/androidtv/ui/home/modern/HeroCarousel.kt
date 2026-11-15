package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** Time each slide of the media bar stays on screen */
internal val HERO_AUTO_ADVANCE = 9.seconds
private val INFO_AREA_HEIGHT = 210.dp

/**
 * Big featured carousel at the top of the home screen (similar to the Media Bar plugin on the web).
 * The bottom of the backdrop fades out so it melts into the blurred ambient background of the page.
 *
 * D-pad: left on "Play" shows the previous slide, right on "More info" shows the next one.
 */
@Composable
internal fun HeroCarousel(
	slides: List<HeroSlide>,
	index: Int,
	onIndexChange: (Int) -> Unit,
	height: Dp,
	autoAdvance: Boolean,
	playFocusRequester: FocusRequester,
	onPlay: (HeroSlide) -> Unit,
	onInfo: (HeroSlide) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (slides.isEmpty()) return

	val currentIndex = index.coerceIn(0, slides.lastIndex)
	val slide = slides[currentIndex]
	val imageLoader = koinInject<ImageLoader>()

	fun showPrevious() = onIndexChange((currentIndex - 1 + slides.size) % slides.size)
	fun showNext() = onIndexChange((currentIndex + 1) % slides.size)

	// Restarts every time the slide changes, so manual navigation also resets the timer
	LaunchedEffect(currentIndex, autoAdvance, slides.size) {
		if (!autoAdvance || slides.size < 2) return@LaunchedEffect
		delay(HERO_AUTO_ADVANCE)
		showNext()
	}

	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(height),
	) {
		// Backdrop + readability gradients, faded out at the bottom
		Box(
			modifier = Modifier
				.fillMaxSize()
				.fadeOutBottom(),
		) {
			Crossfade(
				targetState = slide,
				animationSpec = tween(durationMillis = 700),
				label = "HeroBackdrop",
				modifier = Modifier.fillMaxSize(),
			) { current ->
				if (current.backdropUrl != null) {
					AsyncImage(
						model = current.backdropUrl,
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
						Brush.horizontalGradient(
							0f to Color.Black.copy(alpha = 0.82f),
							0.35f to Color.Black.copy(alpha = 0.5f),
							0.65f to Color.Transparent,
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
				.padding(start = ModernHomeDimens.ScreenPaddingHorizontal, bottom = 26.dp)
				.fillMaxWidth(0.52f),
		) {
			Crossfade(
				targetState = slide,
				animationSpec = tween(durationMillis = 450),
				label = "HeroInfo",
				modifier = Modifier
					.fillMaxWidth()
					.height(INFO_AREA_HEIGHT),
			) { current ->
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.BottomStart,
				) {
					HeroInfo(current, imageLoader)
				}
			}

			Spacer(Modifier.height(18.dp))

			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				HeroButton(
					text = stringResource(R.string.lbl_play),
					icon = R.drawable.ic_play,
					primary = true,
					onClick = { onPlay(slide) },
					modifier = Modifier
						.focusRequester(playFocusRequester)
						.onPreviewKeyEvent { event ->
							if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && slides.size > 1) {
								showPrevious()
								true
							} else false
						},
				)

				HeroButton(
					text = stringResource(R.string.modern_home_more_info),
					icon = R.drawable.ic_info,
					primary = false,
					onClick = { onInfo(slide) },
					modifier = Modifier.onPreviewKeyEvent { event ->
						if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && slides.size > 1) {
							showNext()
							true
						} else false
					},
				)
			}
		}

		// Page indicator
		if (slides.size > 1) {
			Row(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = ModernHomeDimens.ScreenPaddingHorizontal, bottom = 40.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				slides.indices.forEach { dotIndex ->
					val selected = dotIndex == currentIndex
					val width by animateDpAsState(if (selected) 18.dp else 6.dp, label = "HeroDotWidth")
					Box(
						modifier = Modifier
							.size(width = width, height = 6.dp)
							.background(
								color = if (selected) ModernHomeColors.Accent else ModernHomeColors.TextPrimary.copy(alpha = 0.35f),
								shape = CircleShape,
							)
					)
				}
			}
		}
	}
}

@Composable
private fun HeroInfo(
	slide: HeroSlide,
	imageLoader: ImageLoader,
) {
	Column {
		if (slide.logoUrl != null) {
			AsyncImage(
				model = slide.logoUrl,
				contentDescription = slide.title,
				imageLoader = imageLoader,
				contentScale = ContentScale.Fit,
				alignment = Alignment.BottomStart,
				modifier = Modifier
					.widthIn(max = 340.dp)
					.fillMaxWidth()
					.height(96.dp),
			)
		} else {
			Text(
				text = slide.title,
				color = ModernHomeColors.TextPrimary,
				fontSize = 38.sp,
				lineHeight = 40.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Spacer(Modifier.height(12.dp))
		HeroMetadata(slide)

		if (slide.genres.isNotEmpty()) {
			Spacer(Modifier.height(4.dp))
			Text(
				text = slide.genres.joinToString(" · "),
				color = ModernHomeColors.TextSecondary,
				fontSize = 13.sp,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		if (!slide.overview.isNullOrBlank()) {
			Spacer(Modifier.height(10.dp))
			Text(
				text = slide.overview,
				color = ModernHomeColors.TextSecondary,
				fontSize = 13.sp,
				lineHeight = 18.sp,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun HeroMetadata(slide: HeroSlide) {
	val typeLabel = when (slide.item.type) {
		BaseItemKind.SERIES -> stringResource(R.string.modern_home_tv_show)
		else -> slide.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime) ?: stringResource(R.string.modern_home_movie)
	}
	val parts = listOfNotNull(slide.year?.toString(), typeLabel)

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		if (slide.rating != null && slide.rating > 0f) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_star),
				contentDescription = null,
				tint = ModernHomeColors.Accent,
				modifier = Modifier.size(14.dp),
			)
			Text(
				text = String.format(Locale.getDefault(), "%.1f", slide.rating),
				color = ModernHomeColors.TextPrimary,
				fontSize = 14.sp,
				fontWeight = FontWeight.Bold,
			)
			Text(text = "·", color = ModernHomeColors.TextSecondary, fontSize = 14.sp)
		}

		Text(
			text = parts.joinToString("  ·  "),
			color = ModernHomeColors.TextPrimary,
			fontSize = 14.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
		)

		if (!slide.officialRating.isNullOrBlank()) {
			Text(
				text = slide.officialRating,
				color = ModernHomeColors.TextPrimary,
				fontSize = 10.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				modifier = Modifier
					.border(1.dp, ModernHomeColors.BadgeBorder, RoundedCornerShape(3.dp))
					.padding(horizontal = 4.dp, vertical = 1.dp),
			)
		}

		QualityBadges(slide.quality, scale = 1.3f)
		LanguageFlags(slide.languages)
	}
}

internal fun formatRuntime(minutes: Int): String {
	val hours = minutes / 60
	val remainder = minutes % 60
	return when {
		hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
		hours > 0 -> "${hours}h"
		else -> "${remainder}m"
	}
}

@Composable
internal fun HeroButton(
	text: String,
	icon: Int?,
	primary: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "HeroButtonScale")

	val containerColor = when {
		focused -> Color.White
		primary -> Color.White.copy(alpha = 0.88f)
		else -> Color.Black.copy(alpha = 0.45f)
	}
	val contentColor = if (focused || primary) Color.Black else Color.White
	val border = when {
		focused -> Modifier.border(2.dp, ModernHomeColors.Accent, CircleShape)
		!primary -> Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
		else -> Modifier
	}

	Row(
		modifier = modifier
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
			.then(border)
			.padding(horizontal = 22.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (icon != null) {
			Icon(
				imageVector = ImageVector.vectorResource(icon),
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier.size(18.dp),
			)
			Spacer(Modifier.width(8.dp))
		}
		Text(
			text = text,
			color = contentColor,
			fontSize = 15.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
		)
	}
}

/**
 * Fades the bottom of the content to transparent so the hero dissolves into the page background.
 */
internal fun Modifier.fadeOutBottom(fadeStart: Float = 0.5f): Modifier = this
	.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
	.drawWithContent {
		drawContent()
		drawRect(
			brush = Brush.verticalGradient(
				fadeStart to Color.Black,
				1f to Color.Transparent,
			),
			blendMode = BlendMode.DstIn,
		)
	}
