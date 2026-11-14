package org.jellyfin.androidtv.ui.home.modern

import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Pure helpers to compute the badges shown on top of posters. Kept free of Android/Compose
 * dependencies so the same rules can be ported to (or shared with) the web customization.
 */
object MediaBadges {
	/** How long content is considered "new" after it was added to the server */
	const val NEW_CONTENT_WINDOW_DAYS = 14L

	private val dolbyVisionTypes = setOf(
		VideoRangeType.DOVI,
		VideoRangeType.DOVI_WITH_HDR10,
		VideoRangeType.DOVI_WITH_HLG,
		VideoRangeType.DOVI_WITH_SDR,
		VideoRangeType.DOVI_WITH_EL,
		VideoRangeType.DOVI_WITH_HDR10_PLUS,
		VideoRangeType.DOVI_WITH_ELHDR10_PLUS,
	)

	private val dolbyDigitalCodecs = setOf("ac3", "eac3")

	/**
	 * Compute the quality of an item based on its media streams. Requires the item to be requested
	 * with `ItemFields.MEDIA_STREAMS`. Returns null when no video stream is known.
	 */
	fun quality(item: BaseItemDto?): MediaQuality? {
		val streams = item?.mediaStreams ?: return null
		val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
		val audio = streams.filter { it.type == MediaStreamType.AUDIO }

		val width = video.width ?: item.width ?: 0
		val height = video.height ?: item.height ?: 0
		val resolution = when {
			width >= 3200 || height >= 2000 -> "4K"
			width >= 1800 || height >= 1000 -> "1080p"
			width >= 1200 || height >= 700 -> "720p"
			width > 0 || height > 0 -> "SD"
			else -> null
		}

		val dolbyVision = video.videoRangeType in dolbyVisionTypes
		val hdrFormat = when {
			dolbyVision -> null
			video.videoRangeType == VideoRangeType.HDR10_PLUS -> "HDR10+"
			video.videoRangeType == VideoRangeType.HLG -> "HLG"
			video.videoRangeType == VideoRangeType.HDR10 || video.videoRange == VideoRange.HDR -> "HDR"
			else -> null
		}

		val atmos = audio.any { stream ->
			stream.audioSpatialFormat == AudioSpatialFormat.DOLBY_ATMOS ||
				stream.profile?.contains("atmos", ignoreCase = true) == true ||
				stream.displayTitle?.contains("atmos", ignoreCase = true) == true
		}
		val codecs = audio.mapNotNull { it.codec?.lowercase() }
		val trueHd = !atmos && "truehd" in codecs
		val dolbyDigital = !atmos && !trueHd && codecs.any { it in dolbyDigitalCodecs }

		return MediaQuality(
			resolution = resolution,
			dolbyVision = dolbyVision,
			hdr = hdrFormat != null,
			hdrFormat = hdrFormat,
			atmos = atmos,
			dolbyDigital = dolbyDigital,
			trueHd = trueHd,
		).takeUnless { it.isEmpty }
	}

	/**
	 * Decide which "new" label each series gets, based on the most recently added episodes.
	 *
	 * Rules (per series, only episodes added in the last [windowDays] days are considered):
	 * - Episode 1 of season 1 was added → [NewContentLabel.NEW_SERIES]
	 * - Episode 1 of a later season was added → [NewContentLabel.NEW_SEASON]
	 * - Anything else → [NewContentLabel.NEW_EPISODE]
	 *
	 * @param recentEpisodes episodes sorted by date added (newest first), requested with `ItemFields.DATE_CREATED`
	 */
	fun newContentLabels(
		recentEpisodes: List<BaseItemDto>,
		now: LocalDateTime = LocalDateTime.now(),
		windowDays: Long = NEW_CONTENT_WINDOW_DAYS,
	): Map<UUID, NewContentLabel> {
		val cutoff = now.minusDays(windowDays)

		return recentEpisodes
			.filter { episode -> episode.seriesId != null && episode.dateCreated?.isAfter(cutoff) == true }
			.groupBy { episode -> requireNotNull(episode.seriesId) }
			.mapValues { (_, episodes) ->
				// Season 0 contains specials and never counts as a new season
				val startedSeasons = episodes
					.filter { it.indexNumber == 1 }
					.mapNotNull { it.parentIndexNumber }
					.filter { it > 0 }

				when {
					1 in startedSeasons -> NewContentLabel.NEW_SERIES
					startedSeasons.isNotEmpty() -> NewContentLabel.NEW_SEASON
					else -> NewContentLabel.NEW_EPISODE
				}
			}
	}

	/** Maximum number of language flags on a poster */
	const val MAX_LANGUAGE_FLAGS = 3

	/** Languages without meaning for the viewer (undetermined, no linguistic content, multiple...) */
	private val ignoredLanguages = setOf("und", "unk", "zxx", "mul", "mis", "qaa", "nar")

	/** ISO 639-1 / 639-2/T codes and common variants → ISO 639-2/B code */
	private val languageAliases = mapOf(
		"en" to "eng",
		"es" to "spa",
		"ja" to "jpn",
		"fr" to "fre", "fra" to "fre",
		"de" to "ger", "deu" to "ger",
		"it" to "ita",
		"pt" to "por",
		"ru" to "rus",
		"zh" to "chi", "zho" to "chi", "cmn" to "chi", "yue" to "chi",
		"ko" to "kor",
		"hi" to "hin",
		"nl" to "dut", "nld" to "dut",
		"sv" to "swe",
		"no" to "nor", "nb" to "nor", "nob" to "nor", "nn" to "nor", "nno" to "nor",
		"da" to "dan",
		"fi" to "fin",
		"pl" to "pol",
		"tr" to "tur",
		"el" to "gre", "ell" to "gre",
		"ca" to "cat",
		"eu" to "baq", "eus" to "baq",
		"gl" to "glg",
		"uk" to "ukr",
		"cs" to "cze", "ces" to "cze",
		"hu" to "hun",
		"ro" to "rum", "ron" to "rum",
		"th" to "tha",
		"vi" to "vie",
		"id" to "ind",
		"is" to "ice", "isl" to "ice",
		"ar" to "ara",
		"he" to "heb",
	)

	/** Flag drawn for each language, see LanguageFlags.kt */
	private val languageFlags = mapOf(
		"eng" to "gb",
		"spa" to "es",
		"spa-419" to "mx",
		"jpn" to "jp",
		"fre" to "fr",
		"ger" to "de",
		"ita" to "it",
		"por" to "pt",
		"por-br" to "br",
		"rus" to "ru",
		"chi" to "cn",
		"kor" to "kr",
		"hin" to "in",
		"dut" to "nl",
		"swe" to "se",
		"nor" to "no",
		"dan" to "dk",
		"fin" to "fi",
		"pol" to "pl",
		"tur" to "tr",
		"gre" to "gr",
		"cat" to "cat",
		"baq" to "eus",
		"glg" to "gal",
		"ukr" to "ua",
		"cze" to "cz",
		"hun" to "hu",
		"rum" to "ro",
		"tha" to "th",
		"vie" to "vn",
		"ind" to "id",
		"ice" to "is",
	)

	private val latinAmericanRegions = setOf("419", "la", "lat", "latam", "mx", "ar", "co", "cl", "pe", "ve", "us")
	private val latinAmericanTitle = Regex("""\b(latin\w*|latam|lat|latinoam\w*|hispanoam\w*|mexic\w*|méxic\w*)\b""")
	private val brazilianTitle = Regex("""\bbra[sz]il\w*""")

	/**
	 * Normalized audio language of a stream ("eng", "spa", "spa-419"...), null when unknown.
	 * Latin American Spanish and Brazilian Portuguese are told apart by the language tag
	 * ("es-419", "pt-BR") or the track title ("Español (Latino)", "Português (Brasil)").
	 */
	fun languageKey(language: String?, title: String? = null): String? {
		val tag = language?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
		val primary = tag.substringBefore('-')
		val code = languageAliases[primary] ?: primary
		if (code in ignoredLanguages || code.length !in 2..3 || !code.all { it in 'a'..'z' }) return null

		val region = tag.substringAfter('-', "")
		val lowerTitle = title?.lowercase().orEmpty()
		return when {
			code == "spa" && (region in latinAmericanRegions || latinAmericanTitle.containsMatchIn(lowerTitle)) -> "spa-419"
			code == "por" && (region == "br" || brazilianTitle.containsMatchIn(lowerTitle)) -> "por-br"
			else -> code
		}
	}

	/**
	 * Audio languages of an item for the flags on its poster, at most [max]. English, Spanish and
	 * Japanese go first (in that order), then the other languages in the order of the file.
	 * Requires the item to be requested with `ItemFields.MEDIA_STREAMS`.
	 */
	fun audioLanguages(item: BaseItemDto?, max: Int = MAX_LANGUAGE_FLAGS): List<LanguageBadge> {
		val keys = item?.mediaStreams.orEmpty()
			.filter { it.type == MediaStreamType.AUDIO }
			.mapNotNull { stream -> languageKey(stream.language, listOfNotNull(stream.title, stream.displayTitle).joinToString(" ")) }
			.distinct()
		if (keys.isEmpty()) return emptyList()

		// Latin American Spanish counts as "Spanish" when the file has no Spanish from Spain
		val hasSpainSpanish = "spa" in keys
		fun rank(key: String) = when (key) {
			"eng" -> 0
			"spa" -> 1
			"spa-419" -> if (hasSpainSpanish) 3 else 1
			"jpn" -> 2
			else -> 4
		}

		return keys
			.withIndex()
			.sortedWith(compareBy<IndexedValue<String>> { rank(it.value) }.thenBy { it.index })
			.take(max)
			.map { (_, key) -> LanguageBadge(language = key, flag = languageFlags[key]) }
	}

	/** Provider ids used to recognize the same title in different libraries */
	private val duplicateProviders = listOf("Tmdb", "Tvdb", "Imdb")

	/**
	 * Key identifying the same movie/show/episode across libraries (e.g. one library per language).
	 */
	fun duplicateKey(item: BaseItemDto): String {
		val providerKey = item.providerIds?.let { providerIds ->
			duplicateProviders.firstNotNullOfOrNull { provider ->
				providerIds.entries
					.firstOrNull { (key, _) -> key.equals(provider, ignoreCase = true) }
					?.value
					?.takeIf { it.isNotBlank() }
					?.let { "$provider:$it" }
			}
		}

		return when {
			providerKey != null -> "${item.type.name}:$providerKey"
			item.type == BaseItemKind.EPISODE -> "episode:${item.seriesName?.lowercase()}:${item.parentIndexNumber}:${item.indexNumber}"
			else -> item.id.toString()
		}
	}
}
