package org.jellyfin.androidtv.ui.home.modern

import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.util.UUID

/**
 * Order of "Continue Watching": what was watched most recently first, whether it's an episode in
 * progress or the next episode of a series.
 */
object ContinueWatchingOrder {
	/**
	 * @param items episodes/movies in progress and next episodes
	 * @param seriesActivity last time an episode of each series was watched
	 */
	fun sort(items: List<BaseItemDto>, seriesActivity: Map<UUID, LocalDateTime>): List<BaseItemDto> = items
		.distinctBy { it.id }
		.withIndex()
		.sortedWith(
			compareByDescending<IndexedValue<BaseItemDto>> { lastActivity(it.value, seriesActivity) ?: LocalDateTime.MIN }
				// Same date (or none): keep the server order
				.thenBy { it.index }
		)
		.map { it.value }

	/**
	 * The item's own last play, or the last play of its series when that's more recent (a next
	 * episode was never played, or only long ago when rewatching).
	 */
	private fun lastActivity(item: BaseItemDto, seriesActivity: Map<UUID, LocalDateTime>): LocalDateTime? {
		val own = item.userData?.lastPlayedDate
		val series = item.seriesId?.let { seriesActivity[it] }
		return when {
			own == null -> series
			series == null -> own
			series.isAfter(own) -> series
			else -> own
		}
	}
}

/**
 * Order of the rows of the home screen, chosen in Settings › Home sections.
 */
object HomeSectionOrder {
	val default: List<HomeSectionType> = HomeSectionType.entries.toList()

	/** Saved order, with sections missing from it (e.g. added in an update) at the end */
	fun parse(value: String): List<HomeSectionType> {
		val saved = value.split(",")
			.flatMap { name ->
				when (val trimmed = name.trim()) {
					// "Latest releases" was split into episodes and films
					"LATEST_RELEASES" -> listOf(HomeSectionType.LATEST_EPISODES, HomeSectionType.LATEST_FILMS)
					else -> listOfNotNull(HomeSectionType.entries.firstOrNull { it.name == trimmed })
				}
			}
			.distinct()
		return saved + default.filterNot { it in saved }
	}

	fun serialize(order: List<HomeSectionType>): String = order.joinToString(",") { it.name }

	/** Move [type] up ([offset] -1) or down (+1) */
	fun move(order: List<HomeSectionType>, type: HomeSectionType, offset: Int): List<HomeSectionType> {
		val from = order.indexOf(type)
		val to = from + offset
		if (from < 0 || to !in order.indices) return order
		return order.toMutableList().apply {
			removeAt(from)
			add(to, type)
		}
	}
}
