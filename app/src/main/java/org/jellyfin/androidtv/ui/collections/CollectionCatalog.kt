package org.jellyfin.androidtv.ui.collections

import org.jellyfin.androidtv.ui.home.modern.BackgroundTraffic
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the collections of the server hold. The Collections page of a TV show library lists the
 * ones with series, the one of a film library leaves out those with only series. Jellyfin doesn't
 * tell, so each collection is looked into once (one small request) and the answer is kept on the
 * device until its number of titles changes.
 */
class CollectionCatalog(
	private val context: Context,
	private val api: ApiClient,
) {
	data class Kinds(
		/** Collections with at least one series */
		val withSeries: Set<UUID>,
		/** Collections with series and no movies */
		val seriesOnly: Set<UUID>,
	)

	@Serializable
	private data class Entry(
		/** Number of titles when it was looked into, -1 unknown */
		val count: Int,
		val series: Boolean,
		val movies: Boolean,
		val time: Long,
	)

	@Serializable
	private data class Saved(
		val server: String,
		val entries: Map<String, Entry>,
	)

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val json = Json { ignoreUnknownKeys = true }
	private val mutex = Mutex()
	private val file get() = File(context.filesDir, "collection_kinds.json")

	// Loaded from the file on first use, for the current server
	private var entries: MutableMap<String, Entry>? = null
	private var entriesServer: String? = null
	private var checkedAt = 0L

	private fun server() = api.baseUrl.orEmpty()

	private fun loaded(): MutableMap<String, Entry> = synchronized(this) {
		val current = entries
		if (current != null && entriesServer == server()) return current
		val saved = runCatching {
			if (file.exists()) json.decodeFromString<Saved>(file.readText()).takeIf { it.server == server() } else null
		}.onFailure { Timber.w(it, "Unable to read the collection kinds") }.getOrNull()
		HashMap(saved?.entries.orEmpty()).also {
			entries = it
			entriesServer = server()
		}
	}

	private fun save() {
		synchronized(this) {
			val current = entries ?: return
			runCatching { file.writeText(json.encodeToString(Saved(server(), HashMap(current)))) }
				.onFailure { Timber.w(it, "Unable to save the collection kinds") }
		}
	}

	private fun Map<String, Entry>.toKinds(): Kinds {
		val withSeries = HashSet<UUID>()
		val seriesOnly = HashSet<UUID>()
		for ((key, entry) in this) {
			val id = key.toUUIDOrNull() ?: continue
			if (entry.series) withSeries += id
			if (entry.series && !entry.movies) seriesOnly += id
		}
		return Kinds(withSeries, seriesOnly)
	}

	/** What is known now, without asking the server (empty when never looked) */
	fun cached(): Kinds = synchronized(this) { loaded().toKinds() }

	/** Every collection looked into: the new and changed ones are asked now */
	suspend fun kinds(): Kinds = withContext(Dispatchers.IO) {
		mutex.withLock {
			val known = loaded()
			if (System.currentTimeMillis() - checkedAt < RECHECK_INTERVAL.inWholeMilliseconds) return@withLock cached()

			val collections = allCollections()
			val now = System.currentTimeMillis()
			val pending = collections.filter { (id, count) ->
				val entry = synchronized(this@CollectionCatalog) { known[id.toString()] }
				entry == null || entry.count != count || now - entry.time > MAX_AGE.inWholeMilliseconds
			}

			if (pending.isNotEmpty()) {
				Timber.i("Looking into ${pending.size} of ${collections.size} collections")
				val semaphore = Semaphore(PARALLEL_REQUESTS)
				val results = coroutineScope {
					pending.map { (id, count) ->
						async { semaphore.withPermit { classify(id, count) } }
					}.awaitAll()
				}
				synchronized(this@CollectionCatalog) {
					for (result in results) if (result != null) known[result.first.toString()] = result.second
				}
			}

			// Deleted collections are forgotten
			val ids = collections.mapTo(HashSet()) { it.first.toString() }
			synchronized(this@CollectionCatalog) { known.keys.retainAll(ids) }
			save()
			checkedAt = System.currentTimeMillis()
			cached()
		}
	}

	/** A collection the app just made: no need to look into it */
	fun remember(collectionId: UUID, series: Boolean, movies: Boolean, count: Int = -1) {
		synchronized(this) {
			loaded()[collectionId.toString()] = Entry(count, series, movies, System.currentTimeMillis())
		}
		save()
	}

	/** Look into the collections in the background (the home screen calls it) */
	fun preloadInBackground() {
		scope.launch {
			delay(PRELOAD_DELAY)
			try {
				kinds()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to look into the collections")
			}
		}
	}

	/** Every collection with its number of titles */
	private suspend fun allCollections(): List<Pair<UUID, Int>> {
		val collections = ArrayList<Pair<UUID, Int>>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					includeItemTypes = setOf(BaseItemKind.BOX_SET),
					recursive = true,
					fields = setOf(ItemFields.CHILD_COUNT),
					enableImages = false,
					enableUserData = false,
					startIndex = start,
					limit = PAGE_SIZE,
				)
			).content
			page.items.mapTo(collections) { it.id to (it.childCount ?: -1) }
			start += page.items.size
			if (page.items.size < PAGE_SIZE || start >= page.totalRecordCount) break
		}
		return collections
	}

	private suspend fun classify(id: UUID, count: Int): Pair<UUID, Entry>? = try {
		BackgroundTraffic.awaitTurn()
		val series = countOf(id, BaseItemKind.SERIES)
		// Most collections are film sagas: the films are only counted when there are series
		val movies = if (series > 0) countOf(id, BaseItemKind.MOVIE) else 1
		id to Entry(count, series > 0, movies > 0, System.currentTimeMillis())
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to look into collection $id")
		null
	}

	private suspend fun countOf(collectionId: UUID, kind: BaseItemKind): Int = api.itemsApi.getItems(
		GetItemsRequest(
			parentId = collectionId,
			includeItemTypes = setOf(kind),
			enableImages = false,
			enableUserData = false,
			enableTotalRecordCount = true,
			limit = 0,
		)
	).content.totalRecordCount

	private companion object {
		const val PAGE_SIZE = 500
		const val PARALLEL_REQUESTS = 3
		val RECHECK_INTERVAL = 5.minutes
		val MAX_AGE = 7.days
		val PRELOAD_DELAY = 40.seconds
	}
}
