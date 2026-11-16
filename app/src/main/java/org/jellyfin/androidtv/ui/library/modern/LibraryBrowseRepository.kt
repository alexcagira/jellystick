package org.jellyfin.androidtv.ui.library.modern

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.collections.CollectionCatalog
import org.jellyfin.androidtv.ui.home.modern.MediaBadges
import org.jellyfin.androidtv.ui.home.modern.posterImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

/**
 * Items of the browse grid, page by page: a library can have thousands of titles.
 */
class LibraryBrowseRepository(
	private val api: ApiClient,
	private val catalog: CollectionCatalog,
) {
	data class Page(val cards: List<BrowseCard>, val total: Int)

	/** Collections of one kind of library: the ones to show or to leave out (see [BrowseFilter.collectionsOf]) */
	private data class Restriction(val ids: Set<UUID>? = null, val excluded: Set<UUID>? = null) {
		/** Nothing can match */
		val empty get() = ids != null && ids.isEmpty()
	}

	private suspend fun restriction(filter: BrowseFilter): Restriction? {
		if (!filter.collections) return null
		return when (filter.collectionsOf) {
			LibraryKind.SHOWS -> Restriction(ids = catalog.kinds().withSeries.take(MAX_IDS).toSet())
			LibraryKind.MOVIES -> Restriction(excluded = catalog.kinds().seriesOnly.take(MAX_IDS).toSet().ifEmpty { null })
			null -> null
		}
	}

	private fun GetItemsRequest.restrictedTo(restriction: Restriction?): GetItemsRequest =
		if (restriction == null) this else copy(ids = restriction.ids, excludeItemIds = restriction.excluded)

	suspend fun page(filter: BrowseFilter, start: Int, count: Int): Page = withContext(Dispatchers.IO) {
		val restriction = restriction(filter)
		if (restriction?.empty == true) return@withContext Page(emptyList(), 0)
		val result = api.itemsApi.getItems(filter.request(start = start, limit = count, withTotal = true).restrictedTo(restriction)).content
		Page(result.items.map { card(it) }, result.totalRecordCount)
	}

	/**
	 * Random order: the server shuffles again on every request, so the whole list of ids is taken
	 * once (small, without images) and the pages are loaded from it.
	 */
	suspend fun randomIds(filter: BrowseFilter): List<UUID> = titles(filter.copy(sort = BrowseSort.RANDOM)).map { it.id }

	/**
	 * Every title of the grid in its order, only the id and the sort name (small, without images):
	 * for the random order and the audio language filter, which the app applies itself.
	 */
	suspend fun titles(filter: BrowseFilter): List<BaseItemDto> = withContext(Dispatchers.IO) {
		val restriction = restriction(filter)
		if (restriction?.empty == true) return@withContext emptyList()
		val request = filter.request(start = 0, limit = MAX_RANDOM, withTotal = false).copy(
			fields = setOf(ItemFields.SORT_NAME),
			enableImages = false,
			enableUserData = false,
		)
		api.itemsApi.getItems(
			(if (filter.sort == BrowseSort.RANDOM) request.copy(sortBy = setOf(ItemSortBy.RANDOM), sortOrder = null) else request)
				.restrictedTo(restriction)
		).content.items
	}

	suspend fun cards(ids: List<UUID>): List<BrowseCard> = withContext(Dispatchers.IO) {
		if (ids.isEmpty()) return@withContext emptyList()
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				ids = ids,
				fields = CARD_FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
			)
		).content.items.associateBy { it.id }
		// The server doesn't keep the order of the ids
		ids.mapNotNull { id -> items[id]?.let { card(it) } }
	}

	/** Position of the first title starting with [letter] in the list sorted by name ('#': 0) */
	suspend fun indexOfLetter(filter: BrowseFilter, letter: Char): Int = withContext(Dispatchers.IO) {
		if (letter == '#') return@withContext 0
		val restriction = restriction(filter)
		if (restriction?.empty == true) return@withContext 0
		api.itemsApi.getItems(
			filter.copy(sort = BrowseSort.NAME).request(start = 0, limit = 0, withTotal = true).copy(
				// Sort names are lowercase on the server
				nameLessThan = letter.lowercase(),
				enableImages = false,
				enableUserData = false,
				fields = emptySet(),
			).restrictedTo(restriction)
		).content.totalRecordCount
	}

	/** A library (for making collections from it) */
	suspend fun item(id: UUID): BaseItemDto? = withContext(Dispatchers.IO) {
		runCatching { api.userLibraryApi.getItem(id).content }
			.onFailure { Timber.w(it, "Unable to load item $id") }
			.getOrNull()
	}

	/** A random title of the grid, for "Shuffle" */
	suspend fun randomItem(filter: BrowseFilter): BaseItemDto? = withContext(Dispatchers.IO) {
		val restriction = restriction(filter)
		if (restriction?.empty == true) return@withContext null
		api.itemsApi.getItems(
			filter.request(start = 0, limit = 1, withTotal = false).copy(sortBy = setOf(ItemSortBy.RANDOM), sortOrder = null).restrictedTo(restriction)
		).content.items.firstOrNull()
	}

	/**
	 * Spanish age ratings used in a library ("ES-7", "ES-12"...), youngest first. The server keeps
	 * the rating of the country of the metadata, with its code in front (US ones have none).
	 */
	suspend fun officialRatings(filter: BrowseFilter): List<String> = withContext(Dispatchers.IO) {
		api.filterApi.getQueryFiltersLegacy(
			parentId = filter.libraryId,
			includeItemTypes = filter.itemTypes,
		).content.officialRatings.orEmpty()
			.filter { it.startsWith(SPANISH_RATING, ignoreCase = true) }
			.distinctBy { it.uppercase() }
			.sortedWith(compareBy({ ratingAge(it) }, { it }))
	}

	private fun BrowseFilter.request(start: Int, limit: Int, withTotal: Boolean): GetItemsRequest {
		val (sortBy, order) = when (sort) {
			BrowseSort.NAME, BrowseSort.RANDOM -> listOf(ItemSortBy.SORT_NAME) to SortOrder.ASCENDING
			BrowseSort.DATE_ADDED -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME) to SortOrder.DESCENDING
			BrowseSort.RELEASE_DATE -> listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME) to SortOrder.DESCENDING
			BrowseSort.RATING -> listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME) to SortOrder.DESCENDING
		}

		return GetItemsRequest(
			// Collections aren't inside the libraries: the library only picks which ones
			parentId = libraryId.takeUnless { collections },
			includeItemTypes = itemTypes,
			recursive = true,
			genres = genreNames.ifEmpty { listOfNotNull(genre) }.toSet().ifEmpty { null },
			tags = tag?.let { setOf(it) },
			officialRatings = officialRating?.let { setOf(it) },
			isPlayed = when (played) {
				PlayedFilter.UNWATCHED -> false
				PlayedFilter.WATCHED -> true
				else -> null
			},
			isFavorite = if (played == PlayedFilter.FAVORITES) true else null,
			sortBy = sortBy,
			sortOrder = sortBy.map { order },
			fields = CARD_FIELDS,
			imageTypeLimit = 1,
			enableTotalRecordCount = withTotal,
			startIndex = start,
			limit = limit,
		)
	}

	private fun card(item: BaseItemDto): BrowseCard {
		val endYear = item.endDate?.year
		val year = item.productionYear
		val subtitle = when {
			item.type == BaseItemKind.SERIES && year != null && endYear != null && endYear != year -> "$year – $endYear"
			else -> year?.toString()
		}
		return BrowseCard(
			item = item,
			title = item.name.orEmpty(),
			subtitle = subtitle,
			imageUrl = item.posterImage()?.getUrl(api, maxWidth = POSTER_WIDTH),
			// Movies have their own media streams; series get theirs afterwards from an episode
			quality = MediaBadges.quality(item),
			languages = MediaBadges.audioLanguages(item),
		)
	}

	companion object {
		private const val SPANISH_RATING = "ES-"

		/** "ES-12" → "12", "ES-A" → "A" */
		fun ratingLabel(rating: String): String =
			if (rating.startsWith(SPANISH_RATING, ignoreCase = true)) rating.substring(SPANISH_RATING.length) else rating

		/** Minimum age of a rating: "TP", "A" and "APTA" are for everyone */
		private fun ratingAge(rating: String): Int = ratingLabel(rating).filter { it.isDigit() }.toIntOrNull()
			?: if (ratingLabel(rating).uppercase().startsWith("X")) 99 else 0

		private const val MAX_RANDOM = 5_000
		/** Ids in one request (they go in the address, which has a size limit) */
		private const val MAX_IDS = 150
		private const val POSTER_WIDTH = 300
		private val CARD_FIELDS = setOf(ItemFields.SORT_NAME, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.MEDIA_STREAMS)
	}
}
