package org.jellyfin.androidtv.ui.collections

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
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.home.modern.MediaBadges
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.collectionApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Films or TV shows */
@Serializable
enum class LanguageCollectionKind {
	MOVIES,
	SERIES;

	val itemKind: BaseItemKind
		get() = if (this == MOVIES) BaseItemKind.MOVIE else BaseItemKind.SERIES

	companion object {
		fun of(library: BaseItemDto): LanguageCollectionKind? = when (library.collectionType) {
			CollectionType.MOVIES -> MOVIES
			CollectionType.TVSHOWS -> SERIES
			else -> null
		}
	}
}

/**
 * A collection made from a library and an audio language, kept up to date by the app.
 */
@Serializable
data class LanguageCollectionRule(
	@Serializable(with = UUIDSerializer::class)
	val collectionId: UUID,
	@Serializable(with = UUIDSerializer::class)
	val libraryId: UUID,
	@Serializable(with = UUIDSerializer::class)
	val userId: UUID,
	val kind: LanguageCollectionKind,
	/** Normalized language key, see [MediaBadges.languageKey] */
	val language: String,
	val name: String,
	/** Last update (epoch millis) */
	val lastSync: Long = 0,
)

/**
 * Titles of a library for each audio language.
 *
 * @param itemsByLanguage language key → ids of the movies/series with that audio language
 * @param items every scanned title by id (for the poster choices)
 */
data class LanguageScan(
	val libraryId: UUID,
	val kind: LanguageCollectionKind,
	val itemsByLanguage: Map<String, List<UUID>>,
	val items: Map<UUID, BaseItemDto>,
	val time: Long = System.currentTimeMillis(),
)

/** Poster of a new collection */
sealed interface CollectionPoster {
	/** Generated poster with the flag and the name of the language */
	data object Generated : CollectionPoster

	/** Poster of one of the titles */
	data class FromItem(val itemId: UUID) : CollectionPoster

	/** Keep the current poster (updating an existing collection) */
	data object Keep : CollectionPoster
}

/**
 * Collections by audio language ("English films", "Spanish TV shows"...). They are normal Jellyfin
 * collections, so they show up in the Collections library and in every app. The app remembers how
 * each one was made and updates it once a day with the new titles of the library.
 */
class LanguageCollectionsRepository(
	private val context: Context,
	private val api: ApiClient,
	private val userRepository: UserRepository,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val file = File(context.filesDir, "language_collections.json")
	private val json = Json { ignoreUnknownKeys = true }
	private val mutex = Mutex()
	private val scans = HashMap<UUID, LanguageScan>()
	private var syncStarted = false

	// Rules

	private fun readRules(): List<LanguageCollectionRule> = runCatching {
		if (file.exists()) json.decodeFromString<List<LanguageCollectionRule>>(file.readText()) else emptyList()
	}.onFailure { Timber.w(it, "Unable to read the language collections") }.getOrDefault(emptyList())

	private fun writeRules(rules: List<LanguageCollectionRule>) {
		runCatching { file.writeText(json.encodeToString(rules)) }
			.onFailure { Timber.w(it, "Unable to save the language collections") }
	}

	private val userId: UUID?
		get() = userRepository.currentUser.value?.id

	/** Collections made from a library, by language key */
	suspend fun rulesFor(libraryId: UUID): Map<String, LanguageCollectionRule> = mutex.withLock {
		val user = userId
		readRules().filter { it.libraryId == libraryId && it.userId == user }.associateBy { it.language }
	}

	// Scan

	/**
	 * Read the audio languages of every title of a library. Movies use their own audio tracks,
	 * series the first episodes. [onProgress] receives (done, total).
	 */
	suspend fun scan(
		library: BaseItemDto,
		maxAgeMs: Long = SCAN_MAX_AGE.inWholeMilliseconds,
		onProgress: (Int, Int) -> Unit = { _, _ -> },
	): LanguageScan = withContext(Dispatchers.IO) {
		val kind = requireNotNull(LanguageCollectionKind.of(library)) { "Not a movie or TV show library" }
		synchronized(scans) { scans[library.id] }
			?.takeIf { System.currentTimeMillis() - it.time < maxAgeMs }
			?.let { return@withContext it }

		val titles = loadTitles(library.id, kind)
		val languagesById = when (kind) {
			LanguageCollectionKind.MOVIES -> {
				onProgress(titles.size, titles.size)
				titles.associate { it.id to MediaBadges.allAudioLanguageKeys(it) }
			}

			LanguageCollectionKind.SERIES -> seriesLanguages(titles, onProgress)
		}

		val byLanguage = HashMap<String, MutableList<UUID>>()
		for (title in titles) {
			for (language in languagesById[title.id].orEmpty()) byLanguage.getOrPut(language) { ArrayList() } += title.id
		}

		LanguageScan(
			libraryId = library.id,
			kind = kind,
			itemsByLanguage = byLanguage,
			items = titles.associateBy { it.id },
		).also { scan -> synchronized(scans) { scans[library.id] = scan } }
	}

	private suspend fun loadTitles(libraryId: UUID, kind: LanguageCollectionKind): List<BaseItemDto> {
		val titles = ArrayList<BaseItemDto>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					parentId = libraryId,
					includeItemTypes = setOf(kind.itemKind),
					recursive = true,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					fields = if (kind == LanguageCollectionKind.MOVIES) setOf(ItemFields.MEDIA_STREAMS) else null,
					imageTypeLimit = 1,
					enableUserData = false,
					startIndex = start,
					limit = PAGE_SIZE,
				)
			).content
			titles += page.items
			start += page.items.size
			if (page.items.size < PAGE_SIZE || start >= page.totalRecordCount) break
		}
		return titles
	}

	/** Languages of a series: those of its first regular episodes */
	private suspend fun seriesLanguages(
		series: List<BaseItemDto>,
		onProgress: (Int, Int) -> Unit,
	): Map<UUID, Set<String>> = coroutineScope {
		val semaphore = Semaphore(SERIES_PARALLEL_REQUESTS)
		val done = AtomicInteger()
		onProgress(0, series.size)

		series.map { show ->
			async {
				val languages = semaphore.withPermit {
					try {
						api.tvShowsApi.getEpisodes(
							seriesId = show.id,
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
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Unable to read the episodes of ${show.name}")
						emptySet()
					}
				}
				onProgress(done.incrementAndGet(), series.size)
				show.id to languages
			}
		}.awaitAll().toMap()
	}

	// Create / update

	/**
	 * Create the collection with every title of [language], set its poster and remember it so it
	 * stays up to date. Returns the id of the new collection.
	 */
	suspend fun create(
		library: BaseItemDto,
		scan: LanguageScan,
		language: String,
		name: String,
		poster: CollectionPoster,
		posterSubtitle: String,
	): UUID = withContext(Dispatchers.IO) {
		val ids = scan.itemsByLanguage[language].orEmpty()
		require(ids.isNotEmpty()) { "No titles in $language" }

		val first = ids.take(IDS_PER_REQUEST)
		val collectionId = api.collectionApi.createCollection(
			name = name,
			ids = first.map { it.toString() },
			isLocked = true,
		).content.id
		ids.drop(IDS_PER_REQUEST).chunked(IDS_PER_REQUEST).forEach { chunk ->
			api.collectionApi.addToCollection(collectionId, chunk)
		}

		val user = requireNotNull(userId)
		mutex.withLock {
			val rules = readRules().filterNot { it.libraryId == library.id && it.language == language && it.userId == user }
			writeRules(
				rules + LanguageCollectionRule(
					collectionId = collectionId,
					libraryId = library.id,
					userId = user,
					kind = scan.kind,
					language = language,
					name = name,
					lastSync = System.currentTimeMillis(),
				)
			)
		}

		// Last: it fails without administrator rights, the collection is there anyway
		setPoster(collectionId, poster, language, name, posterSubtitle)

		collectionId
	}

	/**
	 * Add the new titles to a collection and remove those that no longer have the language.
	 * Returns false when the collection was deleted on the server (it is forgotten then).
	 */
	suspend fun update(
		rule: LanguageCollectionRule,
		scan: LanguageScan,
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

		val wanted = scan.itemsByLanguage[rule.language].orEmpty().toSet()
		val current = currentItems(rule.collectionId)
		// Only titles of this library: other things added by hand to the collection stay
		val libraryTitles = scan.items.keys
		val toAdd = wanted - current
		val toRemove = current.filter { it in libraryTitles && it !in wanted }

		toAdd.chunked(IDS_PER_REQUEST).forEach { api.collectionApi.addToCollection(rule.collectionId, it) }
		toRemove.chunked(IDS_PER_REQUEST).forEach { api.collectionApi.removeFromCollection(rule.collectionId, it) }

		mutex.withLock {
			writeRules(readRules().map { if (it.collectionId == rule.collectionId) it.copy(lastSync = System.currentTimeMillis()) else it })
		}
		Timber.i("Language collection ${rule.name}: +${toAdd.size} -${toRemove.size}")

		setPoster(rule.collectionId, poster, rule.language, rule.name, posterSubtitle)
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

	private suspend fun forget(rule: LanguageCollectionRule) = mutex.withLock {
		writeRules(readRules().filterNot { it.collectionId == rule.collectionId })
	}

	// Poster

	private suspend fun setPoster(
		collectionId: UUID,
		poster: CollectionPoster,
		language: String,
		name: String,
		subtitle: String,
	) {
		val jpeg = when (poster) {
			CollectionPoster.Keep -> return
			CollectionPoster.Generated -> LanguagePoster.encode(
				LanguagePoster.draw(MediaBadges.flagFor(language), MediaBadges.languageName(language), subtitle)
			)

			is CollectionPoster.FromItem -> api.imageApi.getItemImage(
				itemId = poster.itemId,
				imageType = ImageType.PRIMARY,
				maxWidth = LanguagePoster.WIDTH,
				quality = 92,
				format = ImageFormat.JPG,
			).content
		}

		try {
			uploadPoster(collectionId, jpeg)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// Uploading images needs an administrator: the collection works anyway
			Timber.w(err, "Unable to set the poster of $name")
			throw PosterUploadException(err)
		}
	}

	private suspend fun uploadPoster(collectionId: UUID, jpeg: ByteArray) = api.uploadPrimaryImage(collectionId, jpeg)

	// Daily update

	/**
	 * Update the collections of the current user that were not updated today, in the background.
	 * Called when the home screen opens; does nothing on later calls.
	 */
	fun updateInBackground() {
		if (syncStarted) return
		syncStarted = true

		scope.launch {
			// Let the home screen load first
			delay(BACKGROUND_DELAY)
			val user = userId ?: return@launch
			val due = mutex.withLock { readRules() }
				.filter { it.userId == user && System.currentTimeMillis() - it.lastSync > UPDATE_INTERVAL.inWholeMilliseconds }
			if (due.isEmpty()) return@launch

			for ((libraryId, rules) in due.groupBy { it.libraryId }) {
				try {
					val library = api.userLibraryApi.getItem(libraryId).content
					val scan = scan(library, maxAgeMs = SCAN_MAX_AGE.inWholeMilliseconds)
					for (rule in rules) update(rule, scan)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Unable to update the language collections of library $libraryId")
				}
			}
		}
	}

	private companion object {
		const val PAGE_SIZE = 300
		const val IDS_PER_REQUEST = 60
		const val EPISODES_PER_SERIES = 3
		const val SERIES_PARALLEL_REQUESTS = 6
		val SCAN_MAX_AGE = 10.minutes
		val UPDATE_INTERVAL = 20.hours
		val BACKGROUND_DELAY = 30.seconds
	}
}

/** The collection was made but its poster could not be uploaded (needs an administrator) */
class PosterUploadException(cause: Throwable) : Exception(cause)
