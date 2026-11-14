package org.jellyfin.androidtv.ui.music.modern

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.ModernMenuDialog
import org.jellyfin.androidtv.ui.home.modern.ModernMenuOption
import org.jellyfin.androidtv.ui.home.modern.SectionHeader
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.isPlayKey
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import java.util.UUID

/** Albums per line in the album grids (artist and genre pages) */
private const val GRID_COLUMNS = 7

@Composable
fun ModernMusicItemScreen(
	viewModel: ModernMusicItemViewModel,
) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackLauncher = koinInject<PlaybackLauncher>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val mediaManager = koinInject<MediaManager>()
	val imageLoader = koinInject<ImageLoader>()

	val listState = rememberLazyListState()
	val playFocusRequester = remember { FocusRequester() }
	val restoreFocusRequester = remember { FocusRequester() }
	val restoreKey = remember { viewModel.lastFocusKey }
	var initialFocusDone by remember { mutableStateOf(false) }
	var menuTrack by remember { mutableStateOf<Pair<Int, BaseItemDto>?>(null) }
	var lastMenuTrack by remember { mutableStateOf<Pair<Int, BaseItemDto>?>(null) }
	var playlistItems by remember { mutableStateOf<List<UUID>?>(null) }
	val backgroundState = remember { mutableStateOf<String?>(null) }

	val entry by rememberQueueEntry(koinInject<PlaybackManager>())
	val playingId = entry?.baseItem?.id

	val content = state.content
	LaunchedEffect(content?.backgroundUrl) { backgroundState.value = content?.backgroundUrl }

	fun open(id: UUID) = navigationRepository.navigate(Destinations.modernMusicItem(id))
	fun playTracks(tracks: List<BaseItemDto>, index: Int = 0, shuffle: Boolean = false) {
		if (tracks.isNotEmpty()) playbackLauncher.launch(context, tracks, itemsPosition = index, shuffle = shuffle)
	}
	fun playRadio(source: StationSource) {
		scope.launch { playTracks(viewModel.stationTracks(source)) }
	}

	// Focus: the element focused before, otherwise "Play"
	LaunchedEffect(state.loading) {
		if (state.loading || initialFocusDone || content == null) return@LaunchedEffect
		val trackIndex = content.tracks.indexOfFirst { "track_${it.id}" == restoreKey }
		if (trackIndex >= 0) {
			listState.scrollToItem(trackIndex + trackListOffset(content))
			awaitFrames(2)
			if (restoreFocusRequester.tryRequestFocus()) {
				initialFocusDone = true
				return@LaunchedEffect
			}
		}
		awaitFrames(1)
		initialFocusDone = playFocusRequester.tryRequestFocus()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background),
	) {
		AmbientBackground(urlState = backgroundState, imageLoader = imageLoader)

		when {
			content != null -> LazyColumn(
				state = listState,
				contentPadding = PaddingValues(top = 44.dp, bottom = 80.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				item(key = "header") {
					MusicItemHeader(
						content = content,
						favorite = state.favorite,
						playFocusRequester = playFocusRequester,
						onPlay = {
							when (content.kind) {
								MusicItemKind.GENRE -> playRadio(StationSource(genreId = content.item.id))
								else -> playTracks(content.tracks)
							}
						},
						onShuffle = { playTracks(content.tracks, shuffle = true) },
						onRadio = {
							playRadio(
								when (content.kind) {
									MusicItemKind.ALBUM -> StationSource(albumId = content.item.id)
									MusicItemKind.PLAYLIST -> StationSource(playlistId = content.item.id)
									MusicItemKind.ARTIST -> StationSource(artistId = content.item.id)
									MusicItemKind.GENRE -> StationSource(genreId = content.item.id)
								}
							)
						},
						onFavorite = viewModel::toggleFavorite,
						onArtist = content.item.albumArtists?.firstOrNull()?.id?.let { id -> { open(id) } },
					)
				}

				// Artist and genre: their albums
				if (content.albums.isNotEmpty()) {
					item(key = "albums_title") {
						SectionHeader(stringResource(R.string.lbl_albums), compact = true, modifier = Modifier.padding(top = 18.dp))
					}
					val rows = content.albums.chunked(GRID_COLUMNS)
					itemsIndexed(rows, key = { index, _ -> "albums_$index" }) { _, row ->
						Row(
							horizontalArrangement = Arrangement.spacedBy(ModernHomeDimens.CardSpacing),
							modifier = Modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal, vertical = 10.dp),
						) {
							for (card in row) {
								MusicCardView(
									card = card,
									focusRequester = null,
									onFocused = {
										viewModel.lastFocusKey = card.key
										card.backgroundUrl?.let { backgroundState.value = it }
									},
									onClick = { card.item?.let { open(it.id) } },
									onLongClick = { card.item?.let { open(it.id) } },
									onPlay = { card.item?.let { playbackHelper.retrieveAndPlay(it.id, false, context) } },
								)
							}
						}
					}
				}

				if (content.tracks.isNotEmpty()) {
					item(key = "tracks_title") {
						SectionHeader(
							stringResource(if (content.kind == MusicItemKind.ARTIST) R.string.music_popular_songs else R.string.music_songs),
							compact = true,
							modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
						)
					}
					val showDiscs = content.kind == MusicItemKind.ALBUM && content.tracks.mapNotNull { it.parentIndexNumber }.distinct().size > 1
					itemsIndexed(content.tracks, key = { index, track -> "track_${index}_${track.id}" }) { index, track ->
						val disc = track.parentIndexNumber
						if (showDiscs && disc != null && content.tracks.getOrNull(index - 1)?.parentIndexNumber != disc) {
							Text(
								text = stringResource(R.string.music_disc, disc),
								color = ModernHomeColors.TextMuted,
								fontSize = 12.sp,
								fontWeight = FontWeight.Bold,
								modifier = Modifier.padding(start = ModernHomeDimens.ScreenPaddingHorizontal + 12.dp, top = 12.dp, bottom = 4.dp),
							)
						}
						TrackRow(
							number = if (content.kind == MusicItemKind.ALBUM) track.indexNumber ?: (index + 1) else index + 1,
							track = track,
							showArtist = content.kind != MusicItemKind.ALBUM ||
								(track.artists.orEmpty().joinToString() != (track.albumArtist ?: content.item.albumArtist)),
							showAlbum = content.kind != MusicItemKind.ALBUM,
							playing = track.id == playingId,
							focusRequester = if ("track_${track.id}" == restoreKey) restoreFocusRequester else null,
							onFocused = { viewModel.lastFocusKey = "track_${track.id}" },
							onClick = { playTracks(content.tracks, index) },
							onLongClick = {
								lastMenuTrack = index to track
								menuTrack = index to track
							},
						)
					}
				}

				if (content.similar.isNotEmpty()) {
					item(key = "similar") {
						MusicSectionRow(
							title = stringResource(R.string.lbl_more_like_this),
							cards = content.similar,
							focusRequesterFor = { null },
							onFocused = { card -> card.backgroundUrl?.let { backgroundState.value = it } },
							onClick = { card -> card.item?.let { open(it.id) } },
							onLongClick = { card -> card.item?.let { open(it.id) } },
							onPlay = { card -> card.item?.let { playbackHelper.retrieveAndPlay(it.id, false, context) } },
							modifier = Modifier.padding(top = 18.dp),
						)
					}
				}
			}

			state.loading -> CircularProgressIndicator(
				color = ModernHomeColors.Accent,
				modifier = Modifier
					.align(Alignment.Center)
					.size(48.dp),
			)

			else -> Text(
				text = stringResource(R.string.modern_details_failed),
				color = ModernHomeColors.TextSecondary,
				fontSize = 18.sp,
				modifier = Modifier.align(Alignment.Center),
			)
		}

		// Hold OK on a song
		val menu = lastMenuTrack
		ModernMenuDialog(
			visible = menuTrack != null,
			title = menu?.second?.name.orEmpty(),
			subtitle = menu?.second?.let { listOfNotNull(it.artists?.firstOrNull() ?: it.albumArtist, it.album).joinToString(" · ") },
			options = buildList {
				val (index, track) = menu ?: return@buildList
				val tracks = content?.tracks.orEmpty()
				add(ModernMenuOption(stringResource(R.string.music_play_from_here), R.drawable.ic_play) { playTracks(tracks, index) })
				add(ModernMenuOption(stringResource(R.string.music_add_to_queue), R.drawable.ic_add) {
					mediaManager.addToAudioQueue(listOf(track))
					Toast.makeText(context, R.string.music_added_to_queue, Toast.LENGTH_SHORT).show()
				})
				add(ModernMenuOption(stringResource(R.string.music_add_to_playlist), R.drawable.ic_grid) { playlistItems = listOf(track.id) })
				val favorite = track.userData?.isFavorite == true
				add(ModernMenuOption(stringResource(if (favorite) R.string.music_remove_favorite else R.string.music_add_favorite), if (favorite) R.drawable.ic_heart_red else R.drawable.ic_heart) {
					scope.launch {
						if (viewModel.setSongFavorite(track, !favorite)) viewModel.refresh()
					}
				})
				val albumId = track.albumId
				if (albumId != null && albumId != content?.item?.id) add(ModernMenuOption(stringResource(R.string.music_go_to_album), R.drawable.ic_album) { open(albumId) })
				val artistId = track.artistItems?.firstOrNull()?.id ?: track.albumArtists?.firstOrNull()?.id
				if (artistId != null && artistId != content?.item?.id) add(ModernMenuOption(stringResource(R.string.music_go_to_artist), R.drawable.ic_artist) { open(artistId) })
			},
			onDismissRequest = { menuTrack = null },
		)

		AddToPlaylistDialog(
			visible = playlistItems != null,
			itemIds = playlistItems.orEmpty(),
			onDismissRequest = { playlistItems = null },
		)
	}
}

/** Index of the first song in the list (header, album grid...) */
private fun trackListOffset(content: MusicItemContent): Int {
	var offset = 1
	if (content.albums.isNotEmpty()) offset += 1 + content.albums.chunked(GRID_COLUMNS).size
	return offset + 1
}

@Composable
private fun MusicItemHeader(
	content: MusicItemContent,
	favorite: Boolean,
	playFocusRequester: FocusRequester,
	onPlay: () -> Unit,
	onShuffle: () -> Unit,
	onRadio: () -> Unit,
	onFavorite: () -> Unit,
	onArtist: (() -> Unit)?,
) {
	val item = content.item
	val isArtist = content.kind == MusicItemKind.ARTIST
	val coverShape = if (isArtist) CircleShape else RoundedCornerShape(14.dp)

	val typeLabel = stringResource(
		when (content.kind) {
			MusicItemKind.ALBUM -> R.string.music_type_album
			MusicItemKind.PLAYLIST -> R.string.music_type_playlist
			MusicItemKind.ARTIST -> R.string.music_type_artist
			MusicItemKind.GENRE -> R.string.music_type_genre
		}
	)
	val totalTicks = content.tracks.sumOf { it.runTimeTicks ?: 0L }
	val songsText = if (content.tracks.isNotEmpty() && !isArtist) {
		LocalContext.current.resources.getQuantityString(R.plurals.music_song_count, content.tracks.size, content.tracks.size)
	} else null
	val albumsText = if (content.albums.isNotEmpty()) {
		LocalContext.current.resources.getQuantityString(R.plurals.music_album_count, content.albums.size, content.albums.size)
	} else null
	val details = listOfNotNull(
		item.productionYear?.toString(),
		item.genres?.firstOrNull()?.takeIf { content.kind == MusicItemKind.ALBUM },
		albumsText,
		songsText,
		formatDuration(totalTicks)?.takeIf { !isArtist && content.kind != MusicItemKind.GENRE },
	).joinToString("  ·  ")

	Row(
		verticalAlignment = Alignment.Bottom,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
	) {
		Box(
			modifier = Modifier
				.size(250.dp)
				.clip(coverShape)
				.background(ModernHomeColors.CardSurface, coverShape),
		) {
			CardArtwork(url = content.coverUrl, fallbackTitle = item.name.orEmpty())
		}

		Spacer(Modifier.width(32.dp))

		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = typeLabel.uppercase(),
				color = ModernHomeColors.Accent,
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				letterSpacing = 1.sp,
			)
			Spacer(Modifier.height(6.dp))
			Text(
				text = item.name.orEmpty(),
				color = ModernHomeColors.TextPrimary,
				fontSize = 34.sp,
				lineHeight = 38.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			val artist = item.albumArtist ?: item.artists?.firstOrNull()
			if (artist != null && content.kind == MusicItemKind.ALBUM) {
				Spacer(Modifier.height(4.dp))
				Text(text = artist, color = ModernHomeColors.TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
			}
			if (details.isNotEmpty()) {
				Spacer(Modifier.height(6.dp))
				Text(text = details, color = ModernHomeColors.TextMuted, fontSize = 13.sp, maxLines = 1)
			}
			val overview = item.overview
			if (isArtist && !overview.isNullOrBlank()) {
				Spacer(Modifier.height(8.dp))
				Text(
					text = overview,
					color = ModernHomeColors.TextSecondary,
					fontSize = 13.sp,
					lineHeight = 18.sp,
					maxLines = 3,
					overflow = TextOverflow.Ellipsis,
				)
			}

			Spacer(Modifier.height(18.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				HeroButton(
					text = stringResource(if (content.kind == MusicItemKind.GENRE) R.string.modern_music_radio else R.string.lbl_play),
					icon = if (content.kind == MusicItemKind.GENRE) R.drawable.ic_radio else R.drawable.ic_play,
					primary = true,
					onClick = onPlay,
					modifier = Modifier.focusRequester(playFocusRequester),
				)
				if (content.tracks.isNotEmpty()) {
					HeroButton(text = stringResource(R.string.modern_music_shuffle), icon = R.drawable.ic_shuffle, primary = false, onClick = onShuffle)
				}
				if (content.kind != MusicItemKind.GENRE) {
					HeroButton(text = stringResource(R.string.modern_music_radio), icon = R.drawable.ic_radio, primary = false, onClick = onRadio)
					HeroButton(
						text = stringResource(if (favorite) R.string.music_favorite else R.string.music_add_favorite),
						icon = if (favorite) R.drawable.ic_heart_red else R.drawable.ic_heart,
						primary = false,
						onClick = onFavorite,
					)
				}
				if (onArtist != null) {
					HeroButton(text = stringResource(R.string.music_go_to_artist), icon = R.drawable.ic_artist, primary = false, onClick = onArtist)
				}
			}
		}
	}
}

/**
 * Song of the list: number (or a play icon when it is playing), name, artist, album and length.
 * OK plays from this song, holding OK opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
	number: Int?,
	track: BaseItemDto,
	showArtist: Boolean,
	showAlbum: Boolean,
	playing: Boolean,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(10.dp)
	val primary = when {
		focused -> Color.Black
		playing -> ModernHomeColors.Accent
		else -> ModernHomeColors.TextPrimary
	}
	val secondary = if (focused) Color.Black.copy(alpha = 0.65f) else ModernHomeColors.TextMuted
	val subtitle = listOfNotNull(
		if (showArtist) track.artists?.joinToString(", ")?.takeIf { it.isNotEmpty() } ?: track.albumArtist else null,
		if (showAlbum) track.album else null,
	).joinToString(" · ")

	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal - 12.dp, vertical = 2.dp)
			.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
			.onFocusChanged { if (it.isFocused) onFocused() }
			.onPreviewKeyEvent { event ->
				if (!event.isPlayKey) return@onPreviewKeyEvent false
				if (event.type == KeyEventType.KeyDown) onClick()
				true
			}
			.combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
			.background(if (focused) Color.White else Color.Transparent, shape)
			.padding(horizontal = 12.dp, vertical = 9.dp),
	) {
		Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
			if (playing) {
				Icon(ImageVector.vectorResource(R.drawable.ic_play), contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
			} else if (number != null) {
				Text(text = "$number", color = secondary, fontSize = 14.sp)
			}
		}
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = track.name.orEmpty(),
				color = primary,
				fontSize = 15.sp,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (subtitle.isNotEmpty()) {
				Text(text = subtitle, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		if (track.userData?.isFavorite == true) {
			Icon(ImageVector.vectorResource(R.drawable.ic_heart_red), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(14.dp))
			Spacer(Modifier.width(12.dp))
		}
		formatDuration(track.runTimeTicks)?.let { Text(text = it, color = secondary, fontSize = 13.sp) }
	}
}
