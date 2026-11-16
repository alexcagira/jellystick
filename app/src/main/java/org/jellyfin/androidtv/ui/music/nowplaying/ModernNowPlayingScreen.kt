package org.jellyfin.androidtv.ui.music.nowplaying

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import coil3.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.SeekbarColors
import org.jellyfin.androidtv.ui.composable.LyricsDtoBox
import org.jellyfin.androidtv.ui.composable.modifier.fadingEdges
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.ModernMenuDialog
import org.jellyfin.androidtv.ui.home.modern.ModernMenuOption
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.music.modern.AddToPlaylistDialog
import org.jellyfin.androidtv.ui.music.modern.FavouriteTracks
import org.jellyfin.androidtv.ui.music.modern.formatDuration
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.music.MusicStopper
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackOrder
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.lyrics.lyrics
import org.jellyfin.playback.jellyfin.lyrics.lyricsFlow
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.Date
import java.util.UUID

/** Lyrics stay shown or hidden while the app runs (hidden at start) */
internal object NowPlayingPreferences {
	var showLyrics by mutableStateOf(false)
}

/**
 * "Now playing" in the style of the music page.
 */
class ModernNowPlayingFragment : Fragment() {
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		content { ModernNowPlayingScreen() }
}

@Composable
fun ModernNowPlayingScreen() {
	val api = koinInject<ApiClient>()
	val playbackManager = koinInject<PlaybackManager>()
	val mediaManager = koinInject<MediaManager>()
	val musicStopper = koinInject<MusicStopper>()
	val navigationRepository = koinInject<NavigationRepository>()
	val favouriteTracks = koinInject<FavouriteTracks>()
	val imageLoader = koinInject<ImageLoader>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem).value }
	val lyrics = entry?.run { lyricsFlow.collectAsState(lyrics).value }
	val playState by playbackManager.state.playState.collectAsState()
	val playbackOrder by playbackManager.state.playbackOrder.collectAsState()
	val entries by playbackManager.queue.entries.collectAsState()
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val showLyrics = NowPlayingPreferences.showLyrics

	var upcoming by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }
	var screensaver by remember { mutableStateOf(false) }
	var menuEntry by remember { mutableStateOf<QueueEntry?>(null) }
	var lastMenuEntry by remember { mutableStateOf<QueueEntry?>(null) }
	var playlistItems by remember { mutableStateOf<List<UUID>?>(null) }
	// Favourite changes of this session: the songs of the queue keep the state they were loaded with
	val favouriteChanges by favouriteTracks.changes.collectAsState()
	val playFocusRequester = remember { FocusRequester() }
	val backgroundState = remember { mutableStateOf<String?>(null) }

	// Upcoming songs: the queue only knows them once peeked. None when the queue ran to its end
	LaunchedEffect(entry, entries, playbackOrder) {
		upcoming = if (entry == null) emptyList() else playbackManager.queue.peekNext(QUEUE_PEEK).toList()
	}
	// Songs already played: they stay in the queue to play again (also once it ended, e.g. a shared
	// queue: a new song plays from its place after them) until Stop or ☰
	val played = remember(entries, entry, entryIndex, upcoming, playbackOrder) {
		when {
			entry == null || entryIndex < 0 -> entries
			playbackOrder == PlaybackOrder.DEFAULT -> entries.take(entryIndex)
			else -> entries.filter { it != entry && it !in upcoming }
		}
	}

	// Stop (here, or ☰ anywhere): the queue is emptied and this screen closes
	var hadQueue by remember { mutableStateOf(false) }
	LaunchedEffect(entries.isEmpty() && entry == null) {
		if (entries.isNotEmpty() || entry != null) hadQueue = true
		else if (hadQueue) {
			hadQueue = false
			navigationRepository.goBack()
		}
	}

	val image = item?.songImage()
	val coverUrl = image?.let { api.songCoverUrl(it) }
	LaunchedEffect(image) {
		backgroundState.value = image?.let { api.songBackgroundUrl(it) }
	}
	// The next song's cover and background are loaded while this one plays: no blank cover when it changes
	PrefetchSongArtwork(upcoming.firstOrNull()?.baseItem)
	val shownCoverUrl = rememberLoadedUrl(coverUrl)

	fun open(id: UUID?) {
		if (id != null) navigationRepository.navigate(Destinations.modernMusicItem(id))
	}
	fun artistId(song: BaseItemDto?) = song?.artistItems?.firstOrNull()?.id ?: song?.albumArtists?.firstOrNull()?.id
	fun isFavorite(song: BaseItemDto) = favouriteTracks.isFavorite(song, favouriteChanges)
	fun toggleFavorite(song: BaseItemDto) {
		val favorite = !isFavorite(song)
		scope.launch {
			// Also adds it to (or takes it out of) the "Favourite Tracks" playlist
			runCatching { favouriteTracks.setFavorite(song, favorite) }
				.onSuccess {
					Toast.makeText(context, if (favorite) R.string.music_added_favorite else R.string.music_removed_favorite, Toast.LENGTH_SHORT).show()
				}
				.onFailure { Timber.w(it, "Unable to change the favourite state") }
		}
	}

	LaunchedEffect(Unit) {
		awaitFrames(2)
		playFocusRequester.tryRequestFocus()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background)
			// ⏪ / ⏩ of the remote: previous / next song, also over the screensaver
			.onPreviewKeyEvent { event -> mediaManager.handleSongSkip(event) },
	) {
		AmbientBackground(urlState = backgroundState, imageLoader = imageLoader)

		// Fixed layout that always fits the screen: the cover takes the height that is left
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(top = 20.dp, bottom = 10.dp),
		) {
			// Artist and clock, small
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
			) {
				Text(
					text = stringResource(R.string.music_now_playing).uppercase(),
					color = ModernHomeColors.Accent,
					fontSize = 11.sp,
					fontWeight = FontWeight.Bold,
					letterSpacing = 1.2.sp,
				)
				Spacer(Modifier.width(12.dp))
				Text(
					text = item?.artistsText().orEmpty(),
					color = ModernHomeColors.TextSecondary,
					fontSize = 13.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Clock(fontSize = 13)
			}

			Spacer(Modifier.height(12.dp))

			BoxWithConstraints(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
			) {
				val lyricsShown = showLyrics && lyrics != null
				val coverSize = minOf(maxHeight, if (lyricsShown) 260.dp else 230.dp)
				val animatedCover by animateDpAsState(coverSize, label = "CoverSize")

				Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
					Box(
						modifier = Modifier
							.size(animatedCover)
							.clip(RoundedCornerShape(14.dp))
							.background(ModernHomeColors.CardSurface),
					) {
						Crossfade(targetState = shownCoverUrl, label = "NowPlayingCover") { url ->
							CardArtwork(url = url, fallbackTitle = item?.album ?: item?.name.orEmpty())
						}
					}
					Spacer(Modifier.width(32.dp))

					if (lyricsShown) {
						Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
							SongTitle(item, compact = true)
							Spacer(Modifier.height(6.dp))
							NowPlayingLyrics(
								lyrics = lyrics!!,
								playbackManager = playbackManager,
								fontSize = 22,
								modifier = Modifier
									.fillMaxWidth()
									.weight(1f),
							)
						}
					} else {
						Column(modifier = Modifier.weight(1f)) {
							SongTitle(item, compact = false)
							if (showLyrics && item != null) {
								Spacer(Modifier.height(10.dp))
								Text(stringResource(R.string.music_no_lyrics), color = ModernHomeColors.TextMuted, fontSize = 13.sp)
							}
						}
					}
				}
			}

			// Timeline
			Timeline(playbackManager)

			// Buttons under the timeline
			Row(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.Top,
				modifier = Modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal - 12.dp),
			) {
				ControlButton(R.drawable.ic_previous, stringResource(R.string.lbl_prev_item)) { mediaManager.prevAudioItem() }
				ControlButton(
					icon = if (playState == PlayState.PLAYING) R.drawable.ic_pause else R.drawable.ic_play,
					label = stringResource(if (playState == PlayState.PLAYING) R.string.lbl_pause else R.string.lbl_play),
					primary = true,
					modifier = Modifier.focusRequester(playFocusRequester),
				) { mediaManager.togglePlayPause() }
				ControlButton(R.drawable.ic_next, stringResource(R.string.lbl_next_item)) { mediaManager.nextAudioItem() }
				// Stop: ends the queue and leaves this screen
				ControlButton(R.drawable.ic_stop, stringResource(R.string.music_stop)) {
					musicStopper.stop()
				}
				// Favourite: only the heart, red when the song is a favourite
				val favorite = item?.let { isFavorite(it) } == true
				ControlButton(
					icon = if (favorite) R.drawable.ic_heart_red else R.drawable.ic_heart_outline,
					label = stringResource(if (favorite) R.string.music_remove_favorite else R.string.music_add_favorite),
					keepIconColor = favorite,
				) { item?.let { toggleFavorite(it) } }
				ControlButton(R.drawable.ic_add, stringResource(R.string.music_add_to_playlist)) { item?.let { playlistItems = listOf(it.id) } }
				ControlButton(
					icon = R.drawable.ic_shuffle,
					label = stringResource(R.string.lbl_shuffle_queue),
					active = playbackOrder != PlaybackOrder.DEFAULT,
				) { mediaManager.shuffleAudioQueue() }
				ControlButton(
					icon = R.drawable.ic_subtitles,
					label = stringResource(if (showLyrics) R.string.music_hide_lyrics else R.string.music_show_lyrics),
					active = showLyrics,
				) { NowPlayingPreferences.showLyrics = !showLyrics }
				ControlButton(R.drawable.ic_artist, stringResource(R.string.music_go_to_artist)) { open(artistId(item)) }
				ControlButton(R.drawable.ic_album, stringResource(R.string.music_go_to_album)) { open(item?.albumId) }
				ControlButton(R.drawable.ic_photos, stringResource(R.string.music_screensaver)) { screensaver = true }
			}

			// Queue: the songs played (dimmed), the current one and what comes next
			val queue = played + listOfNotNull(entry) + upcoming
			val currentIndex = played.size
			val queueState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
			var queueFocused by remember { mutableStateOf(false) }
			// The current song first in view, unless the row is being browsed
			LaunchedEffect(entry, currentIndex) {
				if (!queueFocused) queueState.scrollToItem(currentIndex.coerceAtMost((queue.size - 1).coerceAtLeast(0)))
			}
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 2.dp, end = ModernHomeDimens.ScreenPaddingHorizontal),
			) {
				Text(
					text = stringResource(R.string.current_queue).uppercase(),
					color = ModernHomeColors.TextSecondary,
					fontSize = 11.sp,
					fontWeight = FontWeight.Bold,
					letterSpacing = 1.sp,
					modifier = Modifier
						.weight(1f)
						.padding(start = ModernHomeDimens.ScreenPaddingHorizontal),
				)
				if (entryIndex >= 0) {
					Text(
						text = stringResource(R.string.music_queue_position, entryIndex + 1, playbackManager.queue.estimatedSize.coerceAtLeast(entryIndex + 1)),
						color = ModernHomeColors.TextSecondary,
						fontSize = 11.sp,
						fontWeight = FontWeight.SemiBold,
					)
				}
			}
			LazyRow(
				state = queueState,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				contentPadding = PaddingValues(horizontal = ModernHomeDimens.ScreenPaddingHorizontal, vertical = 6.dp),
				modifier = Modifier.onFocusChanged { queueFocused = it.hasFocus },
			) {
				itemsIndexed(queue, key = { index, queueEntry -> "${index}_${queueEntry.baseItem?.id}" }) { index, queueEntry ->
					val isCurrent = index == currentIndex && entry != null
					QueueCard(
						entry = queueEntry,
						playing = isCurrent,
						played = index < currentIndex,
						onClick = { if (!isCurrent) mediaManager.playFrom(queueEntry) },
						onLongClick = {
							lastMenuEntry = queueEntry
							menuEntry = queueEntry
						},
					)
				}
			}
		}

		// Hold OK on a song of the queue
		val menuSong = lastMenuEntry?.baseItem
		ModernMenuDialog(
			visible = menuEntry != null,
			title = menuSong?.name.orEmpty(),
			subtitle = menuSong?.let { listOfNotNull(it.artistsText(), it.album).joinToString(" · ") },
			options = buildList {
				val queueEntry = lastMenuEntry ?: return@buildList
				val song = queueEntry.baseItem ?: return@buildList
				add(ModernMenuOption(stringResource(R.string.music_play_from_here), R.drawable.ic_play) { mediaManager.playFrom(queueEntry) })
				if (queueEntry != entry) {
					add(ModernMenuOption(stringResource(R.string.music_remove_from_queue), R.drawable.ic_trash) { mediaManager.removeFromAudioQueue(queueEntry) })
				}
				val favorite = isFavorite(song)
				add(ModernMenuOption(stringResource(if (favorite) R.string.music_remove_favorite else R.string.music_add_favorite), if (favorite) R.drawable.ic_heart_red else R.drawable.ic_heart) {
					toggleFavorite(song)
				})
				song.albumId?.let { albumId -> add(ModernMenuOption(stringResource(R.string.music_go_to_album), R.drawable.ic_album) { open(albumId) }) }
				artistId(song)?.let { id -> add(ModernMenuOption(stringResource(R.string.music_go_to_artist), R.drawable.ic_artist) { open(id) }) }
			},
			onDismissRequest = { menuEntry = null },
		)

		AddToPlaylistDialog(
			visible = playlistItems != null,
			itemIds = playlistItems.orEmpty(),
			onDismissRequest = { playlistItems = null },
		)

		AnimatedVisibility(visible = screensaver, enter = fadeIn(tween(600)), exit = fadeOut(tween(400))) {
			NowPlayingScreensaver(
				item = item,
				coverUrl = coverUrl,
				backgroundUrl = backgroundState.value,
				lyrics = lyrics,
				playbackManager = playbackManager,
				onExit = { screensaver = false },
			)
		}
	}
}

@Composable
private fun SongTitle(item: BaseItemDto?, compact: Boolean) {
	Text(
		text = item?.name.orEmpty(),
		color = ModernHomeColors.TextPrimary,
		fontSize = if (compact) 22.sp else 32.sp,
		lineHeight = if (compact) 26.sp else 36.sp,
		fontWeight = FontWeight.Bold,
		maxLines = if (compact) 1 else 2,
		overflow = TextOverflow.Ellipsis,
	)
	Spacer(Modifier.height(4.dp))
	Text(
		text = item?.artistsText().orEmpty(),
		color = ModernHomeColors.TextSecondary,
		fontSize = if (compact) 15.sp else 19.sp,
		fontWeight = FontWeight.SemiBold,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
	)
	val details = listOfNotNull(item?.album, item?.productionYear?.toString()).joinToString("  ·  ")
	if (details.isNotEmpty()) {
		Spacer(Modifier.height(2.dp))
		Text(text = details, color = ModernHomeColors.TextMuted, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
internal fun NowPlayingLyrics(
	lyrics: org.jellyfin.sdk.model.api.LyricDto,
	playbackManager: PlaybackManager,
	fontSize: Int,
	modifier: Modifier = Modifier,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)

	LyricsDtoBox(
		lyricDto = lyrics,
		currentTimestamp = positionInfo.active,
		duration = positionInfo.duration,
		paused = playState != PlayState.PLAYING,
		fontSize = fontSize.sp,
		color = Color.White,
		modifier = modifier
			.fadingEdges(vertical = 40.dp)
			// Room for the line that is sung, drawn 10% bigger, so its first and last letters
			// are not cut on the sides
			.padding(horizontal = (fontSize * 1.3f).dp),
	)
}

@Composable
private fun Timeline(playbackManager: PlaybackManager) {
	val positionInfo by rememberPlayerPositionInfo(playbackManager)
	val ticksPerMs = 10_000L

	Column(modifier = Modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal, vertical = 6.dp)) {
		PlayerSeekbar(
			playbackManager = playbackManager,
			colors = SeekbarColors(
				backgroundColor = Color.White.copy(alpha = 0.2f),
				bufferColor = Color.White.copy(alpha = 0.3f),
				progressColor = ModernHomeColors.Accent,
				knobColor = Color.White,
			),
			modifier = Modifier
				.fillMaxWidth()
				.height(4.dp),
		)
		Spacer(Modifier.height(4.dp))
		Row {
			Text(
				text = formatDuration(positionInfo.active.inWholeMilliseconds * ticksPerMs) ?: "0:00",
				color = ModernHomeColors.TextSecondary,
				fontSize = 12.sp,
			)
			Spacer(Modifier.weight(1f))
			Text(
				text = formatDuration(positionInfo.duration.inWholeMilliseconds * ticksPerMs)?.let { total -> total } ?: "",
				color = ModernHomeColors.TextSecondary,
				fontSize = 12.sp,
			)
		}
	}
}

/**
 * Round button of the controls, with its name under it while focused.
 */
@Composable
private fun ControlButton(
	icon: Int,
	label: String,
	modifier: Modifier = Modifier,
	primary: Boolean = false,
	active: Boolean = false,
	// Icons with their own color, like the red heart
	keepIconColor: Boolean = false,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "ControlScale")
	val size: Dp = if (primary) 40.dp else 32.dp

	Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = modifier
				.graphicsLayer {
					scaleX = scale
					scaleY = scale
				}
				.size(size)
				.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
				.background(
					when {
						focused -> Color.White
						primary -> Color.White.copy(alpha = 0.9f)
						active -> ModernHomeColors.Accent.copy(alpha = 0.35f)
						else -> Color.White.copy(alpha = 0.1f)
					},
					CircleShape,
				)
				.border(1.dp, if (active && !focused) ModernHomeColors.Accent else Color.Transparent, CircleShape),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(icon),
				contentDescription = label,
				tint = when {
					keepIconColor -> Color.Unspecified
					focused || primary -> Color.Black
					else -> ModernHomeColors.TextPrimary
				},
				modifier = Modifier.size(if (primary) 20.dp else 15.dp),
			)
		}
		Spacer(Modifier.height(3.dp))
		// Name of the focused button, one line so the layout does not move
		Text(
			text = if (focused) label else "",
			color = ModernHomeColors.TextPrimary,
			fontSize = 10.sp,
			fontWeight = FontWeight.SemiBold,
			textAlign = TextAlign.Center,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			lineHeight = 12.sp,
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueCard(
	entry: QueueEntry,
	playing: Boolean,
	/** Already played: dimmed, it can be played again */
	played: Boolean = false,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val api = koinInject<ApiClient>()
	val song = entry.run { baseItemFlow.collectAsState(baseItem).value }
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) ModernHomeDimens.FocusScale else 1f, label = "QueueScale")
	val shape = RoundedCornerShape(ModernHomeDimens.CardCornerRadius)
	val image = song?.let { it.itemImages[ImageType.PRIMARY] ?: it.albumPrimaryImage ?: it.parentImages[ImageType.PRIMARY] }
	val url = remember(image) { image?.getUrl(api, maxWidth = 240) }

	Column(
		modifier = Modifier
			.width(QUEUE_CARD_SIZE)
			.graphicsLayer { alpha = if (played && !focused) PLAYED_ALPHA else 1f },
	) {
		Box(
			modifier = Modifier
				.graphicsLayer {
					scaleX = scale
					scaleY = scale
				}
				.size(QUEUE_CARD_SIZE)
				.combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
				.clip(shape)
				.background(ModernHomeColors.CardSurface, shape)
				.border(
					width = if (focused || playing) ModernHomeDimens.FocusBorderWidth else 0.dp,
					color = if (focused) ModernHomeColors.FocusRing else if (playing) ModernHomeColors.Accent else Color.Transparent,
					shape = shape,
				),
		) {
			CardArtwork(url = url, fallbackTitle = song?.name.orEmpty())
			if (playing) {
				Box(
					contentAlignment = Alignment.Center,
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(4.dp)
						.size(18.dp)
						.background(ModernHomeColors.Accent, CircleShape),
				) {
					Icon(ImageVector.vectorResource(R.drawable.ic_play), contentDescription = null, tint = Color.Black, modifier = Modifier.size(10.dp))
				}
			}
		}
		Spacer(Modifier.height(4.dp))
		Text(
			text = song?.name.orEmpty(),
			color = if (playing) ModernHomeColors.Accent else ModernHomeColors.TextPrimary,
			fontSize = 10.sp,
			lineHeight = 12.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = listOfNotNull(song?.artistsText(), song?.album).joinToString(" · "),
			color = ModernHomeColors.TextMuted,
			fontSize = 9.sp,
			lineHeight = 11.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
internal fun Clock(fontSize: Int, color: Color = ModernHomeColors.TextSecondary) {
	val context = LocalContext.current
	var now by remember { mutableStateOf(Date()) }
	LaunchedEffect(Unit) {
		while (true) {
			now = Date()
			delay(CLOCK_TICK_MS)
		}
	}
	Text(
		text = DateFormat.getTimeFormat(context).format(now),
		color = color,
		fontSize = fontSize.sp,
		fontWeight = FontWeight.SemiBold,
	)
}

internal fun BaseItemDto.artistsText(): String? =
	artists?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: albumArtist

private const val QUEUE_PEEK = 100
/** Songs of the queue already played */
private const val PLAYED_ALPHA = 0.45f
private val QUEUE_CARD_SIZE = 64.dp
private const val CLOCK_TICK_MS = 15_000L
