package org.jellyfin.androidtv.ui.music.share

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID

/** A song added from a phone */
data class GuestSong(
	val itemId: UUID,
	val name: String,
	val artist: String?,
	val albumId: UUID?,
	val addedBy: String,
)

data class SharedQueueState(
	val running: Boolean = false,
	/** Address to open on the phones (and in the QR code), null when the TV has no network */
	val url: String? = null,
	/** Songs added by guests that did not play yet */
	val guestSongs: List<GuestSong> = emptyList(),
	/** Songs shared from Spotify, Apple Music... that are not in the library (to add them later) */
	val requests: List<SongRequest> = emptyList(),
)

/** A song asked for with a link that the library does not have */
data class SongRequest(
	val title: String,
	val artist: String?,
	val requestedBy: String,
)

/**
 * Shared queue, like a party mode: the TV serves a small web page on the home network. Anyone on
 * the same Wi-Fi scans the QR code, searches the music library and adds songs to the queue,
 * without a Jellyfin account or an app. The page only talks to the TV, which uses its own session.
 */
class SharedQueueServer(
	private val context: Context,
	private val api: ApiClient,
	private val playbackManager: PlaybackManager,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val _state = MutableStateFlow(SharedQueueState())
	val state: StateFlow<SharedQueueState> = _state.asStateFlow()

	private var serverSocket: ServerSocket? = null
	private var serverJob: Job? = null
	private var watchJob: Job? = null
	private var token: String = newToken()
	private val page: ByteArray by lazy {
		context.resources.openRawResource(R.raw.shared_queue).use { it.readBytes() }
	}

	// Start / stop

	fun start() {
		if (serverJob?.isActive == true) return

		val socket = openSocket() ?: run {
			Timber.w("Shared queue: no free port")
			return
		}
		serverSocket = socket
		token = newToken()
		val address = lanAddress(context)
		_state.update {
			SharedQueueState(
				running = true,
				url = address?.let { "http://${it.hostAddress}:${socket.localPort}/?k=$token" },
				guestSongs = emptyList(),
			)
		}

		serverJob = scope.launch {
			while (isActive) {
				val client = try {
					socket.accept()
				} catch (err: IOException) {
					// Closed by stop()
					break
				}
				launch { handle(client) }
			}
		}

		// Songs leave the "added by guests" list once they play
		watchJob = scope.launch {
			playbackManager.queue.entry.collect { entry ->
				val playingId = entry?.baseItem?.id ?: return@collect
				_state.update { state ->
					val index = state.guestSongs.indexOfFirst { it.itemId == playingId }
					if (index < 0) state else state.copy(guestSongs = state.guestSongs.drop(index + 1))
				}
			}
		}
	}

	fun stop() {
		runCatching { serverSocket?.close() }
		serverSocket = null
		serverJob?.cancel()
		watchJob?.cancel()
		serverJob = null
		watchJob = null
		_state.value = SharedQueueState()
	}

	private fun openSocket(): ServerSocket? {
		for (port in PORTS) {
			try {
				return ServerSocket(port, BACKLOG, InetAddress.getByName("0.0.0.0"))
			} catch (err: IOException) {
				Timber.d("Shared queue: port $port busy")
			}
		}
		return null
	}

	// HTTP

	private suspend fun handle(socket: Socket) {
		socket.use { handleRequest(it) }
	}

	private suspend fun handleRequest(socket: Socket) {
		try {
			socket.soTimeout = SOCKET_TIMEOUT_MS
			val input = BufferedInputStream(socket.getInputStream())
			val output = socket.getOutputStream()

			val requestLine = input.readLine() ?: return
			// Skip the headers
			while (true) {
				val header = input.readLine() ?: break
				if (header.isEmpty()) break
			}

			val parts = requestLine.split(' ')
			if (parts.size < 2) return output.respond(400, "text/plain", "Bad request".toByteArray())
			val method = parts[0]
			val target = parts[1]
			val path = target.substringBefore('?')
			val query = parseQuery(target.substringAfter('?', ""))

			if (query["k"] != token) return output.respond(403, "text/plain", "Scan the QR code on the TV again".toByteArray())

			when {
				method == "GET" && path == "/" -> output.respond(200, "text/html; charset=utf-8", page)
				method == "GET" && path == "/api/state" -> output.respondJson(stateJson())
				method == "GET" && path == "/api/search" -> output.respondJson(search(query["q"].orEmpty()))
				method == "POST" && path == "/api/add" -> {
					val id = query["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
					val name = query["by"].orEmpty().trim().take(MAX_NAME_LENGTH).ifEmpty { "Guest" }
					val added = id != null && add(id, name)
					output.respondJson(buildJsonObject { put("ok", added) })
				}

				// Link shared from Spotify, Apple Music or YouTube Music
				method == "POST" && path == "/api/link" -> {
					val name = query["by"].orEmpty().trim().take(MAX_NAME_LENGTH).ifEmpty { "Guest" }
					output.respondJson(addLink(query["url"].orEmpty(), name))
				}

				method == "GET" && path == "/img" -> {
					val id = query["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
					val image = id?.let { image(it) }
					if (image != null) output.respond(200, "image/jpeg", image, cache = true)
					else output.respond(404, "text/plain", ByteArray(0))
				}

				else -> output.respond(404, "text/plain", "Not found".toByteArray())
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.d(err, "Shared queue request failed")
		}
	}

	private fun InputStream.readLine(): String? {
		val line = StringBuilder()
		while (true) {
			val byte = read()
			if (byte == -1) return if (line.isEmpty()) null else line.toString()
			if (byte == '\n'.code) return line.toString().trimEnd('\r')
			if (line.length > MAX_LINE_LENGTH) throw IOException("Line too long")
			line.append(byte.toChar())
		}
	}

	private fun parseQuery(query: String): Map<String, String> = query.split('&')
		.filter { it.isNotEmpty() }
		.associate { pair ->
			val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
			val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
			key to value
		}

	private fun OutputStream.respond(status: Int, type: String, body: ByteArray, cache: Boolean = false) {
		val reason = when (status) {
			200 -> "OK"
			400 -> "Bad Request"
			403 -> "Forbidden"
			else -> "Not Found"
		}
		val headers = buildString {
			append("HTTP/1.1 $status $reason\r\n")
			append("Content-Type: $type\r\n")
			append("Content-Length: ${body.size}\r\n")
			append(if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n")
			append("Connection: close\r\n\r\n")
		}
		write(headers.toByteArray())
		write(body)
		flush()
	}

	private fun OutputStream.respondJson(json: JsonObject) =
		respond(200, "application/json; charset=utf-8", json.toString().toByteArray())

	// API

	private fun stateJson(): JsonObject {
		val current = playbackManager.queue.entry.value?.baseItem
		return buildJsonObject {
			put("playing", playbackManager.state.playState.value == PlayState.PLAYING)
			if (current != null) put("nowPlaying", songJson(current, addedBy = null))
			put("guestSongs", JsonArray(_state.value.guestSongs.map { song ->
				buildJsonObject {
					put("id", song.itemId.toString())
					put("name", song.name)
					song.artist?.let { put("artist", it) }
					put("image", (song.albumId ?: song.itemId).toString())
					put("by", song.addedBy)
				}
			}))
		}
	}

	private suspend fun search(term: String): JsonObject {
		val query = term.trim().take(MAX_SEARCH_LENGTH)
		if (query.length < 2) return buildJsonObject { put("songs", JsonArray(emptyList())); put("albums", JsonArray(emptyList())) }

		val songs = api.itemsApi.getItems(
			GetItemsRequest(
				searchTerm = query,
				includeItemTypes = setOf(BaseItemKind.AUDIO),
				recursive = true,
				limit = SEARCH_SONGS,
				enableUserData = false,
				imageTypeLimit = 1,
			)
		).content.items
		val albums = api.itemsApi.getItems(
			GetItemsRequest(
				searchTerm = query,
				includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
				recursive = true,
				limit = SEARCH_ALBUMS,
				enableUserData = false,
				imageTypeLimit = 1,
			)
		).content.items

		return buildJsonObject {
			put("songs", JsonArray(songs.map { songJson(it, addedBy = null) }))
			put("albums", JsonArray(albums.map { album ->
				buildJsonObject {
					put("id", album.id.toString())
					put("name", album.name.orEmpty())
					(album.albumArtist ?: album.artists?.firstOrNull())?.let { put("artist", it) }
					put("image", album.id.toString())
				}
			}))
		}
	}

	private fun songJson(item: BaseItemDto, addedBy: String?): JsonObject = buildJsonObject {
		put("id", item.id.toString())
		put("name", item.name.orEmpty())
		(item.artists?.firstOrNull() ?: item.albumArtist)?.let { put("artist", it) }
		item.album?.let { put("album", it) }
		put("image", (item.albumId ?: item.id).toString())
		addedBy?.let { put("by", it) }
	}

	/**
	 * A link from a streaming app: find the same song (or album) in the library and add it.
	 * Returns {ok, name, artist} or {ok: false, reason: "link" | "missing" | "full"}.
	 */
	private suspend fun addLink(text: String, addedBy: String): JsonObject {
		fun failure(reason: String, linked: LinkedMusic? = null) = buildJsonObject {
			put("ok", false)
			put("reason", reason)
			linked?.let {
				put("name", it.title)
				it.artist?.let { artist -> put("artist", artist) }
			}
		}

		val linked = StreamingLinkResolver.resolve(text) ?: return failure("link")
		val match = findInLibrary(linked)
		if (match == null) {
			// Not in the library: remember it, so it can be added to the server later
			_state.update { state ->
				val request = SongRequest(linked.title, linked.artist, addedBy)
				val others = state.requests.filterNot { it.title.equals(linked.title, ignoreCase = true) && it.artist == linked.artist }
				state.copy(requests = (listOf(request) + others).take(MAX_REQUESTS))
			}
			return failure("missing", linked)
		}
		if (!add(match.id, addedBy)) return failure("full", linked)

		return buildJsonObject {
			put("ok", true)
			put("name", match.name.orEmpty())
			(match.albumArtist ?: match.artists?.firstOrNull())?.let { put("artist", it) }
		}
	}

	/** Best match of a song or album in the library: same name, and same artist when known */
	private suspend fun findInLibrary(linked: LinkedMusic): BaseItemDto? {
		val title = StreamingLinkResolver.cleanTitle(linked.title)
		val searchTerm = title.substringBefore(" (").substringBefore(" - ").substringBefore(" feat").trim().ifEmpty { title }
		val candidates = api.itemsApi.getItems(
			GetItemsRequest(
				searchTerm = searchTerm,
				includeItemTypes = setOf(if (linked.isAlbum) BaseItemKind.MUSIC_ALBUM else BaseItemKind.AUDIO),
				recursive = true,
				limit = LINK_CANDIDATES,
				enableUserData = false,
				enableImages = false,
			)
		).content.items

		val wantedTitle = StreamingLinkResolver.normalize(title)
		val wantedArtist = linked.artist?.let { StreamingLinkResolver.normalize(it) }?.takeIf { it.isNotEmpty() }

		fun score(item: BaseItemDto): Int {
			val name = StreamingLinkResolver.normalize(item.name.orEmpty())
			var score = when {
				name == wantedTitle -> 4
				name.startsWith(wantedTitle) || wantedTitle.startsWith(name) -> 2
				wantedTitle in name || name in wantedTitle -> 1
				else -> 0
			}
			if (score == 0) return 0

			if (wantedArtist != null) {
				val artists = (item.artists.orEmpty() + listOfNotNull(item.albumArtist)).map { StreamingLinkResolver.normalize(it) }
				score += when {
					artists.any { it == wantedArtist } -> 4
					artists.any { it.isNotEmpty() && (wantedArtist in it || it in wantedArtist) } -> 2
					// Another artist with the same song name: only when nothing better shows up
					else -> -1
				}
			}
			return score
		}

		return candidates
			.map { it to score(it) }
			.filter { (_, score) -> score >= MIN_LINK_SCORE }
			.maxByOrNull { (_, score) -> score }
			?.first
	}

	/** Add a song, or every song of an album, at the end of the queue */
	private suspend fun add(id: UUID, addedBy: String): Boolean {
		if (_state.value.guestSongs.size >= MAX_GUEST_SONGS) return false

		val item = api.userLibraryApi.getItem(id).content
		val songs = when (item.type) {
			BaseItemKind.AUDIO -> listOf(item)
			BaseItemKind.MUSIC_ALBUM -> api.itemsApi.getItems(
				GetItemsRequest(
					parentId = item.id,
					includeItemTypes = setOf(BaseItemKind.AUDIO),
					recursive = true,
					sortBy = setOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
					limit = MAX_ALBUM_SONGS,
				)
			).content.items

			else -> emptyList()
		}
		if (songs.isEmpty()) return false

		withContext(Dispatchers.Main) {
			val wasStopped = playbackManager.queue.entry.value == null ||
				playbackManager.state.playState.value == PlayState.STOPPED
			playbackManager.queue.addSupplier(RewriteMediaManager.BaseItemQueueSupplier(api, songs, true))
			// Start the music when nothing was playing; a paused song stays paused
			if (wasStopped) playbackManager.state.play()
		}

		_state.update { state ->
			state.copy(guestSongs = state.guestSongs + songs.map { song ->
				GuestSong(
					itemId = song.id,
					name = song.name.orEmpty(),
					artist = song.artists?.firstOrNull() ?: song.albumArtist,
					albumId = song.albumId,
					addedBy = addedBy,
				)
			})
		}
		Timber.i("Shared queue: $addedBy added ${songs.size} song(s)")
		return true
	}

	private suspend fun image(itemId: UUID): ByteArray? = try {
		api.imageApi.getItemImage(
			itemId = itemId,
			imageType = ImageType.PRIMARY,
			maxWidth = IMAGE_SIZE,
			quality = 80,
			format = ImageFormat.JPG,
		).content
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		null
	}

	private fun newToken(): String {
		val random = SecureRandom()
		return (1..TOKEN_LENGTH).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
	}

	companion object {
		private val PORTS = listOf(8765, 8766, 8767, 8780)
		private const val BACKLOG = 16
		private const val SOCKET_TIMEOUT_MS = 10_000
		private const val MAX_LINE_LENGTH = 8_192
		private const val MAX_NAME_LENGTH = 24
		private const val MAX_SEARCH_LENGTH = 80
		private const val SEARCH_SONGS = 25
		private const val SEARCH_ALBUMS = 8
		private const val MAX_ALBUM_SONGS = 60
		private const val MAX_GUEST_SONGS = 150
		private const val MAX_REQUESTS = 50
		private const val LINK_CANDIDATES = 40
		private const val MIN_LINK_SCORE = 3
		private const val IMAGE_SIZE = 200
		private const val TOKEN_LENGTH = 6
		private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"

		/** Interfaces that are not the home network (Wi-Fi Direct for screen mirroring, VPNs...) */
		private val ignoredInterfaces = listOf("p2p", "dummy", "tun", "ppp", "rmnet", "ccmni", "ap", "swlan")

		/**
		 * IPv4 address of the TV on the home network: the one of the network Android uses (Wi-Fi or
		 * Ethernet). Looking at every interface picked the Wi-Fi Direct address (192.168.49.1) on
		 * some Fire TVs, which phones can't reach.
		 */
		fun lanAddress(context: Context): InetAddress? {
			val activeAddress = runCatching {
				val connectivity = context.getSystemService(ConnectivityManager::class.java)
				val network = connectivity?.activeNetwork
				network?.let { connectivity.getLinkProperties(it) }
					?.linkAddresses
					?.map { it.address }
					?.filterIsInstance<Inet4Address>()
					?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
			}.getOrNull()
			if (activeAddress != null) return activeAddress

			return runCatching {
				NetworkInterface.getNetworkInterfaces().toList()
					.filter { it.isUp && !it.isLoopback && !it.isVirtual }
					.filter { network -> ignoredInterfaces.none { network.name.startsWith(it) } }
					// Wi-Fi and Ethernet first
					.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("eth") }
					.flatMap { it.inetAddresses.toList() }
					.filterIsInstance<Inet4Address>()
					.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
			}.getOrNull()
		}
	}
}
