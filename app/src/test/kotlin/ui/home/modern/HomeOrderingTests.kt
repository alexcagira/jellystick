package org.jellyfin.androidtv.ui.home.modern

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.util.UUID

private val now = LocalDateTime.of(2026, 11, 14, 20, 0)

private fun userData(lastPlayed: LocalDateTime?) = UserItemDataDto(
	playbackPositionTicks = 0,
	playCount = 0,
	isFavorite = false,
	played = false,
	key = "key",
	itemId = UUID.randomUUID(),
	lastPlayedDate = lastPlayed,
)

private fun episode(name: String, series: UUID, lastPlayed: LocalDateTime?) = BaseItemDto(
	id = UUID.randomUUID(),
	name = name,
	type = BaseItemKind.EPISODE,
	seriesId = series,
	userData = userData(lastPlayed),
)

class HomeOrderingTests : FunSpec({
	test("Continue watching mixes resume and next up by last activity") {
		val resumeSeries = UUID.randomUUID()
		val nextUpSeries = UUID.randomUUID()
		val movie = BaseItemDto(id = UUID.randomUUID(), name = "Movie", type = BaseItemKind.MOVIE, userData = userData(now.minusHours(1)))

		ContinueWatchingOrder.sort(
			items = listOf(
				episode("In progress", resumeSeries, now.minusDays(3)),
				movie,
				episode("Next", nextUpSeries, null),
			),
			seriesActivity = mapOf(resumeSeries to now.minusDays(3), nextUpSeries to now.minusDays(1)),
		).map { it.name } shouldBe listOf("Movie", "Next", "In progress")
	}

	test("Home section order keeps new sections and moves within bounds") {
		HomeSectionOrder.parse("") shouldBe HomeSectionOrder.default
		HomeSectionOrder.parse("FAVORITES,UNKNOWN").first() shouldBe HomeSectionType.FAVORITES
		HomeSectionOrder.parse("FAVORITES").size shouldBe HomeSectionType.entries.size
		// "Latest releases" from older versions becomes episodes + films at the same place
		HomeSectionOrder.parse("FAVORITES,LATEST_RELEASES").take(3) shouldBe
			listOf(HomeSectionType.FAVORITES, HomeSectionType.LATEST_EPISODES, HomeSectionType.LATEST_FILMS)

		val moved = HomeSectionOrder.move(HomeSectionOrder.default, HomeSectionType.WATCHLIST, 1)
		moved.indexOf(HomeSectionType.WATCHLIST) shouldBe 2
		HomeSectionOrder.move(HomeSectionOrder.default, HomeSectionType.CONTINUE_WATCHING, -1) shouldBe HomeSectionOrder.default
	}
})
