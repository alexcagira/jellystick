package org.jellyfin.androidtv.ui.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

interface SearchRepository {
	suspend fun search(
		searchTerm: String,
		itemTypes: Collection<BaseItemKind>,
	): Result<List<BaseItemDto>>
}

class SearchRepositoryImpl(
	private val apiClient: ApiClient,
	private val libraryVisibilityRepository: LibraryVisibilityRepository,
) : SearchRepository {
	companion object {
		private const val QUERY_LIMIT = 25
	}

	override suspend fun search(
		searchTerm: String,
		itemTypes: Collection<BaseItemKind>,
	): Result<List<BaseItemDto>> = try {
		var request = GetItemsRequest(
			searchTerm = searchTerm,
			limit = QUERY_LIMIT,
			imageTypeLimit = 1,
			includeItemTypes = itemTypes,
			fields = ItemRepository.itemFields,
			recursive = true,
			enableTotalRecordCount = false,
		)

		// Special case for video row
		if (itemTypes.size == 1 && itemTypes.first() == BaseItemKind.VIDEO) {
			request = request.copy(
				mediaTypes = setOf(MediaType.VIDEO),
				includeItemTypes = null,
				excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL)
			)
		}

		val items = withContext(Dispatchers.IO) {
			val hiddenLibraryIds = libraryVisibilityRepository.hiddenLibraryIds.value
			if (hiddenLibraryIds.isEmpty()) apiClient.itemsApi.getItems(request).content.items
			else searchVisibleLibraries(request, itemTypes, hiddenLibraryIds)
		}

		Result.success(items)
	} catch (e: ApiClientException) {
		Timber.e(e, "Failed to search for items")
		Result.failure(e)
	}

	/**
	 * Search while skipping the libraries hidden by the user. The API can't exclude a library, so the
	 * search runs inside each visible library that can contain the requested item types instead.
	 */
	private suspend fun searchVisibleLibraries(
		request: GetItemsRequest,
		itemTypes: Collection<BaseItemKind>,
		hiddenLibraryIds: Set<UUID>,
	): List<BaseItemDto> {
		val libraries = libraryVisibilityRepository.libraries()
			.filter { LibraryVisibilityRepository.canContain(it.collectionType, itemTypes) }

		// None of the hidden libraries can contain these results
		if (libraries.none { it.id in hiddenLibraryIds }) return apiClient.itemsApi.getItems(request).content.items

		val visibleLibraries = libraries.filterNot { it.id in hiddenLibraryIds }
		if (visibleLibraries.isEmpty()) return emptyList()

		// Artists, playlists, collections... are not stored inside a library folder: only hide them when
		// every library that could contain them is hidden
		if (itemTypes.any { it in LibraryVisibilityRepository.virtualItemKinds }) {
			return apiClient.itemsApi.getItems(request).content.items
		}

		return coroutineScope {
			visibleLibraries.map { library ->
				async { apiClient.itemsApi.getItems(request.copy(parentId = library.id)).content.items }
			}.awaitAll()
		}.flatten().distinctBy { it.id }.take(QUERY_LIMIT)
	}
}
