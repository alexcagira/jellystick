package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val badgeShape = RoundedCornerShape(4.dp)

/** Soft shadow so the white badges stay readable on bright posters */
private val badgeTextShadow = Shadow(
	color = Color.Black.copy(alpha = 0.75f),
	offset = Offset(0f, 1f),
	blurRadius = 4f,
)

/**
 * Quality badges like streaming services, in this order: the resolution in a white block with
 * see-through letters ("4K", "1080p"), then "Dolby" with "ATMOS · VISION" underneath, then
 * "HDR10+" / "HDR" / "HLG" in bold white. [scale] makes everything bigger (hero and details page).
 */
@Composable
internal fun QualityBadges(
	quality: MediaQuality?,
	modifier: Modifier = Modifier,
	scale: Float = 1f,
) {
	if (quality == null || quality.isEmpty) return

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy((4 * scale).dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		quality.resolution?.let { ResolutionBadge(text = it, scale = scale) }
		quality.dolbyCaption?.let { DolbyBadge(caption = it, scale = scale) }
		quality.hdrFormat?.let { HdrBadge(text = it, scale = scale) }
	}
}

/**
 * White rounded block with the text cut out of it, so the poster shows through the letters.
 */
@Composable
private fun ResolutionBadge(
	text: String,
	scale: Float,
) {
	val density = LocalDensity.current
	val textMeasurer = rememberTextMeasurer()
	val style = TextStyle(
		fontSize = (8.5f * scale).sp,
		fontWeight = FontWeight.ExtraBold,
		letterSpacing = 0.sp,
	)
	val layout = remember(text, style, textMeasurer) { textMeasurer.measure(text, style) }
	val width = with(density) { layout.size.width.toDp() } + (3.5f * scale).dp * 2

	Box(
		modifier = Modifier
			.size(width, (13 * scale).dp)
			// Offscreen layer so the text can punch a hole in the white block
			.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
			.drawBehind {
				drawRoundRect(
					color = Color.White.copy(alpha = 0.95f),
					cornerRadius = CornerRadius(3.dp.toPx() * scale),
				)
				drawText(
					textLayoutResult = layout,
					color = Color.Black,
					topLeft = Offset(
						x = (size.width - layout.size.width) / 2f,
						y = (size.height - layout.size.height) / 2f,
					),
					blendMode = BlendMode.DstOut,
				)
			},
	)
}

@Composable
private fun DolbyBadge(
	caption: String,
	scale: Float,
) {
	val style = TextStyle(shadow = badgeTextShadow)

	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(
			text = "Dolby",
			color = ModernHomeColors.TextPrimary,
			fontSize = (10 * scale).sp,
			lineHeight = (10 * scale).sp,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.2).sp,
			maxLines = 1,
			style = style,
		)
		Text(
			text = caption,
			color = ModernHomeColors.TextPrimary,
			fontSize = (5 * scale).sp,
			lineHeight = (6 * scale).sp,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (0.8f * scale).sp,
			maxLines = 1,
			style = style,
		)
	}
}

@Composable
private fun HdrBadge(
	text: String,
	scale: Float,
) {
	Text(
		text = text,
		color = ModernHomeColors.TextPrimary,
		fontSize = (9.5f * scale).sp,
		lineHeight = (10 * scale).sp,
		fontWeight = FontWeight.ExtraBold,
		letterSpacing = (-0.2).sp,
		maxLines = 1,
		style = TextStyle(shadow = badgeTextShadow),
	)
}

/**
 * Red "New episode" / "New season" tab attached to the bottom edge of posters.
 */
@Composable
internal fun NewContentBadge(
	label: NewContentLabel,
	modifier: Modifier = Modifier,
) {
	val text = when (label) {
		NewContentLabel.NEW_EPISODE -> stringResource(R.string.modern_home_new_episode)
		NewContentLabel.NEW_SEASON -> stringResource(R.string.modern_home_new_season)
		NewContentLabel.NEW_SERIES -> stringResource(R.string.modern_home_new_series)
	}

	Text(
		text = text,
		color = ModernHomeColors.TextPrimary,
		fontSize = 10.sp,
		lineHeight = 12.sp,
		fontWeight = FontWeight.Bold,
		maxLines = 1,
		modifier = modifier
			.background(ModernHomeColors.NewLabel, RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
			.padding(horizontal = 9.dp, vertical = 3.dp),
	)
}

private val releaseDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * "10 NOV" label for the latest releases row.
 */
@Composable
internal fun ReleaseDateBadge(
	date: LocalDate,
	modifier: Modifier = Modifier,
) {
	Text(
		text = date.format(releaseDateFormatter).uppercase(Locale.getDefault()).replace(".", ""),
		color = ModernHomeColors.TextPrimary,
		fontSize = 8.sp,
		lineHeight = 10.sp,
		fontWeight = FontWeight.Bold,
		maxLines = 1,
		modifier = modifier
			.background(ModernHomeColors.BadgeBackground, badgeShape)
			.padding(horizontal = 4.dp, vertical = 2.dp),
	)
}

/**
 * Number of unwatched episodes in a dark circle, top right of series posters.
 */
@Composable
internal fun UnplayedCountBadge(
	count: Int,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier = modifier
			.defaultMinSize(minWidth = 19.dp, minHeight = 19.dp)
			.background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(percent = 50))
			.padding(horizontal = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = if (count > 99) "99+" else count.toString(),
			color = ModernHomeColors.TextPrimary,
			fontSize = 9.sp,
			lineHeight = 11.sp,
			fontWeight = FontWeight.Bold,
			textAlign = TextAlign.Center,
			maxLines = 1,
		)
	}
}

/**
 * "29m left" label for continue watching cards.
 */
@Composable
internal fun TimeLeftBadge(
	minutes: Int,
	modifier: Modifier = Modifier,
) {
	val hours = minutes / 60
	val remainder = minutes % 60
	val text = if (hours > 0) stringResource(R.string.modern_home_time_left_hours, hours, remainder)
	else stringResource(R.string.modern_home_time_left_minutes, minutes)

	Text(
		text = text,
		color = ModernHomeColors.TextPrimary,
		fontSize = 9.sp,
		lineHeight = 11.sp,
		fontWeight = FontWeight.Bold,
		maxLines = 1,
		modifier = modifier
			.background(ModernHomeColors.BadgeBackground, RoundedCornerShape(topStart = 8.dp, bottomEnd = 6.dp))
			.padding(horizontal = 6.dp, vertical = 3.dp),
	)
}

@Composable
internal fun WatchProgressBar(
	progress: Float,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(3.dp)
			.background(ModernHomeColors.ProgressTrack, RoundedCornerShape(2.dp))
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(progress.coerceIn(0f, 1f))
				.fillMaxHeight()
				.background(ModernHomeColors.Accent, RoundedCornerShape(2.dp))
		)
	}
}
