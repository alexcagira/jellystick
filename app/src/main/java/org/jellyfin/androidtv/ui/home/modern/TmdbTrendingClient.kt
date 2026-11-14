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
 * Minimal client for the TMDB "trending this week" list. The results are matched against the
 * Jellyfin library (by TMDB id) to build the "Trending this week" row.
 *
 * The key is read at build time from `tmdb.api.key` in local.properties. Both the v3 "API key" and
 * the v4 "API read access token" are supported. Without a key the home screen falls back to a
 * "Top 10" row based on community ratings.
 */
class TmdbTrendingClient(
	private val apiKey: String,
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

	val isConfigured: Boolean get() = apiKey.isNotBlank()

	/**
	 * Get the trending movies and shows of this week, most popular first.
	 */
	suspend fun trending(pages: Int = 3): List<TmdbRef> = withContext(Dispatchers.IO) {
		if (!isConfigured) return@withContext emptyList()

		(1..pages).flatMap { page ->
			runCatching { fetchPage(page) }
				.onFailure { Timber.w(it, "Unable to fetch TMDB trending page $page") }
				.getOrDefault(emptyList())
		}.distinctBy { it.key }
	}

	private fun fetchPage(page: Int): List<TmdbRef> {
		// v4 read access tokens are long JWTs, v3 API keys are 32 characters
		val useBearer = apiKey.length > 40
		val url = buildString {
			append("https://api.themoviedb.org/3/trending/all/week?page=")
			append(page)
			if (!useBearer) {
				append("&api_key=")
				append(URLEncoder.encode(apiKey, "UTF-8"))
			}
		}

		val connection = URI(url).toURL().openConnection() as HttpURLConnection
		try {
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("Accept", "application/json")
			if (useBearer) connection.setRequestProperty("Authorization", "Bearer $apiKey")

			if (connection.responseCode !in 200..299) {
				Timber.w("TMDB trending returned HTTP ${connection.responseCode}")
				return emptyList()
			}

			val body = connection.inputStream.bufferedReader().use { it.readText() }
			return json.decodeFromString<TrendingResponse>(body).results.mapNotNull { result ->
				val mediaType = result.mediaType ?: return@mapNotNull null
				if (mediaType == MEDIA_TYPE_MOVIE || mediaType == MEDIA_TYPE_TV) TmdbRef(mediaType, result.id)
				else null
			}
		} finally {
			connection.disconnect()
		}
	}

	companion object {
		const val MEDIA_TYPE_MOVIE = "movie"
		const val MEDIA_TYPE_TV = "tv"
		private const val TIMEOUT_MS = 10_000
	}
}
