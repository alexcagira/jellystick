package org.jellyfin.androidtv.ui.home.modern

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.time.LocalDate
import java.util.UUID

/**
 * Label shown in red at the bottom of a poster when a series received new content recently.
 */
@Serializable
enum class NewContentLabel {
	NEW_EPISODE,
	NEW_SEASON,
	NEW_SERIES,
}

/**
 * Technical quality of the media file, used for the small badges on top of posters
 * (Dolby Vision / Atmos / HDR / 4K / 1080p...).
 */
@Serializable
data class MediaQuality(
	val resolution: String? = null,
	val dolbyVision: Boolean = false,
	val hdr: Boolean = false,
	/** "HDR10+", "HDR" or "HLG", null for SDR and Dolby Vision */
	val hdrFormat: String? = null,
	val atmos: Boolean = false,
	/** Dolby Digital (AC3) or Dolby Digital Plus (EAC3) */
	val dolbyDigital: Boolean = false,
	/** Dolby TrueHD without Atmos */
	val trueHd: Boolean = false,
) {
	val isEmpty: Boolean
		get() = resolution == null && !dolbyVision && !hdr && !atmos && !dolbyDigital && !trueHd

	/** Text under "Dolby": "ATMOS · VISION", "DIGITAL", "VISION"... null when nothing Dolby */
	val dolbyCaption: String?
		get() {
			val audio = when {
				atmos -> "ATMOS"
				trueHd -> "TRUEHD"
				dolbyDigital -> "DIGITAL"
				else -> null
			}
			val video = if (dolbyVision) "VISION" else null
			return listOfNotNull(audio, video).joinToString(" · ").ifEmpty { null }
		}
}

/**
 * Audio language of a file, shown as a small flag on posters.
 *
 * @param language normalized ISO 639-2 code, with a variant for Latin American Spanish ("spa-419")
 * and Brazilian Portuguese ("por-br")
 * @param flag flag drawn for the language (see LanguageFlags.kt), null to show the code as text
 */
@Serializable
data class LanguageBadge(
	val language: String,
	val flag: String?,
) {
	/** Short text shown when there is no flag for the language, e.g. "ARA" */
	val label: String
		get() = language.substringBefore('-').uppercase()
}

@Serializable
enum class CardStyle {
	/** 16:9 card with title and progress (continue watching) */
	LANDSCAPE,

	/** 2:3 poster */
	POSTER,

	/** 2:3 poster with a big outlined rank number (trending / top 10) */
	RANKED,
}

@Serializable
enum class HomeSectionType {
	CONTINUE_WATCHING,
	WATCHLIST,
	TRENDING,
	LATEST_EPISODES,
	LATEST_FILMS,
	RECENT_MOVIES,
	RECENT_SHOWS,
	FAVORITES,
}

@Serializable
data class HomeCard(
	/** Stable key, unique inside a section */
	val key: String,
	/** Item used to render the card */
	val item: BaseItemDto,
	/** Item opened (details) or played when the card is clicked */
	@Serializable(with = UUIDSerializer::class)
	val targetId: UUID,
	val title: String,
	val subtitle: String? = null,
	val imageUrl: String? = null,
	/** Server-side blurred backdrop used for the page background while the card is focused */
	val backgroundUrl: String? = null,
	val quality: MediaQuality? = null,
	/** Audio languages shown as flags, at most [MediaBadges.MAX_LANGUAGE_FLAGS] */
	val languages: List<LanguageBadge> = emptyList(),
	val newLabel: NewContentLabel? = null,
	@Serializable(with = LocalDateSerializer::class)
	val releaseDate: LocalDate? = null,
	val unplayedCount: Int? = null,
	val progress: Float? = null,
	val remainingMinutes: Int? = null,
	/** When true the card starts playback directly instead of opening the details screen */
	val playOnClick: Boolean = false,
	/** Community rating (IMDb/TMDB depending on the server metadata settings) */
	val rating: Float? = null,
	val year: Int? = null,
	/** Episode shown when a series poster is focused, e.g. "S1 · E8" */
	val episodeLabel: String? = null,
	/** Item played with the play/pause button of the remote, null to resolve it when pressed */
	@Serializable(with = UUIDSerializer::class)
	val playId: UUID? = null,
	/** 16:9 cards: show the quality badges and the audio flags (library rows) */
	val showBadges: Boolean = false,
	/** The image is a logo (a channel's): shown whole on a dark card instead of cropped */
	val logoImage: Boolean = false,
)

@Serializable
data class HomeSection(
	val type: HomeSectionType,
	val style: CardStyle,
	val cards: List<HomeCard>,
)

@Serializable
data class HeroSlide(
	val item: BaseItemDto,
	val title: String,
	val backdropUrl: String?,
	/** Heavily blurred backdrop for the ambient page background */
	val backgroundUrl: String?,
	/** Lightly blurred backdrop drawn behind the frosted glass navigation bar */
	val glassUrl: String?,
	val logoUrl: String?,
	val rating: Float?,
	val year: Int?,
	val runtimeMinutes: Int?,
	val officialRating: String?,
	val genres: List<String>,
	val overview: String?,
	val quality: MediaQuality?,
	/** Audio languages (movies) */
	val languages: List<LanguageBadge> = emptyList(),
	/** Live TV: "20:00 – 21:45 · Antena 3", shown with the channel logo instead of year and length */
	val liveInfo: String? = null,
	val channelLogoUrl: String? = null,
	/** Live TV: the programme is on now (the "LIVE" badge) */
	val onNow: Boolean = false,
	/** Text of the first button ("Watch now"), "Play" when null */
	val playLabel: String? = null,
)

data class ModernHomeState(
	/** True until the rows have been loaded once */
	val loading: Boolean = true,
	/** True once the hero carousel finished loading (even when it is empty) */
	val heroLoaded: Boolean = false,
	val hero: List<HeroSlide> = emptyList(),
	val sections: List<HomeSection> = emptyList(),
	val libraries: List<BaseItemDto> = emptyList(),
)

/**
 * Last focused card, kept in the ViewModel so focus can be restored when coming back to the home screen.
 */
data class HomeFocusPosition(
	val section: HomeSectionType,
	val cardKey: String,
)

/** ISO date ("2026-11-14") for the home cache */
object LocalDateSerializer : KSerializer<LocalDate> {
	override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
	override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
	override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
