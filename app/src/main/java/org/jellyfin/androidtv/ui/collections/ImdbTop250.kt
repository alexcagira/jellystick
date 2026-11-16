package org.jellyfin.androidtv.ui.collections

import org.jellyfin.androidtv.ui.home.modern.BackgroundTraffic
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.collectionApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * "IMDb Top 250" collection: the movies of the library that are in IMDb's Top 250, updated once a
 * week. The ranking comes from a public MDBList list that mirrors the chart (IMDb has no public
 * API); the movies are matched by their IMDb id, so they need IMDb metadata on the server.
 */
class ImdbTop250(
	private val context: Context,
	private val api: ApiClient,
) {
	@Serializable
	private data class ListItem(
		@SerialName("imdb_id") val imdbId: String? = null,
		val rank: Int? = null,
		val mediatype: String? = null,
	)

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private val json = Json { ignoreUnknownKeys = true }
	private val prefs get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
	private var syncStarted = false
	private var collectionId: UUID? = null
	private var collectionSession: String? = null

	/** IMDb ids of the last update, best first */
	fun ranking(): List<String> = prefs.getString(KEY_RANKING, null)
		?.split(',')
		?.filter { it.isNotBlank() }
		.orEmpty()

	/** Movies of the collection in IMDb order (best first), empty until the collection exists */
	suspend fun rankedMovies(fields: Set<ItemFields>): List<BaseItemDto> = withContext(Dispatchers.IO) {
		val id = findCollection() ?: return@withContext emptyList()
		val items = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = id,
				includeItemTypes = setOf(BaseItemKind.MOVIE),
				fields = fields + ItemFields.PROVIDER_IDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = MAX_ITEMS,
			)
		).content.items

		val rank = ranking().withIndex().associate { (index, imdbId) -> imdbId to index }
		items.sortedBy { item -> item.imdbId?.let { rank[it] } ?: Int.MAX_VALUE }
	}

	/** Update the collection when the last update is a week old. Called when Home opens */
	fun syncInBackground() {
		if (syncStarted) return
		syncStarted = true

		scope.launch {
			// Let the home screen load first
			delay(BACKGROUND_DELAY)
			val lastSync = prefs.getLong(syncKey(), 0L)
			if (System.currentTimeMillis() - lastSync < UPDATE_INTERVAL.inWholeMilliseconds) return@launch

			try {
				BackgroundTraffic.awaitTurn()
				mutex.withLock { sync() }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to update the $NAME collection")
			}
		}
	}

	private suspend fun sync() {
		val ranking = fetchRanking()
		// A broken or changed list: keep the collection as it is
		if (ranking.size < MIN_LIST_SIZE) {
			Timber.w("$NAME: unexpected list (${ranking.size} titles)")
			return
		}

		val moviesByImdb = libraryMovies()
		val wanted = ranking.mapNotNull { moviesByImdb[it] }.distinct()

		val existing = findCollection()
		if (existing == null) {
			if (wanted.isEmpty()) return
			val id = api.collectionApi.createCollection(
				name = NAME,
				ids = wanted.take(IDS_PER_REQUEST).map { it.toString() },
				isLocked = true,
			).content.id
			wanted.drop(IDS_PER_REQUEST).chunked(IDS_PER_REQUEST).forEach { api.collectionApi.addToCollection(id, it) }
			collectionId = id
			collectionSession = session()

			// Needs an administrator, the collection works without it
			try {
				api.uploadPrimaryImage(id, ImdbTop250Cover.jpeg())
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to set the cover of $NAME")
			}
		} else {
			val current = currentItems(existing)
			val wantedSet = wanted.toSet()
			val toAdd = wanted.filterNot { it in current }
			val toRemove = current.filterNot { it in wantedSet }
			toAdd.chunked(IDS_PER_REQUEST).forEach { api.collectionApi.addToCollection(existing, it) }
			toRemove.chunked(IDS_PER_REQUEST).forEach { api.collectionApi.removeFromCollection(existing, it) }
			Timber.i("$NAME: +${toAdd.size} -${toRemove.size}")
		}

		prefs.edit {
			putString(KEY_RANKING, ranking.joinToString(","))
			putLong(syncKey(), System.currentTimeMillis())
		}
	}

	/** IMDb ids of the Top 250, best first */
	private fun fetchRanking(): List<String> {
		val connection = URI(LIST_URL).toURL().openConnection() as HttpURLConnection
		try {
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("Accept", "application/json")
			if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
			val body = connection.inputStream.bufferedReader().use { it.readText() }
			return json.decodeFromString<List<ListItem>>(body)
				.filter { it.mediatype == null || it.mediatype == "movie" }
				.sortedBy { it.rank ?: Int.MAX_VALUE }
				.mapNotNull { it.imdbId?.takeIf { id -> id.startsWith("tt") } }
				.distinct()
		} finally {
			connection.disconnect()
		}
	}

	/** Every movie of the server with an IMDb id: IMDb id → item id */
	private suspend fun libraryMovies(): Map<String, UUID> {
		val movies = HashMap<String, UUID>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					includeItemTypes = setOf(BaseItemKind.MOVIE),
					recursive = true,
					hasImdbId = true,
					fields = setOf(ItemFields.PROVIDER_IDS),
					enableImages = false,
					enableUserData = false,
					startIndex = start,
					limit = PAGE_SIZE,
				)
			).content
			for (item in page.items) {
				val imdbId = item.imdbId ?: continue
				movies.putIfAbsent(imdbId, item.id)
			}
			start += page.items.size
			if (page.items.size < PAGE_SIZE || start >= page.totalRecordCount) break
		}
		return movies
	}

	private suspend fun currentItems(collectionId: UUID): Set<UUID> {
		val ids = HashSet<UUID>()
		var start = 0
		while (true) {
			val page = api.itemsApi.getItems(
				GetItemsRequest(
					parentId = collectionId,
					enableImages = false,
					enableUserData = false,
					startIndex = start,
					limit = PAGE_SIZE,
				)
			).content
			page.items.mapTo(ids) { it.id }
			start += page.items.size
			if (page.items.size < PAGE_SIZE || start >= page.totalRecordCount) break
		}
		return ids
	}

	private suspend fun findCollection(): UUID? {
		val session = session()
		if (collectionSession == session && collectionId != null) return collectionId

		val found = api.itemsApi.getItems(
			GetItemsRequest(
				includeItemTypes = setOf(BaseItemKind.BOX_SET),
				recursive = true,
				searchTerm = NAME,
				enableImages = false,
				enableTotalRecordCount = false,
			)
		).content.items.firstOrNull { it.name.equals(NAME, ignoreCase = true) }?.id

		collectionId = found
		collectionSession = session
		return found
	}

	private fun session() = "${api.baseUrl}|${api.accessToken}"

	/** Per server and user: each one has its own collection permissions */
	private fun syncKey() = "sync_${session().hashCode()}"

	companion object {
		const val NAME = "IMDb Top 250"

		private const val PREFERENCES = "imdb_top_250"
		private const val KEY_RANKING = "ranking"
		private const val LIST_URL = "https://mdblist.com/lists/snoak/imdb-top-250-movies/json/"
		private const val TIMEOUT_MS = 20_000
		private const val MIN_LIST_SIZE = 200
		private const val PAGE_SIZE = 500
		private const val IDS_PER_REQUEST = 60
		private const val MAX_ITEMS = 300
		private val UPDATE_INTERVAL = 7.days
		private val BACKGROUND_DELAY = 45.seconds
	}
}

/** IMDb id of an item ("tt0111161"), from its provider ids */
internal val BaseItemDto.imdbId: String?
	get() = providerIds?.entries?.firstOrNull { it.key.equals("Imdb", ignoreCase = true) }?.value?.takeIf { it.startsWith("tt") }

/**
 * Cover of the collection: a gold star and "TOP 250" on a dark background (poster shape).
 */
internal object ImdbTop250Cover {
	private const val WIDTH = 680
	private const val HEIGHT = 1000

	fun draw(): Bitmap {
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val gold = 0xFFF5C518.toInt()

		// Background and a warm glow behind the star
		canvas.drawPaint(Paint().apply {
			shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF1C1C22.toInt(), 0xFF07070A.toInt(), Shader.TileMode.CLAMP)
		})
		canvas.drawCircle(WIDTH / 2f, 380f, 330f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = RadialGradient(WIDTH / 2f, 380f, 330f, 0x55F5C518, 0x00F5C518, Shader.TileMode.CLAMP)
		})

		// Five-pointed star
		val star = Path()
		val cx = WIDTH / 2f
		val cy = 390f
		for (i in 0 until 10) {
			val radius = if (i % 2 == 0) 190f else 80f
			val angle = -PI / 2 + i * PI / 5
			val x = cx + (radius * cos(angle)).toFloat()
			val y = cy + (radius * sin(angle)).toFloat()
			if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
		}
		star.close()
		canvas.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold })

		val bold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 900, false)
		else Typeface.create("sans-serif-black", Typeface.NORMAL)

		val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt()
			textAlign = Paint.Align.CENTER
			textSize = 54f
			typeface = bold
			letterSpacing = 0.25f
		}
		canvas.drawText("IMDb", cx, 690f, small)

		val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = gold
			textAlign = Paint.Align.CENTER
			textSize = 150f
			typeface = bold
		}
		canvas.drawText("TOP 250", cx, 850f, big)
		return bitmap
	}

	fun jpeg(): ByteArray = ByteArrayOutputStream().use { stream ->
		draw().compress(Bitmap.CompressFormat.JPEG, 92, stream)
		stream.toByteArray()
	}
}
