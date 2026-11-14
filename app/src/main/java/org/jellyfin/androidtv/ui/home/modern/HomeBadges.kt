package org.jellyfin.androidtv.ui.home.modern

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

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
	val bitmap = remember(text, scale, density) { ResolutionBadgeCache.get(text, scale, density.density, density.fontScale) }

	Box(
		modifier = Modifier
			.size(with(density) { bitmap.width.toDp() }, with(density) { bitmap.height.toDp() })
			.drawBehind { drawImage(bitmap) },
	)
}

/**
 * White block with the text cut out, drawn once per resolution label ("4K", "1080p"...) and shared
 * by every poster. Drawing it per poster needed an offscreen layer each, which made scrolling the
 * home rows stutter on the Fire TV.
 */
private object ResolutionBadgeCache {
	private val bitmaps = HashMap<String, ImageBitmap>()

	fun get(text: String, scale: Float, density: Float, fontScale: Float): ImageBitmap =
		bitmaps.getOrPut("$text|$scale|$density|$fontScale") { draw(text, scale, density, fontScale) }

	private fun draw(text: String, scale: Float, density: Float, fontScale: Float): ImageBitmap {
		val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			textSize = 8.5f * scale * density * fontScale
			typeface = badgeTypeface
			textAlign = Paint.Align.CENTER
			xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
		}
		val width = ceil(textPaint.measureText(text) + 3.5f * scale * density * 2).toInt().coerceAtLeast(1)
		val height = ceil(13 * scale * density).toInt().coerceAtLeast(1)
		val radius = 3 * scale * density

		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
		canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, blockPaint)

		val metrics = textPaint.fontMetrics
		val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
		canvas.drawText(text, width / 2f, baseline, textPaint)

		return bitmap.asImageBitmap()
	}

	private val badgeTypeface: Typeface by lazy {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 800, false)
		else Typeface.create("sans-serif-black", Typeface.NORMAL)
	}
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
