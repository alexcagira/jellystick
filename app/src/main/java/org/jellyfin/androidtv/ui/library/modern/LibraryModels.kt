package org.jellyfin.androidtv.ui.library.modern

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jellyfin.androidtv.ui.home.modern.CardStyle
import org.jellyfin.androidtv.ui.home.modern.HeroSlide
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.LanguageBadge
import org.jellyfin.androidtv.ui.home.modern.MediaQuality
import org.jellyfin.androidtv.ui.home.modern.NewContentLabel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/** Movie or TV show library */
@Serializable
enum class LibraryKind(val itemType: BaseItemKind) {
	MOVIES(BaseItemKind.MOVIE),
	SHOWS(BaseItemKind.SERIES),
}

/** Kind of a library, null for the others (music, photos...) */
val BaseItemDto.libraryKind: LibraryKind?
	get() = when (collectionType) {
		CollectionType.MOVIES -> LibraryKind.MOVIES
		CollectionType.TVSHOWS -> LibraryKind.SHOWS
		else -> null
	}

@Serializable
enum class BrowseSort {
	NAME,
	RANDOM,
	DATE_ADDED,
	RELEASE_DATE,
	RATING,
}

@Serializable
enum class PlayedFilter {
	ALL,
	UNWATCHED,
	WATCHED,
	FAVORITES,
}

/**
 * What the browse grid shows: a library (or every movie and show), optionally limited to a genre,
 * a tag or an age rating. Also used as the argument of the browse page.
 */
@Serializable
data class BrowseFilter(
	val title: String,
	val libraryId: @Serializable(with = UUIDSerializer::class) UUID? = null,
	/** null: movies and shows */
	val kind: LibraryKind? = null,
	/** The collections (box sets) instead of movies */
	val collections: Boolean = false,
	/** Genre shown in the title and the filter */
	val genre: String? = null,
	/** Its names on the server (English and Spanish); [genre] when empty */
	val genreNames: List<String> = emptyList(),
	val tag: String? = null,
	val officialRating: String? = null,
	val played: PlayedFilter = PlayedFilter.ALL,
	val sort: BrowseSort = BrowseSort.NAME,
) {
	val itemTypes: Set<BaseItemKind>
		get() = when {
			collections -> setOf(BaseItemKind.BOX_SET)
			kind != null -> setOf(kind.itemType)
			else -> setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		}
}

/** Poster of the browse grid */
data class BrowseCard(
	val item: BaseItemDto,
	val title: String,
	val subtitle: String?,
	val imageUrl: String?,
	val quality: MediaQuality? = null,
	val languages: List<LanguageBadge> = emptyList(),
	val newLabel: NewContentLabel? = null,
)

/** A row of the library page (saved for the next start, without its [items]) */
@Serializable
data class LibrarySection(
	val key: String,
	val title: String,
	val style: CardStyle,
	val cards: List<HomeCard>,
	/** "See all": the browse grid with this filter */
	val seeAll: BrowseFilter? = null,
	/** Titles of the row, to build its cards again once the series badges are known */
	@Transient
	val items: List<BaseItemDto> = emptyList(),
)

data class ModernLibraryState(
	val loading: Boolean = true,
	val heroLoaded: Boolean = false,
	val hero: List<HeroSlide> = emptyList(),
	val sections: List<LibrarySection> = emptyList(),
	/** Libraries of the navigation bar */
	val libraries: List<BaseItemDto> = emptyList(),
	/** Libraries of the same kind (Films, Películas...), the pills under the media bar */
	val siblings: List<BaseItemDto> = emptyList(),
)

/** A genre: the name shown and every name and id it has on the server */
data class BrowseChip(
	/** In the language of the app */
	val name: String,
	/** The same genre can come in English and Spanish ("Action", "Acción") */
	val names: List<String> = listOf(name),
	val ids: List<UUID> = emptyList(),
)
