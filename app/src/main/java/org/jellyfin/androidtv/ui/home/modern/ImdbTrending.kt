package org.jellyfin.androidtv.ui.home.modern

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDate

/**
 * "Trending this week" from IMDb's data: the titles that got the most new IMDb votes in the last
 * 7 days, beyond what any well-known title gets every week.
 *
 * IMDb's popularity charts (MOVIEmeter, TVmeter) can't be read by apps: there is no free API and
 * IMDb doesn't allow reading its pages automatically. Its daily ratings file can, for personal and
 * non-commercial use, and it has the number of votes of every title: [ImdbRatings] downloads it
 * every day and gives the votes of the titles with at least [MIN_VOTES] to [saveVotes], one file a
 * day. The trend compares the newest day with the one of a week before (or the oldest there is,
 * from the second day on):
 *
 *     score = votes gained − votes a week ago × the usual weekly growth
 *
 * The usual growth is the median of the titles with at least [ESTABLISHED_VOTES] votes, so a classic
 * that gets its usual few thousand votes a week scores about 0, and a new release or a series with
 * a new season scores high. A title that had fewer than [MIN_VOTES] a week ago counts from 0.
 */
object ImdbTrending {
	internal const val MIN_VOTES = 1_000
	private const val ESTABLISHED_VOTES = 10_000
	private const val WINDOW_DAYS = 7L
	private const val KEEP_DAYS = 10L
	private const val DIRECTORY = "imdb_votes"
	private const val PREFIX = "votes-"
	private const val SUFFIX = ".bin"
	private const val MAGIC = 0x494D4456 // "IMDV"
	private const val HEADER_SIZE = 8

	private val lock = Any()

	@Volatile
	private var cached: Trend? = null

	@Volatile
	private var appContext: Context? = null

	/** Where the votes are kept (the application, from [ImdbRatings.load]) */
	fun attach(context: Context) {
		appContext = context.applicationContext
	}

	/** The trend, for the repositories (null before [attach] or without votes) */
	suspend fun trend(): Trend? = appContext?.let { trend(it) }

	/**
	 * The trend of the titles with enough IMDb votes. [hasScores] false until there are two days of
	 * votes: then only [votes] is known.
	 */
	class Trend internal constructor(
		/** Day of the newest votes (epoch day) */
		val day: Long,
		/** Days between the two days compared, 0 without a second day */
		val days: Long,
		private val ids: IntArray,
		private val votes: IntArray,
		private val scores: IntArray?,
	) {
		val hasScores: Boolean get() = scores != null

		/** Votes of a title now, null under [MIN_VOTES] */
		fun votes(imdbNumber: Int): Int? {
			val index = ids.binarySearch(imdbNumber)
			return if (index >= 0) votes[index] else null
		}

		/** How much a title is trending (votes beyond the usual), null without a score */
		fun score(imdbNumber: Int): Int? {
			val scores = scores ?: return null
			val index = ids.binarySearch(imdbNumber)
			return if (index >= 0) scores[index] else null
		}
	}

	private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

	private fun dayOf(file: File): Long? = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull()

	private fun files(context: Context): List<Pair<Long, File>> = directory(context).listFiles().orEmpty()
		.mapNotNull { file -> dayOf(file)?.let { it to file } }
		.sortedBy { it.first }

	/** There are votes of today or yesterday on the device */
	fun hasVotes(context: Context): Boolean {
		val today = LocalDate.now().toEpochDay()
		return files(context).any { (day, _) -> day >= today - 1 }
	}

	/** Keep the votes of today (from the IMDb file), the old days go */
	fun saveVotes(context: Context, ids: IntArray, votes: IntArray, count: Int) {
		runCatching {
			// The IMDb file is sorted by text ("tt10000000" before "tt1000001"): by number here
			val order = (0 until count).sortedBy { ids[it] }
			val bytes = ByteArray(HEADER_SIZE + count * 2 * Int.SIZE_BYTES)
			ByteBuffer.wrap(bytes, 0, HEADER_SIZE).putInt(MAGIC).putInt(count)
			val idBuffer = ByteBuffer.wrap(bytes, HEADER_SIZE, count * Int.SIZE_BYTES).asIntBuffer()
			val voteBuffer = ByteBuffer.wrap(bytes, HEADER_SIZE + count * Int.SIZE_BYTES, count * Int.SIZE_BYTES).asIntBuffer()
			for (index in order) {
				idBuffer.put(ids[index])
				voteBuffer.put(votes[index])
			}

			val directory = directory(context).apply { mkdirs() }
			val today = LocalDate.now().toEpochDay()
			val target = File(directory, "$PREFIX$today$SUFFIX")
			val partial = File(directory, "${target.name}.part")
			partial.writeBytes(bytes)
			check(partial.renameTo(target)) { "Unable to save the IMDb votes" }

			for ((day, file) in files(context)) if (day < today - KEEP_DAYS) file.delete()
			synchronized(lock) { cached = null }
			Timber.i("IMDb votes of $count titles kept for the trend")
		}.onFailure { Timber.w(it, "Unable to keep the IMDb votes") }
	}

	/** The trend from the votes on the device, null without any (read once per new day) */
	suspend fun trend(context: Context): Trend? = withContext(Dispatchers.IO) {
		val days = files(context)
		val newest = days.lastOrNull() ?: return@withContext null
		synchronized(lock) { cached?.takeIf { it.day == newest.first }?.let { return@withContext it } }

		val trend = runCatching { compute(newest, days) }
			.onFailure { Timber.w(it, "Unable to read the IMDb votes") }
			.getOrNull()
		synchronized(lock) { cached = trend }
		trend
	}

	private fun compute(newest: Pair<Long, File>, days: List<Pair<Long, File>>): Trend {
		val (ids, votes) = read(newest.second)
		// The day compared: the oldest within the week, otherwise the closest before
		val older = days.filter { it.first < newest.first }
		val reference = older.firstOrNull { it.first >= newest.first - WINDOW_DAYS } ?: older.lastOrNull()
			?: return Trend(newest.first, 0, ids, votes, null)

		val (oldIds, oldVotes) = read(reference.second)
		val before = IntArray(ids.size)
		val growth = FloatArray(ids.size)
		var growthCount = 0
		for (index in ids.indices) {
			val oldIndex = oldIds.binarySearch(ids[index])
			val then = if (oldIndex >= 0) oldVotes[oldIndex] else 0
			before[index] = then
			if (then >= ESTABLISHED_VOTES) growth[growthCount++] = (votes[index] - then).toFloat() / then
		}
		val usualGrowth = if (growthCount == 0) 0f else growth.copyOf(growthCount).apply { sort() }[growthCount / 2].coerceAtLeast(0f)

		val scores = IntArray(ids.size) { index ->
			val gained = votes[index] - before[index]
			(gained - before[index] * usualGrowth).toInt()
		}
		Timber.i("IMDb trend over ${newest.first - reference.first} days, usual growth ${usualGrowth * 100} %")
		return Trend(newest.first, newest.first - reference.first, ids, votes, scores)
	}

	private fun read(file: File): Pair<IntArray, IntArray> {
		val bytes = file.readBytes()
		val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE)
		require(header.int == MAGIC) { "Not a votes file" }
		val count = header.int
		require(bytes.size == HEADER_SIZE + count * 2 * Int.SIZE_BYTES) { "Incomplete votes file" }
		val ids = IntArray(count)
		val votes = IntArray(count)
		ByteBuffer.wrap(bytes, HEADER_SIZE, count * Int.SIZE_BYTES).asIntBuffer().get(ids)
		ByteBuffer.wrap(bytes, HEADER_SIZE + count * Int.SIZE_BYTES, count * Int.SIZE_BYTES).asIntBuffer().get(votes)
		return ids to votes
	}
}
