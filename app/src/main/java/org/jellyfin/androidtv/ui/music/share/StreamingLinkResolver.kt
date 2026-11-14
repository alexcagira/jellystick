package org.jellyfin.androidtv.ui.music.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** Song (or album) named by a link from a streaming app */
data class LinkedMusic(
	val title: String,
	val artist: String?,
	val isAlbum: Boolean,
)

/**
 * Reads what a Spotify, Apple Music or YouTube (Music) link points to: the name of the song and
 * its artist, taken from the public page of the link. The song is then looked up in the Jellyfin
 * library: the TV can only play music from the library.
 */
object StreamingLinkResolver {
	private val urlPattern = Regex("""https?://\S+""")
	private val metaPattern = Regex("""<meta\s+[^>]*?(?:property|name)\s*=\s*"([^"]+)"[^>]*?content\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
	private val metaReversedPattern = Regex("""<meta\s+[^>]*?content\s*=\s*"([^"]*)"[^>]*?(?:property|name)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
	private val titlePattern = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

	/** First link in a shared text ("Listen to X on Spotify: https://...") */
	fun findUrl(text: String): String? = urlPattern.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

	suspend fun resolve(text: String): LinkedMusic? = withContext(Dispatchers.IO) {
		val url = findUrl(text) ?: return@withContext null
		val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return@withContext null

		try {
			when {
				"spotify" in host || host == "spoti.fi" -> spotify(url)
				"apple.com" in host -> appleMusic(url)
				"youtube" in host || host == "youtu.be" -> youtube(url)
				else -> null
			}
		} catch (err: Exception) {
			Timber.w(err, "Unable to read the link $url")
			null
		}
	}

	// Spotify

	private fun spotify(url: String): LinkedMusic? {
		val (finalUrl, html) = download(url) ?: return null
		val meta = meta(html)
		val isAlbum = "/album/" in finalUrl
		val title = meta["og:title"]?.decodeHtml()?.takeIf { it.isNotBlank() } ?: return null

		// "Blinding Lights - song and lyrics by The Weeknd | Spotify", "After Hours - Album by The Weeknd | Spotify"
		val pageTitle = titlePattern.find(html)?.groupValues?.get(1)?.decodeHtml().orEmpty()
		val artist = meta["music:musician_description"]?.decodeHtml()
			?: Regex("""\bby (.+?) \| Spotify""").find(pageTitle)?.groupValues?.get(1)
			// "The Weeknd · Song · 2019"
			?: meta["og:description"]?.decodeHtml()?.split('·')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && it != "Song" && it != "Album" }

		return LinkedMusic(title, artist, isAlbum)
	}

	// Apple Music

	private fun appleMusic(url: String): LinkedMusic? {
		val isAlbum = "?i=" !in url && "&i=" !in url && "/song/" !in url
		val (_, html) = download(url) ?: return null
		val meta = meta(html)
		val ogTitle = (meta["og:title"] ?: meta["apple:title"])?.decodeHtml()?.replace("‎", "")?.trim() ?: return null

		val patterns = listOf(
			// "Blinding Lights – Song by The Weeknd – Apple Music"
			Regex("""^(.+?)\s+[-–]\s+(?:Song|Single|Album|EP)\s+(?:by|de|von|di)\s+(.+?)\s+[-–]\s+Apple Music$""", RegexOption.IGNORE_CASE),
			// "Blinding Lights by The Weeknd on Apple Music"
			Regex("""^(.+?)\s+by\s+(.+?)\s+on Apple Music$""", RegexOption.IGNORE_CASE),
		)
		for (pattern in patterns) {
			val match = pattern.find(ogTitle) ?: continue
			return LinkedMusic(match.groupValues[1].trim(), match.groupValues[2].trim(), isAlbum)
		}

		return LinkedMusic(ogTitle.substringBefore(" - Apple Music").trim(), null, isAlbum)
	}

	// YouTube / YouTube Music

	private fun youtube(url: String): LinkedMusic? {
		// oEmbed only knows www.youtube.com links
		val videoId = Regex("""[?&]v=([\w-]{6,})""").find(url)?.groupValues?.get(1)
			?: Regex("""youtu\.be/([\w-]{6,})""").find(url)?.groupValues?.get(1)
			?: return null
		val watchUrl = "https://www.youtube.com/watch?v=$videoId"
		val (_, body) = download("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(watchUrl, "UTF-8")) ?: return null
		val json = Json.parseToJsonElement(body).jsonObject
		val rawTitle = json["title"]?.jsonPrimitive?.content ?: return null
		val author = json["author_name"]?.jsonPrimitive?.content
			?.removeSuffix(" - Topic")
			?.replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
			?.trim()

		val title = cleanTitle(rawTitle)
		// "The Weeknd - Blinding Lights"
		val dash = title.indexOf(" - ")
		return if (dash > 0) {
			LinkedMusic(title.substring(dash + 3).trim(), title.substring(0, dash).trim(), isAlbum = false)
		} else {
			LinkedMusic(title, author, isAlbum = false)
		}
	}

	// Helpers

	/** Final url and body of a page (follows redirects of short links like spotify.link) */
	private fun download(url: String): Pair<String, String>? {
		var current = url
		repeat(MAX_REDIRECTS) {
			val connection = URL(current).openConnection() as HttpURLConnection
			connection.instanceFollowRedirects = false
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("User-Agent", USER_AGENT)
			connection.setRequestProperty("Accept-Language", "en")
			try {
				val status = connection.responseCode
				if (status in 300..399) {
					current = connection.getHeaderField("Location")?.let { URL(URL(current), it).toString() } ?: return null
					return@repeat
				}
				if (status !in 200..299) return null
				val body = connection.inputStream.use { stream -> String(stream.readNBytesCompat(MAX_PAGE_BYTES), Charsets.UTF_8) }
				return current to body
			} finally {
				connection.disconnect()
			}
		}
		return null
	}

	private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
		val buffer = java.io.ByteArrayOutputStream()
		val chunk = ByteArray(16 * 1024)
		while (buffer.size() < max) {
			val read = read(chunk)
			if (read < 0) break
			buffer.write(chunk, 0, minOf(read, max - buffer.size()))
		}
		return buffer.toByteArray()
	}

	private fun meta(html: String): Map<String, String> {
		val values = HashMap<String, String>()
		metaPattern.findAll(html).forEach { values.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2]) }
		metaReversedPattern.findAll(html).forEach { values.putIfAbsent(it.groupValues[2].lowercase(), it.groupValues[1]) }
		return values
	}

	private fun String.decodeHtml(): String = replace("&amp;", "&")
		.replace("&quot;", "\"")
		.replace("&#39;", "'")
		.replace("&#x27;", "'")
		.replace("&apos;", "'")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.trim()

	/** "Song (Official Video) [4K]" → "Song" */
	fun cleanTitle(title: String): String = title
		.replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|live|hd|4k|explicit|clip)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
		.replace(Regex("""\s+[-–]\s+(remaster(ed)?|official).*$""", RegexOption.IGNORE_CASE), "")
		.trim()

	/** Lowercase letters and digits only, without accents ("Café del Mar!" → "cafedelmar") */
	fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
		.replace(Regex("""\p{M}+"""), "")
		.lowercase()
		.replace(Regex("""\(.*?\)|\[.*?]"""), "")
		.replace(Regex("""\b(feat|ft)\b.*$"""), "")
		.filter { it.isLetterOrDigit() }

	private const val TIMEOUT_MS = 6_000
	private const val MAX_REDIRECTS = 5
	private const val MAX_PAGE_BYTES = 1_500_000
	private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
}
