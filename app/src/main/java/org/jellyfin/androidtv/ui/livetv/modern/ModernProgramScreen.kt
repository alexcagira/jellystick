package org.jellyfin.androidtv.ui.livetv.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.asTimerInfoDto
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.CardRow
import org.jellyfin.androidtv.ui.home.modern.CardStyle
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.LiveBadge
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.ModernImageSizes
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.formatRuntime
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.channelPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class ModernProgramState(
	val program: BaseItemDto? = null,
	/** What the channel shows after it */
	val later: List<BaseItemDto> = emptyList(),
	val busy: Boolean = false,
)

class ModernProgramViewModel(
	private val api: ApiClient,
	private val loader: LiveTvProgramLoader,
) : ViewModel() {
	private val _state = MutableStateFlow(ModernProgramState())
	val state: StateFlow<ModernProgramState> = _state.asStateFlow()

	fun load(program: BaseItemDto) {
		if (_state.value.program?.id == program.id) return
		_state.value = ModernProgramState(program = program)
		viewModelScope.launch {
			// The latest of it (recordings set since the list was read)
			reload(program.id.toString())
			val channelId = program.channelId ?: return@launch
			val later = runCatching {
				loader.programs(
					mapOf(
						"channelIds" to listOf(channelId),
						"minStartDate" to LiveTvProgramLoader.queryDate(program.endDate?.let(LiveTvClock::programInstant) ?: LiveTvClock.serverNow()),
						"sortBy" to listOf(ItemSortBy.START_DATE),
						"sortOrder" to listOf(SortOrder.ASCENDING),
						"fields" to listOf(ItemFields.OVERVIEW, ItemFields.CHANNEL_INFO),
						"imageTypeLimit" to 1,
						"enableTotalRecordCount" to false,
						"limit" to LATER_LIMIT,
					)
				)
			}.onFailure { Timber.w(it, "Unable to load what comes later on the channel") }.getOrNull().orEmpty()
			_state.update { it.copy(later = later) }
		}
	}

	private suspend fun reload(programId: String) {
		val fresh = runCatching { withContext(Dispatchers.IO) { api.liveTvApi.getProgram(programId).content } }
			.onFailure { Timber.w(it, "Unable to read the programme") }
			.getOrNull() ?: return
		_state.update { it.copy(program = fresh) }
	}

	/** Record the programme, or stop recording it. Returns false when the server refused */
	fun toggleRecording(series: Boolean, onDone: (Boolean) -> Unit) {
		val program = _state.value.program ?: return
		if (_state.value.busy) return
		_state.update { it.copy(busy = true) }
		viewModelScope.launch {
			val ok = try {
				withContext(Dispatchers.IO) {
					val api = api.liveTvApi
					val programId = program.id.toString()
					when {
						series && program.seriesTimerId != null -> api.cancelSeriesTimer(program.seriesTimerId!!)
						series -> api.createSeriesTimer(api.getDefaultTimer(programId).content)
						program.timerId != null -> api.cancelTimer(program.timerId!!)
						else -> api.createTimer(api.getDefaultTimer(programId).content.asTimerInfoDto())
					}
				}
				true
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to change the recording of ${program.name}")
				false
			}
			reload(program.id.toString())
			_state.update { it.copy(busy = false) }
			onDone(ok)
		}
	}

	private companion object {
		const val LATER_LIMIT = 12
	}
}

class ModernProgramFragment : Fragment() {
	private val viewModel by viewModel<ModernProgramViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val program = arguments?.getString(ARGUMENT_PROGRAM)?.let { json ->
			runCatching { Json.decodeFromString<BaseItemDto>(json) }
				.onFailure { Timber.w(it, "Invalid programme argument") }
				.getOrNull()
		}
		if (program != null) viewModel.load(program)
		return content { ModernProgramScreen(viewModel) }
	}

	companion object {
		const val ARGUMENT_PROGRAM = "program"
	}
}

/**
 * A programme of the guide, like the details pages: its picture as the background, the channel
 * logo where a title logo goes, when it's on and what it is, and the buttons to watch the channel
 * and to record it (or the series). Below, what the channel shows after it.
 */
@Composable
fun ModernProgramScreen(viewModel: ModernProgramViewModel) {
	val state by viewModel.state.collectAsState()
	val program = state.program ?: return
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val imageLoader = koinInject<ImageLoader>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val navigationRepository = koinInject<NavigationRepository>()
	val watchFocusRequester = remember { FocusRequester() }
	val now = LiveTvClock.now()
	val onNow = program.isOnNow(now)
	val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()) }

	val backdropUrl = remember(program.id) { programImage(program)?.getUrl(api, maxWidth = ModernImageSizes.HERO) }
	val channelLogoUrl = remember(program.id) { program.channelPrimaryImage?.getUrl(api, maxHeight = 160, format = ImageFormat.PNG) }

	val recordFailed = stringResource(R.string.live_tv_record_failed)
	fun toggle(series: Boolean) = viewModel.toggleRecording(series) { ok ->
		if (!ok) Toast.makeText(context, recordFailed, Toast.LENGTH_LONG).show()
	}

	LaunchedEffect(program.id) {
		awaitFrames(2)
		watchFocusRequester.tryRequestFocus()
	}

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background),
	) {
		// The programme's picture over the whole screen
		if (backdropUrl != null) {
			AsyncImage(
				model = backdropUrl,
				contentDescription = null,
				imageLoader = imageLoader,
				contentScale = ContentScale.Crop,
				alignment = Alignment.TopCenter,
				modifier = Modifier.fillMaxSize(),
			)
		}
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.horizontalGradient(
						0f to Color.Black.copy(alpha = 0.88f),
						0.45f to Color.Black.copy(alpha = 0.55f),
						0.8f to Color.Black.copy(alpha = 0.1f),
					)
				),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0.45f to Color.Transparent,
						1f to ModernHomeColors.Background,
					)
				),
		)

		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(top = 56.dp, bottom = 40.dp),
		) {
			Column(
				modifier = Modifier
					.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal)
					.fillMaxWidth(0.58f),
			) {
				// The channel's logo where a film has its title logo
				if (channelLogoUrl != null) {
					AsyncImage(
						model = channelLogoUrl,
						contentDescription = program.channelName,
						imageLoader = imageLoader,
						contentScale = ContentScale.Fit,
						alignment = Alignment.BottomStart,
						modifier = Modifier
							.height(64.dp)
							.widthIn(max = 220.dp),
					)
				} else if (!program.channelName.isNullOrBlank()) {
					Text(
						text = program.channelName.orEmpty(),
						color = ModernHomeColors.TextSecondary,
						fontSize = 18.sp,
						fontWeight = FontWeight.Bold,
					)
				}

				Spacer(Modifier.height(16.dp))
				Text(
					text = program.name.orEmpty(),
					color = ModernHomeColors.TextPrimary,
					fontSize = 36.sp,
					lineHeight = 40.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				program.episodeTitle?.takeIf { it.isNotBlank() && it != program.name }?.let { episode ->
					Spacer(Modifier.height(4.dp))
					Text(
						text = episode,
						color = ModernHomeColors.TextSecondary,
						fontSize = 17.sp,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				Spacer(Modifier.height(12.dp))
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					if (onNow) LiveBadge()
					val start = program.startDate
					val end = program.endDate
					val parts = listOfNotNull(
						if (start != null && end != null) "${start.format(timeFormatter)} – ${end.format(timeFormatter)}" else null,
						program.durationMinutes()?.let(::formatRuntime),
						program.channelName,
						program.productionYear?.takeIf { program.isMovie == true }?.toString(),
					)
					Text(
						text = parts.joinToString("  ·  "),
						color = ModernHomeColors.TextPrimary,
						fontSize = 15.sp,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
					)
					if (!program.officialRating.isNullOrBlank()) {
						Text(
							text = program.officialRating.orEmpty(),
							color = ModernHomeColors.TextSecondary,
							fontSize = 12.sp,
							fontWeight = FontWeight.Bold,
						)
					}
				}

				// On now: how much is left
				if (onNow) {
					val end = program.endDate
					val start = program.startDate
					if (start != null && end != null) {
						val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
						val elapsed = Duration.between(start, now).toMillis().coerceIn(0, total)
						Spacer(Modifier.height(10.dp))
						Row(verticalAlignment = Alignment.CenterVertically) {
							Box(
								modifier = Modifier
									.widthIn(max = 260.dp)
									.fillMaxWidth()
									.height(4.dp)
									.background(ModernHomeColors.ProgressTrack, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
							) {
								Box(
									modifier = Modifier
										.fillMaxWidth(elapsed.toFloat() / total)
										.height(4.dp)
										.background(ModernHomeColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
								)
							}
							Spacer(Modifier.padding(start = 10.dp))
							Text(
								text = stringResource(R.string.live_tv_minutes_left, Duration.between(now, end).toMinutes().coerceAtLeast(1)),
								color = ModernHomeColors.TextSecondary,
								fontSize = 13.sp,
							)
						}
					}
				}

				val genres = program.genres.orEmpty()
				if (genres.isNotEmpty()) {
					Spacer(Modifier.height(8.dp))
					Text(
						text = genres.take(4).joinToString(" · "),
						color = ModernHomeColors.TextSecondary,
						fontSize = 13.sp,
						fontWeight = FontWeight.Medium,
						maxLines = 1,
					)
				}

				if (!program.overview.isNullOrBlank()) {
					Spacer(Modifier.height(12.dp))
					Text(
						text = program.overview.orEmpty(),
						color = ModernHomeColors.TextSecondary,
						fontSize = 14.sp,
						lineHeight = 20.sp,
						maxLines = 4,
						overflow = TextOverflow.Ellipsis,
					)
				}

				Spacer(Modifier.height(24.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					HeroButton(
						text = stringResource(if (onNow) R.string.live_tv_watch_now else R.string.live_tv_watch_channel),
						icon = R.drawable.ic_play,
						primary = true,
						onClick = { program.channelId?.let { playbackHelper.retrieveAndPlay(it, false, context) } },
						modifier = Modifier.focusRequester(watchFocusRequester),
					)
					// What already aired can't be recorded
					val ended = program.endDate?.isBefore(now) == true
					if (!ended) {
						HeroButton(
							text = stringResource(if (program.timerId != null) R.string.lbl_cancel_recording else R.string.lbl_record),
							icon = if (program.timerId != null) R.drawable.ic_record_red else R.drawable.ic_record,
							primary = false,
							onClick = { toggle(series = false) },
						)
						if (program.isSeries == true) {
							HeroButton(
								text = stringResource(if (program.seriesTimerId != null) R.string.lbl_cancel_series else R.string.lbl_record_series),
								icon = if (program.seriesTimerId != null) R.drawable.ic_record_series_red else R.drawable.ic_record_series,
								primary = false,
								onClick = { toggle(series = true) },
							)
						}
					}
					HeroButton(
						text = stringResource(R.string.lbl_live_tv_guide),
						icon = R.drawable.ic_tv_guide,
						primary = false,
						onClick = { navigationRepository.navigate(Destinations.liveTvGuide) },
					)
				}
			}

			// What the channel shows after it
			if (state.later.isNotEmpty()) {
				val cards = state.later.map { later ->
					HomeCard(
						key = later.id.toString(),
						item = later,
						targetId = later.id,
						title = later.name.orEmpty(),
						subtitle = later.startDate?.format(timeFormatter),
						imageUrl = (programImage(later) ?: later.channelPrimaryImage)?.getUrl(api, maxWidth = ModernImageSizes.LANDSCAPE),
						playId = later.channelId,
					)
				}
				CardRow(
					style = CardStyle.LANDSCAPE,
					cards = cards,
					title = stringResource(R.string.live_tv_later_on, program.channelName.orEmpty()),
					initialCardIndex = 0,
					focusRequesterFor = { _, _ -> null },
					onCardClick = { card -> navigationRepository.navigate(Destinations.liveProgram(card.item)) },
					onCardLongClick = { card -> navigationRepository.navigate(Destinations.liveProgram(card.item)) },
					onCardPlay = { card -> card.playId?.let { playbackHelper.retrieveAndPlay(it, false, context) } },
					onCardFocused = {},
					modifier = Modifier.padding(top = 36.dp),
				)
			}
		}
	}
}
