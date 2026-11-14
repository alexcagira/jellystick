package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.jellyfin.androidtv.preference.UserPreferences
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/**
 * Libraries the user chose to hide. Hidden libraries are removed from the navigation bar of the
 * modern home screen and from search results.
 */
class LibraryVisibilityRepository(
	private val api: ApiClient,
	private val userViewsRepository: UserViewsRepository,
	private val userPreferences: UserPreferences,
) {
	private data class LibrariesCache(val session: String?, val time: Long, val libraries: List<BaseItemDto>)

	private val _hiddenLibraryIds = MutableStateFlow(readHiddenLibraryIds())
	val hiddenLibraryIds: StateFlow<Set<UUID>> = _hiddenLibraryIds.asStateFlow()

	@Volatile
	private var cache: LibrariesCache? = null

	private data class HiddenItemsCache(val session: String?, val hidden: Set<UUID>, val time: Long, val itemIds: Set<UUID>)

	@Volatile
	private var hiddenItemsCache: HiddenItemsCache? = null

	fun isHidden(libraryId: UUID): Boolean = libraryId in _hiddenLibraryIds.value

	fun setHidden(libraryId: UUID, hidden: Boolean) {
		val updated = if (hidden) _hiddenLibraryIds.value + libraryId else _hiddenLibraryIds.value - libraryId
		_hiddenLibraryIds.value = updated
		userPreferences[UserPreferences.hiddenLibraryIds] = updated.joinToString(",")
	}

	/**
	 * All libraries of the current user, in the order configured on the server. Cached for a few
	 * minutes because search needs them for every query.
	 */
	suspend fun libraries(forceRefresh: Boolean = false): List<BaseItemDto> {
		val session = api.accessToken
		val now = System.currentTimeMillis()
		cache?.let { cached ->
			if (!forceRefresh && cached.session == session && now - cached.time < CACHE_DURATION_MS) return cached.libraries
		}

		val libraries = userViewsRepository.views.first().toList()
		cache = LibrariesCache(session, now, libraries)
		return libraries
	}

	/**
	 * Ids of the movies, series, collections... stored in hidden libraries. Used to remove them from
	 * the home screen (episodes are matched through their series id).
	 */
	suspend fun hiddenItemIds(): Set<UUID> = withContext(Dispatchers.IO) {
		val hidden = _hiddenLibraryIds.value
		if (hidden.isEmpty()) return@withContext emptySet()

		val session = api.accessToken
		val now = System.currentTimeMillis()
		hiddenItemsCache?.let { cached ->
			if (cached.session == session && cached.hidden == hidden && now - cached.time < CACHE_DURATION_MS) {
				return@withContext cached.itemIds
			}
		}

		val itemIds = coroutineScope {
			hidden.map { libraryId ->
				async {
					runCatching {
						api.itemsApi.getItems(
							GetItemsRequest(
								parentId = libraryId,
								recursive = true,
								includeItemTypes = hiddenItemKinds,
								enableImages = false,
								enableUserData = false,
								enableTotalRecordCount = false,
							)
						).content.items.map { it.id }
					}.getOrDefault(emptyList())
				}
			}.awaitAll()
		}.flatten().toSet()

		hiddenItemsCache = HiddenItemsCache(session, hidden, now, itemIds)
		itemIds
	}

	private fun readHiddenLibraryIds(): Set<UUID> = userPreferences[UserPreferences.hiddenLibraryIds]
		.split(",")
		.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
		.toSet()

	companion object {
		private const val CACHE_DURATION_MS = 5 * 60 * 1000L

		private val hiddenItemKinds = setOf(
			BaseItemKind.MOVIE,
			BaseItemKind.SERIES,
			BaseItemKind.VIDEO,
			BaseItemKind.MUSIC_VIDEO,
			BaseItemKind.BOX_SET,
			BaseItemKind.MUSIC_ALBUM,
		)

		/** Item types that are not stored inside a library folder (they can't be filtered by parent) */
		val virtualItemKinds = setOf(
			BaseItemKind.PERSON,
			BaseItemKind.MUSIC_ARTIST,
			BaseItemKind.PLAYLIST,
			BaseItemKind.BOX_SET,
			BaseItemKind.LIVE_TV_CHANNEL,
			BaseItemKind.LIVE_TV_PROGRAM,
			BaseItemKind.TV_CHANNEL,
			BaseItemKind.PROGRAM,
			BaseItemKind.GENRE,
			BaseItemKind.MUSIC_GENRE,
			BaseItemKind.STUDIO,
		)

		private val collectionTypesByKind = mapOf(
			BaseItemKind.MOVIE to setOf(CollectionType.MOVIES, CollectionType.HOMEVIDEOS),
			BaseItemKind.TRAILER to setOf(CollectionType.MOVIES, CollectionType.TRAILERS),
			BaseItemKind.SERIES to setOf(CollectionType.TVSHOWS),
			BaseItemKind.SEASON to setOf(CollectionType.TVSHOWS),
			BaseItemKind.EPISODE to setOf(CollectionType.TVSHOWS),
			BaseItemKind.VIDEO to setOf(CollectionType.HOMEVIDEOS, CollectionType.MUSICVIDEOS, CollectionType.MOVIES, CollectionType.TVSHOWS),
			BaseItemKind.MUSIC_VIDEO to setOf(CollectionType.MUSICVIDEOS),
			BaseItemKind.AUDIO to setOf(CollectionType.MUSIC),
			BaseItemKind.MUSIC_ALBUM to setOf(CollectionType.MUSIC),
			BaseItemKind.MUSIC_ARTIST to setOf(CollectionType.MUSIC),
			BaseItemKind.PHOTO to setOf(CollectionType.PHOTOS, CollectionType.HOMEVIDEOS),
			BaseItemKind.PHOTO_ALBUM to setOf(CollectionType.PHOTOS, CollectionType.HOMEVIDEOS),
			BaseItemKind.PLAYLIST to setOf(CollectionType.PLAYLISTS),
			BaseItemKind.BOX_SET to setOf(CollectionType.BOXSETS),
			BaseItemKind.BOOK to setOf(CollectionType.BOOKS),
			BaseItemKind.AUDIO_BOOK to setOf(CollectionType.BOOKS),
			BaseItemKind.LIVE_TV_CHANNEL to setOf(CollectionType.LIVETV),
			BaseItemKind.LIVE_TV_PROGRAM to setOf(CollectionType.LIVETV),
			BaseItemKind.TV_CHANNEL to setOf(CollectionType.LIVETV),
			BaseItemKind.PROGRAM to setOf(CollectionType.LIVETV),
		)

		/**
		 * Whether a library of [collectionType] can contain any of [itemKinds]. Mixed libraries
		 * (no collection type) can contain anything that is stored in a folder.
		 */
		fun canContain(collectionType: CollectionType?, itemKinds: Collection<BaseItemKind>): Boolean {
			if (collectionType == null || collectionType == CollectionType.UNKNOWN || collectionType == CollectionType.FOLDERS) {
				return itemKinds.any { it !in virtualItemKinds }
			}

			return itemKinds.any { kind -> collectionTypesByKind[kind]?.contains(collectionType) == true }
		}
	}
}
