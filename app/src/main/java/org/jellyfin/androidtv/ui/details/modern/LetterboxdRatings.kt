package org.jellyfin.androidtv.ui.details.modern

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Average rating of a film on Letterboxd (0–5), for its details page. Letterboxd has no public API:
 * the film page (found by the IMDb or TMDb id: letterboxd.com/imdb/tt0120737/) carries the average
 * in a meta tag ("4.27 out of 5"), the same one browser extensions read. One request per film,
 * kept on the device for a week (a day when the film has no rating).
 */
object LetterboxdRatings {
	private const val PREFERENCES = "letterboxd_ratings"
	private const val MAX_PAGE_CHARS = 400_000
	private const val CHUNK_CHARS = 8_192
	private const val HEAD_END = "</head>"
	private val KEEP = 7.days
	private val KEEP_MISSING = 1.days
	// <meta name="twitter:data2" content="4.27 out of 5" />
	private val ratingTag = Regex("""name="twitter:data2"[^>]*content="([0-9]+(?:\.[0-9]+)?) out of 5""")

	private val client by lazy {
		OkHttpClient.Builder()
			.callTimeout(15.seconds.toJavaDuration())
			.followRedirects(true)
			.build()
	}

	/** Letterboxd page of a film, by its IMDb id, otherwise its TMDb id */
	private fun pathOf(item: BaseItemDto): String? {
		val ids = item.providerIds.orEmpty()
		fun id(name: String) = ids.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
		return id("Imdb")?.let { "imdb/$it" } ?: id("Tmdb")?.let { "tmdb/$it" }
	}

	/** The average rating of a film, null when Letterboxd doesn't have one (or can't be reached) */
	suspend fun rating(context: Context, item: BaseItemDto): Float? = withContext(Dispatchers.IO) {
		val path = pathOf(item) ?: return@withContext null
		val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
		val now = System.currentTimeMillis()

		// "rating|time", rating empty when the film has none
		preferences.getString(path, null)?.split('|')?.takeIf { it.size == 2 }?.let { (rating, time) ->
			val age = now - (time.toLongOrNull() ?: 0L)
			val value = rating.toFloatOrNull()
			if (age < (if (value != null) KEEP else KEEP_MISSING).inWholeMilliseconds) return@withContext value
		}

		val rating = runCatching { fetch(path) }
			.onFailure { Timber.w(it, "Unable to read the Letterboxd rating of $path") }
			.getOrElse { return@withContext null }
		preferences.edit().putString(path, "${rating ?: ""}|$now").apply()
		rating
	}

	private fun fetch(path: String): Float? {
		val request = Request.Builder()
			.url("https://letterboxd.com/$path/")
			.header("User-Agent", "Mozilla/5.0 (Linux; Android 11; AFTKRT) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
			.header("Accept-Language", "en")
			.build()
		return client.newCall(request).execute().use { response ->
			// No film with that id: no rating (kept a day)
			if (response.code == 404) return@use null
			check(response.isSuccessful) { "HTTP ${response.code}" }
			// Only the head of the page: the meta tags are there
			val page = StringBuilder()
			response.body?.charStream()?.use { reader ->
				val chunk = CharArray(CHUNK_CHARS)
				while (page.length < MAX_PAGE_CHARS) {
					val n = reader.read(chunk)
					if (n < 0) break
					val searchFrom = (page.length - HEAD_END.length).coerceAtLeast(0)
					page.appendRange(chunk, 0, n)
					if (page.indexOf(HEAD_END, searchFrom) >= 0) break
				}
			}
			ratingTag.find(page)?.groupValues?.get(1)?.toFloatOrNull()
		}
	}
}
