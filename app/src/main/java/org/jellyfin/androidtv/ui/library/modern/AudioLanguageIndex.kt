package org.jellyfin.androidtv.ui.library.modern

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.ui.home.modern.BackgroundTraffic
import org.jellyfin.androidtv.ui.home.modern.MediaBadges
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Audio languages of every title of a movie or TV show library, for the *Audio* filter of the browse
 * page. Jellyfin can't filter by audio language, so the app reads them: the audio tracks of each
 * movie, and of the first episodes of each series. They are kept on the device and only the new
 * titles are read afterwards (and the old ones again after a month).
 */
class AudioLanguageIndex(
	private val context: Context,
	private val api: ApiClient,
) {
	/** Titles read of those to read */
	data class Progress(val done: Int, val total: Int)

	@Serializable
	private data class Entry(
		/** Normalized keys ("eng", "spa-419"...), see [MediaBadges.languageKey] */
		val languages: List<String>,
		val time: Long,
	)

	@Serializable
	private data class Saved(
		val server: String,
		val kind: LibraryKind,
		/** Every title was read once */
		val complete: Boolean,
		val items: Map<String, Entry>,
	)

	private class Library(val id: UUID, val kind: LibraryKind) {
		var server: String? = null
		var items: HashMap<String, Entry>? = null
		var complete = false
		var checkedAt = 0L
		var job: Deferred<Unit>? = null

		/** Someone is waiting (the browse page): no waiting for the background's turn */
		@Volatile
		var urgent = false
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val json = Json { ignoreUnknownKeys = true }
	private val libraries = HashMap<UUID, Library>()
	private val _progress = MutableStateFlow<Map<UUID, Progress>>(emptyMap())

	/** Libraries being read, with how far */
	val progress: StateFlow<Map<UUID, Progress>> = _progress.asStateFlow()

	private fun server() = api.baseUrl.orEmpty()
	private fun file(id: UUID) = File(context.filesDir, "audio_languages_$id.json")

	private fun library(id: UUID, kind: LibraryKind): Library = synchronized(libraries) {
		libraries.getOrPut(id) { Library(id, kind) }
	}

	private fun loaded(library: Library): HashMap<String, Entry> = synchronized(library) {
		val current = library.items
		if (current != null && library.server == server()) return current
		val saved = runCatching {
			file(library.id).takeIf { it.exists() }?.let { json.decodeFromString<Saved>(it.readText()) }
		}.onFailure { Timber.w(it, "Unable to read the audio languages of ${library.id}") }
			.getOrNull()
			?.takeIf { it.server == server() && it.kind == library.kind }
		library.server = server()
		library.complete = saved?.complete == true
		library.checkedAt = 0L
		HashMap(saved?.items.orEmpty()).also { library.items = it }
	}

	private fun save(library: Library) {
		val saved = synchronized(library) {
			Saved(server(), library.kind, library.complete, HashMap(library.items ?: return))
		}
		runCatching { file(library.id).writeText(json.encodeToString(saved)) }
			.onFailure { Timber.w(it, "Unable to save the audio languages of ${library.id}") }
	}

	private fun byLanguage(library: Library): Map<String, Set<UUID>> = synchronized(library) {
		val result = HashMap<String, MutableSet<UUID>>()
		for ((key, entry) in loaded(library)) {
			val id = key.toUUIDOrNull() ?: continue
			for (language in entry.languages) result.getOrPut(language) { HashSet() } += id
		}
		result
	}

	/** Language → titles, from what the device knows; null when the library was never read whole */
	fun cached(libraryId: UUID, kind: LibraryKind): Map<String, Set<UUID>>? {
		val library = library(libraryId, kind)
		loaded(library)
		return if (synchronized(library) { library.complete }) byLanguage(library) else null
	}

	/** Language → titles, reading the titles that are new first (and the whole library the first time) */
	suspend fun update(libraryId: UUID, kind: LibraryKind): Map<String, Set<UUID>> {
		val library = library(libraryId, kind)
		library.urgent = true
		start(library).await()
		return byLanguage(library)
	}

	/** Bring a library up to date without hurry (the library page calls it) */
	fun updateInBackground(libraryId: UUID, kind: LibraryKind) {
		start(library(libraryId, kind))
	}

	private fun start(library: Library): Deferred<Unit> = synchronized(library) {
		library.job?.takeIf { it.isActive }?.let { return it }
		scope.async {
			try {
				refresh(library)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to read the audio languages of ${library.id}")
				throw err
			} finally {
				library.urgent = false
				_progress.update { it - library.id }
			}
		}.also { library.job = it }
	}

	private suspend fun awaitTurn(library: Library) {
		if (!library.urgent) BackgroundTraffic.awaitTurn()
	}

	private suspend fun refresh(library: Library) {
		val items = loaded(library)
		val fresh = synchronized(library) {
			library.complete && System.currentTimeMillis() - library.checkedAt < RECHECK_INTERVAL.inWholeMilliseconds
		}
		if (fresh) return

		awaitTurn(library)
		val ids = allTitles(library)
		val now = System.currentTimeMillis()
		val pending = synchronized(library) {
			// Deleted titles are forgotten
			items.keys.retainAll(ids.mapTo(HashSet()) { it.toString() })
			val missing = ids.filter { items[it.toString()] == null }
			val stale = ids
				.filter { id -> items[id.toString()]?.let { now - it.time > MAX_AGE.inWholeMilliseconds } == true }
				.take(MAX_STALE_PER_UPDATE)
			missing + stale
		}

		if (pending.isNotEmpty()) {
			Timber.i("Reading the audio languages of ${pending.size} of ${ids.size} titles of ${library.id}")
			_progress.update { it + (library.id to Progress(0, pending.size)) }
			when (library.kind) {
				LibraryKind.MOVIES -> readMovies(library, pending)
				LibraryKind.SHOWS -> readSeries(library, pending)
			}
		}

		synchronized(library) {
			library.complete = true
			library.checkedAt = System.currentTimeMillis()
		}
		save(library)
	}

	/** Every movie or series of the library (only the ids: a small answer) */
	private suspend fun allTitles(library: Library): List<UUID> {
		val ids = ArrayList<UUID>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					parentId = library.id,
					includeItemTypes = setOf(library.kind.itemType),
					recursive = true,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					enableImages = false,
					enableUserData = false,
					startIndex = start,
					limit = TITLES_PER_PAGE,
				)
			).content
			page.items.mapTo(ids) { it.id }
			start += page.items.size
			if (page.items.size < TITLES_PER_PAGE || start >= page.totalRecordCount) break
		}
		return ids
	}

	private fun remember(library: Library, values: Map<UUID, Set<String>>) {
		val time = System.currentTimeMillis()
		synchronized(library) {
			val items = library.items ?: return
			for ((id, languages) in values) items[id.toString()] = Entry(languages.toList(), time)
		}
	}

	private fun progress(library: Library, done: Int, total: Int) {
		_progress.update { it + (library.id to Progress(done, total)) }
	}

	/** Movies have their audio tracks: a few dozen per request */
	private suspend fun readMovies(library: Library, pending: List<UUID>) {
		var done = 0
		for (chunk in pending.chunked(MOVIES_PER_REQUEST)) {
			awaitTurn(library)
			val found = api.itemsApi.getItems(
				GetItemsRequest(
					ids = chunk,
					fields = setOf(ItemFields.MEDIA_STREAMS),
					enableImages = false,
					enableUserData = false,
					enableTotalRecordCount = false,
				)
			).content.items.associate { it.id to MediaBadges.allAudioLanguageKeys(it) }
			remember(library, chunk.associateWith { found[it].orEmpty() })
			done += chunk.size
			progress(library, done, pending.size)
			if (done % SAVE_EVERY < MOVIES_PER_REQUEST) save(library)
		}
	}

	/** Series: the audio languages of their first regular episodes (one request per series) */
	private suspend fun readSeries(library: Library, pending: List<UUID>) = coroutineScope {
		val semaphore = Semaphore(SERIES_PARALLEL_REQUESTS)
		val done = AtomicInteger()
		pending.map { seriesId ->
			async {
				semaphore.withPermit {
					awaitTurn(library)
					try {
						val languages = api.tvShowsApi.getEpisodes(
							seriesId = seriesId,
							fields = setOf(ItemFields.MEDIA_STREAMS),
							isMissing = false,
							limit = EPISODES_PER_SERIES * 2,
							enableImages = false,
							enableUserData = false,
						).content.items
							.filter { (it.parentIndexNumber ?: 1) > 0 }
							.take(EPISODES_PER_SERIES)
							.flatMap { MediaBadges.allAudioLanguageKeys(it) }
							.toSet()
						remember(library, mapOf(seriesId to languages))
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						// Read again next time
						Timber.w(err, "Unable to read the episodes of $seriesId")
					}
					val count = done.incrementAndGet()
					progress(library, count, pending.size)
					if (count % SAVE_EVERY == 0) save(library)
				}
			}
		}.awaitAll()
		Unit
	}

	private companion object {
		const val TITLES_PER_PAGE = 1_000
		const val MOVIES_PER_REQUEST = 40
		const val SERIES_PARALLEL_REQUESTS = 4
		const val EPISODES_PER_SERIES = 3
		const val SAVE_EVERY = 80
		/** Titles read again (in case the file changed) on each update */
		const val MAX_STALE_PER_UPDATE = 60
		val RECHECK_INTERVAL = 5.minutes
		val MAX_AGE = 30.days
	}
}
