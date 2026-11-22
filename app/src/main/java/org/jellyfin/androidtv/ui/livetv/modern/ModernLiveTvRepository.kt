package org.jellyfin.androidtv.ui.livetv.modern

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.home.modern.CardStyle
import org.jellyfin.androidtv.ui.home.modern.HeroSlide
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.ModernImageSizes
import org.jellyfin.androidtv.ui.library.modern.LibrarySection
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.channelPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** A channel of the Channels and Favourites tabs, with what it shows now */
data class LiveChannel(
	val id: UUID,
	val item: BaseItemDto,
	val number: String?,
	val name: String,
	val logoUrl: String?,
	val program: BaseItemDto?,
	/** Picture of the programme on now (Favourites tab) */
	val programImageUrl: String?,
	val favorite: Boolean,
) {
	/** Number, name and programme, without accents, for the search */
	val searchText: String = foldForSearch(listOfNotNull(number, name, program?.name).joinToString(" "))
}

/** Lower case without accents: "Fútbol" is found with "futbol" */
internal fun foldForSearch(text: String): String =
	Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

/**
 * Live TV, like the Programmes tab of Jellyfin web: a media bar with the highlights of the guide
 * (a football match on now or about to start, films on now, premieres...) and the rows On now,
 * Shows, Movies, Sports, For kids and News. Then the channels, for the Channels and Favourites
 * tabs. Everything comes from the EPG of the server, through [LiveTvProgramLoader].
 */
class ModernLiveTvRepository(
	private val context: Context,
	private val api: ApiClient,
	private val loader: LiveTvProgramLoader,
	private val itemMutationRepository: ItemMutationRepository,
) {
	data class ProgrammesPage(
		val hero: List<HeroSlide>,
		val sections: List<LibrarySection>,
		/** Why the rows are empty, when every request failed */
		val error: String?,
	)

	private data class Row(val key: String, val title: Int, val programs: List<BaseItemDto>?, val error: Throwable?)

	private data class RowRequest(val key: String, val title: Int, val recommended: Boolean, val query: Map<String, Any?>)

	// Programmes tab

	/**
	 * The media bar and the rows, given to [onUpdate] as each row arrives. A few rows at a time and
	 * only the next hours: the queries of the guide are heavy for the server, and a channel tuned in
	 * meanwhile waits for them.
	 */
	suspend fun programmes(onUpdate: (ProgrammesPage) -> Unit = {}): ProgrammesPage = coroutineScope {
		loader.syncClock()
		val serverNow = LiveTvClock.serverNow()
		val now = LiveTvClock.now()
		// What hasn't finished yet and starts in the next hours: on now first, then coming up
		val window = mapOf(
			"minEndDate" to LiveTvProgramLoader.queryDate(serverNow),
			"maxStartDate" to LiveTvProgramLoader.queryDate(serverNow.plus(ROW_WINDOW)),
			"sortBy" to listOf(ItemSortBy.START_DATE),
		)
		// Same rows as Jellyfin web: on now from the recommendations, then each kind
		val requests = listOf(
			RowRequest(SECTION_ON_NOW, R.string.live_tv_on_now, recommended = true, ROW_ON_NOW),
			RowRequest(SECTION_SHOWS, R.string.live_tv_shows, recommended = false, window + ROW_SHOWS),
			RowRequest(SECTION_MOVIES, R.string.live_tv_movies, recommended = false, window + ROW_MOVIES),
			RowRequest(SECTION_SPORTS, R.string.live_tv_sports, recommended = false, window + ROW_SPORTS),
			RowRequest(SECTION_KIDS, R.string.live_tv_kids, recommended = false, window + ROW_KIDS),
			RowRequest(SECTION_NEWS, R.string.live_tv_news, recommended = false, window + ROW_NEWS),
		)

		val semaphore = Semaphore(PARALLEL_ROWS)
		val done = mutableMapOf<String, Row>()
		fun page() = buildPage(requests.mapNotNull { done[it.key] }, now, complete = done.size == requests.size)

		requests.map { request ->
			async {
				val row = semaphore.withPermit {
					try {
						val programs = if (request.recommended) loader.recommended(PROGRAM_QUERY + request.query)
						else loader.programs(PROGRAM_QUERY + request.query)
						Row(request.key, request.title, programs, null)
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Unable to load the Live TV row ${request.key}")
						Row(request.key, request.title, null, err)
					}
				}
				// The coroutines of the page run on the main thread: one at a time here
				done[request.key] = row
				onUpdate(page())
			}
		}.awaitAll()

		page()
	}

	private fun buildPage(rows: List<Row>, now: LocalDateTime, complete: Boolean): ProgrammesPage {
		fun programs(key: String) = rows.firstOrNull { it.key == key }?.programs.orEmpty()

		val sections = rows.mapNotNull { row ->
			val programs = row.programs ?: return@mapNotNull null
			val cards = programs.distinctBy { it.id }.take(ROW_LIMIT).map { programCard(it, now) }
			if (cards.isEmpty()) null
			else LibrarySection(row.key, context.getString(row.title), CardStyle.LANDSCAPE, cards)
		}
		val error = if (complete) rows.mapNotNull { it.error }.takeIf { it.size == rows.size }?.firstOrNull() else null

		return ProgrammesPage(
			hero = highlights(now, programs(SECTION_SPORTS), programs(SECTION_MOVIES), programs(SECTION_ON_NOW), programs(SECTION_SHOWS)),
			sections = sections,
			error = error?.let(LiveTvProgramLoader::describe),
		)
	}

	// Channels and Favourites tabs

	/** Every channel with what it shows now, in the order of their numbers */
	suspend fun channels(): List<LiveChannel> = loader.channels(
		mapOf(
			"addCurrentProgram" to true,
			"enableUserData" to true,
			"enableImages" to true,
			"imageTypeLimit" to 1,
			"enableImageTypes" to listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
			"fields" to listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
			"sortBy" to listOf(ItemSortBy.SORT_NAME),
			"enableTotalRecordCount" to false,
		)
	).map(::liveChannel)

	private fun liveChannel(channel: BaseItemDto): LiveChannel {
		val program = channel.currentProgram
		return LiveChannel(
			id = channel.id,
			item = channel,
			number = channel.number?.takeIf { it.isNotBlank() } ?: channel.channelNumber?.takeIf { it.isNotBlank() },
			name = channel.name.orEmpty(),
			logoUrl = channel.itemImages[ImageType.PRIMARY]?.getUrl(api, maxHeight = CHANNEL_TILE_LOGO_HEIGHT, format = ImageFormat.PNG),
			program = program,
			programImageUrl = program?.let(::programImage)?.getUrl(api, maxWidth = ModernImageSizes.LANDSCAPE),
			favorite = channel.userData?.isFavorite == true,
		)
	}

	/** Adds the channel to the favourites or takes it out. Returns whether it is one now */
	suspend fun setFavorite(channelId: UUID, favorite: Boolean): Boolean =
		itemMutationRepository.setFavorite(channelId, favorite).isFavorite

	// Media bar

	/**
	 * The highlights: sports on now or about to start (a match), films on now, then premieres and
	 * live programmes and what is on now. Only programmes with a picture, one per channel; a
	 * landscape picture first (a poster in the media bar only when there are not enough).
	 */
	private fun highlights(
		now: LocalDateTime,
		sports: List<BaseItemDto>,
		movies: List<BaseItemDto>,
		onNow: List<BaseItemDto>,
		shows: List<BaseItemDto>,
	): List<HeroSlide> {
		val soon = now.plusHours(HIGHLIGHT_SOON_HOURS)
		fun startsSoon(program: BaseItemDto) = program.isOnNow(now) || program.startDate?.isBefore(soon) == true

		val candidates = (sports.filter(::startsSoon) +
			movies.filter { it.isOnNow(now) } +
			onNow.filter { it.isPremiere == true || it.isLive == true } +
			shows.filter { (it.isPremiere == true || it.isLive == true) && startsSoon(it) } +
			onNow)
			.filter { programImage(it) != null }
			.distinctBy { it.id }
			.distinctBy { it.channelId ?: it.id }

		val landscape = candidates.filter(::hasLandscapeImage)
		val chosen = if (landscape.size >= MIN_HERO_SLIDES) landscape else landscape + candidates.filterNot(::hasLandscapeImage)
		return chosen.take(HERO_LIMIT).map { heroSlide(it, now) }
	}

	private fun hasLandscapeImage(program: BaseItemDto): Boolean {
		val images = program.itemImages
		return images[ImageType.THUMB] != null || images[ImageType.BACKDROP] != null ||
			(images[ImageType.PRIMARY] != null && (program.primaryImageAspectRatio ?: 0.0) >= LANDSCAPE_RATIO)
	}

	private fun heroSlide(program: BaseItemDto, now: LocalDateTime): HeroSlide {
		val image = programImage(program)
		val onNow = program.isOnNow(now)
		return HeroSlide(
			item = program,
			title = program.name.orEmpty(),
			backdropUrl = image?.getUrl(api, maxWidth = ModernImageSizes.HERO),
			backgroundUrl = image?.let { blurred(it, ModernImageSizes.BACKGROUND, ModernImageSizes.BACKGROUND_BLUR) },
			glassUrl = image?.let { blurred(it, ModernImageSizes.GLASS, ModernImageSizes.GLASS_BLUR) },
			logoUrl = null,
			rating = program.communityRating?.takeIf { it > 0f },
			year = program.productionYear.takeIf { program.isMovie == true },
			runtimeMinutes = program.durationMinutes(),
			officialRating = program.officialRating,
			genres = program.genres.orEmpty().take(3),
			overview = listOfNotNull(program.episodeTitle?.takeIf { it.isNotBlank() && it != program.name }, program.overview)
				.joinToString(" — ")
				.ifEmpty { null },
			quality = null,
			liveInfo = listOfNotNull(timeRange(program, now), program.channelName).joinToString("  ·  "),
			channelLogoUrl = program.channelPrimaryImage?.getUrl(api, maxHeight = CHANNEL_LOGO_HEIGHT, format = ImageFormat.PNG),
			onNow = onNow,
			playLabel = context.getString(if (onNow) R.string.live_tv_watch_now else R.string.live_tv_watch_channel),
		)
	}

	private fun blurred(image: JellyfinImage, width: Int, blur: Int): String = api.imageApi.getItemImageUrl(
		itemId = image.item,
		imageType = image.type,
		tag = image.tag,
		maxWidth = width,
		quality = 70,
		blur = blur,
	)

	// Rows

	/**
	 * A programme: its picture (the logo of the channel when it has none), its time ("20:00 – 21:00"
	 * on now, "Sun 22 Nov 10:30" later), the episode and the channel
	 */
	private fun programCard(program: BaseItemDto, now: LocalDateTime): HomeCard {
		val onNow = program.isOnNow(now)
		val start = program.startDate
		val end = program.endDate
		val total = if (start != null && end != null) Duration.between(start, end).toMillis() else 0L
		val elapsed = if (start != null) Duration.between(start, now).toMillis() else 0L
		val image = programImage(program)
		val episode = episodeLabel(program)
		return HomeCard(
			key = program.id.toString(),
			item = program,
			targetId = program.id,
			title = program.name.orEmpty(),
			subtitle = listOfNotNull(
				timeRange(program, now, withEnd = onNow),
				episode,
				program.channelName,
			).joinToString(" · ").ifEmpty { null },
			imageUrl = image?.getUrl(api, maxWidth = ModernImageSizes.LANDSCAPE)
				?: program.channelPrimaryImage?.getUrl(api, maxHeight = CHANNEL_TILE_LOGO_HEIGHT, format = ImageFormat.PNG),
			logoImage = image == null,
			progress = if (onNow && total > 0) (elapsed.toFloat() / total).coerceIn(0f, 1f) else null,
			remainingMinutes = if (onNow && end != null) Duration.between(now, end).toMinutes().toInt().coerceAtLeast(1) else null,
			playId = program.channelId,
		)
	}

	/** "S1:E4", like the web */
	private fun episodeLabel(program: BaseItemDto): String? {
		val season = program.parentIndexNumber
		val episode = program.indexNumber
		return when {
			season != null && episode != null -> "S$season:E$episode"
			episode != null -> "E$episode"
			else -> null
		}
	}

	// Formatting

	private val timeFormatter: DateTimeFormatter
		get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

	/** "20:00 – 21:45" on now or later today, "Tomorrow 20:00", "Sun 22 Nov 10:30" another day */
	private fun timeRange(program: BaseItemDto, now: LocalDateTime, withEnd: Boolean = true): String? {
		val start = program.startDate ?: return null
		val end = program.endDate
		val startText = when (start.toLocalDate()) {
			now.toLocalDate() -> start.format(timeFormatter)
			now.toLocalDate().plusDays(1) -> context.getString(R.string.live_tv_tomorrow_at, start.format(timeFormatter))
			else -> dayAndTime(start)
		}
		return if (withEnd && end != null) "$startText – ${end.format(timeFormatter)}" else startText
	}

	private fun dayAndTime(date: LocalDateTime): String {
		val today = LocalDate.now()
		return when (date.toLocalDate()) {
			today -> context.getString(R.string.live_tv_today_at, date.format(timeFormatter))
			else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + " " + date.format(timeFormatter)
		}
	}

	companion object {
		const val SECTION_ON_NOW = "on_now"
		const val SECTION_SHOWS = "shows"
		const val SECTION_MOVIES = "movies"
		const val SECTION_SPORTS = "sports"
		const val SECTION_KIDS = "kids"
		const val SECTION_NEWS = "news"

		private const val ROW_LIMIT = 30
		private const val HERO_LIMIT = 8
		private const val MIN_HERO_SLIDES = 4
		private const val CHANNEL_LOGO_HEIGHT = 80
		private const val CHANNEL_TILE_LOGO_HEIGHT = 160
		private const val LANDSCAPE_RATIO = 1.2
		/** A match that starts within this many hours is a highlight already */
		private const val HIGHLIGHT_SOON_HOURS = 3L

		/** What every row asks for */
		private val PROGRAM_QUERY: Map<String, Any?> = mapOf(
			"limit" to ROW_LIMIT,
			"imageTypeLimit" to 1,
			"enableImageTypes" to listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
			"fields" to listOf(
				ItemFields.CHANNEL_INFO,
				ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
				ItemFields.OVERVIEW,
				ItemFields.GENRES,
			),
			"enableTotalRecordCount" to false,
		)

		/** How far ahead the rows look */
		private val ROW_WINDOW: Duration = Duration.ofHours(12)
		/** Rows asked for at the same time */
		private const val PARALLEL_ROWS = 2

		private val ROW_ON_NOW: Map<String, Any?> = mapOf("isAiring" to true)
		private val ROW_SHOWS: Map<String, Any?> = mapOf(
			"isSeries" to true,
			"isMovie" to false,
			"isSports" to false,
			"isKids" to false,
			"isNews" to false,
		)
		private val ROW_MOVIES: Map<String, Any?> = mapOf("isMovie" to true)
		private val ROW_SPORTS: Map<String, Any?> = mapOf("isSports" to true)
		private val ROW_KIDS: Map<String, Any?> = mapOf("isKids" to true)
		private val ROW_NEWS: Map<String, Any?> = mapOf("isNews" to true)
	}
}

/** The programme's own picture: its thumb or its primary image */
internal fun programImage(program: BaseItemDto): JellyfinImage? =
	program.itemImages[ImageType.THUMB] ?: program.itemImages[ImageType.PRIMARY] ?: program.itemImages[ImageType.BACKDROP]

/** The programme is on at [now] */
internal fun BaseItemDto.isOnNow(now: LocalDateTime = LiveTvClock.now()): Boolean {
	val start = startDate ?: return false
	val end = endDate ?: return false
	return !start.isAfter(now) && end.isAfter(now)
}

internal fun BaseItemDto.durationMinutes(): Int? {
	val start = startDate ?: return null
	val end = endDate ?: return null
	return Duration.between(start, end).toMinutes().toInt().takeIf { it > 0 }
}

/** How far the programme is, between 0 and 1, null when it isn't on */
internal fun BaseItemDto.progressAt(now: LocalDateTime): Float? {
	val start = startDate ?: return null
	val end = endDate ?: return null
	if (!isOnNow(now)) return null
	val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
	return (Duration.between(start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

/** Programmes and channels open the programme page or tune in; recordings their details */
internal val BaseItemDto.isLiveTvProgram: Boolean
	get() = type == BaseItemKind.PROGRAM || type == BaseItemKind.LIVE_TV_PROGRAM
