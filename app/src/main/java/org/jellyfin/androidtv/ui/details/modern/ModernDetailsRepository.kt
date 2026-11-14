package org.jellyfin.androidtv.ui.details.modern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.MediaBadges
import org.jellyfin.androidtv.ui.home.modern.ModernImageSizes
import org.jellyfin.androidtv.ui.home.modern.backdropImage
import org.jellyfin.androidtv.ui.home.modern.blurredBackdropUrl
import org.jellyfin.androidtv.ui.home.modern.landscapeImage
import org.jellyfin.androidtv.ui.home.modern.logoImage
import org.jellyfin.androidtv.ui.home.modern.posterImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import timber.log.Timber
import java.util.UUID

/**
 * Loads everything shown on the modern details screen of a movie, series, season or person.
 */
class ModernDetailsRepository(
	private val api: ApiClient,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) {
	suspend fun load(itemId: UUID): DetailsState = withContext(Dispatchers.IO) {
		val item = api.userLibraryApi.getItem(itemId).content
		val kind = when (item.type) {
			BaseItemKind.MOVIE -> DetailsKind.MOVIE
			BaseItemKind.SERIES -> DetailsKind.SERIES
			BaseItemKind.SEASON -> DetailsKind.SEASON
			BaseItemKind.PERSON -> DetailsKind.PERSON
			else -> null
		}

		if (kind == null) return@withContext DetailsState(loading = false, item = item, kind = null)

		val hidden = safe("hidden libraries") { libraryVisibilityRepository.hiddenItemIds() }.orEmpty()
		when (kind) {
			DetailsKind.MOVIE -> loadMovie(item, hidden)
			DetailsKind.SERIES -> loadSeries(item, hidden)
			DetailsKind.SEASON -> loadSeason(item)
			DetailsKind.PERSON -> loadPerson(item, hidden)
		}
	}

	private suspend fun loadMovie(item: BaseItemDto, hidden: Set<UUID>): DetailsState = coroutineScope {
		val similar = async { safe("similar") { similarItems(item.id) }.orEmpty() }
		val header = header(item)

		DetailsState(
			loading = false,
			item = item,
			kind = DetailsKind.MOVIE,
			header = header,
			backgroundUrl = header.backgroundUrl,
			cast = cast(item.people),
			similar = posters(similar.await(), hidden),
			isFavorite = item.userData?.isFavorite == true,
			isPlayed = item.userData?.played == true,
			watchlistItemId = item.id,
			isInWatchlist = item.userData?.likes == true,
		)
	}

	private suspend fun loadSeries(item: BaseItemDto, hidden: Set<UUID>): DetailsState = coroutineScope {
		val seasonsAsync = async {
			safe("seasons") {
				api.tvShowsApi.getSeasons(
					seriesId = item.id,
					fields = CARD_FIELDS,
				).content.items
			}.orEmpty()
		}
		val nextUpAsync = async { safe("next up") { nextUp(item.id) } }
		val firstEpisodeAsync = async { safe("first episode") { firstEpisode(item.id) } }
		val similarAsync = async { safe("similar") { similarItems(item.id) }.orEmpty() }

		val seasons = seasonsAsync.await()
		val header = header(item, seasonCount = seasons.count { (it.indexNumber ?: 0) > 0 }.takeIf { it > 0 })
		val nextUp = nextUpAsync.await()
		// Not started (or finished) series start again at the first episode
		val playEpisode = nextUp ?: firstEpisodeAsync.await()

		DetailsState(
			loading = false,
			item = item,
			kind = DetailsKind.SERIES,
			header = header,
			backgroundUrl = header.backgroundUrl,
			nextUp = nextUp?.let { nextUpCard(it) },
			seasons = seasons.map { seasonCard(it, item) },
			cast = cast(item.people),
			similar = posters(similarAsync.await(), hidden),
			isFavorite = item.userData?.isFavorite == true,
			isPlayed = item.userData?.played == true,
			playTarget = playEpisode?.let { playTarget(it) },
			watchlistItemId = item.id,
			isInWatchlist = item.userData?.likes == true,
			themeSeriesId = item.id,
		)
	}

	private suspend fun loadSeason(item: BaseItemDto): DetailsState = coroutineScope {
		val seriesId = item.seriesId
		val seriesAsync = async { seriesId?.let { id -> safe("series") { api.userLibraryApi.getItem(id).content } } }
		val episodesAsync = async {
			seriesId?.let { id ->
				safe("episodes") {
					api.tvShowsApi.getEpisodes(
						seriesId = id,
						seasonId = item.id,
						fields = setOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
					).content.items
				}
			}.orEmpty()
		}
		val nextUpAsync = async { seriesId?.let { id -> safe("next up") { nextUp(id) } } }
		val seasonsAsync = async {
			seriesId?.let { id -> safe("seasons") { api.tvShowsApi.getSeasons(seriesId = id).content.items } }.orEmpty()
		}

		val series = seriesAsync.await()
		val episodes = episodesAsync.await()
		val nextUpId = nextUpAsync.await()?.id
		val header = header(item, series = series, episodeCount = episodes.size.takeIf { it > 0 })
		// Next episode of the series when it's in this season, otherwise the first one not watched yet
		val playEpisode = episodes.firstOrNull { it.id == nextUpId }
			?: episodes.firstOrNull { it.userData?.played != true }
			?: episodes.firstOrNull()

		DetailsState(
			loading = false,
			item = item,
			kind = DetailsKind.SEASON,
			header = header,
			backgroundUrl = header.backgroundUrl,
			episodes = episodes.map { episodeEntry(it, isNext = it.id == nextUpId) },
			cast = cast(item.people?.takeIf { it.isNotEmpty() } ?: series?.people),
			isFavorite = item.userData?.isFavorite == true,
			isPlayed = item.userData?.played == true,
			playTarget = playEpisode?.let { playTarget(it) },
			// The watchlist is about the whole series
			watchlistItemId = seriesId ?: item.id,
			isInWatchlist = (series ?: item).userData?.likes == true,
			themeSeriesId = seriesId,
			seasonTabs = seasonsAsync.await().map { season -> SeasonTab(id = season.id, name = season.name.orEmpty()) },
		)
	}

	private suspend fun loadPerson(item: BaseItemDto, hidden: Set<UUID>): DetailsState {
		val items = safe("filmography") {
			api.itemsApi.getItems(
				GetItemsRequest(
					personIds = setOf(item.id),
					includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
					recursive = true,
					sortBy = setOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR),
					sortOrder = setOf(SortOrder.DESCENDING),
					fields = CARD_FIELDS,
					imageTypeLimit = 1,
					enableTotalRecordCount = false,
					limit = FILMOGRAPHY_LIMIT,
				)
			).content.items
		}.orEmpty()

		val movies = posters(items.filter { it.type == BaseItemKind.MOVIE }, hidden)
		val shows = posters(items.filter { it.type == BaseItemKind.SERIES }, hidden)

		return DetailsState(
			loading = false,
			item = item,
			kind = DetailsKind.PERSON,
			person = PersonInfo(
				name = item.name.orEmpty(),
				imageUrl = item.itemImages[ImageType.PRIMARY]?.getUrl(api, maxWidth = ModernImageSizes.PERSON),
				overview = item.overview?.takeIf { it.isNotBlank() },
				birthDate = item.premiereDate?.toLocalDate(),
				deathDate = item.endDate?.toLocalDate(),
				birthPlace = item.productionLocations?.firstOrNull { it.isNotBlank() },
			),
			// The page takes the colors of the most recent title
			backgroundUrl = (movies + shows).firstNotNullOfOrNull { it.backgroundUrl },
			personMovies = movies,
			personShows = shows,
			isFavorite = item.userData?.isFavorite == true,
		)
	}

	// Actions

	suspend fun setFavorite(itemId: UUID, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
		val userData = if (favorite) api.userLibraryApi.markFavoriteItem(itemId).content
		else api.userLibraryApi.unmarkFavoriteItem(itemId).content
		userData.isFavorite
	}

	suspend fun setPlayed(itemId: UUID, played: Boolean): Boolean = withContext(Dispatchers.IO) {
		val userData = if (played) api.playStateApi.markPlayedItem(itemId).content
		else api.playStateApi.markUnplayedItem(itemId).content
		userData.played
	}

	/**
	 * Add to or remove from the watchlist. Stored as "Likes", like the KefinTweaks web watchlist.
	 */
	suspend fun setInWatchlist(itemId: UUID, inWatchlist: Boolean): Boolean = withContext(Dispatchers.IO) {
		val userData = if (inWatchlist) api.userLibraryApi.updateUserItemRating(itemId = itemId, likes = true).content
		else api.userLibraryApi.deleteUserItemRating(itemId = itemId).content
		userData.likes == true
	}

	/** Item with all its details (file path, size, streams...) for the media info dialog */
	suspend fun fullItem(itemId: UUID): BaseItemDto = withContext(Dispatchers.IO) {
		api.userLibraryApi.getItem(itemId).content
	}

	suspend fun localTrailerIds(itemId: UUID): List<UUID> = withContext(Dispatchers.IO) {
		api.userLibraryApi.getLocalTrailers(itemId).content.map { it.id }
	}

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = withContext(Dispatchers.IO) {
		if (item.type != BaseItemKind.SERIES) return@withContext item.id
		safe("next up") { nextUp(item.id) }?.id ?: firstEpisodeId(item.id)
	}

	suspend fun firstEpisodeId(seriesId: UUID): UUID? = withContext(Dispatchers.IO) { firstEpisode(seriesId)?.id }

	/** First regular episode of a series (specials only when there is nothing else) */
	private suspend fun firstEpisode(seriesId: UUID): BaseItemDto? {
		val episodes = api.tvShowsApi.getEpisodes(
			seriesId = seriesId,
			isMissing = false,
			limit = FIRST_EPISODE_CANDIDATES,
		).content.items
		return episodes.firstOrNull { (it.parentIndexNumber ?: 1) > 0 } ?: episodes.firstOrNull()
	}

	// Queries

	private suspend fun similarItems(itemId: UUID): List<BaseItemDto> = api.libraryApi.getSimilarItems(
		itemId = itemId,
		limit = SIMILAR_LIMIT,
		fields = CARD_FIELDS,
	).content.items

	private suspend fun nextUp(seriesId: UUID): BaseItemDto? = api.tvShowsApi.getNextUp(
		GetNextUpRequest(
			seriesId = seriesId,
			limit = 1,
			fields = CARD_FIELDS,
			enableTotalRecordCount = false,
		)
	).content.items.firstOrNull()

	// Builders

	private fun header(
		item: BaseItemDto,
		series: BaseItemDto? = null,
		seasonCount: Int? = null,
		episodeCount: Int? = null,
	): DetailsHeader {
		val isSeason = item.type == BaseItemKind.SEASON
		val seriesName = series?.name ?: item.seriesName
		val originalTitle = item.originalTitle?.takeIf { it.isNotBlank() && it != item.name }

		return DetailsHeader(
			title = (if (isSeason) seriesName else item.name).orEmpty(),
			secondaryTitle = if (isSeason) seriesName?.uppercase() else originalTitle,
			subtitle = if (isSeason) item.name else null,
			logoUrl = (item.logoImage() ?: series?.logoImage())?.getUrl(api, maxWidth = ModernImageSizes.LOGO),
			backdropUrl = (item.backdropImage() ?: series?.backdropImage())?.getUrl(api, maxWidth = ModernImageSizes.HERO),
			backgroundUrl = item.blurredBackdropUrl(api) ?: series?.blurredBackdropUrl(api),
			glassUrl = item.blurredBackdropUrl(api, ModernImageSizes.GLASS, ModernImageSizes.GLASS_BLUR)
				?: series?.blurredBackdropUrl(api, ModernImageSizes.GLASS, ModernImageSizes.GLASS_BLUR),
			rating = (item.communityRating ?: series?.communityRating)?.takeIf { it > 0f },
			year = item.productionYear ?: item.premiereDate?.year,
			runtimeMinutes = if (item.type == BaseItemKind.MOVIE) item.runTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() } else null,
			seasonCount = seasonCount,
			episodeCount = episodeCount,
			officialRating = item.officialRating ?: series?.officialRating,
			genres = (item.genres?.takeIf { it.isNotEmpty() } ?: series?.genres).orEmpty().take(3),
			overview = item.overview?.takeIf { it.isNotBlank() } ?: series?.overview,
			quality = MediaBadges.quality(item),
			languages = MediaBadges.audioLanguages(item),
		)
	}

	private fun cast(people: List<BaseItemPerson>?): List<CastEntry> = people.orEmpty()
		.distinctBy { it.id }
		.take(CAST_LIMIT)
		.map { person ->
			val isCharacter = person.type == PersonKind.ACTOR || person.type == PersonKind.GUEST_STAR
			CastEntry(
				id = person.id,
				name = person.name.orEmpty(),
				role = if (isCharacter) person.role?.takeIf { it.isNotBlank() }
				else person.role?.takeIf { it.isNotBlank() } ?: person.type.serialName,
				isCharacter = isCharacter && !person.role.isNullOrBlank(),
				imageUrl = person.primaryImageTag?.let { tag ->
					api.imageApi.getItemImageUrl(
						itemId = person.id,
						imageType = ImageType.PRIMARY,
						tag = tag,
						maxWidth = CAST_IMAGE_WIDTH,
					)
				},
			)
		}

	private fun posters(items: List<BaseItemDto>, hidden: Set<UUID>): List<HomeCard> = items
		.filterNot { it.id in hidden }
		.distinctBy { MediaBadges.duplicateKey(it) }
		.map { posterCard(it) }

	private fun posterCard(item: BaseItemDto) = HomeCard(
		key = item.id.toString(),
		item = item,
		targetId = item.id,
		title = item.name.orEmpty(),
		imageUrl = item.posterImage()?.getUrl(api, maxWidth = ModernImageSizes.POSTER),
		backgroundUrl = item.blurredBackdropUrl(api),
		quality = MediaBadges.quality(item),
		languages = MediaBadges.audioLanguages(item),
		unplayedCount = if (item.type == BaseItemKind.SERIES) item.userData?.unplayedItemCount?.takeIf { it > 0 } else null,
		rating = item.communityRating?.takeIf { it > 0f },
		year = item.productionYear,
		playId = if (item.type == BaseItemKind.SERIES) null else item.id,
	)

	private fun seasonCard(season: BaseItemDto, series: BaseItemDto) = HomeCard(
		key = season.id.toString(),
		item = season,
		targetId = season.id,
		title = season.name.orEmpty(),
		imageUrl = (season.posterImage() ?: series.posterImage())?.getUrl(api, maxWidth = ModernImageSizes.POSTER),
		backgroundUrl = season.blurredBackdropUrl(api) ?: series.blurredBackdropUrl(api),
		unplayedCount = season.userData?.unplayedItemCount?.takeIf { it > 0 },
		year = season.productionYear,
	)

	private fun nextUpCard(episode: BaseItemDto): HomeCard {
		val runtime = episode.runTimeTicks ?: 0L
		val position = episode.userData?.playbackPositionTicks ?: 0L
		val hasProgress = runtime > 0 && position > 0
		val number = listOfNotNull(
			episode.parentIndexNumber?.let { "S$it" },
			episode.indexNumber?.let { "E$it" },
		).joinToString(":")

		return HomeCard(
			key = episode.id.toString(),
			item = episode,
			targetId = episode.id,
			title = listOf(number, episode.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" - "),
			imageUrl = episode.landscapeImage()?.getUrl(api, maxWidth = ModernImageSizes.LANDSCAPE),
			backgroundUrl = episode.blurredBackdropUrl(api),
			progress = if (hasProgress) (position.toFloat() / runtime.toFloat()).coerceIn(0f, 1f) else null,
			remainingMinutes = if (hasProgress) ((runtime - position) / TICKS_PER_MINUTE).toInt().coerceAtLeast(1) else null,
			playOnClick = true,
		)
	}

	private fun playTarget(episode: BaseItemDto) = PlayTarget(
		id = episode.id,
		seasonNumber = episode.parentIndexNumber,
		episodeNumber = episode.indexNumber,
		resume = (episode.userData?.playbackPositionTicks ?: 0L) > 0L,
	)

	private fun episodeEntry(episode: BaseItemDto, isNext: Boolean): EpisodeEntry {
		val runtime = episode.runTimeTicks ?: 0L
		val position = episode.userData?.playbackPositionTicks ?: 0L

		return EpisodeEntry(
			item = episode,
			number = episode.indexNumber?.let { String.format("%02d", it) } ?: "–",
			title = episode.name.orEmpty(),
			overview = episode.overview?.takeIf { it.isNotBlank() },
			imageUrl = episode.landscapeImage()?.getUrl(api, maxWidth = EPISODE_IMAGE_WIDTH),
			runtimeMinutes = (runtime / TICKS_PER_MINUTE).toInt().takeIf { it > 0 },
			rating = episode.communityRating?.takeIf { it > 0f },
			progress = if (runtime > 0 && position > 0) (position.toFloat() / runtime.toFloat()).coerceIn(0f, 1f) else null,
			played = episode.userData?.played == true,
			isNext = isNext,
		)
	}

	private inline fun <T> safe(name: String, block: () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load details section: $name")
		null
	}

	private companion object {
		const val TICKS_PER_MINUTE = 600_000_000L
		const val SIMILAR_LIMIT = 16
		const val CAST_LIMIT = 30
		const val FILMOGRAPHY_LIMIT = 150
		const val CAST_IMAGE_WIDTH = 300
		const val EPISODE_IMAGE_WIDTH = 560
		const val FIRST_EPISODE_CANDIDATES = 10

		val CARD_FIELDS = setOf(
			ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
			ItemFields.MEDIA_STREAMS,
			ItemFields.PROVIDER_IDS,
		)
	}
}
