package org.jellyfin.androidtv.ui.music.modern

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MusicSectionOrderTests : FunSpec({
	test("Empty preference gives the default order") {
		MusicSectionOrder.parse("") shouldBe MusicSectionOrder.default
	}

	test("Saved order first, new and unknown sections handled") {
		MusicSectionOrder.parse("PLAYLISTS,UNKNOWN,STATIONS,PLAYLISTS") shouldBe listOf(
			MusicSectionType.PLAYLISTS,
			MusicSectionType.STATIONS,
			MusicSectionType.LATEST,
			MusicSectionType.LAST_PLAYED,
			MusicSectionType.RECOMMENDED,
			MusicSectionType.FAVORITES,
		)
	}

	test("Moving a section keeps the others in place and stops at the edges") {
		val order = MusicSectionOrder.default
		MusicSectionOrder.move(order, MusicSectionType.STATIONS, -1).take(3) shouldBe listOf(
			MusicSectionType.LATEST,
			MusicSectionType.STATIONS,
			MusicSectionType.LAST_PLAYED,
		)
		MusicSectionOrder.move(order, MusicSectionType.LATEST, -1) shouldBe order
		MusicSectionOrder.serialize(MusicSectionOrder.parse(MusicSectionOrder.serialize(order))) shouldBe MusicSectionOrder.serialize(order)
	}
})
