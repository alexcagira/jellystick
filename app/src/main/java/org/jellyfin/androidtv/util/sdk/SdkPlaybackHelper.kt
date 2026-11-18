package org.jellyfin.androidtv.util.sdk

import org.jellyfin.androidtv.ui.home.modern.BackgroundTraffic
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SdkPlaybackHelper(
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val playbackLauncher: PlaybackLauncher,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val videoQueueManager: VideoQueueManager,
) : PlaybackHelper {
	companion object {
		const val ITEM_QUERY_LIMIT = 150

		/**
		 * Episodes asked before the player opens: the one chosen and the next ones. The rest of the
		 * queue (up to [ITEM_QUERY_LIMIT]) comes once it plays: 150 episodes with all their media
		 * information took seconds on a busy server, and the player waited for them.
		 */
		const val QUICK_EPISODE_LIMIT = 3
		private val MORE_EPISODES_DELAY = 15.seconds
	}

	override fun getItemsToPlay(
		context: Context,
		mainItem: BaseItemDto,
		allowIntros: Boolean,
		shuffle: Boolean,
		outerResponse: Response<List<BaseItemDto>>
	) {
		getScope(context).launch {
			runCatching {
				val items = getItems(mainItem, allowIntros, shuffle)
				if (items.isEmpty() && !mainItem.mediaSources.isNullOrEmpty()) listOf(mainItem)
				else items
			}.fold(
				onSuccess = { items -> outerResponse.onResponse(items) },
				onFailure = { exception ->
					when (exception) {
						is Exception -> outerResponse.onError(exception)
						else -> outerResponse.onError(Exception(exception))
					}
				}
			)
		}
	}

	private suspend fun getItems(
		mainItem: BaseItemDto,
		allowIntros: Boolean,
		shuffle: Boolean,
		quickStart: Boolean = false,
	): List<BaseItemDto> = withContext(Dispatchers.IO) {
		when (mainItem.type) {
			BaseItemKind.EPISODE -> {
				if (userPreferences[UserPreferences.mediaQueuingEnabled] && mainItem.seriesId != null) {
					// The chosen one and the next ones (never empty: see EpisodeQueue)
					EpisodeQueue.from(
						api = api,
						episode = mainItem,
						limit = if (quickStart) QUICK_EPISODE_LIMIT else ITEM_QUERY_LIMIT,
						fields = ItemRepository.itemFields,
					)
				} else {
					listOf(mainItem)
				}
			}

			BaseItemKind.SERIES -> {
				val response by api.tvShowsApi.getEpisodes(
					seriesId = mainItem.id,
					isMissing = false,
					sortBy = if (shuffle) ItemSortBy.RANDOM else ItemSortBy.SORT_NAME,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields,
				)
				// Empty when the server's list of the series misses its episodes: from its folder
				response.items.ifEmpty {
					EpisodeQueue.ofFolder(api, mainItem.id, shuffle, ITEM_QUERY_LIMIT, ItemRepository.itemFields)
				}
			}

			BaseItemKind.SEASON -> {
				val response by api.tvShowsApi.getEpisodes(
					seriesId = requireNotNull(mainItem.seriesId),
					seasonId = mainItem.id,
					isMissing = false,
					sortBy = if (shuffle) ItemSortBy.RANDOM else ItemSortBy.SORT_NAME,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields,
				)
				response.items.ifEmpty {
					EpisodeQueue.ofFolder(api, mainItem.id, shuffle, ITEM_QUERY_LIMIT, ItemRepository.itemFields)
				}
			}

			BaseItemKind.FOLDER -> {
				val response by api.itemsApi.getItems(
					parentId = mainItem.id,
					isMissing = false,
					includeItemTypes = listOf(
						BaseItemKind.EPISODE,
						BaseItemKind.MOVIE,
						BaseItemKind.VIDEO
					),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else listOf(ItemSortBy.SORT_NAME),
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields
				)

				response.items
			}

			BaseItemKind.BOX_SET -> {
				val response by api.itemsApi.getItems(
					parentId = mainItem.id,
					isMissing = false,
					includeItemTypes = listOf(
						BaseItemKind.EPISODE,
						BaseItemKind.MOVIE,
						BaseItemKind.VIDEO
					),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else null,
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields
				)

				response.items
			}

			BaseItemKind.MUSIC_ALBUM -> {
				val response by api.itemsApi.getItems(
					isMissing = false,
					mediaTypes = listOf(MediaType.AUDIO),
					filters = listOf(ItemFilter.IS_NOT_FOLDER),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else listOf(
						ItemSortBy.ALBUM,
						ItemSortBy.PARENT_INDEX_NUMBER,
						ItemSortBy.INDEX_NUMBER,
						ItemSortBy.SORT_NAME
					),
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields,
					albumIds = listOf(mainItem.id)
				)

				response.items
			}

			BaseItemKind.MUSIC_ARTIST -> {
				val response by api.itemsApi.getItems(
					isMissing = false,
					mediaTypes = listOf(MediaType.AUDIO),
					filters = listOf(ItemFilter.IS_NOT_FOLDER),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else listOf(
						ItemSortBy.ALBUM,
						ItemSortBy.PARENT_INDEX_NUMBER,
						ItemSortBy.INDEX_NUMBER,
						ItemSortBy.SORT_NAME
					),
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields,
					artistIds = listOf(mainItem.id)
				)

				response.items
			}

			BaseItemKind.PLAYLIST -> {
				val response by api.itemsApi.getItems(
					parentId = mainItem.id,
					isMissing = false,
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else null,
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields
				)

				response.items
			}

			BaseItemKind.PROGRAM -> {
				val parentId = requireNotNull(mainItem.parentId)
				val channel by api.userLibraryApi.getItem(parentId)
				val channelWithProgramMetadata = channel.copy(
					premiereDate = mainItem.premiereDate,
					endDate = mainItem.endDate,
					officialRating = mainItem.officialRating,
					runTimeTicks = mainItem.runTimeTicks,
				)

				listOf(channelWithProgramMetadata)
			}

			BaseItemKind.TV_CHANNEL -> {
				val channel by api.liveTvApi.getChannel(mainItem.id)
				val currentProgram = channel.currentProgram
				if (currentProgram != null) {
					val channelWithCurrentProgramMetadata = channel.copy(
						premiereDate = currentProgram.premiereDate,
						endDate = currentProgram.endDate,
						officialRating = currentProgram.officialRating,
						runTimeTicks = currentProgram.runTimeTicks,
					)
					listOf(channelWithCurrentProgramMetadata)
				} else {
					listOf(channel)
				}
			}

			else -> {
				val parts = getParts(mainItem)
				val addIntros = allowIntros && userPreferences[UserPreferences.cinemaModeEnabled]

				if (addIntros) {
					val intros = runCatching { api.userLibraryApi.getIntros(mainItem.id).content.items }.getOrNull()
						.orEmpty()
						// Force the type to be trailer as the legacy playback UI uses it to determine if it should show the next up screen
						.map { it.copy(type = BaseItemKind.TRAILER) }

					intros + parts
				} else {
					parts
				}
			}
		}
	}

	private suspend fun getParts(item: BaseItemDto): List<BaseItemDto> = buildList {
		add(item)

		val partCount = item.partCount
		if (partCount != null && partCount > 1) {
			val response by api.videosApi.getAdditionalPart(item.id)
			addAll(response.items)
		}
	}

	override fun retrieveAndPlay(itemId: UUID, shuffle: Boolean, context: Context) =
		retrieveAndPlay(itemId, shuffle, fromStart = false, context = context)

	override fun retrieveAndPlay(itemId: UUID, shuffle: Boolean, fromStart: Boolean, context: Context) {
		// The player first: background requests of the app wait
		BackgroundTraffic.playRequested()
		getScope(context).launch {
			val resumeSubtractDuration =
				userPreferences[UserPreferences.resumeSubtractDuration].toIntOrNull()?.seconds
					?: Duration.ZERO

			val (item, items) = try {
				val item = withContext(Dispatchers.IO) {
					val response by api.userLibraryApi.getItem(itemId)
					response
				}
				val allowIntros = item.userData?.playbackPositionTicks.let { it == null || it == 0L } && item.type == BaseItemKind.MOVIE
				// Episodes: only the next few before the player opens, the others once it plays
				val quickStart = item.type == BaseItemKind.EPISODE && !shuffle
				val items = getItems(item, allowIntros, shuffle, quickStart)
					// Something that plays by itself (an episode, a movie) plays even if its list failed
					.ifEmpty { if (item.mediaSources.isNullOrEmpty()) emptyList() else listOf(item) }
				item to items
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				// It used to do nothing at all: now it says so
				Timber.e(error, "Unable to load the items to play for $itemId")
				Toast.makeText(context, R.string.msg_playback_load_failed, Toast.LENGTH_LONG).show()
				return@launch
			}

			if (items.isEmpty()) {
				Timber.w("Nothing to play for $itemId")
				Toast.makeText(context, R.string.msg_no_playable_items, Toast.LENGTH_LONG).show()
				return@launch
			}

			val resumePosition = item.userData?.playbackPositionTicks?.ticks?.minus(resumeSubtractDuration) ?: Duration.ZERO
			// Played again from the beginning (no intros: it was already started)
			val pos = if (fromStart) Duration.ZERO else resumePosition
			val quickStart = item.type == BaseItemKind.EPISODE && !shuffle

			playbackLauncher.launch(
				context,
				items,
				pos.inWholeMilliseconds.toInt(),
				playbackControllerContainer.playbackController?.hasFragment() == true,
				0,
				shuffle,
			)

			if (quickStart && items.size >= QUICK_EPISODE_LIMIT) appendMoreEpisodes(items.last())
		}
	}

	/** The rest of the episodes of the queue, a moment after the playback started */
	private fun appendMoreEpisodes(lastEpisode: BaseItemDto) {
		if (lastEpisode.seriesId == null) return
		ProcessLifecycleOwner.get().lifecycleScope.launch {
			delay(MORE_EPISODES_DELAY)
			// Same way as the first ones (from the series folder when the server's list misses them)
			val more = withContext(Dispatchers.IO) {
				EpisodeQueue.from(api, lastEpisode, ITEM_QUERY_LIMIT - QUICK_EPISODE_LIMIT + 1, ItemRepository.itemFields)
			}
			// The first one is the last episode of the queue
			videoQueueManager.appendToVideoQueue(lastEpisode.id, more.dropWhile { it.id == lastEpisode.id })
		}
	}

	override fun retrieveAndPlay(itemIds: List<UUID>, shuffle: Boolean, position: Long?, index: Int?, context: Context) {
		BackgroundTraffic.playRequested()
		getScope(context).launch {
			val resumeSubtractDuration =
				userPreferences[UserPreferences.resumeSubtractDuration].toIntOrNull()?.seconds
					?: Duration.ZERO

			val items = withContext(Dispatchers.IO) {
				val response by api.itemsApi.getItems(
					ids = itemIds,
				)
				response.items
			}

			val pos = position?.ticks ?: items[0].userData?.playbackPositionTicks?.ticks?.minus(
				resumeSubtractDuration
			) ?: Duration.ZERO

			playbackLauncher.launch(
				context,
				items,
				pos.inWholeMilliseconds.toInt(),
				playbackControllerContainer.playbackController?.hasFragment() == true,
				index ?: 0,
				shuffle,
			)
		}
	}

	override fun playInstantMix(context: Context, item: BaseItemDto) {
		getScope(context).launch {
			val response = withContext(Dispatchers.IO) {
				api.instantMixApi.getInstantMixFromItem(
					itemId = item.id,
					fields = ItemRepository.itemFields
				).content
			}

			val items = response.items
			if (items.isNotEmpty()) {
				playbackLauncher.launch(context, items)
			} else {
				Toast.makeText(context, R.string.msg_no_playable_items, Toast.LENGTH_LONG).show()
			}
		}
	}

	private fun getScope(context: Context? = null) = when (context) {
		is LifecycleOwner -> context.lifecycleScope
		else -> ProcessLifecycleOwner.get().lifecycleScope
	}
}
