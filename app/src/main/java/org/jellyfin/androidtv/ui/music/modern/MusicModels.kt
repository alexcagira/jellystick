package org.jellyfin.androidtv.ui.music.modern

import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Rows of the music library, in the default order */
enum class MusicSectionType {
	LATEST,
	LAST_PLAYED,
	STATIONS,
	RECOMMENDED,
	FAVORITES,
	PLAYLISTS,
}

enum class MusicCardKind {
	ALBUM,
	TRACK,
	PLAYLIST,
	STATION,
}

/** Where a station (instant mix) comes from */
data class StationSource(
	val genreId: UUID? = null,
	val artistId: UUID? = null,
	/** "Your mix": songs like an album */
	val albumId: UUID? = null,
)

data class MusicCard(
	val key: String,
	val kind: MusicCardKind,
	/** Album, track or playlist; null for stations */
	val item: BaseItemDto?,
	val title: String,
	val subtitle: String?,
	val imageUrl: String?,
	/** Blurred cover for the page background while the card is focused */
	val backgroundUrl: String?,
	val station: StationSource? = null,
)

data class MusicSection(
	val type: MusicSectionType,
	val cards: List<MusicCard>,
)

/** Recommended album in the media bar */
data class MusicHeroSlide(
	val album: BaseItemDto,
	val title: String,
	val artist: String?,
	val details: String?,
	/** "Because you listen to …" */
	val reason: String?,
	val coverUrl: String?,
	val backgroundUrl: String?,
)

data class ModernMusicState(
	val loading: Boolean = true,
	val hero: List<MusicHeroSlide> = emptyList(),
	val sections: List<MusicSection> = emptyList(),
	val libraries: List<BaseItemDto> = emptyList(),
)

/**
 * Order of the music rows, chosen in Settings › Music sections.
 */
object MusicSectionOrder {
	val default: List<MusicSectionType> = MusicSectionType.entries.toList()

	fun parse(value: String): List<MusicSectionType> {
		val saved = value.split(",")
			.mapNotNull { name -> MusicSectionType.entries.firstOrNull { it.name == name.trim() } }
			.distinct()
		return saved + default.filterNot { it in saved }
	}

	fun serialize(order: List<MusicSectionType>): String = order.joinToString(",") { it.name }

	fun move(order: List<MusicSectionType>, type: MusicSectionType, offset: Int): List<MusicSectionType> {
		val from = order.indexOf(type)
		val to = from + offset
		if (from < 0 || to !in order.indices) return order
		return order.toMutableList().apply {
			removeAt(from)
			add(to, type)
		}
	}
}
