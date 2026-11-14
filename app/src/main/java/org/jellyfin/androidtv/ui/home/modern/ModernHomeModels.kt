package org.jellyfin.androidtv.ui.home.modern

import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDate
import java.util.UUID

/**
 * Label shown in red at the bottom of a poster when a series received new content recently.
 */
enum class NewContentLabel {
	NEW_EPISODE,
	NEW_SEASON,
	NEW_SERIES,
}

/**
 * Technical quality of the media file, used for the small badges on top of posters
 * (Dolby Vision / Atmos / HDR / 4K / 1080p...).
 */
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
data class LanguageBadge(
	val language: String,
	val flag: String?,
) {
	/** Short text shown when there is no flag for the language, e.g. "ARA" */
	val label: String
		get() = language.substringBefore('-').uppercase()
}

enum class CardStyle {
	/** 16:9 card with title and progress (continue watching) */
	LANDSCAPE,

	/** 2:3 poster */
	POSTER,

	/** 2:3 poster with a big outlined rank number (trending / top 10) */
	RANKED,
}

enum class HomeSectionType {
	CONTINUE_WATCHING,
	WATCHLIST,
	TRENDING,
	LATEST_RELEASES,
	RECENT_MOVIES,
	RECENT_SHOWS,
	FAVORITES,
}

data class HomeCard(
	/** Stable key, unique inside a section */
	val key: String,
	/** Item used to render the card */
	val item: BaseItemDto,
	/** Item opened (details) or played when the card is clicked */
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
	val playId: UUID? = null,
)

data class HomeSection(
	val type: HomeSectionType,
	val style: CardStyle,
	val cards: List<HomeCard>,
)

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
