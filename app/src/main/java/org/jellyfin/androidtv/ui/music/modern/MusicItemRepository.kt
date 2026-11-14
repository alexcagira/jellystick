package org.jellyfin.androidtv.ui.music.modern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

enum class MusicItemKind {
	ALBUM,
	PLAYLIST,
	ARTIST,
	GENRE,
}

/**
 * Everything the music page of an album, playlist, artist or genre shows.
 *
 * @param tracks songs (album and playlist: all of them in order; artist: the most played)
 * @param albums albums of the artist or the genre
 * @param similar albums like this one
 */
data class MusicItemContent(
	val item: BaseItemDto,
	val kind: MusicItemKind,
	val coverUrl: String?,
	val backgroundUrl: String?,
	val tracks: List<BaseItemDto>,
	val albums: List<MusicCard>,
	val similar: List<MusicCard>,
)

/**
 * Albums, playlists, artists and genres for the modern music pages, plus the actions on songs
 * (favorites, playlists).
 */
class MusicItemRepository(
	private val api: ApiClient,
	private val musicRepository: ModernMusicRepository,
) {
	suspend fun load(itemId: UUID): MusicItemContent = withContext(Dispatchers.IO) {
		val item = api.userLibraryApi.getItem(itemId).content
		val kind = when (item.type) {
			BaseItemKind.PLAYLIST -> MusicItemKind.PLAYLIST
			BaseItemKind.MUSIC_ARTIST -> MusicItemKind.ARTIST
			BaseItemKind.MUSIC_GENRE -> MusicItemKind.GENRE
			else -> MusicItemKind.ALBUM
		}

		coroutineScope {
			val tracksAsync = async {
				when (kind) {
					MusicItemKind.ALBUM -> albumTracks(item.id)
					MusicItemKind.PLAYLIST -> playlistTracks(item.id)
					MusicItemKind.ARTIST -> artistSongs(item.id)
					MusicItemKind.GENRE -> emptyList()
				}
			}
			val albumsAsync = async {
				when (kind) {
					MusicItemKind.ARTIST -> safe("artist albums") { artistAlbums(item.id) }.orEmpty()
					MusicItemKind.GENRE -> safe("genre albums") { genreAlbums(item.id) }.orEmpty()
					else -> emptyList()
				}.map { musicRepository.albumCard(it) }
			}
			val similarAsync = async {
				if (kind != MusicItemKind.ALBUM) emptyList()
				else safe("similar albums") {
					api.libraryApi.getSimilarAlbums(itemId = item.id, limit = SIMILAR_LIMIT).content.items
				}.orEmpty().map { musicRepository.albumCard(it) }
			}

			val tracks = tracksAsync.await()
			val albums = albumsAsync.await()
			// Playlists and genres have no image of their own most of the time: first album of the content
			val image = item.itemImages[ImageType.PRIMARY]
				?: item.albumPrimaryImage
				?: tracks.firstNotNullOfOrNull { it.itemImages[ImageType.PRIMARY] ?: it.albumPrimaryImage }
				?: albums.firstOrNull()?.item?.itemImages?.get(ImageType.PRIMARY)

			MusicItemContent(
				item = item,
				kind = kind,
				coverUrl = image?.getUrl(api, maxWidth = COVER_WIDTH),
				backgroundUrl = image?.let {
					api.imageApi.getItemImageUrl(itemId = it.item, imageType = it.type, tag = it.tag, maxWidth = BLUR_WIDTH, blur = BLUR, quality = 70)
				},
				tracks = tracks,
				albums = albums,
				similar = similarAsync.await(),
			)
		}
	}

	private suspend fun albumTracks(albumId: UUID) = api.itemsApi.getItems(
		GetItemsRequest(
			parentId = albumId,
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			sortBy = setOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
			fields = setOf(ItemFields.MEDIA_SOURCES),
		)
	).content.items

	/** Songs of a playlist, in the order of the playlist */
	private suspend fun playlistTracks(playlistId: UUID) = api.playlistsApi.getPlaylistItems(
		playlistId = playlistId,
		limit = PLAYLIST_LIMIT,
		enableUserData = true,
	).content.items.filter { it.type == BaseItemKind.AUDIO }

	private suspend fun artistSongs(artistId: UUID) = api.itemsApi.getItems(
		GetItemsRequest(
			artistIds = setOf(artistId),
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			sortBy = setOf(ItemSortBy.PLAY_COUNT, ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME),
			sortOrder = setOf(SortOrder.DESCENDING),
			limit = ARTIST_SONGS,
		)
	).content.items

	private suspend fun artistAlbums(artistId: UUID) = api.itemsApi.getItems(
		GetItemsRequest(
			albumArtistIds = setOf(artistId),
			includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
			recursive = true,
			sortBy = setOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
			sortOrder = setOf(SortOrder.DESCENDING),
			limit = ALBUMS_LIMIT,
		)
	).content.items

	private suspend fun genreAlbums(genreId: UUID) = api.itemsApi.getItems(
		GetItemsRequest(
			genreIds = setOf(genreId),
			includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
			recursive = true,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			limit = GENRE_ALBUMS_LIMIT,
		)
	).content.items

	// Actions

	suspend fun setFavorite(itemId: UUID, favorite: Boolean) = withContext(Dispatchers.IO) {
		if (favorite) api.userLibraryApi.markFavoriteItem(itemId)
		else api.userLibraryApi.unmarkFavoriteItem(itemId)
		Unit
	}

	/** Music playlists of the user, for "Add to playlist" */
	suspend fun playlists(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.PLAYLIST),
				mediaTypes = setOf(MediaType.AUDIO),
				recursive = true,
				sortBy = setOf(ItemSortBy.SORT_NAME),
				enableTotalRecordCount = false,
			)
		).content.items
	}

	suspend fun addToPlaylist(playlistId: UUID, itemIds: List<UUID>) = withContext(Dispatchers.IO) {
		api.playlistsApi.addItemToPlaylist(playlistId = playlistId, ids = itemIds)
		Unit
	}

	suspend fun createPlaylist(name: String, itemIds: List<UUID>) = withContext(Dispatchers.IO) {
		api.playlistsApi.createPlaylist(
			CreatePlaylistDto(
				name = name,
				ids = itemIds,
				mediaType = MediaType.AUDIO,
				users = emptyList(),
				isPublic = false,
			)
		)
		Unit
	}

	private inline fun <T> safe(name: String, block: () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load $name")
		null
	}

	private companion object {
		const val COVER_WIDTH = 600
		const val BLUR_WIDTH = 300
		const val BLUR = 18
		const val SIMILAR_LIMIT = 16
		const val PLAYLIST_LIMIT = 500
		const val ARTIST_SONGS = 20
		const val ALBUMS_LIMIT = 60
		const val GENRE_ALBUMS_LIMIT = 300
	}
}
