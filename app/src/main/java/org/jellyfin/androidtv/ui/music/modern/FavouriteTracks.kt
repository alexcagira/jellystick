package org.jellyfin.androidtv.ui.music.modern

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.androidtv.ui.collections.uploadPrimaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Favourite songs, with a "Favourite Tracks" playlist that always has them: marking a song as
 * favourite adds it to the playlist, removing it takes it out. The playlist is made the first time
 * (with a green heart cover) and checked against the favourites once per session, so songs marked
 * in other apps show up too.
 */
class FavouriteTracks(
	private val api: ApiClient,
) {
	// Own scope: a change keeps going when the screen that made it is closed
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private var playlistId: UUID? = null
	private var playlistSession: String? = null
	private var syncedSession: String? = null

	private val _changes = MutableStateFlow<Map<UUID, Boolean>>(emptyMap())

	/** Favourite changes made in this session, newer than the songs already loaded by the screens */
	val changes: StateFlow<Map<UUID, Boolean>> = _changes.asStateFlow()

	fun isFavorite(song: BaseItemDto, changes: Map<UUID, Boolean> = _changes.value) =
		changes[song.id] ?: (song.userData?.isFavorite == true)

	/** Mark or unmark a song as favourite and update the playlist */
	suspend fun setFavorite(song: BaseItemDto, favorite: Boolean) {
		val previous = isFavorite(song)
		_changes.update { it + (song.id to favorite) }

		scope.async {
			try {
				if (favorite) api.userLibraryApi.markFavoriteItem(song.id)
				else api.userLibraryApi.unmarkFavoriteItem(song.id)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				_changes.update { it + (song.id to previous) }
				throw err
			}

			try {
				mutex.withLock {
					if (favorite) addToPlaylist(song.id) else removeFromPlaylist(song.id)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to update the $PLAYLIST_NAME playlist")
			}
		}.await()
	}

	/** Make the playlist match the favourite songs, once per session, in the background */
	fun syncInBackground() {
		val session = session()
		if (syncedSession == session) return
		syncedSession = session
		scope.launch {
			try {
				mutex.withLock { sync() }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to sync the $PLAYLIST_NAME playlist")
				syncedSession = null
			}
		}
	}

	private suspend fun sync() {
		val favourites = favouriteSongs()
		val id = findPlaylist() ?: run {
			if (favourites.isNotEmpty()) createPlaylist(favourites)
			return
		}

		val entries = playlistEntries(id)
		val inPlaylist = entries.map { it.first }.toSet()
		val favouriteSet = favourites.toSet()
		val missing = favourites.filterNot { it in inPlaylist }
		val extra = entries.filter { (itemId, _) -> itemId !in favouriteSet }.map { it.second }

		missing.chunked(IDS_PER_REQUEST).forEach { api.playlistsApi.addItemToPlaylist(playlistId = id, ids = it) }
		extra.chunked(IDS_PER_REQUEST).forEach { api.playlistsApi.removeItemFromPlaylist(playlistId = id.toString(), entryIds = it) }
	}

	private suspend fun addToPlaylist(songId: UUID) {
		val id = findPlaylist()
		if (id == null) {
			// First favourite: the playlist starts with every favourite song
			createPlaylist((favouriteSongs() + songId).distinct())
			return
		}
		if (playlistEntries(id).none { it.first == songId }) {
			api.playlistsApi.addItemToPlaylist(playlistId = id, ids = listOf(songId))
		}
	}

	private suspend fun removeFromPlaylist(songId: UUID) {
		val id = findPlaylist() ?: return
		val entryIds = playlistEntries(id).filter { it.first == songId }.map { it.second }
		if (entryIds.isNotEmpty()) api.playlistsApi.removeItemFromPlaylist(playlistId = id.toString(), entryIds = entryIds)
	}

	/** Server and user signed in: the playlist is looked up again when they change */
	private fun session() = "${api.baseUrl}|${api.accessToken}"

	// Server

	private suspend fun favouriteSongs(): List<UUID> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			isFavorite = true,
			sortBy = setOf(ItemSortBy.DATE_CREATED),
			sortOrder = setOf(SortOrder.ASCENDING),
			enableImages = false,
			enableUserData = false,
			enableTotalRecordCount = false,
			limit = MAX_SONGS,
		)
	).content.items.map { it.id }

	/** Song id and playlist entry id of each song of the playlist */
	private suspend fun playlistEntries(playlistId: UUID): List<Pair<UUID, String>> = api.playlistsApi.getPlaylistItems(
		playlistId = playlistId,
		limit = MAX_SONGS,
		enableImages = false,
		enableUserData = false,
	).content.items.mapNotNull { item -> item.playlistItemId?.let { item.id to it } }

	private suspend fun findPlaylist(): UUID? {
		val session = session()
		if (playlistSession != session) {
			playlistId = null
			playlistSession = session
		}
		playlistId?.let { return it }

		val found = api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.PLAYLIST),
				recursive = true,
				searchTerm = PLAYLIST_NAME,
				enableImages = false,
				enableTotalRecordCount = false,
			)
		).content.items.firstOrNull { it.name.equals(PLAYLIST_NAME, ignoreCase = true) }?.id
		playlistId = found
		return found
	}

	private suspend fun createPlaylist(songs: List<UUID>) {
		val result = api.playlistsApi.createPlaylist(
			CreatePlaylistDto(
				name = PLAYLIST_NAME,
				ids = songs.take(MAX_SONGS),
				mediaType = MediaType.AUDIO,
				users = emptyList(),
				isPublic = false,
			)
		).content
		val id = result.id.toUUIDOrNull() ?: return
		playlistId = id

		// Green heart cover (needs an administrator, the playlist works without it)
		try {
			api.uploadPrimaryImage(id, FavouriteTracksCover.jpeg())
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to set the cover of the $PLAYLIST_NAME playlist")
		}
	}

	companion object {
		const val PLAYLIST_NAME = "Favourite Tracks"
		private const val MAX_SONGS = 3_000
		private const val IDS_PER_REQUEST = 60
	}
}

/**
 * Cover of the "Favourite Tracks" playlist: a heart drawn like the Jellystick logo (a heart inside a
 * heart, green gradient) on the dark blue of the app.
 */
object FavouriteTracksCover {
	private const val SIZE = 1000

	fun draw(): ImageBitmap {
		val image = ImageBitmap(SIZE, SIZE)
		val size = Size(SIZE.toFloat(), SIZE.toFloat())
		val gradient = Brush.linearGradient(
			0f to Color(0xFF9BE15D),
			1f to Color(0xFF00B37A),
			start = Offset(200f, 180f),
			end = Offset(820f, 820f),
		)

		CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
			drawRect(Brush.verticalGradient(0f to Color(0xFF0E1B33), 1f to Color(0xFF000B25)))
			drawCircle(
				brush = Brush.radialGradient(0f to Color(0x332ECC71), 1f to Color.Transparent, center = Offset(500f, 440f), radius = 480f),
				radius = 480f,
				center = Offset(500f, 440f),
			)

			// Outer heart with a heart shaped hole, and a small heart in the middle
			val ring = Path().apply {
				fillType = PathFillType.EvenOdd
				addHeart(this, scale = 1f)
				addHeart(this, scale = 0.62f)
			}
			drawPath(ring, gradient)
			drawPath(Path().apply { addHeart(this, scale = 0.3f) }, gradient)
		}

		val bitmap = image.asAndroidBitmap()
		val canvas = android.graphics.Canvas(bitmap)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt()
			textAlign = Paint.Align.CENTER
			textSize = 78f
			typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 800, false)
			else Typeface.create("sans-serif-black", Typeface.NORMAL)
		}
		canvas.drawText(FavouriteTracks.PLAYLIST_NAME, SIZE / 2f, 905f, paint)
		return image
	}

	fun jpeg(): ByteArray = ByteArrayOutputStream().use { stream ->
		draw().asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 92, stream)
		stream.toByteArray()
	}

	/** Heart centered at (500, 460), 720 wide at scale 1 */
	private fun addHeart(path: Path, scale: Float) {
		val cx = 500f
		val cy = 460f
		fun x(value: Float) = cx + (value - 500f) * scale
		fun y(value: Float) = cy + (value - 470f) * scale

		path.moveTo(x(500f), y(800f))
		path.cubicTo(x(470f), y(772f), x(140f), y(580f), x(140f), y(380f))
		path.cubicTo(x(140f), y(260f), x(232f), y(170f), x(345f), y(170f))
		path.cubicTo(x(420f), y(170f), x(470f), y(210f), x(500f), y(262f))
		path.cubicTo(x(530f), y(210f), x(580f), y(170f), x(655f), y(170f))
		path.cubicTo(x(768f), y(170f), x(860f), y(260f), x(860f), y(380f))
		path.cubicTo(x(860f), y(580f), x(530f), y(772f), x(500f), y(800f))
		path.close()
	}
}
