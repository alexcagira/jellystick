package org.jellyfin.androidtv.ui.home.modern

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemLookupApi
import org.jellyfin.sdk.api.client.extensions.itemRefreshApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MetadataRefreshMode
import org.jellyfin.sdk.model.api.MovieInfo
import org.jellyfin.sdk.model.api.MovieInfoRemoteSearchQuery
import org.jellyfin.sdk.model.api.RemoteSearchResult
import org.jellyfin.sdk.model.api.SeriesInfo
import org.jellyfin.sdk.model.api.SeriesInfoRemoteSearchQuery
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/**
 * Actions of the poster menus (hold OK on a poster): watched state, file info and "Identify".
 */
class ItemActions(
	private val api: ApiClient,
	private val userRepository: UserRepository,
) {
	/** "Identify" changes the metadata of the library: administrators only (same as the web app) */
	val canIdentify: Boolean
		get() = userRepository.currentUser.value?.policy?.isAdministrator == true

	/** Scanning a library for new files: administrators only */
	val canScan: Boolean
		get() = canIdentify

	/**
	 * "Trigger scan": the server looks for new, changed and removed files in the library (like "Scan
	 * library" in the web app) and refreshes the metadata of what it finds.
	 */
	suspend fun scanLibrary(libraryId: UUID) = withContext(Dispatchers.IO) {
		api.itemRefreshApi.refreshItem(
			itemId = libraryId,
			metadataRefreshMode = MetadataRefreshMode.DEFAULT,
			imageRefreshMode = MetadataRefreshMode.DEFAULT,
			replaceAllMetadata = false,
			replaceAllImages = false,
		)
		Unit
	}

	suspend fun setPlayed(itemId: UUID, played: Boolean) = withContext(Dispatchers.IO) {
		if (played) api.playStateApi.markPlayedItem(itemId)
		else api.playStateApi.markUnplayedItem(itemId)
		Unit
	}

	/** On the watchlist (the "likes" of the user, the same list as KefinTweaks on the web) */
	suspend fun isInWatchlist(itemId: UUID): Boolean? = withContext(Dispatchers.IO) {
		api.itemsApi.getItems(
			GetItemsRequest(
				ids = setOf(itemId),
				enableImages = false,
				enableTotalRecordCount = false,
			)
		).content.items.firstOrNull()?.userData?.likes
	}

	/** Add to or remove from the watchlist; returns whether it is on it now */
	suspend fun setInWatchlist(itemId: UUID, inWatchlist: Boolean): Boolean = withContext(Dispatchers.IO) {
		val userData = if (inWatchlist) api.userLibraryApi.updateUserItemRating(itemId = itemId, likes = true).content
		else api.userLibraryApi.deleteUserItemRating(itemId = itemId).content
		userData.likes == true
	}

	/** Item with its media sources, for "File info" */
	suspend fun fullItem(itemId: UUID): BaseItemDto = withContext(Dispatchers.IO) {
		api.userLibraryApi.getItem(itemId).content
	}

	/**
	 * Search the metadata providers (TMDb, TVDb, OMDb... as configured on the server) for a movie
	 * or a series. [externalId] can be an IMDb id ("tt0120737") or a TMDb id ("120").
	 */
	suspend fun searchMatches(
		item: BaseItemDto,
		name: String,
		year: Int?,
		externalId: String?,
	): List<RemoteSearchResult> = withContext(Dispatchers.IO) {
		val providerIds = externalId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
			if (id.startsWith("tt", ignoreCase = true)) mapOf("Imdb" to id.lowercase())
			else mapOf("Tmdb" to id)
		}

		when (item.type) {
			BaseItemKind.SERIES -> api.itemLookupApi.getSeriesRemoteSearchResults(
				SeriesInfoRemoteSearchQuery(
					searchInfo = SeriesInfo(name = name, year = year, providerIds = providerIds, isAutomated = false),
					itemId = item.id,
					includeDisabledProviders = true,
				)
			).content

			else -> api.itemLookupApi.getMovieRemoteSearchResults(
				MovieInfoRemoteSearchQuery(
					searchInfo = MovieInfo(name = name, year = year, providerIds = providerIds, isAutomated = false),
					itemId = item.id,
					includeDisabledProviders = true,
				)
			).content
		}
	}

	/** Apply a match: the server downloads the new metadata and images in the background */
	suspend fun applyMatch(itemId: UUID, match: RemoteSearchResult) = withContext(Dispatchers.IO) {
		api.itemLookupApi.applySearchCriteria(itemId = itemId, replaceAllImages = true, data = match)
		Unit
	}
}

/** Item "Identify" works on: the series for an episode or a season, the item itself otherwise */
internal val BaseItemDto.identifyTargetId: UUID?
	get() = when (type) {
		BaseItemKind.MOVIE, BaseItemKind.SERIES -> id
		BaseItemKind.EPISODE, BaseItemKind.SEASON -> seriesId
		else -> null
	}
