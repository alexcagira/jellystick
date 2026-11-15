package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

/**
 * Song playing right now, shown under the buttons of the navigation bar while music plays. OK opens
 * "Now playing". The cover has a ring with the progress of the song, like the original bubble.
 */
@Composable
internal fun ModernNowPlayingBubble(
	glassBackdrop: GlassBackdrop?,
	focusRequester: FocusRequester,
	modifier: Modifier = Modifier,
	focusable: Boolean = true,
	onFocusChanged: (Boolean) -> Unit = {},
) {
	val api = koinInject<ApiClient>()
	val playbackManager = koinInject<PlaybackManager>()
	val navigationRepository = koinInject<NavigationRepository>()
	val imageLoader = koinInject<ImageLoader>()

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val progress by rememberPlayerProgress(playbackManager)

	// Last song, kept to show it while the bubble fades out
	val lastItem = remember { arrayOfNulls<BaseItemDto>(1) }
	if (item != null) lastItem[0] = item

	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "NowPlayingScale")

	AnimatedVisibility(
		visible = item != null,
		enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
		exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
		modifier = modifier,
	) {
		val shown = item ?: lastItem[0] ?: return@AnimatedVisibility
		val image = shown.itemImages[ImageType.PRIMARY] ?: shown.albumPrimaryImage ?: shown.parentImages[ImageType.PRIMARY]
		val imageUrl = remember(image) { image?.getUrl(api, maxWidth = 120) }
		val artists = shown.artists ?: shown.albumArtists?.mapNotNull { it.name } ?: shown.albumArtist?.let(::listOf)

		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.padding(top = 10.dp)
				.graphicsLayer {
					scaleX = scale
					scaleY = scale
				}
				.widthIn(max = 280.dp)
				.focusRequester(focusRequester)
				.onFocusChanged { onFocusChanged(it.isFocused) }
				.focusProperties { canFocus = focusable }
				.glassSurface(glassBackdrop, CircleShape)
				.clickable(
					interactionSource = interactionSource,
					indication = null,
					onClick = { navigationRepository.navigate(Destinations.nowPlaying) },
				)
				.background(if (focused) Color.White else Color.Transparent, CircleShape)
				.padding(4.dp),
		) {
			val ringColor = if (focused) Color.Black else ModernHomeColors.Accent

			Box(
				modifier = Modifier
					.size(34.dp)
					.clip(CircleShape)
					.background(ModernHomeColors.CardSurface)
					.drawWithContent {
						drawContent()
						val width = 2.5.dp.toPx()
						drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = size.minDimension / 2 - width / 2, style = Stroke(width))
						drawArc(
							color = ringColor,
							startAngle = -90f,
							sweepAngle = 360f * progress,
							useCenter = false,
							topLeft = androidx.compose.ui.geometry.Offset(width / 2, width / 2),
							size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
							style = Stroke(width, cap = StrokeCap.Round),
						)
					},
			) {
				if (imageUrl != null) {
					AsyncImage(
						model = imageUrl,
						contentDescription = null,
						imageLoader = imageLoader,
						contentScale = ContentScale.Crop,
						modifier = Modifier.size(34.dp),
					)
				}
			}

			Spacer(Modifier.width(8.dp))

			Column(modifier = Modifier.padding(end = 12.dp)) {
				Text(
					text = shown.name.orEmpty(),
					color = if (focused) Color.Black else ModernHomeColors.TextPrimary,
					fontSize = 12.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (!artists.isNullOrEmpty()) {
					Text(
						text = artists.joinToString(", "),
						color = if (focused) Color.Black.copy(alpha = 0.7f) else ModernHomeColors.TextSecondary,
						fontSize = 11.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

/** True while something is in the music queue (the bubble is shown) */
@Composable
internal fun rememberMusicPlaying(): Boolean {
	val entry by rememberQueueEntry(koinInject<PlaybackManager>())
	return entry != null
}
