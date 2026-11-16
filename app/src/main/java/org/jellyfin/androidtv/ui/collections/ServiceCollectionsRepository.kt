package org.jellyfin.androidtv.ui.collections

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.collectionApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A collection of the series of a library released by one streaming service or network, kept up
 * to date by the app.
 */
@Serializable
data class ServiceCollectionRule(
	@Serializable(with = UUIDSerializer::class)
	val collectionId: UUID,
	@Serializable(with = UUIDSerializer::class)
	val libraryId: UUID,
	@Serializable(with = UUIDSerializer::class)
	val userId: UUID,
	/** [StreamingServices.Service.key] */
	val service: String,
	val name: String,
	/** Last update (epoch millis) */
	val lastSync: Long = 0,
)

/**
 * Series of a library by streaming service or network.
 *
 * @param services streaming services first, then the networks, the biggest first
 * @param seriesByService service key → ids of its series
 * @param items every series by id (for the poster choices)
 */
internal data class ServiceScan(
	val libraryId: UUID,
	val services: List<StreamingServices.Service>,
	val seriesByService: Map<String, List<UUID>>,
	val items: Map<UUID, BaseItemDto>,
	val time: Long = System.currentTimeMillis(),
)

/**
 * Collections of series by streaming service ("Netflix series", "HBO Max series"...). Normal
 * Jellyfin collections: they show in the Collections page of the TV show libraries (not in the
 * one of the films) and in every app. Updated once a day with the new series of the library.
 */
class ServiceCollectionsRepository(
	private val context: Context,
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val catalog: CollectionCatalog,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val file = File(context.filesDir, "service_collections.json")
	private val json = Json { ignoreUnknownKeys = true }
	private val mutex = Mutex()
	private val scans = HashMap<UUID, ServiceScan>()
	private var syncStarted = false

	// Rules

	private fun readRules(): List<ServiceCollectionRule> = runCatching {
		if (file.exists()) json.decodeFromString<List<ServiceCollectionRule>>(file.readText()) else emptyList()
	}.onFailure { Timber.w(it, "Unable to read the service collections") }.getOrDefault(emptyList())

	private fun writeRules(rules: List<ServiceCollectionRule>) {
		runCatching { file.writeText(json.encodeToString(rules)) }
			.onFailure { Timber.w(it, "Unable to save the service collections") }
	}

	private val userId: UUID?
		get() = userRepository.currentUser.value?.id

	/** Collections made from a library, by service key */
	suspend fun rulesFor(libraryId: UUID): Map<String, ServiceCollectionRule> = mutex.withLock {
		val user = userId
		readRules().filter { it.libraryId == libraryId && it.userId == user }.associateBy { it.service }
	}

	// Scan

	/** The networks of every series of a TV show library */
	internal suspend fun scan(
		library: BaseItemDto,
		maxAgeMs: Long = SCAN_MAX_AGE.inWholeMilliseconds,
	): ServiceScan = withContext(Dispatchers.IO) {
		synchronized(scans) { scans[library.id] }
			?.takeIf { System.currentTimeMillis() - it.time < maxAgeMs }
			?.let { return@withContext it }

		val series = loadSeries(library.id)
		val services = HashMap<String, StreamingServices.Service>()
		val byService = HashMap<String, MutableList<UUID>>()
		for (show in series) {
			val ofShow = show.studios.orEmpty()
				.mapNotNull { studio -> studio.name?.let(StreamingServices::of) }
				.distinctBy { it.key }
			for (service in ofShow) {
				services.putIfAbsent(service.key, service)
				byService.getOrPut(service.key) { ArrayList() } += show.id
			}
		}

		val listed = services.values
			// A network with a single series is mostly noise (production companies...)
			.filter { service -> service.streaming || byService[service.key].orEmpty().size >= MIN_NETWORK_SERIES }
			.sortedWith(
				compareByDescending<StreamingServices.Service> { it.streaming }
					.thenByDescending { byService[it.key].orEmpty().size }
					.thenBy { it.name.lowercase() }
			)

		ServiceScan(
			libraryId = library.id,
			services = listed,
			seriesByService = byService,
			items = series.associateBy { it.id },
		).also { scan -> synchronized(scans) { scans[library.id] = scan } }
	}

	private suspend fun loadSeries(libraryId: UUID): List<BaseItemDto> {
		val series = ArrayList<BaseItemDto>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					parentId = libraryId,
					includeItemTypes = setOf(BaseItemKind.SERIES),
					recursive = true,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					fields = setOf(ItemFields.STUDIOS),
					imageTypeLimit = 1,
					enableUserData = false,
					startIndex = start,
					limit = PAGE_SIZE,
				)
			).content
			series += page.items
			start += page.items.size
			if (page.items.size < PAGE_SIZE || start >= page.totalRecordCount) break
		}
		return series
	}

	// Create / update

	/** Create the collection with every series of [service] and remember it. Returns its id. */
	internal suspend fun create(
		library: BaseItemDto,
		scan: ServiceScan,
		service: StreamingServices.Service,
		name: String,
		poster: CollectionPoster,
		posterSubtitle: String,
	): UUID = withContext(Dispatchers.IO) {
		val ids = scan.seriesByService[service.key].orEmpty()
		require(ids.isNotEmpty()) { "No series of ${service.name}" }

		val collectionId = api.collectionApi.createCollection(
			name = name,
			ids = ids.take(IDS_PER_REQUEST).map { it.toString() },
			isLocked = true,
		).content.id
		ids.drop(IDS_PER_REQUEST).chunked(IDS_PER_REQUEST).forEach { chunk ->
			api.collectionApi.addToCollection(collectionId, chunk)
		}
		// Shown at once in the Collections page of the TV shows, not in the one of the films
		catalog.remember(collectionId, series = true, movies = false, count = ids.size)

		val user = requireNotNull(userId)
		mutex.withLock {
			val rules = readRules().filterNot { it.libraryId == library.id && it.service == service.key && it.userId == user }
			writeRules(
				rules + ServiceCollectionRule(
					collectionId = collectionId,
					libraryId = library.id,
					userId = user,
					service = service.key,
					name = name,
					lastSync = System.currentTimeMillis(),
				)
			)
		}

		// Last: it fails without administrator rights, the collection is there anyway
		setPoster(collectionId, poster, service.name, name, posterSubtitle)
		collectionId
	}

	/**
	 * Add the new series of the service and remove those that left it. Returns false when the
	 * collection was deleted on the server (it is forgotten then).
	 */
	internal suspend fun update(
		rule: ServiceCollectionRule,
		scan: ServiceScan,
		poster: CollectionPoster = CollectionPoster.Keep,
		posterSubtitle: String = "",
	): Boolean = withContext(Dispatchers.IO) {
		val exists = try {
			api.userLibraryApi.getItem(rule.collectionId)
			true
		} catch (err: InvalidStatusException) {
			if (err.status == 404) false else throw err
		}
		if (!exists) {
			forget(rule)
			return@withContext false
		}

		val wanted = scan.seriesByService[rule.service].orEmpty().toSet()
		val current = currentItems(rule.collectionId)
		// Only series of this library: anything added by hand stays
		val toAdd = wanted - current
		val toRemove = current.filter { it in scan.items && it !in wanted }

		toAdd.chunked(IDS_PER_REQUEST).forEach { api.collectionApi.addToCollection(rule.collectionId, it) }
		toRemove.chunked(IDS_PER_REQUEST).forEach { api.collectionApi.removeFromCollection(rule.collectionId, it) }
		catalog.remember(rule.collectionId, series = true, movies = false)

		mutex.withLock {
			writeRules(readRules().map { if (it.collectionId == rule.collectionId) it.copy(lastSync = System.currentTimeMillis()) else it })
		}
		Timber.i("Service collection ${rule.name}: +${toAdd.size} -${toRemove.size}")

		val serviceName = scan.services.firstOrNull { it.key == rule.service }?.name ?: rule.name
		setPoster(rule.collectionId, poster, serviceName, rule.name, posterSubtitle)
		true
	}

	private suspend fun currentItems(collectionId: UUID): Set<UUID> {
		val ids = HashSet<UUID>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					parentId = collectionId,
					enableImages = false,
					enableUserData = false,
					startIndex = start,
					limit = PAGE_SIZE,
				)
			).content
			page.items.mapTo(ids) { it.id }
			start += page.items.size
			if (page.items.size < PAGE_SIZE || start >= page.totalRecordCount) break
		}
		return ids
	}

	private suspend fun forget(rule: ServiceCollectionRule) = mutex.withLock {
		writeRules(readRules().filterNot { it.collectionId == rule.collectionId })
	}

	// Poster

	private suspend fun setPoster(
		collectionId: UUID,
		poster: CollectionPoster,
		serviceName: String,
		name: String,
		subtitle: String,
	) {
		val jpeg = when (poster) {
			CollectionPoster.Keep -> return
			CollectionPoster.Generated -> LanguagePoster.encode(LanguagePoster.drawService(serviceName, subtitle))
			is CollectionPoster.FromItem -> api.imageApi.getItemImage(
				itemId = poster.itemId,
				imageType = ImageType.PRIMARY,
				maxWidth = LanguagePoster.WIDTH,
				quality = 92,
				format = ImageFormat.JPG,
			).content
		}

		try {
			api.uploadPrimaryImage(collectionId, jpeg)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// Uploading images needs an administrator: the collection works anyway
			Timber.w(err, "Unable to set the poster of $name")
			throw PosterUploadException(err)
		}
	}

	// Daily update

	/** Update the collections of the current user not updated today, in the background */
	fun updateInBackground() {
		if (syncStarted) return
		syncStarted = true

		scope.launch {
			// After the home screen and the language collections
			delay(BACKGROUND_DELAY)
			val user = userId ?: return@launch
			val due = mutex.withLock { readRules() }
				.filter { it.userId == user && System.currentTimeMillis() - it.lastSync > UPDATE_INTERVAL.inWholeMilliseconds }
			if (due.isEmpty()) return@launch

			for ((libraryId, rules) in due.groupBy { it.libraryId }) {
				try {
					val library = api.userLibraryApi.getItem(libraryId).content
					val scan = scan(library)
					for (rule in rules) update(rule, scan)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Unable to update the service collections of library $libraryId")
				}
			}
		}
	}

	private companion object {
		const val PAGE_SIZE = 300
		const val IDS_PER_REQUEST = 60
		const val MIN_NETWORK_SERIES = 2
		val SCAN_MAX_AGE = 10.minutes
		val UPDATE_INTERVAL = 20.hours
		val BACKGROUND_DELAY = 50.seconds
	}
}
