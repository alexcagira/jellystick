package org.jellyfin.androidtv.ui.livetv.modern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the programmes of the guide (the guide, the guide of the player and the Live TV page).
 *
 * The SDK reads a whole answer or nothing: one programme of the EPG with a field the app doesn't
 * expect (a number too big, an id that isn't one...) left the whole guide with "No program data
 * available", and the guide of a big lineup can take the server longer than the usual 30 seconds.
 * Here the request has more time, the channels go in small groups (the address of a request with
 * hundreds of channels is too long for the server) and each programme is read on its own: one that
 * can't be read is read again with only the fields the app uses, or left out.
 *
 * The times asked for are the server's ([LiveTvClock]): with the clock or the time zone of the TV
 * different from the server's, the guide asked for hours with nothing in them.
 */
class LiveTvProgramLoader(
	private val api: ApiClient,
	private val okHttpFactory: OkHttpFactory,
) {
	private val client by lazy {
		okHttpFactory.createClient(
			HttpClientOptions(
				requestTimeout = 120.seconds,
				connectTimeout = 10.seconds,
				socketTimeout = 90.seconds,
			)
		)
	}

	/** What a request gave, and why a part of it failed */
	class Result(
		val items: List<BaseItemDto>,
		val error: Throwable?,
		/** Programmes the server sent, before leaving out the ones without times */
		val received: Int = items.size,
		/** The hours asked for, in the server's time */
		val from: Instant? = null,
		val to: Instant? = null,
	)

	/**
	 * The guide: the programmes of [channelIds] between [start] and [end] of the TV's clock. Their
	 * times come back in the TV's clock too, so they line up with the timeline of the guide.
	 */
	suspend fun guide(channelIds: List<UUID>, start: LocalDateTime, end: LocalDateTime): Result = coroutineScope {
		syncClock()
		val from = LiveTvClock.toServerInstant(start)
		val to = LiveTvClock.toServerInstant(end)
		val parts = channelIds.chunked(CHANNELS_PER_REQUEST).map { ids ->
			async {
				attempt {
					programs(
						mapOf(
							"channelIds" to ids,
							"minEndDate" to queryDate(from),
							"maxStartDate" to queryDate(to),
							"sortBy" to listOf(ItemSortBy.START_DATE),
							"enableImages" to false,
							"enableUserData" to false,
							"enableTotalRecordCount" to false,
						)
					)
				}
			}
		}.awaitAll()

		val received = parts.flatMap { it.getOrNull().orEmpty() }
		Result(
			// The guide needs the channel and both times of each programme
			items = received
				.filter { it.channelId != null && it.startDate != null && it.endDate != null }
				.map(LiveTvClock::toDeviceClock),
			error = parts.firstNotNullOfOrNull { it.exceptionOrNull() },
			received = received.size,
			from = from,
			to = to,
		)
	}

	/** GET /LiveTv/Programs */
	suspend fun programs(query: Map<String, Any?>): List<BaseItemDto> = items("/LiveTv/Programs", query)

	/** GET /LiveTv/Programs/Recommended */
	suspend fun recommended(query: Map<String, Any?>): List<BaseItemDto> = items("/LiveTv/Programs/Recommended", query)

	/** GET /LiveTv/Channels */
	suspend fun channels(query: Map<String, Any?>): List<BaseItemDto> = items("/LiveTv/Channels", query)

	/** Reads the server's time when it hasn't been read for a while (any answer brings it) */
	suspend fun syncClock() {
		if (!LiveTvClock.stale) return
		try {
			call("/System/Ping", emptyMap())
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to read the time of the server")
		}
	}

	private suspend fun items(path: String, query: Map<String, Any?>): List<BaseItemDto> =
		LenientItems.decodeList(call(path, query))

	private suspend fun call(path: String, query: Map<String, Any?>): String {
		val url = api.createUrl(path, queryParameters = query)
		val authorization = AuthorizationHeaderBuilder.buildHeader(
			clientName = api.clientInfo.name,
			clientVersion = api.clientInfo.version,
			deviceId = api.deviceInfo.id,
			deviceName = api.deviceInfo.name,
			accessToken = api.accessToken,
		)
		val request = Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.header("Authorization", authorization)
			.build()

		return runInterruptible(Dispatchers.IO) {
			client.newCall(request).execute().use { response ->
				LiveTvClock.record(response.header("Date"))
				if (!response.isSuccessful) throw HttpStatusException(response.code)
				response.body?.string().orEmpty()
			}
		}
	}

	class HttpStatusException(val status: Int) : Exception("HTTP $status")

	companion object {
		/** 25 channel ids make an address of about 1.2 KB */
		private const val CHANNELS_PER_REQUEST = 25

		/** A time for a request: the instant itself ("2026-11-22T20:00:00Z"), not the TV's hour */
		fun queryDate(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

		/** Why the guide is empty, in a few words for a message */
		fun describe(error: Throwable): String = when (error) {
			is HttpStatusException -> "HTTP ${error.status}"
			is InterruptedIOException -> "timeout"
			is UnknownHostException -> "server unreachable"
			else -> listOfNotNull(error::class.simpleName, error.message?.take(80)).joinToString(": ")
		}
	}
}

/**
 * The server's time. The guide compares the times of the programmes with "now": when the clock or
 * the time zone of the TV don't match the server's, the programme on now looked hours away (the
 * guide of the TV asked for 17:00 when the server was at 20:00) and the guide stayed empty.
 *
 * Every answer of the server says its time (the Date header). From it:
 * - [toServerInstant]: an hour of the TV's clock as the instant on the server (to ask for it).
 * - [now]: the server's "now" in the hours the app reads the programmes in (to compare with them).
 * - [toDeviceClock]: a programme moved to the TV's clock (the legacy guide works with the TV's).
 */
object LiveTvClock {
	private class Sample(
		/** The TV's clock when the answer came */
		val device: LocalDateTime,
		val nanos: Long,
		/** The server's time then */
		val server: Instant,
		/** How far the hours of the programmes are from the TV's clock */
		val offset: Duration,
	)

	@Volatile
	private var sample: Sample? = null

	/** Under this, the clocks match (the Date header only has seconds, the network takes some) */
	private val TOLERANCE = Duration.ofMinutes(2)
	private val MAX_AGE_NANOS = Duration.ofMinutes(15).toNanos()

	val stale: Boolean
		get() = sample?.let { System.nanoTime() - it.nanos > MAX_AGE_NANOS } ?: true

	/** How far the hours of the programmes are from the TV's clock (zero when they match) */
	val offset: Duration
		get() = sample?.offset ?: Duration.ZERO

	/** "Now" for the programmes: the server's time in the hours the app reads them in */
	fun now(): LocalDateTime = LocalDateTime.now().plus(offset)

	/** The server's time now */
	fun serverNow(): Instant = sample?.let { it.server.plusNanos(System.nanoTime() - it.nanos) } ?: Instant.now()

	/** An hour of the TV's clock, as the instant it is on the server */
	fun toServerInstant(deviceTime: LocalDateTime): Instant = sample
		?.let { it.server.plus(Duration.between(it.device, deviceTime)) }
		?: deviceTime.atZone(ZoneId.systemDefault()).toInstant()

	/** An hour of a programme (as the app reads it), as the instant it is on the server */
	fun programInstant(programTime: LocalDateTime): Instant = toServerInstant(programTime.minus(offset))

	/** The programme with its times in the TV's clock */
	fun toDeviceClock(program: BaseItemDto): BaseItemDto {
		val offset = offset
		if (offset.isZero) return program
		return program.copy(
			startDate = program.startDate?.minus(offset),
			endDate = program.endDate?.minus(offset),
		)
	}

	/** The TV's clock and the server's, "17:20" and "20:20", when they don't match */
	fun mismatch(): Pair<LocalTime, LocalTime>? {
		if (offset.isZero) return null
		val device = LocalTime.now()
		return device to device.plus(offset)
	}

	/** An answer came with the server's time */
	fun record(dateHeader: String?) {
		if (dateHeader.isNullOrBlank()) return
		val device = LocalDateTime.now()
		val nanos = System.nanoTime()
		val server = try {
			// The header has whole seconds: half a second more is closer on average
			ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().plusMillis(500)
		} catch (err: Exception) {
			Timber.w(err, "Unreadable Date header %s", dateHeader)
			return
		}
		// That instant read like the times of the programmes (the SDK's time zone)
		val programTime = LenientItems.programTime(server) ?: server.atZone(ZoneId.systemDefault()).toLocalDateTime()
		val difference = Duration.between(device, programTime)
		val offset = if (difference.abs() < TOLERANCE) Duration.ZERO else Duration.ofMinutes(Math.round(difference.seconds / 60.0))
		if (offset != sample?.offset) Timber.i("Live TV clock: programmes are %s from the TV clock", offset)
		sample = Sample(device, nanos, server, offset)
	}
}

/** [block], its exception as a result (except a cancellation) */
private inline fun <T> attempt(block: () -> T): kotlin.Result<T> = try {
	kotlin.Result.success(block())
} catch (err: CancellationException) {
	throw err
} catch (err: Exception) {
	Timber.w(err, "Live TV request failed")
	kotlin.Result.failure(err)
}

/**
 * Items read one by one: an item that can't be read whole is read again with the fields the app
 * uses only (the ones with a value of the expected type), or left out.
 */
internal object LenientItems {
	private val json = Json {
		isLenient = true
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		explicitNulls = false
		coerceInputValues = true
	}

	private val STRING_KEYS = setOf(
		"Name", "ServerId", "Etag", "SortName", "ChannelName", "ChannelNumber", "Number",
		"ChannelPrimaryImageTag", "EpisodeTitle", "Overview", "OfficialRating", "SeriesName",
		"ParentThumbImageTag", "ParentPrimaryImageTag", "TimerId", "SeriesTimerId", "MediaType",
		"ChannelType",
	)
	private val BOOLEAN_KEYS = setOf(
		"IsMovie", "IsSeries", "IsSports", "IsNews", "IsKids", "IsLive", "IsPremiere", "IsRepeat",
		"IsHD", "IsFolder",
	)
	private val INT_KEYS = setOf("ProductionYear", "IndexNumber", "ParentIndexNumber")
	private val LONG_KEYS = setOf("RunTimeTicks")
	private val NUMBER_KEYS = setOf("CommunityRating", "PrimaryImageAspectRatio")
	private val UUID_KEYS = setOf(
		"ChannelId", "ParentId", "SeriesId", "ParentBackdropItemId", "ParentThumbItemId",
		"ParentPrimaryImageItemId", "ParentLogoItemId",
	)
	private val DATE_KEYS = setOf("StartDate", "EndDate", "PremiereDate", "DateCreated")
	private val STRING_LIST_KEYS = setOf("Genres", "BackdropImageTags", "ParentBackdropImageTags")
	private val ITEM_KINDS by lazy { BaseItemKind.entries.map { it.serialName }.toSet() }
	private val IMAGE_TYPES by lazy { ImageType.entries.map { it.serialName }.toSet() }

	/** A date without its zone: the server's dates are UTC */
	private val DATE_WITHOUT_ZONE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$""")

	fun decodeList(body: String): List<BaseItemDto> {
		if (body.isBlank()) return emptyList()
		val items = when (val root = json.parseToJsonElement(body)) {
			is JsonArray -> root
			is JsonObject -> root["Items"] as? JsonArray
			else -> null
		} ?: return emptyList()

		var skipped = 0
		val decoded = items.mapNotNull { element ->
			(element as? JsonObject)?.let(::decodeItem) ?: run {
				skipped++
				null
			}
		}
		if (skipped > 0) Timber.w("Left out %d Live TV items the app can't read", skipped)
		return decoded
	}

	/** [instant] read the way the times of the programmes are read */
	fun programTime(instant: Instant): LocalDateTime? = try {
		json.decodeFromJsonElement<BaseItemDto>(
			JsonObject(
				mapOf(
					"Id" to JsonPrimitive("00000000000000000000000000000001"),
					"Type" to JsonPrimitive(BaseItemKind.PROGRAM.serialName),
					"StartDate" to JsonPrimitive(instant.truncatedTo(ChronoUnit.MILLIS).toString()),
				)
			)
		).startDate
	} catch (err: Exception) {
		Timber.w(err, "Unable to read a time like the programmes")
		null
	}

	fun decodeItem(item: JsonObject): BaseItemDto? {
		val zoned = withZones(item)
		return try {
			json.decodeFromJsonElement<BaseItemDto>(zoned)
		} catch (err: Exception) {
			Timber.w(err, "Live TV item %s read with its main fields only", item["Name"])
			try {
				json.decodeFromJsonElement<BaseItemDto>(essentials(zoned))
			} catch (err: Exception) {
				Timber.w(err, "Live TV item %s left out", item["Name"])
				null
			}
		}
	}

	/** Dates without a zone are UTC (the SDK reads them as "no date") */
	private fun withZones(item: JsonObject): JsonObject {
		var changed = false
		val fixed = item.mapValues { (key, value) ->
			when {
				key in DATE_KEYS && value is JsonPrimitive && value.isString && DATE_WITHOUT_ZONE.matches(value.content) -> {
					changed = true
					JsonPrimitive(value.content + "Z")
				}

				key == "CurrentProgram" && value is JsonObject -> {
					val program = withZones(value)
					if (program !== value) changed = true
					program
				}

				else -> value
			}
		}
		return if (changed) JsonObject(fixed) else item
	}

	/** Only the fields the app uses, each with a value of the type it expects */
	private fun essentials(item: JsonObject): JsonObject {
		val kept = mutableMapOf<String, JsonElement>()
		for ((key, value) in item) {
			val primitive = value as? JsonPrimitive
			val clean: JsonElement? = when (key) {
				in STRING_KEYS -> value.takeIf { primitive?.isString == true }
				in BOOLEAN_KEYS -> value.takeIf { primitive != null && !primitive.isString && primitive.booleanOrNull != null }
				in INT_KEYS -> value.takeIf { primitive != null && !primitive.isString && primitive.intOrNull != null }
				in LONG_KEYS -> value.takeIf { primitive != null && !primitive.isString && primitive.longOrNull != null }
				in NUMBER_KEYS -> value.takeIf { primitive != null && !primitive.isString && primitive.doubleOrNull != null }
				in UUID_KEYS -> value.takeIf { primitive?.isString == true && primitive.content.toUUIDOrNull() != null }
				in DATE_KEYS -> value.takeIf { primitive?.isString == true && isDate(primitive.content) }
				in STRING_LIST_KEYS -> (value as? JsonArray)?.filter { it is JsonPrimitive && it.isString }?.let(::JsonArray)
				"Type" -> value.takeIf { primitive?.contentOrNull in ITEM_KINDS }
				"ImageTags" -> (value as? JsonObject)
					?.filter { (type, tag) -> type in IMAGE_TYPES && tag is JsonPrimitive && tag.isString }
					?.let(::JsonObject)

				"UserData" -> (value as? JsonObject)?.let { userData(it, item) }
				"CurrentProgram" -> (value as? JsonObject)?.let(::essentials)
				else -> null
			}
			if (clean != null) kept[key] = clean
		}

		// An item has to have an id and a kind
		val id = (item["Id"] as? JsonPrimitive)?.contentOrNull?.toUUIDOrNull()
			?: UUID.nameUUIDFromBytes(item.toString().toByteArray())
		kept["Id"] = JsonPrimitive(id.toString())
		if ("Type" !in kept) kept["Type"] = JsonPrimitive(BaseItemKind.PROGRAM.serialName)
		return JsonObject(kept)
	}

	/** The favourite flag of a channel, with what the SDK needs around it */
	private fun userData(userData: JsonObject, item: JsonObject): JsonObject {
		val favorite = (userData["IsFavorite"] as? JsonPrimitive)?.booleanOrNull ?: false
		val id = (item["Id"] as? JsonPrimitive)?.contentOrNull?.toUUIDOrNull() ?: UUID(0, 0)
		return JsonObject(
			mapOf(
				"PlaybackPositionTicks" to JsonPrimitive(0),
				"PlayCount" to JsonPrimitive(0),
				"IsFavorite" to JsonPrimitive(favorite),
				"Played" to JsonPrimitive(false),
				"Key" to JsonPrimitive(""),
				"ItemId" to JsonPrimitive(id.toString()),
			)
		)
	}

	private fun isDate(value: String): Boolean = try {
		ZonedDateTime.parse(value)
		true
	} catch (err: Exception) {
		false
	}
}
