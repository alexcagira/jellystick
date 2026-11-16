package org.jellyfin.androidtv.ui.collections

import java.text.Normalizer
import java.util.Locale

/**
 * Where a series is released, from the networks in its metadata (Jellyfin keeps them as the
 * studios of the series): the streaming services under one name whatever the spelling ("HBO",
 * "HBO Max" and "Max" are HBO Max), the other networks as they are.
 */
internal object StreamingServices {
	data class Service(
		/** Stable key, also saved with the collections made from it */
		val key: String,
		val name: String,
		/** A streaming service, otherwise a TV network */
		val streaming: Boolean,
	)

	private class Known(val name: String, aliases: List<String>, prefixes: List<String> = emptyList()) {
		val key = "service:" + normalize(name)
		val aliases = aliases.map(::normalize).toSet() + normalize(name)
		val prefixes = prefixes.map(::normalize)
	}

	private val known = listOf(
		Known("Netflix", listOf("Netflix Kids")),
		Known("Prime Video", listOf("Amazon", "Amazon Prime Video", "Amazon Video", "Amazon Studios", "Amazon Prime", "Amazon Freevee", "Freevee", "IMDb TV")),
		Known("Disney+", listOf("Disney Plus", "Disney+ Hotstar", "Star+", "Star Plus")),
		Known("HBO Max", listOf("Max", "HBO", "HBO Go", "Cinemax"), prefixes = listOf("HBO")),
		Known("Apple TV+", listOf("Apple TV", "Apple TV Plus")),
		Known("Hulu", listOf("Hulu Japan")),
		Known("Paramount+", listOf("Paramount Plus", "CBS All Access", "Paramount+ with Showtime")),
		Known("Peacock", listOf("Peacock TV", "Peacock Premium")),
		Known("SkyShowtime", listOf("Sky Showtime")),
		Known("Sky", listOf("Sky Atlantic", "Sky One", "Sky Max", "Sky Showcase", "Sky Comedy", "Sky Cinema", "Sky Witness", "NOW", "NOW TV")),
		Known("Movistar Plus+", listOf("Movistar+", "Movistar Plus", "Movistar", "#0", "Movistar Series")),
		Known("Atresplayer", listOf("Atresplayer Premium", "ATRESplayer PREMIUM")),
		Known("RTVE Play", listOf("RTVE", "Playz")),
		Known("Filmin", emptyList()),
		Known("Crunchyroll", listOf("Funimation")),
		Known("YouTube", listOf("YouTube Premium", "YouTube Red", "YouTube Originals")),
		Known("Showtime", emptyList()),
		Known("Starz", emptyList()),
		Known("AMC+", listOf("AMC Plus")),
		Known("discovery+", listOf("Discovery Plus")),
		Known("Viaplay", emptyList()),
		Known("BritBox", emptyList()),
		Known("Shudder", emptyList()),
		Known("MUBI", emptyList()),
	)

	/** The service or network of a studio name of a series */
	fun of(studio: String): Service? {
		val normalized = normalize(studio)
		if (normalized.isEmpty()) return null
		val match = known.firstOrNull { service ->
			normalized in service.aliases || service.prefixes.any { normalized.startsWith(it) }
		}
		return if (match != null) Service(match.key, match.name, streaming = true)
		else Service("network:$normalized", studio.trim(), streaming = false)
	}

	/** "Disney+" → "disneyplus", "Movistar Plus+" → "movistarplusplus", "HBO España" → "hboespana" */
	private fun normalize(text: String): String =
		Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
			.replace(Regex("\\p{M}+"), "")
			.replace("+", "plus")
			.filter { it.isLetterOrDigit() || it == '#' }
}
