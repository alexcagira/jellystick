package org.jellyfin.androidtv.ui.home.modern

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Minimal client for TMDB's "trending this week" lists (all, movies or TV shows). The results are
 * matched against the Jellyfin libraries (by TMDB id) to build the "Trending this week" rows.
 *
 * The key is the one set in Settings → Customization → Home → TMDB key, otherwise the one of the
 * build (`tmdb.api.key` in local.properties). Both the v3 "API key" and the v4 "API read access
 * token" work. Without a key the rows use the IMDb trend ([ImdbTrending]).
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
class TmdbTrendingClient(
	private val apiKey: () -> String,
) {
	data class TmdbRef(
		val mediaType: String,
		val id: Int,
	) {
		val key get() = "$mediaType:$id"
	}

	@Serializable
	private data class TrendingResponse(
		val results: List<TrendingResult> = emptyList(),
	)

	@Serializable
	private data class TrendingResult(
		val id: Int,
		@SerialName("media_type") val mediaType: String? = null,
	)

	private val json = Json { ignoreUnknownKeys = true }

	val isConfigured: Boolean get() = apiKey().isNotBlank()

	/** Changes with the key (the lists of another key aren't reused) */
	val keyHash: Int get() = apiKey().trim().hashCode()

	/**
	 * The trending titles of this week, most popular first: [mediaType] is [MEDIA_TYPE_ALL],
	 * [MEDIA_TYPE_MOVIE] or [MEDIA_TYPE_TV]. 20 per page.
	 */
	suspend fun trending(mediaType: String = MEDIA_TYPE_ALL, pages: Int = 3): List<TmdbRef> = withContext(Dispatchers.IO) {
		val key = apiKey().trim()
		if (key.isEmpty()) return@withContext emptyList()

		(1..pages).flatMap { page ->
			runCatching { fetchPage(key, mediaType, page) }
				.onFailure { Timber.w(it, "Unable to fetch TMDB trending page $page") }
				.getOrNull()
				.orEmpty()
		}.distinctBy { it.key }
	}

	/** Whether [key] works: the number of trending titles it gets, null when TMDB refuses it */
	suspend fun check(key: String): Int? = withContext(Dispatchers.IO) {
		runCatching { fetchPage(key.trim(), MEDIA_TYPE_ALL, 1) }
			.onFailure { Timber.w(it, "TMDB key check failed") }
			.getOrNull()
			?.size
	}

	/** One page of the list, null when TMDB answers with an error (a wrong key) */
	private fun fetchPage(key: String, mediaType: String, page: Int): List<TmdbRef>? {
		// v4 read access tokens are long JWTs, v3 API keys are 32 characters
		val useBearer = key.length > 40
		val url = buildString {
			append("https://api.themoviedb.org/3/trending/")
			append(mediaType)
			append("/week?page=")
			append(page)
			if (!useBearer) {
				append("&api_key=")
				append(URLEncoder.encode(key, "UTF-8"))
			}
		}

		val connection = URI(url).toURL().openConnection() as HttpURLConnection
		try {
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("Accept", "application/json")
			if (useBearer) connection.setRequestProperty("Authorization", "Bearer $key")

			if (connection.responseCode !in 200..299) {
				Timber.w("TMDB trending returned HTTP ${connection.responseCode}")
				return null
			}

			val body = connection.inputStream.bufferedReader().use { it.readText() }
			return json.decodeFromString<TrendingResponse>(body).results.mapNotNull { result ->
				// The lists of one kind say it too; just in case they don't
				val type = result.mediaType ?: mediaType.takeIf { it != MEDIA_TYPE_ALL } ?: return@mapNotNull null
				if (type == MEDIA_TYPE_MOVIE || type == MEDIA_TYPE_TV) TmdbRef(type, result.id)
				else null
			}
		} finally {
			connection.disconnect()
		}
	}

	companion object {
		const val MEDIA_TYPE_ALL = "all"
		const val MEDIA_TYPE_MOVIE = "movie"
		const val MEDIA_TYPE_TV = "tv"
		private const val TIMEOUT_MS = 10_000
	}
}
