package org.jellyfin.androidtv.ui.home.modern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.PlaybackStopped
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
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
import java.time.LocalDateTime
import java.util.UUID

/**
 * Loads everything the modern home screen needs from the Jellyfin server (and optionally TMDB).
 */
/** Jellyfin's defaults: under 5 % the position isn't kept, from 90 % on the title is watched */
internal const val MIN_RESUME_FRACTION = 0.05
internal const val MAX_RESUME_FRACTION = 0.90

class ModernHomeRepository(
	private val api: ApiClient,
	private val tmdb: TmdbTrendingClient,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) {
	private data class TrendingCache(val key: String, val time: Long, val refs: List<TmdbTrendingClient.TmdbRef>)

	/** TMDB's trending lists, by kind (all, movies, TV shows) */
	private val trendingCache = HashMap<String, TrendingCache>()

	private data class MostWatchedCache(val server: String?, val time: Long, val ids: List<UUID>)

	/** Movies and series with their TMDB and IMDb ids (of a library, or of all), for the trends */
	private data class LibraryTitlesCache(val server: String?, val time: Long, val titles: List<BaseItemDto>)
	private val libraryTitlesCache = HashMap<String, LibraryTitlesCache>()

	@Volatile
	private var mostWatchedCache: MostWatchedCache? = null

	/** Quality and audio languages of a series, taken from one of its episodes */
	private data class SeriesMedia(val quality: MediaQuality?, val languages: List<LanguageBadge>)

	private data class LabelsCache(val session: String, val time: Long, val labels: Map<UUID, NewContentLabel>)

	@Volatile
	private var labelsCache: LabelsCache? = null
	private val labelsMutex = Mutex()
	private val lookupSemaphore = Semaphore(PARALLEL_LOOKUPS)

	/** Media info per series, it rarely changes so it's kept for the whole session */
	private val seriesMediaCache = HashMap<UUID, SeriesMedia>()
	private var seriesMediaServer: String? = null

	// Hero carousel

	suspend fun loadHero(): List<HeroSlide> = withContext(Dispatchers.IO) {
		// Both at the same time: the hero is the first thing on screen
		val hiddenAsync = async { hiddenItemIds() }
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				recursive = true,
				sortBy = setOf(ItemSortBy.RANDOM),
				imageTypes = setOf(ImageType.BACKDROP),
				fields = setOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS, ItemFields.PROVIDER_IDS),
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = HERO_CANDIDATES,
			)
		).content.items.withoutHidden(hiddenAsync.await())

		// Prefer items with a logo, they look a lot better in the hero
		items
			.sortedByDescending { it.imageTags?.containsKey(ImageType.LOGO) == true }
			.take(HERO_SLIDES)
			.shuffled()
			.map { it.toHeroSlide() }
	}

	/**
	 * Find what to play when pressing "Play" on a hero slide: series start at their next up episode.
	 */
	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = withContext(Dispatchers.IO) {
		if (item.type != BaseItemKind.SERIES) return@withContext item.id

		val nextUp = runCatching {
			api.tvShowsApi.getNextUp(
				GetNextUpRequest(
					seriesId = item.id,
					limit = 1,
					enableTotalRecordCount = false,
				)
			).content.items.firstOrNull()
		}.getOrNull()

		nextUp?.id ?: runCatching {
			api.tvShowsApi.getEpisodes(
				seriesId = item.id,
				isMissing = false,
				limit = 1,
			).content.items.firstOrNull()?.id
		}.getOrNull()
	}

	/**
	 * Everything in the watchlist, for the watchlist page.
	 */
	suspend fun loadWatchlist(): List<HomeCard> = withContext(Dispatchers.IO) {
		val items = watchlist(limit = WATCHLIST_PAGE_LIMIT).withoutHidden(hiddenItemIds())
		val seriesIds = items.filter { it.type == BaseItemKind.SERIES }.map { it.id }
		val context = CardContext(
			labels = emptyMap(),
			seriesMedia = safe("series media") { seriesMedia(seriesIds, emptyMap()) }.orEmpty(),
			episodeHints = emptyMap(),
			nextUpBySeries = emptyMap(),
		)

		items
			.distinctBy { MediaBadges.duplicateKey(it) }
			.map { posterCard(it, context, withReleaseDate = false) }
	}

	/**
	 * Poster cards with every badge (quality, HDR, Dolby, audio languages) for any list of
	 * movies, series, episodes or people, e.g. search results. Items need `ItemFields.MEDIA_STREAMS`.
	 */
	suspend fun posterCards(
		items: List<BaseItemDto>,
		lookupSeriesMedia: Boolean = true,
		/** "New episode", "New season", "New series" on series, like on Home */
		withNewContentLabels: Boolean = false,
	): List<HomeCard> = withContext(Dispatchers.IO) {
		val visible = items.withoutHidden(hiddenItemIds())
		val seriesIds = visible.filter { it.type == BaseItemKind.SERIES }.map { it.id }
		val context = CardContext(
			labels = if (withNewContentLabels && seriesIds.isNotEmpty()) newContentLabels() else emptyMap(),
			// Badges of series come from one of their episodes: without lookups (many rows at once)
			// only the series already known this session have them
			seriesMedia = if (lookupSeriesMedia) safe("series media") { seriesMedia(seriesIds, emptyMap()) }.orEmpty()
			else cachedSeriesMedia(),
			episodeHints = emptyMap(),
			nextUpBySeries = emptyMap(),
		)

		visible
			.distinctBy { if (it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES) MediaBadges.duplicateKey(it) else it.id.toString() }
			.map { item ->
				val card = posterCard(item, context, withReleaseDate = false)
				// Episodes: the series page, the play button plays the episode
				if (item.type == BaseItemKind.EPISODE) card.copy(key = item.id.toString(), subtitle = item.name) else card
			}
	}

	/**
	 * "New episode/season/series" of each series, from the episodes added lately (the same as on
	 * Home). Kept a few minutes; several rows asking at once share one request.
	 */
	suspend fun newContentLabels(): Map<UUID, NewContentLabel> = withContext(Dispatchers.IO) {
		labelsMutex.withLock {
			val session = cacheKey()
			labelsCache?.takeIf { it.session == session && System.currentTimeMillis() - it.time < LABELS_TTL_MS }?.let { return@withContext it.labels }
			val labels = safe("new content labels") { MediaBadges.newContentLabels(airedEpisodes()) } ?: return@withContext emptyMap()
			labelsCache = LabelsCache(session, System.currentTimeMillis(), labels)
			labels
		}
	}

	/**
	 * Looks up the quality and audio languages of many series at once (one of their episodes each),
	 * so the cards built afterwards with `lookupSeriesMedia = false` have their badges.
	 */
	suspend fun prefetchSeriesMedia(seriesIds: List<UUID>) {
		withContext(Dispatchers.IO) { safe("series media") { seriesMedia(seriesIds.distinct(), emptyMap(), maxLookups = MAX_PREFETCH_LOOKUPS) } }
	}

	/** Quality and audio languages of series, for the browse grid */
	suspend fun seriesBadges(seriesIds: List<UUID>): Map<UUID, Pair<MediaQuality?, List<LanguageBadge>>> = withContext(Dispatchers.IO) {
		safe("series badges") { seriesMedia(seriesIds.distinct(), emptyMap(), maxLookups = MAX_PREFETCH_LOOKUPS) }
			.orEmpty()
			.mapValues { (_, media) -> media.quality to media.languages }
	}

	/** Media bar slides for any movies or series (the library pages) */
	fun heroSlides(items: List<BaseItemDto>): List<HeroSlide> = items.map { it.toHeroSlide() }

	/** 16:9 cards with progress, like "Continue watching"; [withBadges]: quality and audio flags too */
	fun landscapeCards(items: List<BaseItemDto>, withBadges: Boolean = false): List<HomeCard> = items.map { item ->
		val card = landscapeCard(item)
		if (withBadges) card.copy(showBadges = true, languages = MediaBadges.audioLanguages(item)) else card
	}

	/** Identifies the server and session for the home cache */
	fun cacheKey(): String = "${api.baseUrl}|${api.accessToken?.hashCode()}"

	suspend fun setPlayed(itemId: UUID) = withContext(Dispatchers.IO) {
		api.playStateApi.markPlayedItem(itemId)
		Unit
	}

	// Rows

	/**
	 * Load all rows. [onPartial] receives the rows as soon as the quick ones are ready (without
	 * "Trending this week" and the series badges), so the first start shows content sooner.
	 */
	suspend fun loadSections(onPartial: ((List<HomeSection>) -> Unit)? = null): List<HomeSection> = withContext(Dispatchers.IO) {
		// Titles of hidden libraries (e.g. the same movies with Spanish metadata) never show up on the home screen
		val hidden = hiddenItemIds()

		coroutineScope {
			val recentEpisodesAsync = async { safe("recent episodes") { recentEpisodes() }.orEmpty().withoutHidden(hidden) }
			// New episode / season / series: what came out lately, not what was added lately
			val airedEpisodesAsync = async { safe("aired episodes") { airedEpisodes() }.orEmpty().withoutHidden(hidden) }
			val nextUpAsync = async {
				safe("next up") { nextUpBySeries() }.orEmpty().filterKeys { it !in hidden }
			}
			val continueWatchingAsync = async { safe("continue watching") { continueWatching() }.orEmpty().withoutHidden(hidden) }
			// The slowest row (plays of every user), started first and added last
			val trendingAsync = async { safe("trending") { trending(hidden) }.orEmpty() }
			val latestEpisodesAsync = async { safe("latest episodes") { latestEpisodes() }.orEmpty().withoutHidden(hidden) }
			val latestFilmsAsync = async { safe("latest films") { latestFilms() }.orEmpty().withoutHidden(hidden) }
			val recentMoviesAsync = async { safe("recent movies") { recentMovies() }.orEmpty().withoutHidden(hidden) }
			val favoritesAsync = async { safe("favorites") { favorites() }.orEmpty().withoutHidden(hidden) }
			val watchlistAsync = async { safe("watchlist") { watchlist() }.orEmpty().withoutHidden(hidden) }

			// New episode/season labels and recently added shows are derived from the latest added episodes
			val recentEpisodes = recentEpisodesAsync.await()
			val labels = MediaBadges.newContentLabels(airedEpisodesAsync.await())
			labelsCache = LabelsCache(cacheKey(), System.currentTimeMillis(), labels)
			val newestEpisodeBySeries = LinkedHashMap<UUID, BaseItemDto>()
			for (episode in recentEpisodes) {
				val seriesId = episode.seriesId ?: continue
				if (!newestEpisodeBySeries.containsKey(seriesId)) newestEpisodeBySeries[seriesId] = episode
			}
			val recentSeriesIds = newestEpisodeBySeries.keys.take(SECTION_LIMIT)

			val recentShowsAsync = async { safe("recent shows") { itemsByIds(recentSeriesIds, CARD_FIELDS) }.orEmpty() }

			val continueWatching = continueWatchingAsync.await()
			val latestEpisodes = latestEpisodesAsync.await()
			val latestFilms = latestFilmsAsync.await()
			val recentMovies = recentMoviesAsync.await()
			val recentShows = recentShowsAsync.await()
			val favorites = favoritesAsync.await()
			val watchlist = watchlistAsync.await()
			val nextUpBySeries = nextUpAsync.await()
			// Episode shown on a focused series: the next one to watch, otherwise the newest one
			val episodeHints = newestEpisodeBySeries + nextUpBySeries

			fun build(trending: List<BaseItemDto>, seriesMedia: Map<UUID, SeriesMedia>): List<HomeSection> {
				val cardContext = CardContext(
					labels = labels,
					seriesMedia = seriesMedia,
					episodeHints = episodeHints,
					nextUpBySeries = nextUpBySeries,
				)

				fun posters(items: List<BaseItemDto>, withReleaseDate: Boolean = false) = items
					// The same movie/show can exist in several libraries (e.g. one per language)
					.distinctBy { MediaBadges.duplicateKey(it) }
					.map { posterCard(it, cardContext, withReleaseDate) }
					.distinctBy { it.key }

				return listOfNotNull(
					// Quality, HDR, Dolby Vision and the audio flags, like on the library pages
					section(HomeSectionType.CONTINUE_WATCHING, CardStyle.LANDSCAPE, landscapeCards(continueWatching, withBadges = true).distinctBy { it.key }),
					section(HomeSectionType.WATCHLIST, CardStyle.POSTER, posters(watchlist)),
					section(HomeSectionType.TRENDING, CardStyle.RANKED, posters(trending).take(RANKED_LIMIT)),
					section(HomeSectionType.LATEST_EPISODES, CardStyle.POSTER, posters(latestEpisodes, withReleaseDate = true)),
					section(HomeSectionType.LATEST_FILMS, CardStyle.POSTER, posters(latestFilms, withReleaseDate = true)),
					section(HomeSectionType.RECENT_MOVIES, CardStyle.POSTER, posters(recentMovies)),
					section(HomeSectionType.RECENT_SHOWS, CardStyle.POSTER, posters(recentShows)),
					section(HomeSectionType.FAVORITES, CardStyle.POSTER, posters(favorites)),
				)
			}

			// Quick rows first, with the badges already known (cached series)
			if (onPartial != null && !trendingAsync.isCompleted) {
				onPartial(build(trending = emptyList(), seriesMedia = cachedSeriesMedia()))
			}

			val trending = trendingAsync.await()

			// Series have no media streams themselves: quality and languages come from one of their episodes
			val posterSeriesIds = (watchlist + trending + recentShows + favorites)
				.filter { it.type == BaseItemKind.SERIES }
				.map { it.id }
				.distinct()
			val seriesMedia = safe("series media") { seriesMedia(posterSeriesIds, episodeHints) }.orEmpty()

			build(trending, seriesMedia)
		}
	}

	/**
	 * Only the "Continue watching" row, quick: after watching something it shows the new position
	 * without waiting for the other rows (the slowest one takes seconds). Null when it is empty.
	 */
	suspend fun continueWatchingSection(): HomeSection? = withContext(Dispatchers.IO) {
		val hidden = hiddenItemIds()
		val items = continueWatching().withoutHidden(hidden)
		section(HomeSectionType.CONTINUE_WATCHING, CardStyle.LANDSCAPE, landscapeCards(items, withBadges = true).distinctBy { it.key })
	}

	/**
	 * "Continue watching" right after a video stopped, before the server has it (it can take
	 * seconds): the title goes first with its new position, or leaves the row once watched, with the
	 * next episode of the player queue in its place. One card per series. Null when nothing changes.
	 *
	 * @param addNew also titles that were not in the row (the home screen; a library page only
	 *   changes its own titles, it doesn't know the library of the others)
	 */
	fun continueAfterStop(cards: List<HomeCard>, stopped: PlaybackStopped, addNew: Boolean): List<HomeCard>? {
		val item = stopped.item
		if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.EPISODE) return null
		val runtime = item.runTimeTicks?.takeIf { it > 0 } ?: return null
		val fraction = stopped.positionTicks.toDouble() / runtime
		// Under 5 % (Jellyfin's minimum) the server doesn't keep the position: its answer decides
		if (fraction < MIN_RESUME_FRACTION) return null

		val seriesId = item.seriesId
		fun sameTitle(card: HomeCard) = card.item.id == item.id || (seriesId != null && card.item.seriesId == seriesId)
		val existing = cards.firstOrNull { it.item.id == item.id }
		if (!addNew && cards.none(::sameTitle)) return null

		val front = if (fraction >= MAX_RESUME_FRACTION) {
			// Watched (past 90 %, like the server): the next episode, without progress
			stopped.next?.let { next -> landscapeCards(listOf(next), withBadges = true).first().copy(progress = null, remainingMinutes = null) }
		} else {
			val card = existing ?: landscapeCards(listOf(item), withBadges = true).first()
			card.copy(
				progress = fraction.toFloat().coerceIn(0f, 1f),
				remainingMinutes = ((runtime - stopped.positionTicks) / TICKS_PER_MINUTE).toInt().coerceAtLeast(1),
			)
		}
		return listOfNotNull(front) + cards.filterNot(::sameTitle)
	}

	/** A title of a hidden library (the home screen leaves them out) */
	suspend fun isHidden(item: BaseItemDto): Boolean {
		val hidden = hiddenItemIds()
		val seriesId = item.seriesId
		return item.id in hidden || (seriesId != null && seriesId in hidden)
	}

	private fun cachedSeriesMedia(): Map<UUID, SeriesMedia> = synchronized(seriesMediaCache) { HashMap(seriesMediaCache) }

	private suspend fun hiddenItemIds(): Set<UUID> = safe("hidden libraries") { libraryVisibilityRepository.hiddenItemIds() }.orEmpty()

	private fun List<BaseItemDto>.withoutHidden(hidden: Set<UUID>): List<BaseItemDto> {
		if (hidden.isEmpty()) return this
		return filterNot { item ->
			val seriesId = item.seriesId
			item.id in hidden || (seriesId != null && seriesId in hidden)
		}
	}

	private data class CardContext(
		val labels: Map<UUID, NewContentLabel>,
		val seriesMedia: Map<UUID, SeriesMedia>,
		val episodeHints: Map<UUID, BaseItemDto>,
		val nextUpBySeries: Map<UUID, BaseItemDto>,
	)


	private fun section(type: HomeSectionType, style: CardStyle, cards: List<HomeCard>) =
		if (cards.isEmpty()) null else HomeSection(type, style, cards)

	private suspend fun recentEpisodes(): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.EPISODE),
			recursive = true,
			sortBy = setOf(ItemSortBy.DATE_CREATED),
			sortOrder = setOf(SortOrder.DESCENDING),
			isMissing = false,
			fields = setOf(ItemFields.DATE_CREATED),
			enableImages = false,
			enableUserData = false,
			enableTotalRecordCount = false,
			limit = RECENT_EPISODES_LIMIT,
		)
	).content.items

	/** Episodes released (aired) in the window of the "new" labels, up to today, newest first */
	private suspend fun airedEpisodes(): List<BaseItemDto> {
		val now = LocalDateTime.now()
		return api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.EPISODE),
				recursive = true,
				minPremiereDate = now.minusDays(MediaBadges.NEW_CONTENT_WINDOW_DAYS),
				maxPremiereDate = now,
				sortBy = setOf(ItemSortBy.PREMIERE_DATE),
				sortOrder = setOf(SortOrder.DESCENDING),
				isMissing = false,
				enableImages = false,
				enableUserData = false,
				enableTotalRecordCount = false,
				limit = RECENT_EPISODES_LIMIT,
			)
		).content.items
	}

	private suspend fun nextUpBySeries(): Map<UUID, BaseItemDto> = api.tvShowsApi.getNextUp(
		GetNextUpRequest(
			limit = NEXT_UP_LIMIT,
			enableImages = false,
			enableUserData = false,
			enableTotalRecordCount = false,
		)
	).content.items
		.mapNotNull { episode -> episode.seriesId?.let { seriesId -> seriesId to episode } }
		.toMap()

	/**
	 * Episodes/movies in progress and next episodes, mixed and sorted by the last time something of
	 * them was watched (the series for next episodes), most recent first.
	 */
	private suspend fun continueWatching(): List<BaseItemDto> = coroutineScope {
		val resumeAsync = async {
			api.itemsApi.getResumeItems(
				GetResumeItemsRequest(
					limit = SECTION_LIMIT,
					mediaTypes = setOf(MediaType.VIDEO),
					fields = CARD_FIELDS,
					imageTypeLimit = 1,
					enableTotalRecordCount = false,
				)
			).content.items
		}

		val nextUpAsync = async {
			api.tvShowsApi.getNextUp(
				GetNextUpRequest(
					limit = SECTION_LIMIT,
					fields = CARD_FIELDS,
					imageTypeLimit = 1,
					enableResumable = false,
					enableTotalRecordCount = false,
				)
			).content.items
		}

		val seriesActivityAsync = async { safe("series activity") { seriesActivity() }.orEmpty() }

		val resume = resumeAsync.await()
		// Don't show the next episode of a series that is already in progress
		val resumedSeries = resume.mapNotNull { it.seriesId }.toSet()
		val nextUp = nextUpAsync.await().filter { episode ->
			val seriesId = episode.seriesId
			seriesId == null || !resumedSeries.contains(seriesId)
		}

		ContinueWatchingOrder.sort(resume + nextUp, seriesActivityAsync.await())
	}

	/**
	 * Last time an episode of each series was watched (or started).
	 */
	private suspend fun seriesActivity(): Map<UUID, LocalDateTime> {
		val episodes = api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.EPISODE),
				recursive = true,
				sortBy = setOf(ItemSortBy.DATE_PLAYED),
				sortOrder = setOf(SortOrder.DESCENDING),
				enableImages = false,
				enableTotalRecordCount = false,
				limit = SERIES_ACTIVITY_LIMIT,
			)
		).content.items

		val activity = HashMap<UUID, LocalDateTime>()
		for (episode in episodes) {
			val seriesId = episode.seriesId ?: continue
			val lastPlayed = episode.userData?.lastPlayedDate ?: continue
			val current = activity[seriesId]
			if (current == null || lastPlayed.isAfter(current)) activity[seriesId] = lastPlayed
		}
		return activity
	}

	/**
	 * "Trending this week": the titles of the libraries trending this week ([trendingTitles]: TMDB,
	 * then IMDb). Empty spots are filled with what was watched the most on this server in the last 7
	 * days (all users when the current user is an administrator) and then with the best rated recent
	 * titles, then of all time.
	 *
	 * The ten places are counted once the titles of hidden libraries and the copies of a title in
	 * other libraries (one per language) are left out: counted before, they left 6 or 7.
	 */
	private suspend fun trending(hidden: Set<UUID>): List<BaseItemDto> {
		val sources: List<Pair<String, suspend () -> List<UUID>>> = listOf(
			"trending" to { trendingTitles(parentId = null, types = TRENDING_TYPES, hidden = hidden) },
			"most watched" to { cachedMostWatched() },
			"top rated" to { topRated(minPremiereDate = LocalDateTime.now().minusYears(2)).map { it.id } },
			"top rated of all time" to { topRated(minPremiereDate = null).map { it.id } },
		)

		// One id per title, in the order of the sources
		val picked = LinkedHashMap<String, UUID>()
		val seen = HashSet<UUID>()
		for ((name, source) in sources) {
			if (picked.size >= RANKED_LIMIT) break
			val ids = safe(name) { source() }.orEmpty().filter { it !in hidden && seen.add(it) }
			if (ids.isEmpty()) continue
			// Just enough to tell the copies of a title apart (and the series of hidden libraries)
			val items = safe("$name titles") { itemsByIds(ids, setOf(ItemFields.PROVIDER_IDS)) }.orEmpty().withoutHidden(hidden)
			for (item in items) {
				if (picked.size >= RANKED_LIMIT) break
				picked.putIfAbsent(MediaBadges.duplicateKey(item), item.id)
			}
		}

		return itemsByIds(picked.values.toList(), CARD_FIELDS)
	}

	/**
	 * Titles of a library ([parentId], null for all of them) trending this week, best first, one per
	 * movie or series (the copies in other libraries are left out):
	 * 1. TMDB's weekly trending list of that kind (all, movies or TV shows), when there is a TMDB key.
	 * 2. The IMDb trend ([ImdbTrending]: new IMDb votes beyond the usual).
	 * 3. Until the device has two days of IMDb votes, and for the places left, the most voted on IMDb
	 *    of the titles released in the last year.
	 */
	suspend fun trendingTitles(
		parentId: UUID?,
		types: Set<BaseItemKind>,
		hidden: Set<UUID> = emptySet(),
		limit: Int = RANKED_LIMIT,
	): List<UUID> = withContext(Dispatchers.IO) {
		val titles = libraryTitles(parentId, types).withoutHidden(hidden)
		val ranked = ArrayList<BaseItemDto>()

		if (tmdb.isConfigured) {
			val mediaType = when (types) {
				setOf(BaseItemKind.MOVIE) -> TmdbTrendingClient.MEDIA_TYPE_MOVIE
				setOf(BaseItemKind.SERIES) -> TmdbTrendingClient.MEDIA_TYPE_TV
				else -> TmdbTrendingClient.MEDIA_TYPE_ALL
			}
			val refs = safe("tmdb trending") { cachedTrendingRefs(mediaType) }.orEmpty()
			val byTmdb = titles.groupBy { tmdbKey(it) }
			for (ref in refs) byTmdb[ref.key]?.firstOrNull()?.let(ranked::add)
		}

		val trend = ImdbTrending.trend()
		if (trend != null) {
			val withNumber = titles.mapNotNull { item -> ImdbRatings.imdbNumber(item)?.let { item to it } }
			if (trend.hasScores) ranked += withNumber
				.mapNotNull { (item, number) -> trend.score(number)?.takeIf { it >= MIN_TREND_SCORE }?.let { item to it } }
				.sortedByDescending { it.second }
				.map { it.first }

			val since = LocalDateTime.now().minusDays(RECENT_POPULAR_DAYS)
			ranked += withNumber
				.filter { (item, _) -> item.premiereDate?.isAfter(since) == true }
				.mapNotNull { (item, number) -> trend.votes(number)?.let { item to it } }
				.sortedByDescending { it.second }
				.map { it.first }
		}

		ranked
			.distinctBy { MediaBadges.duplicateKey(it) }
			.take(limit)
			.map { it.id }
	}

	/** "movie:603" / "tv:1399", like TMDB's lists, null without a TMDB id */
	private fun tmdbKey(item: BaseItemDto): String? {
		val id = item.providerIds?.entries
			?.firstOrNull { (key, _) -> key.equals("Tmdb", ignoreCase = true) }
			?.value?.trim()?.takeIf { it.isNotEmpty() }
			?: return null
		return when (item.type) {
			BaseItemKind.MOVIE -> "${TmdbTrendingClient.MEDIA_TYPE_MOVIE}:$id"
			BaseItemKind.SERIES -> "${TmdbTrendingClient.MEDIA_TYPE_TV}:$id"
			else -> null
		}
	}

	/** Movies and series of [parentId] (null: all) with their provider ids, nothing else, kept a while */
	private suspend fun libraryTitles(parentId: UUID?, types: Set<BaseItemKind>): List<BaseItemDto> {
		val key = "$parentId|${types.joinToString(",")}"
		val now = System.currentTimeMillis()
		synchronized(libraryTitlesCache) {
			libraryTitlesCache[key]
				?.takeIf { it.server == api.baseUrl && now - it.time < LIBRARY_TITLES_TTL_MS }
				?.let { return it.titles }
		}

		val titles = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = parentId,
				includeItemTypes = types,
				recursive = true,
				fields = setOf(ItemFields.PROVIDER_IDS),
				enableImages = false,
				enableUserData = false,
				enableTotalRecordCount = false,
			)
		).content.items

		synchronized(libraryTitlesCache) { libraryTitlesCache[key] = LibraryTitlesCache(api.baseUrl, now, titles) }
		return titles
	}

	private class WatchScore {
		var viewers = 0
		var plays = 0
		var lastPlayed: LocalDateTime = LocalDateTime.MIN
	}

	private suspend fun cachedMostWatched(): List<UUID> {
		val now = System.currentTimeMillis()
		mostWatchedCache?.let { cache ->
			if (cache.server == api.baseUrl && now - cache.time < MOST_WATCHED_TTL_MS) return cache.ids
		}

		val ids = mostWatchedThisWeek()
		mostWatchedCache = MostWatchedCache(api.baseUrl, now, ids)
		return ids
	}

	private suspend fun mostWatchedThisWeek(): List<UUID> = coroutineScope {
		val cutoff = LocalDateTime.now().minusDays(TRENDING_DAYS)

		// Listing users requires an administrator, other users only count their own plays
		val userIds: List<UUID?> = runCatching { api.userApi.getUsers(isDisabled = false).content.map { it.id } }
			.getOrNull()
			?.takeIf { it.isNotEmpty() }
			?: listOf(null)

		val playsPerUser = userIds.map { userId ->
			async { runCatching { recentlyPlayed(userId, cutoff) }.getOrDefault(emptyMap()) }
		}.awaitAll()

		val scores = HashMap<UUID, WatchScore>()
		for (userPlays in playsPerUser) {
			for ((id, plays) in userPlays) {
				val score = scores.getOrPut(id) { WatchScore() }
				score.viewers += 1
				score.plays += plays.first
				if (plays.second.isAfter(score.lastPlayed)) score.lastPlayed = plays.second
			}
		}

		// Most viewers first, then most episodes watched, then most recent
		scores.entries
			.sortedWith(
				compareByDescending<Map.Entry<UUID, WatchScore>> { it.value.viewers }
					.thenByDescending { it.value.plays }
					.thenByDescending { it.value.lastPlayed }
			)
			.map { it.key }
	}

	/**
	 * Movies and series played by [userId] (null = current user) since [cutoff]: id to (plays, last play).
	 * Episodes are counted for their series.
	 */
	private suspend fun recentlyPlayed(userId: UUID?, cutoff: LocalDateTime): Map<UUID, Pair<Int, LocalDateTime>> {
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
				recursive = true,
				sortBy = setOf(ItemSortBy.DATE_PLAYED),
				sortOrder = setOf(SortOrder.DESCENDING),
				enableImages = false,
				enableTotalRecordCount = false,
				limit = RECENTLY_PLAYED_LIMIT,
			)
		).content.items

		val result = HashMap<UUID, Pair<Int, LocalDateTime>>()
		for (item in items) {
			val lastPlayed = item.userData?.lastPlayedDate ?: continue
			// Sorted by date played, everything after this is older
			if (lastPlayed.isBefore(cutoff)) break

			val id = if (item.type == BaseItemKind.EPISODE) item.seriesId ?: continue else item.id
			val previous = result[id]
			val latest = if (previous != null && previous.second.isAfter(lastPlayed)) previous.second else lastPlayed
			result[id] = ((previous?.first ?: 0) + 1) to latest
		}
		return result
	}

	private suspend fun topRated(minPremiereDate: LocalDateTime?): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			recursive = true,
			sortBy = setOf(ItemSortBy.COMMUNITY_RATING),
			sortOrder = setOf(SortOrder.DESCENDING),
			minPremiereDate = minPremiereDate,
			enableImages = false,
			enableUserData = false,
			enableTotalRecordCount = false,
			// Several libraries can have the same titles: enough to fill the ten places with different ones
			limit = TOP_RATED_CANDIDATES,
		)
	).content.items

	/** TMDB's trending list of [mediaType], kept a few hours */
	private suspend fun cachedTrendingRefs(mediaType: String): List<TmdbTrendingClient.TmdbRef> {
		val now = System.currentTimeMillis()
		// A new key in the settings: a new list
		val key = "$mediaType|${tmdb.keyHash}"
		synchronized(trendingCache) {
			trendingCache[mediaType]?.takeIf { it.key == key && now - it.time < TRENDING_TTL_MS }?.let { return it.refs }
		}

		val pages = if (mediaType == TmdbTrendingClient.MEDIA_TYPE_ALL) TMDB_PAGES_ALL else TMDB_PAGES_ONE_KIND
		val refs = tmdb.trending(mediaType, pages)
		if (refs.isNotEmpty()) synchronized(trendingCache) { trendingCache[mediaType] = TrendingCache(key, now, refs) }
		return refs
	}

	/** Latest aired episodes, one card per series with the date of its most recent episode */
	private suspend fun latestEpisodes(): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.EPISODE),
			recursive = true,
			sortBy = setOf(ItemSortBy.PREMIERE_DATE),
			sortOrder = setOf(SortOrder.DESCENDING),
			maxPremiereDate = LocalDateTime.now(),
			isMissing = false,
			fields = CARD_FIELDS,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = LATEST_EPISODES_CANDIDATES,
		)
	).content.items
		.distinctBy { it.seriesId ?: it.id }
		.take(SECTION_LIMIT)

	/** Latest released films */
	private suspend fun latestFilms(): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
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

	private suspend fun recentMovies(): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.MOVIE),
			recursive = true,
			sortBy = setOf(ItemSortBy.DATE_CREATED),
			sortOrder = setOf(SortOrder.DESCENDING),
			fields = CARD_FIELDS,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = SECTION_LIMIT,
		)
	).content.items

	private suspend fun favorites(): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			recursive = true,
			isFavorite = true,
			sortBy = setOf(ItemSortBy.DATE_CREATED),
			sortOrder = setOf(SortOrder.DESCENDING),
			fields = CARD_FIELDS,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = SECTION_LIMIT,
		)
	).content.items

	private fun seriesMediaOf(episode: BaseItemDto?) = SeriesMedia(
		quality = MediaBadges.quality(episode),
		languages = MediaBadges.audioLanguages(episode),
	)

	/**
	 * Watchlist: titles marked with "Likes", the same field the KefinTweaks web watchlist uses,
	 * so both stay in sync.
	 */
	private suspend fun watchlist(limit: Int = SECTION_LIMIT): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			recursive = true,
			filters = setOf(ItemFilter.LIKES),
			sortBy = setOf(ItemSortBy.DATE_CREATED),
			sortOrder = setOf(SortOrder.DESCENDING),
			fields = CARD_FIELDS,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = limit,
		)
	).content.items

	/**
	 * Quality and audio languages of each series, from the episode in [episodeHints] (next up or
	 * newest) or otherwise its first episode. Results are cached for the session.
	 */
	private suspend fun seriesMedia(
		seriesIds: List<UUID>,
		episodeHints: Map<UUID, BaseItemDto>,
		maxLookups: Int = SERIES_MEDIA_LOOKUPS,
	): Map<UUID, SeriesMedia> = coroutineScope {
		synchronized(seriesMediaCache) {
			if (seriesMediaServer != api.baseUrl) {
				seriesMediaCache.clear()
				seriesMediaServer = api.baseUrl
			}
		}

		val missing = synchronized(seriesMediaCache) { seriesIds.filterNot { it in seriesMediaCache } }
		val (hinted, unhinted) = missing.partition { it in episodeHints }

		// One request for all series with a known episode
		val hintedAsync = async {
			safe("series media") {
				itemsByIds(hinted.mapNotNull { episodeHints[it]?.id }, setOf(ItemFields.MEDIA_STREAMS))
			}.orEmpty()
		}

		// The others: first episode of each series, in parallel and limited to keep the home screen fast
		val firstEpisodes = unhinted.take(maxLookups).map { seriesId ->
			async {
				// A few at a time: a library page can ask for a hundred series
				val episodes = lookupSemaphore.withPermit { BackgroundTraffic.awaitTurn(); safe("first episode") {
					api.tvShowsApi.getEpisodes(
						seriesId = seriesId,
						fields = setOf(ItemFields.MEDIA_STREAMS),
						isMissing = false,
						limit = 1,
						enableImages = false,
						enableUserData = false,
					).content.items
				} }
				// Null when the request failed: not cached so it's tried again on the next refresh
				seriesId to episodes
			}
		}.awaitAll()
		val hintedEpisodes = hintedAsync.await()

		synchronized(seriesMediaCache) {
			for (episode in hintedEpisodes) {
				val seriesId = episode.seriesId ?: continue
				seriesMediaCache[seriesId] = seriesMediaOf(episode)
			}
			for ((seriesId, episodes) in firstEpisodes) {
				if (episodes != null) seriesMediaCache[seriesId] = seriesMediaOf(episodes.firstOrNull())
			}
			seriesIds.mapNotNull { seriesId -> seriesMediaCache[seriesId]?.let { seriesId to it } }.toMap()
		}
	}

	private suspend fun itemsByIds(ids: List<UUID>, fields: Set<ItemFields>): List<BaseItemDto> {
		if (ids.isEmpty()) return emptyList()

		val items = api.itemsApi.getItems(
			GetItemsRequest(
				ids = ids,
				fields = fields,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
			)
		).content.items

		// The server does not keep the order of the requested ids
		val itemsById = items.associateBy { it.id }
		return ids.mapNotNull { itemsById[it] }
	}

	// Card builders

	private fun posterCard(
		item: BaseItemDto,
		context: CardContext,
		withReleaseDate: Boolean,
	): HomeCard {
		val seriesId = when (item.type) {
			BaseItemKind.SERIES -> item.id
			BaseItemKind.SEASON, BaseItemKind.EPISODE -> item.seriesId
			else -> null
		}
		val targetId = seriesId ?: item.id

		// Episode shown when the poster is focused ("S1 · E8")
		val episodeHint = when (item.type) {
			BaseItemKind.EPISODE -> item
			BaseItemKind.SERIES, BaseItemKind.SEASON -> seriesId?.let { context.episodeHints[it] }
			else -> null
		}

		// What the play/pause button of the remote starts. Null for series without a next up
		// episode: resolved when pressed (first episode)
		val playId = when (item.type) {
			BaseItemKind.SERIES, BaseItemKind.SEASON -> seriesId?.let { context.nextUpBySeries[it]?.id }
			else -> item.id
		}

		return HomeCard(
			key = targetId.toString(),
			item = item,
			targetId = targetId,
			title = if (item.type == BaseItemKind.EPISODE) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty(),
			imageUrl = item.posterImage()?.getUrl(api, maxWidth = POSTER_IMAGE_WIDTH),
			backgroundUrl = item.blurredBackdropUrl(api),
			quality = MediaBadges.quality(item) ?: seriesId?.let { context.seriesMedia[it]?.quality },
			languages = MediaBadges.audioLanguages(item).ifEmpty { seriesId?.let { context.seriesMedia[it]?.languages }.orEmpty() },
			newLabel = seriesId?.let { context.labels[it] },
			releaseDate = if (withReleaseDate) item.premiereDate?.toLocalDate() else null,
			unplayedCount = if (item.type == BaseItemKind.SERIES) item.userData?.unplayedItemCount?.takeIf { it > 0 } else null,
			// IMDb's rating (its dataset), otherwise the server's
			rating = ImdbRatings.rating(item),
			year = item.productionYear,
			episodeLabel = episodeHint?.let { episodeNumberLabel(it) },
			playId = playId,
		)
	}

	private fun episodeNumberLabel(episode: BaseItemDto): String? {
		val season = episode.parentIndexNumber
		val number = episode.indexNumber
		return when {
			season != null && number != null -> "S$season · E$number"
			number != null -> "E$number"
			else -> null
		}
	}

	private fun landscapeCard(item: BaseItemDto): HomeCard {
		val runtime = item.runTimeTicks ?: 0L
		val position = item.userData?.playbackPositionTicks ?: 0L
		val hasProgress = runtime > 0 && position > 0
		val isEpisode = item.type == BaseItemKind.EPISODE

		return HomeCard(
			key = item.id.toString(),
			item = item,
			targetId = item.id,
			title = if (isEpisode) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty(),
			subtitle = if (isEpisode) episodeLabel(item) else item.productionYear?.toString(),
			imageUrl = item.landscapeImage()?.getUrl(api, maxWidth = LANDSCAPE_IMAGE_WIDTH),
			backgroundUrl = item.blurredBackdropUrl(api),
			quality = MediaBadges.quality(item),
			progress = if (hasProgress) (position.toFloat() / runtime.toFloat()).coerceIn(0f, 1f) else null,
			remainingMinutes = if (hasProgress) ((runtime - position) / TICKS_PER_MINUTE).toInt().coerceAtLeast(1) else null,
			playOnClick = true,
		)
	}

	private fun episodeLabel(item: BaseItemDto): String? {
		val season = item.parentIndexNumber
		val episode = item.indexNumber
		return when {
			season != null && episode != null -> "S$season · E$episode"
			episode != null -> "E$episode"
			else -> item.name
		}
	}

	private fun BaseItemDto.toHeroSlide() = HeroSlide(
		item = this,
		title = name.orEmpty(),
		// Media bar images: a bit lighter, they are the first thing on screen
		backdropUrl = backdropImage()?.getUrl(api, maxWidth = HERO_IMAGE_WIDTH, quality = HERO_IMAGE_QUALITY),
		backgroundUrl = blurredBackdropUrl(api),
		glassUrl = blurredBackdropUrl(api, maxWidth = ModernImageSizes.GLASS, blur = ModernImageSizes.GLASS_BLUR),
		logoUrl = logoImage()?.getUrl(api, maxWidth = LOGO_IMAGE_WIDTH, maxHeight = LOGO_IMAGE_HEIGHT, format = ImageFormat.WEBP),
		rating = ImdbRatings.rating(this),
		year = productionYear,
		runtimeMinutes = if (type == BaseItemKind.MOVIE) runTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() } else null,
		officialRating = officialRating,
		genres = genres.orEmpty().take(3),
		overview = overview,
		quality = MediaBadges.quality(this),
		languages = MediaBadges.audioLanguages(this),
	)

	private inline fun <T> safe(name: String, block: () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load home section: $name")
		null
	}

	private companion object {
		const val TICKS_PER_MINUTE = 600_000_000L

		const val HERO_SLIDES = 8
		const val HERO_CANDIDATES = 20
		const val SECTION_LIMIT = 30
		const val RANKED_LIMIT = 10
		/** Best rated titles asked to fill "Trending this week" */
		const val TOP_RATED_CANDIDATES = 40
		val TRENDING_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		/** Votes beyond the usual for a title to be trending (fewer is noise) */
		const val MIN_TREND_SCORE = 100
		/** Without a trend yet: the most voted titles released in this many days */
		const val RECENT_POPULAR_DAYS = 365L
		const val LIBRARY_TITLES_TTL_MS = 30 * 60 * 1000L
		const val TRENDING_DAYS = 7L
		const val RECENTLY_PLAYED_LIMIT = 200
		const val NEXT_UP_LIMIT = 150
		const val RECENT_EPISODES_LIMIT = 150
		const val LATEST_EPISODES_CANDIDATES = 80
		const val SERIES_MEDIA_LOOKUPS = 40
		const val MAX_PREFETCH_LOOKUPS = 200
		// Below the connections the app has per server: playback and page requests find a free one
		const val PARALLEL_LOOKUPS = 4
		const val LABELS_TTL_MS = 5 * 60 * 1000L
		const val SERIES_ACTIVITY_LIMIT = 150
		const val WATCHLIST_PAGE_LIMIT = 300

		const val POSTER_IMAGE_WIDTH = 360
		const val LANDSCAPE_IMAGE_WIDTH = 560
		const val HERO_IMAGE_WIDTH = 1600
		const val HERO_IMAGE_QUALITY = 80
		// The logo shows at most 340 × 96 dp: WebP keeps the transparency in a much smaller file than PNG
		const val LOGO_IMAGE_WIDTH = 600
		const val LOGO_IMAGE_HEIGHT = 200

		const val TRENDING_TTL_MS = 6 * 60 * 60 * 1000L
		/** TMDB trending pages asked (20 titles each): all kinds for Home, more of one kind for a library */
		const val TMDB_PAGES_ALL = 3
		const val TMDB_PAGES_ONE_KIND = 5
		const val MOST_WATCHED_TTL_MS = 5 * 60 * 1000L

		val CARD_FIELDS = setOf(
			ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
			ItemFields.DATE_CREATED,
			ItemFields.MEDIA_STREAMS,
			ItemFields.PROVIDER_IDS,
		)
	}
}
