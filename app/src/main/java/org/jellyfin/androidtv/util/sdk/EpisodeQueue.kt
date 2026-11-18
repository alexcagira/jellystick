package org.jellyfin.androidtv.util.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * The episodes the player gets: the chosen one and the next ones of the series.
 *
 * The usual list (`Shows/{series}/Episodes?startItemId=`) is built by the server from the seasons
 * of the series and the series key stored in each episode. When an episode is left out of it (its
 * key is from before the series was identified again or the server was upgraded, its season
 * number doesn't match a season...) and it is the one chosen, the list comes back empty and
 * nothing played, from Continue Watching or the details page (South Park on a long-running
 * server). The episodes are then read from the folder of the series, in season and episode order,
 * which only depends on where the files are. If even that fails, the episode plays alone.
 */
internal object EpisodeQueue {
	/** A list that doesn't come in this time is given up for the next way */
	private val QUERY_TIMEOUT = 12.seconds

	/** Up to [limit] episodes from [episode] on (it first), never empty */
	suspend fun from(
		api: ApiClient,
		episode: BaseItemDto,
		limit: Int,
		fields: Collection<ItemFields>,
	): List<BaseItemDto> {
		val seriesId = episode.seriesId ?: return listOf(episode)

		val fromSeries = attempt("episodes of the series") {
			api.tvShowsApi.getEpisodes(
				seriesId = seriesId,
				startItemId = episode.id,
				isMissing = false,
				limit = limit,
				fields = fields,
			).content.items
		}
		if (fromSeries?.firstOrNull()?.id == episode.id) return fromSeries

		Timber.w("Episode ${episode.id} is not in the episode list of its series (${fromSeries?.size} items): reading the series folder")
		val fromFolder = attempt("episodes of the series folder") { fromFolder(api, seriesId, episode, limit, fields) }
		if (fromFolder?.firstOrNull()?.id == episode.id) return fromFolder

		Timber.w("Episode ${episode.id} not found in the series folder either: it plays alone")
		return listOf(episode)
	}

	/**
	 * Episodes of a series or season straight from its folder, for when the server's list of the
	 * series is empty (see above). Specials only when there is nothing else.
	 */
	suspend fun ofFolder(
		api: ApiClient,
		folderId: UUID,
		shuffle: Boolean,
		limit: Int,
		fields: Collection<ItemFields>,
	): List<BaseItemDto> = attempt("episodes of the folder") {
		val episodes = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = folderId,
				recursive = true,
				includeItemTypes = setOf(BaseItemKind.EPISODE),
				isMissing = false,
				sortBy = if (shuffle) setOf(ItemSortBy.RANDOM) else EPISODE_ORDER,
				fields = fields,
				limit = limit,
				enableTotalRecordCount = false,
			)
		).content.items
		episodes.filter { it.parentIndexNumber != 0 }.ifEmpty { episodes }
	}.orEmpty()

	private suspend fun fromFolder(
		api: ApiClient,
		seriesId: UUID,
		episode: BaseItemDto,
		limit: Int,
		fields: Collection<ItemFields>,
	): List<BaseItemDto> {
		// Just the order first: a long series has hundreds of episodes, without their media information it's light
		val all = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = seriesId,
				recursive = true,
				includeItemTypes = setOf(BaseItemKind.EPISODE),
				isMissing = false,
				sortBy = EPISODE_ORDER,
				enableImages = false,
				enableUserData = false,
				enableTotalRecordCount = false,
			)
		).content.items

		val start = all.indexOfFirst { it.id == episode.id }
		if (start < 0) return emptyList()
		// Specials (season 0) come first in this order: after a normal episode they are left out
		val isSpecial = episode.parentIndexNumber == 0
		val ids = all.drop(start)
			.filter { it.id == episode.id || isSpecial || it.parentIndexNumber != 0 }
			.take(limit)
			.map { it.id }

		// Then those episodes with everything the player needs (the server doesn't keep the order of ids)
		val byId = api.itemsApi.getItems(
			GetItemsRequest(
				ids = ids,
				fields = fields,
				enableTotalRecordCount = false,
			)
		).content.items.associateBy { it.id }
		return ids.mapNotNull { byId[it] }
	}

	/** [block] within the time limit, null when it fails or takes too long */
	private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
		withTimeoutOrNull(QUERY_TIMEOUT) { block() }
			.also { if (it == null) Timber.w("Loading the $what took too long") }
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.w(error, "Unable to load the $what")
		null
	}

	/** Season, then episode number; the name for those without numbers */
	private val EPISODE_ORDER = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME)
}
