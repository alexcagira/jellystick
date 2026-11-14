package org.jellyfin.androidtv.ui.home.modern

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import java.time.LocalDateTime
import java.util.UUID

private val now = LocalDateTime.of(2026, 11, 13, 12, 0)

private fun episode(series: UUID, season: Int, episode: Int, daysAgo: Long) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.EPISODE,
	seriesId = series,
	parentIndexNumber = season,
	indexNumber = episode,
	dateCreated = now.minusDays(daysAgo),
)

private fun stream(
	type: MediaStreamType,
	width: Int? = null,
	height: Int? = null,
	range: VideoRangeType = VideoRangeType.UNKNOWN,
	codec: String? = null,
	spatial: AudioSpatialFormat = AudioSpatialFormat.NONE,
) = MediaStream(
	type = type,
	width = width,
	height = height,
	videoRangeType = range,
	codec = codec,
	audioSpatialFormat = spatial,
	index = 0,
	isDefault = true,
	isForced = false,
	isHearingImpaired = false,
	isExternal = false,
	isTextSubtitleStream = false,
	supportsExternalStream = false,
	isInterlaced = false,
)

private fun movie(vararg streams: MediaStream) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.MOVIE,
	mediaStreams = streams.toList(),
)

class MediaBadgesTests : FunSpec({
	test("New content labels follow the season/episode rules") {
		val ongoing = UUID.randomUUID()
		val newSeason = UUID.randomUUID()
		val newSeries = UUID.randomUUID()
		val old = UUID.randomUUID()
		val specials = UUID.randomUUID()

		val labels = MediaBadges.newContentLabels(
			recentEpisodes = listOf(
				episode(ongoing, season = 9, episode = 3, daysAgo = 1),
				episode(newSeason, season = 2, episode = 1, daysAgo = 2),
				episode(newSeason, season = 2, episode = 2, daysAgo = 2),
				episode(newSeries, season = 1, episode = 1, daysAgo = 3),
				episode(old, season = 3, episode = 5, daysAgo = 40),
				episode(specials, season = 0, episode = 1, daysAgo = 1),
			),
			now = now,
		)

		labels[ongoing] shouldBe NewContentLabel.NEW_EPISODE
		labels[newSeason] shouldBe NewContentLabel.NEW_SEASON
		labels[newSeries] shouldBe NewContentLabel.NEW_SERIES
		labels[old] shouldBe null
		labels[specials] shouldBe NewContentLabel.NEW_EPISODE
	}

	test("Quality badges are detected from media streams") {
		MediaBadges.quality(
			movie(
				stream(MediaStreamType.VIDEO, width = 3840, height = 1600, range = VideoRangeType.DOVI_WITH_HDR10),
				stream(MediaStreamType.AUDIO, codec = "truehd", spatial = AudioSpatialFormat.DOLBY_ATMOS),
			)
		) shouldBe MediaQuality(resolution = "4K", dolbyVision = true, atmos = true)

		MediaBadges.quality(
			movie(
				stream(MediaStreamType.VIDEO, width = 1920, height = 1080, range = VideoRangeType.SDR),
				stream(MediaStreamType.AUDIO, codec = "eac3"),
			)
		) shouldBe MediaQuality(resolution = "1080p", dolbyDigital = true)

		MediaBadges.quality(
			movie(stream(MediaStreamType.VIDEO, width = 3840, height = 2160, range = VideoRangeType.HDR10))
		) shouldBe MediaQuality(resolution = "4K", hdr = true, hdrFormat = "HDR")

		MediaBadges.quality(
			movie(
				stream(MediaStreamType.VIDEO, width = 3840, height = 2160, range = VideoRangeType.HDR10_PLUS),
				stream(MediaStreamType.AUDIO, codec = "eac3"),
			)
		)?.let { it.hdrFormat to it.dolbyCaption } shouldBe ("HDR10+" to "DIGITAL")

		MediaBadges.quality(
			movie(
				stream(MediaStreamType.VIDEO, width = 3840, height = 2160, range = VideoRangeType.DOVI_WITH_HDR10),
				stream(MediaStreamType.AUDIO, codec = "eac3"),
			)
		)?.dolbyCaption shouldBe "DIGITAL · VISION"

		MediaBadges.quality(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES)) shouldBe null
	}

	test("Language flags put English, Spanish and Japanese first") {
		fun audio(language: String?, title: String? = null) = MediaStream(
			type = MediaStreamType.AUDIO,
			language = language,
			title = title,
			index = 0,
			isDefault = false,
			isForced = false,
			isHearingImpaired = false,
			isExternal = false,
			isTextSubtitleStream = false,
			supportsExternalStream = false,
			isInterlaced = false,
		)

		fun languages(vararg streams: MediaStream) = MediaBadges.audioLanguages(movie(*streams)).map { it.language }

		languages(audio("fre"), audio("jpn"), audio("ger"), audio("spa"), audio("eng")) shouldBe listOf("eng", "spa", "jpn")
		languages(audio("ita"), audio("fr"), audio("deu"), audio("kor")) shouldBe listOf("ita", "fre", "ger")
		languages(audio("und"), audio(null), audio("jpn")) shouldBe listOf("jpn")
		languages(audio("spa", "Latino"), audio("eng"), audio("spa", "Castellano"), audio("jpn")) shouldBe listOf("eng", "spa", "jpn")
		languages(audio("fre"), audio("spa", "Español (Latino)"), audio("jpn")) shouldBe listOf("spa-419", "jpn", "fre")

		MediaBadges.languageKey("pt-BR") shouldBe "por-br"
		MediaBadges.audioLanguages(movie(audio("eng"), audio("ara"))).map { it.flag } shouldBe listOf("gb", null)
	}

	test("The same title in two libraries has the same duplicate key") {
		val english = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, providerIds = mapOf("Tmdb" to "550", "Imdb" to "tt0137523"))
		val spanish = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, providerIds = mapOf("tmdb" to "550"))
		val other = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, providerIds = mapOf("Tmdb" to "680"))

		MediaBadges.duplicateKey(english) shouldBe MediaBadges.duplicateKey(spanish)
		(MediaBadges.duplicateKey(english) == MediaBadges.duplicateKey(other)) shouldBe false
	}

	test("Language keys map to display tags for the collection names") {
		MediaBadges.languageTag("eng") shouldBe "en"
		MediaBadges.languageTag("fre") shouldBe "fr"
		MediaBadges.languageTag("spa-419") shouldBe "es-419"
		MediaBadges.languageTag("por-br") shouldBe "pt-BR"
		MediaBadges.flagFor("jpn") shouldBe "jp"
		MediaBadges.flagFor("ara") shouldBe null
	}
})
