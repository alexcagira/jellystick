package org.jellyfin.androidtv.ui.details.modern

import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.LanguageBadge
import org.jellyfin.androidtv.ui.home.modern.MediaQuality
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDate
import java.util.UUID

/** Item types shown with the modern details screen, everything else uses the original screen */
enum class DetailsKind {
	MOVIE,
	SERIES,
	SEASON,
	PERSON,
}

data class DetailsHeader(
	/** Title shown when there is no logo */
	val title: String,
	/** Small text under the logo: original title, or the series name on a season */
	val secondaryTitle: String?,
	/** Bigger text under the logo, e.g. "Season 9" */
	val subtitle: String?,
	val logoUrl: String?,
	val backdropUrl: String?,
	val backgroundUrl: String?,
	val glassUrl: String?,
	val rating: Float?,
	/** [rating] is IMDb's (otherwise the server's) */
	val ratingFromImdb: Boolean = false,
	/** Average rating on Letterboxd (0–5), films only, once read */
	val letterboxdRating: Float? = null,
	val year: Int?,
	val runtimeMinutes: Int?,
	val seasonCount: Int?,
	val episodeCount: Int?,
	val officialRating: String?,
	val genres: List<String>,
	val overview: String?,
	val quality: MediaQuality?,
	/** Audio languages of the file (movies) */
	val languages: List<LanguageBadge> = emptyList(),
)

/**
 * Episode started by the "Play" button of a series or season, shown as "Resume S01:E05".
 */
data class PlayTarget(
	val id: UUID,
	val seasonNumber: Int?,
	val episodeNumber: Int?,
	/** The episode was already started */
	val resume: Boolean,
)

data class EpisodeEntry(
	val item: BaseItemDto,
	val number: String,
	val title: String,
	val overview: String?,
	val imageUrl: String?,
	val runtimeMinutes: Int?,
	val rating: Float?,
	val progress: Float?,
	val played: Boolean,
	/** Next episode to watch, highlighted like on the web */
	val isNext: Boolean,
	/** Resolution, HDR, Dolby Vision... of its file */
	val quality: MediaQuality? = null,
	/** Audio languages of its file, as flags */
	val languages: List<LanguageBadge> = emptyList(),
)

/** Season in the season switcher of a season page */
data class SeasonTab(
	val id: UUID,
	val name: String,
)

data class CastEntry(
	val id: UUID,
	val name: String,
	/** Character name for actors, job for the crew */
	val role: String?,
	val isCharacter: Boolean,
	val imageUrl: String?,
)

data class PersonInfo(
	val name: String,
	val imageUrl: String?,
	val overview: String?,
	val birthDate: LocalDate?,
	val deathDate: LocalDate?,
	val birthPlace: String?,
)

data class DetailsState(
	val loading: Boolean = true,
	val failed: Boolean = false,
	val item: BaseItemDto? = null,
	/** Null once loaded means the item type is not supported: the original screen is used instead */
	val kind: DetailsKind? = null,
	val header: DetailsHeader? = null,
	val person: PersonInfo? = null,
	val backgroundUrl: String? = null,
	val nextUp: HomeCard? = null,
	val seasons: List<HomeCard> = emptyList(),
	val episodes: List<EpisodeEntry> = emptyList(),
	val cast: List<CastEntry> = emptyList(),
	val similar: List<HomeCard> = emptyList(),
	val personMovies: List<HomeCard> = emptyList(),
	val personShows: List<HomeCard> = emptyList(),
	val libraries: List<BaseItemDto> = emptyList(),
	val isFavorite: Boolean = false,
	val isPlayed: Boolean = false,
	/** Episode played by the "Play" button (series and seasons) */
	val playTarget: PlayTarget? = null,
	/** Item added to the watchlist by the watchlist button: the series on a season page */
	val watchlistItemId: UUID? = null,
	val isInWatchlist: Boolean = false,
	/** Series whose theme song plays on this page (series and seasons) */
	val themeSeriesId: UUID? = null,
	/** All seasons of the series, to switch season from a season page */
	val seasonTabs: List<SeasonTab> = emptyList(),
	/** First part of the page (header and buttons): the slower rows (seasons, similar...) come later */
	val partial: Boolean = false,
) {
	/** Where a movie was left, when it is more than a minute in ("Resume from…"), otherwise null */
	val movieResumeTicks: Long?
		get() = item?.takeIf { kind == DetailsKind.MOVIE }
			?.userData?.playbackPositionTicks
			?.takeIf { it >= MIN_RESUME_TICKS }

	/** The ⏮ button: a movie with "Resume from…", or a series / season whose Play button resumes an episode */
	val canRestart: Boolean
		get() = when (kind) {
			DetailsKind.MOVIE -> movieResumeTicks != null
			DetailsKind.SERIES, DetailsKind.SEASON -> playTarget?.resume == true
			else -> false
		}

	private companion object {
		/** One minute: before that, "Play" (from the beginning anyway) */
		const val MIN_RESUME_TICKS = 60L * 10_000_000L
	}
}
