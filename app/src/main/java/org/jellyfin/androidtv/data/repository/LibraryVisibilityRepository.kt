package org.jellyfin.androidtv.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.androidtv.preference.UserPreferences
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.io.File
import java.util.UUID

/**
 * Libraries the user chose to hide. Hidden libraries are removed from the navigation bar of the
 * modern home screen and from search results.
 */
class LibraryVisibilityRepository(
	private val api: ApiClient,
	private val userViewsRepository: UserViewsRepository,
	private val userPreferences: UserPreferences,
	private val context: Context,
) {
	/** Background refresh of the hidden items saved on disk */
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	/** Only one request for the hidden items at a time (the hero and the rows ask at the same time) */
	private val hiddenItemsMutex = Mutex()

	private data class LibrariesCache(val session: String?, val time: Long, val libraries: List<BaseItemDto>)

	private val _hiddenLibraryIds = MutableStateFlow(readHiddenLibraryIds())
	val hiddenLibraryIds: StateFlow<Set<UUID>> = _hiddenLibraryIds.asStateFlow()

	/** Custom order of the libraries in the navigation bar, empty for the server order */
	private val _libraryOrder = MutableStateFlow(readLibraryOrder())
	val libraryOrder: StateFlow<List<UUID>> = _libraryOrder.asStateFlow()

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
	 * Move a library one position to the left ([offset] -1) or right (+1) among the visible ones.
	 */
	suspend fun moveLibrary(libraryId: UUID, offset: Int) {
		val arranged = arrange(libraries(), _libraryOrder.value).map { it.id }.toMutableList()
		val visible = arranged.filterNot { it in _hiddenLibraryIds.value }
		val visibleIndex = visible.indexOf(libraryId)
		val targetId = visible.getOrNull(visibleIndex + offset)
		if (visibleIndex < 0 || targetId == null) return

		// Swap with the visible neighbor, hidden libraries keep their place
		val from = arranged.indexOf(libraryId)
		val to = arranged.indexOf(targetId)
		arranged[from] = targetId
		arranged[to] = libraryId

		_libraryOrder.value = arranged
		userPreferences[UserPreferences.libraryOrder] = arranged.joinToString(",")
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

		hiddenItemsMutex.withLock {
			val session = api.accessToken
			val now = System.currentTimeMillis()
			hiddenItemsCache?.let { cached ->
				if (cached.session == session && cached.hidden == hidden && now - cached.time < HIDDEN_ITEMS_CACHE_DURATION_MS) {
					return@withContext cached.itemIds
				}
			}

			// On app start use the list saved on disk right away and update it in the background:
			// big hidden libraries take seconds to list on a Fire TV
			val saved = readSavedHiddenItems(hidden)
			if (saved != null && hiddenItemsCache == null) {
				hiddenItemsCache = HiddenItemsCache(session, hidden, now, saved)
				scope.launch { runCatching { refreshHiddenItems(hidden) } }
				return@withContext saved
			}

			refreshHiddenItems(hidden)
		}
	}

	private suspend fun refreshHiddenItems(hidden: Set<UUID>): Set<UUID> {
		val itemIds = coroutineScope {
			hidden.map { libraryId ->
				async {
					runCatching {
						api.itemsApi.getItems(
							GetItemsRequest(
								parentId = libraryId,
								recursive = true,
								includeItemTypes = hiddenItemKinds,
								fields = emptySet(),
								enableImages = false,
								enableUserData = false,
								enableTotalRecordCount = false,
							)
						).content.items.map { it.id }
					}.getOrDefault(emptyList())
				}
			}.awaitAll()
		}.flatten().toSet()

		hiddenItemsCache = HiddenItemsCache(api.accessToken, hidden, System.currentTimeMillis(), itemIds)
		saveHiddenItems(hidden, itemIds)
		return itemIds
	}

	private val hiddenItemsFile: File
		get() = File(context.cacheDir, HIDDEN_ITEMS_FILE)

	/** Saved ids, only when they belong to the same hidden libraries and server */
	private fun readSavedHiddenItems(hidden: Set<UUID>): Set<UUID>? = runCatching {
		val lines = hiddenItemsFile.takeIf { it.exists() }?.readLines() ?: return null
		if (lines.size < 2 || lines[0] != hiddenItemsKey(hidden)) return null
		parseIds(lines[1]).toSet()
	}.getOrNull()

	private fun saveHiddenItems(hidden: Set<UUID>, itemIds: Set<UUID>) {
		runCatching { hiddenItemsFile.writeText(hiddenItemsKey(hidden) + "\n" + itemIds.joinToString(",")) }
	}

	private fun hiddenItemsKey(hidden: Set<UUID>) = api.baseUrl + "|" + hidden.map { it.toString() }.sorted().joinToString(",")

	private fun readHiddenLibraryIds(): Set<UUID> = parseIds(userPreferences[UserPreferences.hiddenLibraryIds]).toSet()

	private fun readLibraryOrder(): List<UUID> = parseIds(userPreferences[UserPreferences.libraryOrder]).distinct()

	private fun parseIds(value: String): List<UUID> = value
		.split(",")
		.mapNotNull { id -> id.trim().takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() } }

	companion object {
		private const val CACHE_DURATION_MS = 5 * 60 * 1000L
		private const val HIDDEN_ITEMS_CACHE_DURATION_MS = 30 * 60 * 1000L
		private const val HIDDEN_ITEMS_FILE = "hidden_library_items.txt"

		/**
		 * Sort [libraries] (server order) with the custom [order]. Libraries missing from the custom
		 * order (e.g. added later on the server) go at the end.
		 */
		fun arrange(libraries: List<BaseItemDto>, order: List<UUID>): List<BaseItemDto> {
			if (order.isEmpty()) return libraries
			val positions = order.withIndex().associate { (index, id) -> id to index }
			return libraries
				.withIndex()
				.sortedWith(compareBy<IndexedValue<BaseItemDto>> { positions[it.value.id] ?: Int.MAX_VALUE }.thenBy { it.index })
				.map { it.value }
		}

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
