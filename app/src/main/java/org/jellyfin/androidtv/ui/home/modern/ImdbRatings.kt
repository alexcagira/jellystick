package org.jellyfin.androidtv.ui.home.modern

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * IMDb ratings of every title, from IMDb's daily dataset `title.ratings.tsv.gz` (for personal and
 * non-commercial use: https://developer.imdb.com/non-commercial-datasets/). The stars of the posters,
 * the media bar, the details pages and the player come from it, by the IMDb id of each title,
 * instead of the server's rating (TMDb's with the default metadata providers). Titles without an
 * IMDb id or rating keep the server's.
 *
 * Downloaded once a day (about 8 MB) and kept on the device as two arrays sorted by the IMDb
 * number: the number ("tt0120737" → 120737) and the rating × 10. About 8 MB in memory. Each download
 * also keeps the number of votes of the known titles for "Trending this week" ([ImdbTrending]).
 */
object ImdbRatings {
	private const val DATASET_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
	private const val FILE_NAME = "imdb_ratings.bin"
	private const val MAGIC = 0x494D4442 // "IMDB"
	private const val INITIAL_CAPACITY = 2_000_000
	private const val INITIAL_VOTES_CAPACITY = 200_000
	private const val HEADER_SIZE = 8
	/** IMDb publishes the file every day: a new copy each day gives the weekly trend day by day */
	private val MAX_AGE = 20.hours

	private val lock = Any()

	@Volatile
	private var ids: IntArray? = null

	@Volatile
	private var ratings: ByteArray? = null

	private val _loaded = MutableStateFlow(false)

	/** The ratings are in memory */
	val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

	private val client by lazy {
		OkHttpClient.Builder()
			.callTimeout(10.minutes.toJavaDuration())
			.build()
	}

	/** The IMDb number of a title ("tt0120737" → 120737), null without an IMDb id */
	fun imdbNumber(item: BaseItemDto?): Int? {
		val tconst = item?.providerIds?.entries?.firstOrNull { it.key.equals("Imdb", ignoreCase = true) }?.value
			?: return null
		return tconst.trim().removePrefix("tt").toIntOrNull()
	}

	/** IMDb rating of a title (0–10), null without an IMDb id or rating */
	fun imdbRating(item: BaseItemDto?): Float? {
		val number = imdbNumber(item) ?: return null
		val ids = ids ?: return null
		val ratings = ratings ?: return null
		val index = ids.binarySearch(number)
		if (index < 0) return null
		return ratings[index].toInt() / 10f
	}

	/** The rating shown: IMDb's, otherwise the server's */
	fun rating(item: BaseItemDto?): Float? = imdbRating(item) ?: item?.communityRating?.takeIf { it > 0f }

	private fun file(context: Context) = File(context.filesDir, FILE_NAME)

	/** What the device has, into memory (from a background thread; quick, it is one read) */
	fun load(context: Context) {
		ImdbTrending.attach(context)
		synchronized(lock) {
			if (ids != null) return
			val file = file(context)
			if (!file.exists()) return
			runCatching {
				val bytes = file.readBytes()
				val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE)
				require(header.int == MAGIC) { "Not a ratings file" }
				val count = header.int
				require(bytes.size == HEADER_SIZE + count * (Int.SIZE_BYTES + 1)) { "Incomplete ratings file" }
				val newIds = IntArray(count)
				ByteBuffer.wrap(bytes, HEADER_SIZE, count * Int.SIZE_BYTES).asIntBuffer().get(newIds)
				val ratingsStart = HEADER_SIZE + count * Int.SIZE_BYTES
				val newRatings = bytes.copyOfRange(ratingsStart, ratingsStart + count)
				ratings = newRatings
				ids = newIds
				_loaded.value = true
				Timber.i("IMDb ratings: $count titles")
			}.onFailure {
				Timber.w(it, "Unable to read the IMDb ratings")
				file.delete()
			}
		}
	}

	/** A new copy of the dataset when the one on the device is a day old (in the background) */
	suspend fun refreshIfOld(context: Context) = withContext(Dispatchers.IO) {
		val file = file(context)
		val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE.inWholeMilliseconds
		// The first time with the trend: the votes of today are needed too
		if (fresh && ImdbTrending.hasVotes(context)) {
			load(context)
			return@withContext
		}

		runCatching { download(context, file) }
			.onFailure { Timber.w(it, "Unable to download the IMDb ratings") }
			.onSuccess {
				synchronized(lock) {
					ids = null
					ratings = null
				}
				load(context)
			}
		// An old copy is better than none
		load(context)
	}

	private fun download(context: Context, target: File) {
		val request = Request.Builder().url(DATASET_URL).build()
		client.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "HTTP ${response.code}" }
			var ids = IntArray(INITIAL_CAPACITY)
			var ratings = ByteArray(INITIAL_CAPACITY)
			var count = 0
			var sorted = true
			// Votes of the titles with enough of them, for the trend
			var voteIds = IntArray(INITIAL_VOTES_CAPACITY)
			var votes = IntArray(INITIAL_VOTES_CAPACITY)
			var voteCount = 0

			val reader = BufferedReader(InputStreamReader(GZIPInputStream(response.body?.byteStream() ?: error("No body")), Charsets.UTF_8), 1 shl 16)
			reader.use {
				// tconst \t averageRating \t numVotes
				reader.readLine()
				while (true) {
					val line = reader.readLine() ?: break
					val firstTab = line.indexOf('\t')
					val secondTab = line.indexOf('\t', firstTab + 1)
					if (firstTab < 3 || secondTab < 0 || !line.startsWith("tt")) continue
					val number = line.substring(2, firstTab).toIntOrNull() ?: continue
					val rating = line.substring(firstTab + 1, secondTab).toFloatOrNull() ?: continue
					val numVotes = line.substring(secondTab + 1).trim().toIntOrNull() ?: 0
					if (numVotes >= ImdbTrending.MIN_VOTES) {
						if (voteCount == voteIds.size) {
							voteIds = voteIds.copyOf(voteCount * 3 / 2)
							votes = votes.copyOf(voteCount * 3 / 2)
						}
						voteIds[voteCount] = number
						votes[voteCount] = numVotes
						voteCount++
					}

					if (count == ids.size) {
						ids = ids.copyOf(count * 3 / 2)
						ratings = ratings.copyOf(count * 3 / 2)
					}
					if (count > 0 && number <= ids[count - 1]) sorted = false
					ids[count] = number
					ratings[count] = (rating * 10).roundToInt().coerceIn(0, 100).toByte()
					count++
				}
			}

			// The file comes sorted by id; just in case it isn't
			if (!sorted) {
				val order = (0 until count).sortedBy { ids[it] }
				ids = IntArray(count) { ids[order[it]] }
				ratings = ByteArray(count) { ratings[order[it]] }
			}

			// Magic, count, the ids, the ratings
			val bytes = ByteArray(HEADER_SIZE + count * (Int.SIZE_BYTES + 1))
			ByteBuffer.wrap(bytes, 0, HEADER_SIZE).putInt(MAGIC).putInt(count)
			ByteBuffer.wrap(bytes, HEADER_SIZE, count * Int.SIZE_BYTES).asIntBuffer().put(ids, 0, count)
			System.arraycopy(ratings, 0, bytes, HEADER_SIZE + count * Int.SIZE_BYTES, count)

			val partial = File(target.parentFile, "${target.name}.part")
			partial.writeBytes(bytes)
			check(partial.renameTo(target)) { "Unable to save the IMDb ratings" }
			Timber.i("IMDb ratings downloaded: $count titles")

			ImdbTrending.saveVotes(context, voteIds, votes, voteCount)
		}
	}
}
