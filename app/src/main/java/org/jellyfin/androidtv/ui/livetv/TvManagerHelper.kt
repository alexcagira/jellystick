package org.jellyfin.androidtv.ui.livetv

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.ui.livetv.modern.LiveTvClock
import org.jellyfin.androidtv.ui.livetv.modern.LiveTvProgramLoader
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

fun BaseItemDto.copyWithLastPlayedDate(
	lastPlayedDate: LocalDateTime,
) = copy(
	userData = userData?.copy(
		lastPlayedDate = lastPlayedDate,
	)
)

fun loadLiveTvChannels(fragment: Fragment, callback: (channels: Collection<BaseItemDto>?) -> Unit) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val sortDatePlayed =
			liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName

		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvChannels(
					addCurrentProgram = true,
					enableFavoriteSorting = liveTvPreferences[LiveTvPreferences.favsAtTop],
					sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
					sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
				).content.items
			}
		}.fold(
			onSuccess = { channels -> callback(channels) },
			onFailure = { callback(null) },
		)
	}
}

/**
 * The programmes of the guide. They come from [LiveTvProgramLoader]: more time for the server, the
 * channels in small groups, the hours asked for in the server's time and a programme that can't be
 * read left out instead of the whole guide. When nothing came, a message says why (the guide shows
 * "No program data available" otherwise).
 */
fun getPrograms(
	fragment: Fragment,
	channelIds: Array<UUID>,
	startTime: LocalDateTime,
	endTime: LocalDateTime,
	callback: (programs: Collection<BaseItemDto>?) -> Unit,
) {
	val loader by fragment.inject<LiveTvProgramLoader>()

	fragment.lifecycleScope.launch {
		val result = loader.guide(channelIds.toList(), startTime, endTime)
		val error = result.error
		val context = fragment.context
		if (error != null) Timber.w(error, "Unable to load (part of) the guide")

		if (context != null) {
			val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
			val message = when {
				result.items.isNotEmpty() -> null
				error != null -> context.getString(R.string.live_tv_guide_failed, LiveTvProgramLoader.describe(error))
				// Nothing and no error: the hours asked for and the clocks, to see what's wrong
				else -> context.getString(
					R.string.live_tv_guide_nothing,
					channelIds.size,
					result.from?.atZone(ZoneId.systemDefault())?.format(timeFormatter).orEmpty(),
					result.to?.atZone(ZoneId.systemDefault())?.format(timeFormatter).orEmpty(),
					result.received,
					LocalTime.now().format(timeFormatter),
					LiveTvClock.serverNow().atZone(ZoneId.systemDefault()).format(timeFormatter),
				)
			}
			if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			else LiveTvClock.mismatch()?.let { (device, server) ->
				// Once: the guide follows the TV's clock, which isn't the server's
				if (!clockWarningShown) {
					clockWarningShown = true
					Toast.makeText(
						context,
						context.getString(R.string.live_tv_clock_mismatch, device.format(timeFormatter), server.format(timeFormatter)),
						Toast.LENGTH_LONG,
					).show()
				}
			}
		}
		callback(if (result.items.isEmpty() && error != null) null else result.items)
	}
}

/** The warning about the TV's clock, once per start of the app */
private var clockWarningShown = false

fun getScheduleRows(
	fragment: Fragment,
	seriesTimerId: String?,
	callback: (timers: Map<LocalDate, List<BaseItemDto>>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getTimers(
					seriesTimerId = seriesTimerId,
				).content.items
			}
		}.fold(
			onSuccess = { timers ->
				val groupedTimers = timers
					.filterNot { it.startDate == null }
					.map { timer ->
						timer.programInfo ?: BaseItemDto(
							id = requireNotNull(timer.id?.toUUIDOrNull()),
							channelName = timer.channelName,
							name = timer.name.orEmpty(),
							type = BaseItemKind.PROGRAM,
							mediaType = MediaType.UNKNOWN,
							timerId = timer.id,
							seriesTimerId = timer.seriesTimerId,
							startDate = timer.startDate,
							endDate = timer.endDate,
						)
					}
					.map { it.copy(locationType = LocationType.VIRTUAL) }
					.groupBy { it.startDate!!.toLocalDate() }

				callback(groupedTimers)
			},
			onFailure = {
				callback(null)
			},
		)
	}
}
