package org.jellyfin.androidtv.ui.library.modern

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.collections.CollectionCatalog
import org.jellyfin.androidtv.ui.collections.ImdbTop250
import org.jellyfin.androidtv.ui.home.modern.CardStyle
import org.jellyfin.androidtv.ui.home.modern.HeroSlide
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.ModernHomeRepository
import org.jellyfin.androidtv.ui.home.modern.slim
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.util.Locale
import java.util.UUID

/**
 * Rows of a movie or TV show library, in the spirit of the home screen: continue watching (only
 * that kind), recently added, recommendations from what was watched, liked or put in the
 * watchlist, studios, genres, tags, collections, IMDb Top 250 and seasonal rows (Halloween,
 * Christmas...).
 */
class ModernLibraryRepository(
	private val context: Context,
	private val api: ApiClient,
	private val homeRepository: ModernHomeRepository,
	private val imdbTop250: ImdbTop250,
	private val catalog: CollectionCatalog,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val json = Json { ignoreUnknownKeys = true }
	private val genreMemory = HashMap<String, List<Pair<String, UUID?>>>()

	@Serializable
	private data class CachedGenre(val name: String, val id: String? = null)

	// What each library page showed, for this session: it opens at once while it refreshes
	private val memory = HashMap<UUID, Pair<List<HeroSlide>, List<LibrarySection>>>()
	private var memorySession: String? = null

	private fun session() = "${api.baseUrl}|${api.accessToken}"

	fun cached(libraryId: UUID): Pair<List<HeroSlide>, List<LibrarySection>>? = synchronized(memory) {
		if (memorySession != session()) null else memory[libraryId]
	}

	fun store(libraryId: UUID, hero: List<HeroSlide>, sections: List<LibrarySection>) {
		synchronized(memory) {
			if (memorySession != session()) {
				memory.clear()
				memorySession = session()
			}
			memory[libraryId] = hero to sections
		}
		scope.launch { writePage(libraryId, hero, sections) }
	}

	// What each library page showed the last time the app ran: the first visit after starting the
	// app shows it at once too, while the page refreshes

	@Serializable
	private data class CachedPage(
		/** Server and user (hashed token) it belongs to */
		val owner: String,
		val hero: List<HeroSlide>,
		val sections: List<LibrarySection>,
	)

	private val pageJson = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}
	private val pageMutex = Mutex()

	private fun pageOwner() = "${api.baseUrl}|${api.accessToken?.hashCode()}"
	private fun pageFile(libraryId: UUID) = File(context.cacheDir, "modern_library_$libraryId.json")

	suspend fun savedPage(libraryId: UUID): Pair<List<HeroSlide>, List<LibrarySection>>? = withContext(Dispatchers.IO) {
		pageMutex.withLock {
			try {
				val file = pageFile(libraryId)
				if (!file.exists()) return@withContext null
				val page = pageJson.decodeFromString<CachedPage>(file.readText())
				if (page.owner != pageOwner() || page.sections.isEmpty()) null else page.hero to page.sections
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to read the saved library page")
				null
			}
		}
	}

	private suspend fun writePage(libraryId: UUID, hero: List<HeroSlide>, sections: List<LibrarySection>) = pageMutex.withLock {
		try {
			// Only what the cards need: small and quick to read on a Fire TV
			val page = CachedPage(
				owner = pageOwner(),
				hero = hero.map { it.copy(item = it.item.slim()) },
				sections = sections.map { section -> section.copy(cards = section.cards.map { it.copy(item = it.item.slim()) }) },
			)
			pageFile(libraryId).writeText(pageJson.encodeToString(page))
		} catch (err: Exception) {
			Timber.w(err, "Unable to save the library page")
		}
	}

	/** Media bar: random titles of the library with a backdrop, the ones with a logo first */
	suspend fun hero(libraryId: UUID, kind: LibraryKind): List<HeroSlide> = withContext(Dispatchers.IO) {
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = libraryId,
				includeItemTypes = setOf(kind.itemType),
				recursive = true,
				sortBy = setOf(ItemSortBy.RANDOM),
				imageTypes = setOf(ImageType.BACKDROP),
				fields = setOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS),
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = HERO_CANDIDATES,
			)
		).content.items

		homeRepository.heroSlides(
			items
				.sortedByDescending { it.imageTags?.containsKey(ImageType.LOGO) == true }
				.take(HERO_SLIDES)
				.shuffled()
		)
	}

	/**
	 * The rows of a library in two steps. First the ones at the top of the page (continue watching,
	 * latest, recently added) alone, given to [onPartial]: with every row and the media bar asked at
	 * once the requests queued behind each other (a few per server at a time) and the page took
	 * seconds to show anything. Then the media bar ([onHero], when asked) and the other rows.
	 */
	suspend fun sections(
		libraryId: UUID,
		kind: LibraryKind,
		onPartial: (List<LibrarySection>) -> Unit,
		onHero: ((List<HeroSlide>) -> Unit)? = null,
	): List<LibrarySection> = withContext(Dispatchers.IO) {
		coroutineScope {
			val movies = kind == LibraryKind.MOVIES

			// 1. The top of the page, without the slow parts (next up, series badges)
			val resumeAsync = async { safe("continue") { resumeItems(libraryId, kind) }.orEmpty() }
			// Shows: latest episodes; movies: latest releases, both as 16:9 cards with their badges
			val latestAsync = async {
				if (movies) safe("latest films") { latestFilms(libraryId) }
				else safe("latest episodes") { latestEpisodes(libraryId) }
			}
			val recentAsync = async { safe("recent") { recentlyAdded(libraryId, kind) } }
			val resume = resumeAsync.await()
			val latest = latestAsync.await()
			val recent = recentAsync.await()
			onPartial(listOfNotNull(continueSection(resume, emptyList()), latest, recent))

			// 2. The media bar before the other rows, so it gets a free connection
			val heroAsync = if (onHero != null) async { onHero(safe("hero") { hero(libraryId, kind) }.orEmpty()) } else null
			// The other rows a few at a time: the media bar and the posters keep loading meanwhile
			val permits = Semaphore(PARALLEL_ROWS)
			fun <T> row(block: suspend () -> T) = async { permits.withPermit { block() } }

			val nextUpAsync = row { if (movies) emptyList() else safe("next up") { nextUp(libraryId) }.orEmpty() }
			val genresAsync = row { safe("genres") { genres(libraryId, kind) }.orEmpty() }
			val watchedAsync = row { safe("because watched") { becauseWatched(libraryId, kind) } }
			val imdbAsync = row { if (movies) safe("imdb") { imdb() } else null }
			// Films: their collections; shows: the collections of series (by service, by language...)
			val collectionsAsync = row { safe("collections") { collections(libraryId, kind) } }
			val studioAsync = row { safe("studio") { studio(libraryId, kind) } }
			val likedAsync = row { safe("because liked") { becauseLiked(libraryId, kind) } }
			val spotlightAsync = row { if (movies) safe("collection") { collectionSpotlight() } else null }
			val watchlistAsync = row { safe("because watchlist") { becauseWatchlist(libraryId, kind) } }
			val tagAsync = row { safe("tag") { tagPick(libraryId, kind) } }
			// The genres first, without holding a permit while waiting for them
			val genrePicksAsync = async {
				val genres = genresAsync.await()
				permits.withPermit { safe("genre picks") { genrePicks(libraryId, kind, genres) }.orEmpty() }
			}
			val seasonalAsync = async {
				val genres = genresAsync.await()
				permits.withPermit { safe("seasonal") { seasonal(libraryId, kind, genres) }.orEmpty() }
			}

			val genrePicks = genrePicksAsync.await()
			val sections = listOfNotNull(
				continueSection(resume, nextUpAsync.await()),
				latest,
				recent,
				watchedAsync.await(),
				imdbAsync.await(),
			) + seasonalAsync.await() + listOfNotNull(
				genrePicks.getOrNull(0),
				collectionsAsync.await(),
				studioAsync.await(),
				likedAsync.await(),
				genrePicks.getOrNull(1),
				spotlightAsync.await(),
				watchlistAsync.await(),
				genrePicks.getOrNull(2),
				tagAsync.await(),
			)
			heroAsync?.await()
			sections
		}
	}

	// Rows

	private suspend fun resumeItems(libraryId: UUID, kind: LibraryKind): List<BaseItemDto> =
		api.itemsApi.getResumeItems(
			GetResumeItemsRequest(
				parentId = libraryId,
				includeItemTypes = setOf(if (kind == LibraryKind.MOVIES) BaseItemKind.MOVIE else BaseItemKind.EPISODE),
				mediaTypes = setOf(MediaType.VIDEO),
				fields = CARD_FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = SECTION_LIMIT,
			)
		).content.items

	/** Shows: the next episode of the series being watched (slow on big libraries, comes later) */
	private suspend fun nextUp(libraryId: UUID): List<BaseItemDto> =
		api.tvShowsApi.getNextUp(
			GetNextUpRequest(
				parentId = libraryId,
				fields = CARD_FIELDS,
				imageTypeLimit = 1,
				enableResumable = false,
				enableTotalRecordCount = false,
				limit = SECTION_LIMIT,
			)
		).content.items

	/** Started titles, then the next episodes of the series not already in it */
	private fun continueSection(resume: List<BaseItemDto>, nextUp: List<BaseItemDto>): LibrarySection? {
		val resumedSeries = resume.mapNotNull { it.seriesId }.toSet()
		val next = nextUp.filter { it.seriesId == null || it.seriesId !in resumedSeries }
		val cards = homeRepository.landscapeCards(resume + next, withBadges = true)
		return section("continue", context.getString(R.string.library_continue_watching), CardStyle.LANDSCAPE, cards)
	}

	/** Newest releases of the library (not the last ones added): 16:9 with quality and audio flags */
	private suspend fun latestFilms(libraryId: UUID): LibrarySection? {
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = libraryId,
				includeItemTypes = setOf(BaseItemKind.MOVIE),
				recursive = true,
				sortBy = setOf(ItemSortBy.PREMIERE_DATE),
				sortOrder = setOf(SortOrder.DESCENDING),
				maxPremiereDate = LocalDateTime.now(),
				fields = CARD_FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = SECTION_LIMIT,
			)
		).content.items
		// Opening a movie shows its page, it doesn't start playing
		val cards = homeRepository.landscapeCards(items, withBadges = true).map { it.copy(playOnClick = false, progress = null, remainingMinutes = null) }
		return section("latest_films", context.getString(R.string.library_latest_films), CardStyle.LANDSCAPE, cards)
	}

	private suspend fun latestEpisodes(libraryId: UUID): LibrarySection? {
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = libraryId,
				includeItemTypes = setOf(BaseItemKind.EPISODE),
				recursive = true,
				isMissing = false,
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
				fields = CARD_FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = SECTION_LIMIT,
			)
		).content.items
		// Opening an episode shows its page, it doesn't start playing
		val cards = homeRepository.landscapeCards(items, withBadges = true).map { it.copy(playOnClick = false, progress = null, remainingMinutes = null) }
		return section("latest_episodes", context.getString(R.string.library_latest_episodes), CardStyle.LANDSCAPE, cards)
	}

	private suspend fun recentlyAdded(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val items = query(libraryId, kind) {
			copy(sortBy = setOf(ItemSortBy.DATE_CREATED), sortOrder = setOf(SortOrder.DESCENDING))
		}
		return posterSection(
			key = "recent",
			title = context.getString(R.string.library_recently_added),
			items = items,
			seeAll = BrowseFilter(title = context.getString(R.string.library_recently_added), libraryId = libraryId, kind = kind, sort = BrowseSort.DATE_ADDED),
		)
	}

	/** Like a title watched lately */
	private suspend fun becauseWatched(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val seed = if (kind == LibraryKind.MOVIES) {
			query(libraryId, kind, limit = SEED_CANDIDATES, fields = emptySet()) {
				copy(filters = setOf(ItemFilter.IS_PLAYED), sortBy = setOf(ItemSortBy.DATE_PLAYED), sortOrder = setOf(SortOrder.DESCENDING))
			}.randomOrNull()?.let { it.id to it.name.orEmpty() }
		} else {
			// Series of the episodes watched lately
			api.itemsApi.getItems(
				GetItemsRequest(
					parentId = libraryId,
					includeItemTypes = setOf(BaseItemKind.EPISODE),
					recursive = true,
					filters = setOf(ItemFilter.IS_PLAYED),
					sortBy = setOf(ItemSortBy.DATE_PLAYED),
					sortOrder = setOf(SortOrder.DESCENDING),
					enableImages = false,
					enableTotalRecordCount = false,
					limit = SEED_CANDIDATES * 4,
				)
			).content.items
				.mapNotNull { episode -> episode.seriesId?.let { it to episode.seriesName.orEmpty() } }
				.distinctBy { it.first }
				.take(SEED_CANDIDATES)
				.randomOrNull()
		} ?: return null

		return similarSection("because_watched", seed.first, context.getString(R.string.library_because_watched, seed.second))
	}

	/** Like a favorite */
	private suspend fun becauseLiked(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val seed = query(libraryId, kind, limit = 1, fields = emptySet()) {
			copy(isFavorite = true, sortBy = setOf(ItemSortBy.RANDOM))
		}.firstOrNull() ?: return null
		return similarSection("because_liked", seed.id, context.getString(R.string.library_because_liked, seed.name.orEmpty()))
	}

	/** Like something in the watchlist ("Likes", same as the web watchlist) */
	private suspend fun becauseWatchlist(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val seed = query(libraryId, kind, limit = 1, fields = emptySet()) {
			copy(filters = setOf(ItemFilter.LIKES), sortBy = setOf(ItemSortBy.RANDOM))
		}.firstOrNull() ?: return null
		return similarSection("because_watchlist", seed.id, context.getString(R.string.library_because_watchlist, seed.name.orEmpty()))
	}

	private suspend fun similarSection(key: String, itemId: UUID, title: String): LibrarySection? {
		val items = api.libraryApi.getSimilarItems(
			itemId = itemId,
			limit = SECTION_LIMIT,
			fields = CARD_FIELDS,
		).content.items
		return posterSection(key, title, items)
	}

	/** A studio (network for shows) that made several of the best rated titles */
	private suspend fun studio(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val studios = query(libraryId, kind, limit = STUDIO_CANDIDATES, fields = setOf(ItemFields.STUDIOS)) {
			copy(sortBy = setOf(ItemSortBy.COMMUNITY_RATING), sortOrder = setOf(SortOrder.DESCENDING), enableImages = false)
		}
			.flatMap { it.studios.orEmpty() }
			.filter { !it.name.isNullOrBlank() }
			.groupingBy { it.id to it.name.orEmpty() }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(STUDIO_CHOICES)
		val (studioId, studioName) = studios.randomOrNull()?.key ?: return null

		val movies = kind == LibraryKind.MOVIES
		val items = query(libraryId, kind) {
			copy(
				studioIds = setOf(studioId),
				sortBy = setOf(if (movies) ItemSortBy.COMMUNITY_RATING else ItemSortBy.RANDOM),
				sortOrder = setOf(SortOrder.DESCENDING),
			)
		}
		val title = context.getString(if (movies) R.string.library_top_rated_from else R.string.library_shows_from, studioName)
		return posterSection("studio", title, items)
	}

	/** A popular tag of the library ("Based on a true story", "Time travel"...) */
	private suspend fun tagPick(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val tag = popularTags(libraryId, kind).take(TAG_CHOICES).randomOrNull() ?: return null
		val items = query(libraryId, kind) { copy(tags = setOf(tag), sortBy = setOf(ItemSortBy.RANDOM)) }
		val title = tag.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
		return posterSection("tag", title, items, seeAll = BrowseFilter(title = title, libraryId = libraryId, kind = kind, tag = tag))
	}

	private suspend fun imdb(): LibrarySection? {
		val items = imdbTop250.rankedMovies(CARD_FIELDS).take(LONG_SECTION_LIMIT)
		return posterSection("imdb", ImdbTop250.NAME, items)
	}

	/**
	 * Collections of the kind of the library: in films all but those with only series, in TV shows
	 * those with series (known from the last look into the collections, it doesn't wait for it).
	 */
	private suspend fun collections(libraryId: UUID, kind: LibraryKind): LibrarySection? {
		val kinds = catalog.cached()
		val shows = kind == LibraryKind.SHOWS
		if (shows && kinds.withSeries.isEmpty()) return null
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.BOX_SET),
				recursive = true,
				ids = if (shows) kinds.withSeries.take(MAX_COLLECTION_IDS) else null,
				excludeItemIds = if (shows) null else kinds.seriesOnly.take(MAX_COLLECTION_IDS).ifEmpty { null },
				sortBy = setOf(ItemSortBy.RANDOM),
				fields = setOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = SECTION_LIMIT,
			)
		).content.items
		val title = context.getString(R.string.library_collections)
		val seeAll = BrowseFilter(title = title, libraryId = libraryId, kind = kind, collections = true, collectionsOf = kind, sort = BrowseSort.NAME)
		return posterSection("collections", title, items, seeAll = seeAll)
	}

	/** The movies of a random collection, with its name */
	private suspend fun collectionSpotlight(): LibrarySection? {
		val collection = api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.BOX_SET),
				recursive = true,
				// Not a collection of series in the films
				excludeItemIds = catalog.cached().seriesOnly.take(MAX_COLLECTION_IDS).ifEmpty { null },
				sortBy = setOf(ItemSortBy.RANDOM),
				enableImages = false,
				enableTotalRecordCount = false,
				limit = 1,
			)
		).content.items.firstOrNull() ?: return null

		val items = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = collection.id,
				sortBy = setOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR),
				fields = CARD_FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = SECTION_LIMIT,
			)
		).content.items
		// One movie is not much of a row
		if (items.size < 2) return null
		return posterSection("collection_${collection.id}", collection.name.orEmpty(), items)
	}

	/** Two or three genres of the library, the well-known ones with their own title */
	private suspend fun genrePicks(libraryId: UUID, kind: LibraryKind, genres: List<BrowseChip>): List<LibrarySection> = coroutineScope {
		val movies = kind == LibraryKind.MOVIES
		val (curated, others) = genres.partition { movies && curatedTitle(it.name) != null }
		val picks = (curated.shuffled() + others.shuffled()).take(GENRE_PICKS)

		picks.map { genre ->
			async {
				safe("genre ${genre.name}") {
					val genreIds = genre.ids.toSet().ifEmpty { return@safe null }
					// "Worth your time": well rated first, any rating when there are few
					var items = query(libraryId, kind) {
						copy(genreIds = genreIds, minCommunityRating = GOOD_RATING, sortBy = setOf(ItemSortBy.RANDOM))
					}
					if (items.size < MIN_SECTION_SIZE) {
						items = query(libraryId, kind) { copy(genreIds = genreIds, sortBy = setOf(ItemSortBy.RANDOM)) }
					}

					val title = curatedTitle(genre.name)?.let { context.getString(it) }
						?: context.getString(if (movies) R.string.library_top_genre_movies else R.string.library_top_genre_shows, genre.name)
					posterSection(
						key = "genre_${genre.name}",
						title = title,
						items = items,
						seeAll = BrowseFilter(title = genre.name, libraryId = libraryId, kind = kind, genre = genre.name, genreNames = genre.names),
					)
				}
			}
		}.mapNotNull { it.await() }
	}

	/** Rows of the time of the year: Halloween, Christmas, Valentine's day, summer */
	private suspend fun seasonal(libraryId: UUID, kind: LibraryKind, genres: List<BrowseChip>): List<LibrarySection> = coroutineScope {
		val today = MonthDay.from(LocalDate.now())
		val movies = kind == LibraryKind.MOVIES
		fun genre(style: GenreStyle) = genres.firstOrNull { GenreStyle.of(it.name) == style }

		val rows = mutableListOf<Deferred<LibrarySection?>>()
		fun add(block: suspend () -> LibrarySection?) {
			rows += async { safe("seasonal") { block() } }
		}

		when {
			// October
			today.monthValue == 10 -> {
				if (movies) {
					add { themed(libraryId, kind, "halloween", R.string.library_halloween_movies, listOf("halloween"), listOf("Halloween")) }
					genre(GenreStyle.HORROR)?.let { horror -> add { genreRow(libraryId, kind, "horror", R.string.library_horror_movies, horror) } }
					genre(GenreStyle.THRILLER)?.let { thriller -> add { genreRow(libraryId, kind, "thriller", R.string.library_thriller_movies, thriller) } }
				} else {
					add { episodesNamed(libraryId, "halloween_episodes", R.string.library_halloween_episodes, listOf("Halloween")) }
				}
			}

			// December 1 to January 6
			today.monthValue == 12 || (today.monthValue == 1 && today.dayOfMonth <= 6) -> {
				if (movies) {
					add { themed(libraryId, kind, "christmas", R.string.library_christmas_movies, listOf("christmas", "navidad"), listOf("Christmas", "Navidad")) }
					genre(GenreStyle.FAMILY)?.let { family -> add { genreRow(libraryId, kind, "holidays", R.string.library_holiday_family, family) } }
				} else {
					add { episodesNamed(libraryId, "christmas_episodes", R.string.library_christmas_episodes, listOf("Christmas", "Navidad")) }
				}
			}

			// February 1 to 14
			today.monthValue == 2 && today.dayOfMonth <= 14 -> {
				genre(GenreStyle.ROMANCE)?.let { romance -> add { genreRow(libraryId, kind, "valentine", R.string.library_valentine, romance) } }
			}

			// July and August
			today.monthValue == 7 || today.monthValue == 8 -> {
				genre(GenreStyle.ACTION)?.let { action -> add { genreRow(libraryId, kind, "summer", R.string.library_summer, action) } }
			}
		}

		rows.mapNotNull { it.await() }
	}

	/** Titles with one of [tags] (keywords from TMDb) or with one of [words] in their name */
	private suspend fun themed(
		libraryId: UUID,
		kind: LibraryKind,
		key: String,
		title: Int,
		tags: List<String>,
		words: List<String>,
	): LibrarySection? = coroutineScope {
		val tagged = async { query(libraryId, kind) { copy(tags = tags.toSet(), sortBy = setOf(ItemSortBy.RANDOM)) } }
		val named = words.map { word ->
			async { safe("search $word") { query(libraryId, kind) { copy(searchTerm = word) } }.orEmpty() }
		}
		val items = (tagged.await() + named.flatMap { it.await() }).distinctBy { it.id }.take(SECTION_LIMIT)
		posterSection(key, context.getString(title), items)
	}

	private suspend fun genreRow(libraryId: UUID, kind: LibraryKind, key: String, title: Int, genre: BrowseChip): LibrarySection? {
		val genreIds = genre.ids.toSet().ifEmpty { return null }
		val items = query(libraryId, kind) {
			copy(genreIds = genreIds, sortBy = setOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.RANDOM), sortOrder = setOf(SortOrder.DESCENDING))
		}
		return posterSection(key, context.getString(title), items.shuffled())
	}

	/** Episodes with one of [words] in their name ("Halloween", "Christmas") */
	private suspend fun episodesNamed(libraryId: UUID, key: String, title: Int, words: List<String>): LibrarySection? = coroutineScope {
		val items = words.map { word ->
			async {
				safe("episodes $word") {
					api.itemsApi.getItems(
						GetItemsRequest(
							parentId = libraryId,
							includeItemTypes = setOf(BaseItemKind.EPISODE),
							recursive = true,
							searchTerm = word,
							isMissing = false,
							fields = CARD_FIELDS,
							imageTypeLimit = 1,
							enableTotalRecordCount = false,
							limit = SECTION_LIMIT,
						)
					).content.items
				}.orEmpty()
			}
		}.flatMap { it.await() }.distinctBy { it.id }.shuffled().take(SECTION_LIMIT)

		val cards = homeRepository.landscapeCards(items, withBadges = true).map { it.copy(playOnClick = false, progress = null, remainingMinutes = null) }
		section(key, context.getString(title), CardStyle.LANDSCAPE, cards)
	}

	// Lists shared with the browse page and search

	/**
	 * Genres of a library (every library when null), one per genre in the language of the app.
	 * The server is slow to list them: they are kept for the session, and the list of every
	 * genre (search page) also on disk.
	 */
	suspend fun genres(libraryId: UUID?, kind: LibraryKind?, refresh: Boolean = false): List<BrowseChip> = withContext(Dispatchers.IO) {
		val key = genresKey(libraryId, kind)
		if (!refresh) synchronized(genreMemory) { genreMemory[key] }?.let { return@withContext GenreStyle.merge(it, spanish()) }

		val raw = api.genresApi.getGenres(
			parentId = libraryId,
			includeItemTypes = kind?.let { setOf(it.itemType) } ?: setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			sortBy = setOf(ItemSortBy.SORT_NAME),
			enableImages = false,
			enableTotalRecordCount = false,
		).content.items
			.filter { !it.name.isNullOrBlank() }
			.map { it.name.orEmpty() to it.id }

		synchronized(genreMemory) { genreMemory[key] = raw }
		if (libraryId == null && kind == null) writeGenresFile(raw)
		GenreStyle.merge(raw, spanish())
	}

	/** Genres known already (this session, or from disk for every library), shown at once */
	fun cachedGenres(libraryId: UUID?, kind: LibraryKind?): List<BrowseChip>? {
		val key = genresKey(libraryId, kind)
		val raw = synchronized(genreMemory) { genreMemory[key] }
			?: if (libraryId == null && kind == null) readGenresFile()?.also { synchronized(genreMemory) { genreMemory[key] = it } } else null
		return raw?.let { GenreStyle.merge(it, spanish()) }
	}

	/** Loads the list of every genre in the background, so the search page has it at once */
	fun preloadGenres() {
		scope.launch {
			delay(PRELOAD_DELAY)
			try {
				genres(null, null, refresh = true)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to preload genres")
			}
		}
	}

	private fun genresKey(libraryId: UUID?, kind: LibraryKind?) = "${api.baseUrl}|$libraryId|$kind"

	private fun spanish() = Locale.getDefault().language == "es"

	private val genresFile get() = File(context.cacheDir, "modern_genres_${api.baseUrl.hashCode()}.json")

	private fun readGenresFile(): List<Pair<String, UUID?>>? = try {
		val file = genresFile
		if (!file.exists()) null
		else json.decodeFromString<List<CachedGenre>>(file.readText()).map { it.name to it.id?.toUUIDOrNull() }
	} catch (err: Exception) {
		Timber.w(err, "Unable to read the genres cache")
		null
	}

	private fun writeGenresFile(genres: List<Pair<String, UUID?>>) = try {
		genresFile.writeText(json.encodeToString(genres.map { (name, id) -> CachedGenre(name, id?.toString()) }))
	} catch (err: Exception) {
		Timber.w(err, "Unable to write the genres cache")
	}

	/**
	 * The most used tags, counted on a random sample of titles (there is no count in the API and
	 * a library can have thousands of tags).
	 */
	suspend fun popularTags(libraryId: UUID?, kind: LibraryKind?): List<String> = withContext(Dispatchers.IO) {
		api.itemsApi.getItems(
			GetItemsRequest(
				parentId = libraryId,
				includeItemTypes = kind?.let { setOf(it.itemType) } ?: setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				recursive = true,
				sortBy = setOf(ItemSortBy.RANDOM),
				fields = setOf(ItemFields.TAGS),
				enableImages = false,
				enableUserData = false,
				enableTotalRecordCount = false,
				limit = TAG_SAMPLE,
			)
		).content.items
			.flatMap { it.tags.orEmpty() }
			.filter { it.isNotBlank() }
			.groupingBy { it.lowercase() to it }
			.eachCount()
			.entries
			.groupBy { it.key.first }
			.map { (_, entries) -> entries.first().key.second to entries.sumOf { it.value } }
			.filter { (_, count) -> count >= MIN_TAG_COUNT }
			.sortedByDescending { (_, count) -> count }
			.take(MAX_TAGS)
			.map { (tag, _) -> tag }
	}

	// Helpers

	private suspend fun query(
		libraryId: UUID,
		kind: LibraryKind,
		limit: Int = SECTION_LIMIT,
		fields: Set<ItemFields> = CARD_FIELDS,
		change: GetItemsRequest.() -> GetItemsRequest,
	): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			parentId = libraryId,
			includeItemTypes = setOf(kind.itemType),
			recursive = true,
			fields = fields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = limit,
		).change()
	).content.items

	private suspend fun posterSection(key: String, title: String, items: List<BaseItemDto>, seeAll: BrowseFilter? = null): LibrarySection? {
		if (items.isEmpty()) return null
		// Series badges need a request per series: the ones known this session show now, the
		// others come for every row at once afterwards (withSeriesBadges)
		val cards = homeRepository.posterCards(items, lookupSeriesMedia = false, withNewContentLabels = true)
		return section(key, title, CardStyle.POSTER, cards, seeAll, items)
	}

	private fun section(
		key: String,
		title: String,
		style: CardStyle,
		cards: List<HomeCard>,
		seeAll: BrowseFilter? = null,
		items: List<BaseItemDto> = emptyList(),
	): LibrarySection? =
		if (cards.isEmpty()) null else LibrarySection(key, title, style, cards.distinctBy { it.key }, seeAll, items)

	/**
	 * Quality, HDR, Dolby and audio flags on every series poster: their badges come from one of
	 * their episodes, looked up here for all the rows at once (a few at a time), then the rows with
	 * series are built again.
	 */
	suspend fun withSeriesBadges(sections: List<LibrarySection>): List<LibrarySection> = withContext(Dispatchers.IO) {
		val seriesIds = sections.flatMap { it.items }.filter { it.type == BaseItemKind.SERIES }.map { it.id }.distinct()
		if (seriesIds.isEmpty()) return@withContext sections

		homeRepository.prefetchSeriesMedia(seriesIds)
		sections.map { section ->
			if (section.style != CardStyle.POSTER || section.items.none { it.type == BaseItemKind.SERIES }) section
			else {
				val cards = homeRepository.posterCards(section.items, lookupSeriesMedia = false, withNewContentLabels = true)
				if (cards.isEmpty()) section else section.copy(cards = cards.distinctBy { it.key })
			}
		}
	}

	/** Movie rows with their own title, by genre */
	private fun curatedTitle(genre: String): Int? = when (GenreStyle.of(genre)) {
		GenreStyle.ACTION -> R.string.library_genre_action
		GenreStyle.DRAMA -> R.string.library_genre_drama
		GenreStyle.COMEDY -> R.string.library_genre_comedy
		GenreStyle.HORROR -> R.string.library_genre_horror
		GenreStyle.SCIFI -> R.string.library_genre_scifi
		GenreStyle.ANIMATION -> R.string.library_genre_animation
		GenreStyle.THRILLER -> R.string.library_genre_thriller
		GenreStyle.ROMANCE -> R.string.library_genre_romance
		GenreStyle.DOCUMENTARY -> R.string.library_genre_documentary
		GenreStyle.CRIME -> R.string.library_genre_crime
		GenreStyle.FANTASY -> R.string.library_genre_fantasy
		GenreStyle.ADVENTURE -> R.string.library_genre_adventure
		GenreStyle.FAMILY -> R.string.library_genre_family
		GenreStyle.WAR -> R.string.library_genre_war
		GenreStyle.MYSTERY -> R.string.library_genre_mystery
		else -> null
	}

	private inline fun <T> safe(name: String, block: () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load library row: $name")
		null
	}

	private companion object {
		const val HERO_SLIDES = 8
		const val HERO_CANDIDATES = 20
		const val SECTION_LIMIT = 20
		const val MAX_COLLECTION_IDS = 150
		/** Rows loading at the same time after the first ones */
		const val PARALLEL_ROWS = 3
		const val LONG_SECTION_LIMIT = 40
		const val SEED_CANDIDATES = 8
		const val STUDIO_CANDIDATES = 80
		const val STUDIO_CHOICES = 6
		const val TAG_CHOICES = 15
		const val TAG_SAMPLE = 300
		const val MIN_TAG_COUNT = 2
		const val MAX_TAGS = 40
		const val GENRE_PICKS = 3
		const val GOOD_RATING = 7.0
		const val MIN_SECTION_SIZE = 6
		val PRELOAD_DELAY = 20.seconds

		val CARD_FIELDS = setOf(
			ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
			ItemFields.DATE_CREATED,
			ItemFields.MEDIA_STREAMS,
			ItemFields.PROVIDER_IDS,
		)
	}
}
