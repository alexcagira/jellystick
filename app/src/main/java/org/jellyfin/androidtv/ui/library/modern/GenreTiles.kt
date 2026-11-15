package org.jellyfin.androidtv.ui.library.modern

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens

private val tileShape = RoundedCornerShape(10.dp)

/**
 * Colored tile of a genre with a big faded icon (search page): OK opens every title of the genre.
 */
@Composable
internal fun GenreTile(
	name: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	music: Boolean = false,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "GenreTileScale")
	val color = if (music) MusicGenreStyle.colorOf(name) else GenreStyle.colorOf(name)
	val icon = if (music) MusicGenreStyle.iconOf(name) else GenreStyle.iconOf(name)

	Box(
		modifier = modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.aspectRatio(1.7f)
			.clip(tileShape)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(Color(0xFF0E1118), tileShape)
			.background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))), tileShape)
			.then(if (focused) Modifier.border(2.5.dp, Color.White, tileShape) else Modifier),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = null,
			tint = Color.White.copy(alpha = if (focused) 0.32f else 0.2f),
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(top = 4.dp, end = 4.dp)
				.size(58.dp),
		)
		Text(
			text = name,
			color = Color.White,
			fontSize = 15.sp,
			fontWeight = FontWeight.Bold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(horizontal = 12.dp, vertical = 10.dp),
		)
	}
}

/** Genres in rows of [columns] tiles */
@Composable
internal fun GenreTileRow(
	genres: List<BrowseChip>,
	columns: Int,
	onGenre: (BrowseChip) -> Unit,
	modifier: Modifier = Modifier,
	music: Boolean = false,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(14.dp),
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
	) {
		for (genre in genres) {
			GenreTile(name = genre.name, onClick = { onGenre(genre) }, modifier = Modifier.weight(1f), music = music)
		}
		// Keep the size of the tiles in the last row
		repeat(columns - genres.size) { Spacer(Modifier.weight(1f)) }
	}
}

/** Popular tags in a few horizontal rows of pills */
@Composable
internal fun TagPillRows(
	tags: List<String>,
	rows: Int,
	onTag: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val perRow = (tags.size + rows - 1) / rows
	Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
		for (row in tags.chunked(perRow.coerceAtLeast(1))) {
			LazyRow(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
			) {
				items(row, key = { it }) { tag ->
					TagPill(text = tag, selected = false, onClick = { onTag(tag) })
				}
			}
		}
	}
}
