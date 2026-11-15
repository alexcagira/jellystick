package org.jellyfin.androidtv.ui.music.modern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.musicGenresApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

/**
 * Everything the modern music page shows. Recommendations and stations come from what the user
 * listens to the most: no external service needed.
 */
class ModernMusicRepository(
	private val api: ApiClient,
) {
	data class Content(
		val hero: List<MusicHeroSlide>,
		val sections: List<MusicSection>,
	)

	suspend fun load(libraryId: UUID, labels: MusicLabels): Content = withContext(Dispatchers.IO) {
		coroutineScope {
			val latestAsync = async { safe("latest") { latest(libraryId) }.orEmpty() }
			val lastPlayedAsync = async { safe("last played") { lastPlayed(libraryId) }.orEmpty() }
			val favoritesAsync = async { safe("favorites") { favorites(libraryId) }.orEmpty() }
			val playlistsAsync = async { safe("playlists") { playlists() }.orEmpty() }
			val mostPlayed = safe("most played") { mostPlayed(libraryId) }.orEmpty()

			// Albums listened to the most, they seed the recommendations and "Your mix"
			val topAlbums = mostPlayed
				.mapNotNull { track -> track.albumId?.let { it to track } }
				.groupBy({ it.first }, { it.second })
				.entries
				.sortedByDescending { (_, tracks) -> tracks.sumOf { it.userData?.playCount ?: 1 } }
				.take(RECOMMENDATION_SEEDS)
				.map { (albumId, tracks) -> albumId to tracks.first() }

			val recommendationsAsync = async { safe("recommendations") { recommendations(libraryId, topAlbums) }.orEmpty() }
			val stations = stations(libraryId, mostPlayed, topAlbums.firstOrNull()?.first, labels)

			val recommendations = recommendationsAsync.await()
			val hero = recommendations.take(HERO_SLIDES).map { (album, seed) -> heroSlide(album, seed, labels) }

			Content(
				hero = hero,
				sections = listOfNotNull(
					section(MusicSectionType.LATEST, latestAsync.await().map { albumCard(it) }),
					section(MusicSectionType.LAST_PLAYED, lastPlayedAsync.await().map { trackCard(it) }),
					section(MusicSectionType.STATIONS, stations),
					section(MusicSectionType.RECOMMENDED, recommendations.drop(HERO_SLIDES).map { (album, _) -> albumCard(album) }),
					section(MusicSectionType.FAVORITES, favoritesAsync.await().map { albumCard(it) }),
					section(MusicSectionType.PLAYLISTS, playlistsAsync.await().map { playlistCard(it) }),
				),
			)
		}
	}

	/** Genres of the library (shown as colored squares with their name) */
	suspend fun genres(libraryId: UUID): List<MusicCard> = withContext(Dispatchers.IO) {
		api.musicGenresApi.getMusicGenres(
			parentId = libraryId,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			limit = MAX_GENRES,
			enableImages = false,
			enableTotalRecordCount = false,
		).content.items.map { genreCard(it) }
	}

	/**
	 * Every station: the personal ones (like on the music page), one per genre and one per artist.
	 */
	suspend fun allStations(libraryId: UUID, labels: MusicLabels): StationGroups = withContext(Dispatchers.IO) {
		coroutineScope {
			val genresAsync = async {
				safe("genres") {
					api.musicGenresApi.getMusicGenres(
						parentId = libraryId,
						sortBy = setOf(ItemSortBy.SORT_NAME),
						limit = MAX_GENRES,
						enableTotalRecordCount = false,
					).content.items
				}.orEmpty()
			}
			val artistsAsync = async {
				safe("artists") {
					api.artistsApi.getAlbumArtists(
						parentId = libraryId,
						sortBy = setOf(ItemSortBy.SORT_NAME),
						limit = MAX_STATION_ARTISTS,
						imageTypeLimit = 1,
						enableTotalRecordCount = false,
					).content.items
				}.orEmpty()
			}
			val mostPlayed = safe("most played") { mostPlayed(libraryId) }.orEmpty()
			val topAlbumId = mostPlayed
				.mapNotNull { it.albumId }
				.groupingBy { it }
				.eachCount()
				.maxByOrNull { it.value }
				?.key

			StationGroups(
				forYou = stations(libraryId, mostPlayed, topAlbumId, labels),
				genres = genresAsync.await().map { genre ->
					stationCard("genre_${genre.id}", genre.name.orEmpty(), labels.radio, StationSource(genreId = genre.id), imageItemId = null)
				},
				artists = artistsAsync.await().map { artist ->
					stationCard(
						key = "artist_${artist.id}",
						title = artist.name.orEmpty(),
						subtitle = labels.radio,
						source = StationSource(artistId = artist.id),
						imageItemId = artist.id.takeIf { artist.itemImages[ImageType.PRIMARY] != null },
					)
				},
			)
		}
	}

	/** Tracks of a station (instant mix) */
	suspend fun stationTracks(source: StationSource): List<BaseItemDto> = withContext(Dispatchers.IO) {
		when {
			source.genreId != null -> api.instantMixApi.getInstantMixFromMusicGenreById(id = source.genreId, limit = STATION_TRACKS).content.items
			source.artistId != null -> api.instantMixApi.getInstantMixFromArtists(itemId = source.artistId, limit = STATION_TRACKS).content.items
			source.albumId != null -> api.instantMixApi.getInstantMixFromAlbum(itemId = source.albumId, limit = STATION_TRACKS).content.items
			source.playlistId != null -> api.instantMixApi.getInstantMixFromPlaylist(itemId = source.playlistId, limit = STATION_TRACKS).content.items
			else -> emptyList()
		}
	}

	/** Random songs of the library ("Shuffle") */
	suspend fun shuffleTracks(libraryId: UUID): List<BaseItemDto> = withContext(Dispatchers.IO) {
		api.itemsApi.getItems(
			GetItemsRequest(
				parentId = libraryId,
				includeItemTypes = setOf(BaseItemKind.AUDIO),
				recursive = true,
				sortBy = setOf(ItemSortBy.RANDOM),
				enableTotalRecordCount = false,
				limit = STATION_TRACKS,
			)
		).content.items
	}

	// Queries

	private suspend fun latest(libraryId: UUID): List<BaseItemDto> = api.userLibraryApi.getLatestMedia(
		parentId = libraryId,
		includeItemTypes = setOf(BaseItemKind.AUDIO),
		fields = setOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
		imageTypeLimit = 1,
		groupItems = true,
		limit = SECTION_LIMIT,
	).content

	private suspend fun lastPlayed(libraryId: UUID): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			parentId = libraryId,
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			filters = setOf(ItemFilter.IS_PLAYED),
			sortBy = setOf(ItemSortBy.DATE_PLAYED),
			sortOrder = setOf(SortOrder.DESCENDING),
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = SECTION_LIMIT,
		)
	).content.items

	private suspend fun favorites(libraryId: UUID): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			parentId = libraryId,
			includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
			recursive = true,
			isFavorite = true,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = LONG_SECTION_LIMIT,
		)
	).content.items

	private suspend fun playlists(): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			includeItemTypes = setOf(BaseItemKind.PLAYLIST),
			mediaTypes = setOf(MediaType.AUDIO),
			recursive = true,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = LONG_SECTION_LIMIT,
		)
	).content.items

	private suspend fun mostPlayed(libraryId: UUID): List<BaseItemDto> = api.itemsApi.getItems(
		GetItemsRequest(
			parentId = libraryId,
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			filters = setOf(ItemFilter.IS_PLAYED),
			sortBy = setOf(ItemSortBy.PLAY_COUNT, ItemSortBy.DATE_PLAYED),
			sortOrder = setOf(SortOrder.DESCENDING),
			fields = setOf(ItemFields.GENRES),
			enableImages = false,
			enableTotalRecordCount = false,
			limit = MOST_PLAYED_LIMIT,
		)
	).content.items

	/**
	 * Albums similar to the ones listened to the most (not those already played a lot), with the
	 * track that led to each one. Random albums when nothing was played yet.
	 */
	private suspend fun recommendations(
		libraryId: UUID,
		topAlbums: List<Pair<UUID, BaseItemDto>>,
	): List<Pair<BaseItemDto, BaseItemDto?>> = coroutineScope {
		val played = topAlbums.map { it.first }.toSet()

		val similar = topAlbums.map { (albumId, seed) ->
			async {
				safe("similar albums") {
					api.libraryApi.getSimilarAlbums(
						itemId = albumId,
						limit = SIMILAR_PER_SEED,
						fields = setOf(ItemFields.GENRES, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
					).content.items
				}.orEmpty().map { album -> album to seed }
			}
		}.awaitAll()

		// Take the first suggestion of each seed in turn so the list stays varied
		val merged = ArrayList<Pair<BaseItemDto, BaseItemDto?>>()
		val maxSize = similar.maxOfOrNull { it.size } ?: 0
		for (index in 0 until maxSize) {
			for (list in similar) list.getOrNull(index)?.let { merged += it }
		}

		val recommendations = merged
			.filter { (album, _) -> album.id !in played && album.itemImages[ImageType.PRIMARY] != null }
			.distinctBy { (album, _) -> album.id }
			.take(RECOMMENDATIONS)

		recommendations.ifEmpty {
			api.itemsApi.getItems(
				GetItemsRequest(
					parentId = libraryId,
					includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
					recursive = true,
					sortBy = setOf(ItemSortBy.RANDOM),
					imageTypes = setOf(ImageType.PRIMARY),
					fields = setOf(ItemFields.GENRES),
					imageTypeLimit = 1,
					enableTotalRecordCount = false,
					limit = RECOMMENDATIONS,
				)
			).content.items.map { it to null }
		}
	}

	/**
	 * "Your mix" (top album), radios of the artists and genres listened to the most. Genres of the
	 * library when nothing was played yet.
	 */
	private suspend fun stations(
		libraryId: UUID,
		mostPlayed: List<BaseItemDto>,
		topAlbumId: UUID?,
		labels: MusicLabels,
	): List<MusicCard> {
		val stations = ArrayList<MusicCard>()

		if (topAlbumId != null) {
			stations += stationCard("mix", labels.yourMix, null, StationSource(albumId = topAlbumId), imageItemId = topAlbumId)
		}

		val topArtists = mostPlayed
			.flatMap { track -> track.artistItems.orEmpty() }
			.filter { !it.name.isNullOrBlank() }
			.groupingBy { it.id to it.name.orEmpty() }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(STATION_ARTISTS)
		for ((artist, _) in topArtists) {
			val (id, name) = artist
			stations += stationCard("artist_$id", name, labels.radio, StationSource(artistId = id), imageItemId = id)
		}

		var topGenres = mostPlayed
			.flatMap { track -> track.genreItems.orEmpty() }
			.filter { !it.name.isNullOrBlank() }
			.groupingBy { it.id to it.name.orEmpty() }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(STATION_GENRES)
			.map { it.key }

		if (topGenres.isEmpty()) {
			topGenres = safe("genres") {
				api.musicGenresApi.getMusicGenres(
					parentId = libraryId,
					sortBy = setOf(ItemSortBy.RANDOM),
					limit = STATION_GENRES,
					enableTotalRecordCount = false,
				).content.items.map { it.id to it.name.orEmpty() }
			}.orEmpty()
		}
		for ((id, name) in topGenres) {
			stations += stationCard("genre_$id", name, labels.radio, StationSource(genreId = id), imageItemId = null)
		}

		return stations
	}

	// Cards

	private fun section(type: MusicSectionType, cards: List<MusicCard>) =
		if (cards.isEmpty()) null else MusicSection(type, cards.distinctBy { it.key })

	internal fun albumCard(album: BaseItemDto): MusicCard {
		val image = album.itemImages[ImageType.PRIMARY] ?: album.albumPrimaryImage
		return MusicCard(
			key = album.id.toString(),
			kind = if (album.type == BaseItemKind.AUDIO) MusicCardKind.TRACK else MusicCardKind.ALBUM,
			item = album,
			title = album.name.orEmpty(),
			subtitle = album.albumArtist ?: album.artists?.firstOrNull(),
			imageUrl = image?.getUrl(api, maxWidth = COVER_WIDTH),
			backgroundUrl = image?.blurredUrl(),
		)
	}

	internal fun trackCard(track: BaseItemDto): MusicCard {
		val image = track.itemImages[ImageType.PRIMARY] ?: track.albumPrimaryImage
		return MusicCard(
			key = track.id.toString(),
			kind = MusicCardKind.TRACK,
			item = track,
			title = track.name.orEmpty(),
			subtitle = track.albumArtist ?: track.artists?.firstOrNull(),
			imageUrl = image?.getUrl(api, maxWidth = COVER_WIDTH),
			backgroundUrl = image?.blurredUrl(),
		)
	}

	internal fun playlistCard(playlist: BaseItemDto): MusicCard {
		val image = playlist.itemImages[ImageType.PRIMARY]
		return MusicCard(
			key = playlist.id.toString(),
			kind = MusicCardKind.PLAYLIST,
			item = playlist,
			title = playlist.name.orEmpty(),
			subtitle = playlist.childCount?.let { "$it" },
			imageUrl = image?.getUrl(api, maxWidth = COVER_WIDTH),
			backgroundUrl = image?.blurredUrl(),
		)
	}

	internal fun artistCard(artist: BaseItemDto): MusicCard {
		val image = artist.itemImages[ImageType.PRIMARY]
		return MusicCard(
			key = artist.id.toString(),
			kind = MusicCardKind.ARTIST,
			item = artist,
			title = artist.name.orEmpty(),
			subtitle = null,
			imageUrl = image?.getUrl(api, maxWidth = COVER_WIDTH),
			backgroundUrl = image?.blurredUrl(),
		)
	}

	/** Genre: drawn by the app (colored square with the name), no image */
	internal fun genreCard(genre: BaseItemDto): MusicCard = MusicCard(
		key = genre.id.toString(),
		kind = MusicCardKind.GENRE,
		item = genre,
		title = genre.name.orEmpty(),
		subtitle = null,
		imageUrl = null,
		backgroundUrl = null,
	)

	internal fun stationCard(key: String, title: String, subtitle: String?, source: StationSource, imageItemId: UUID?) = MusicCard(
		key = "station_$key",
		kind = MusicCardKind.STATION,
		item = null,
		title = title,
		subtitle = subtitle,
		imageUrl = imageItemId?.let { api.imageApi.getItemImageUrl(itemId = it, imageType = ImageType.PRIMARY, maxWidth = COVER_WIDTH) },
		backgroundUrl = imageItemId?.let {
			api.imageApi.getItemImageUrl(itemId = it, imageType = ImageType.PRIMARY, maxWidth = BLUR_WIDTH, blur = BLUR, quality = 70)
		},
		station = source,
	)

	private fun heroSlide(album: BaseItemDto, seed: BaseItemDto?, labels: MusicLabels): MusicHeroSlide {
		val image = album.itemImages[ImageType.PRIMARY]
		val seedArtist = seed?.albumArtist ?: seed?.artists?.firstOrNull()
		return MusicHeroSlide(
			album = album,
			title = album.name.orEmpty(),
			artist = album.albumArtist ?: album.artists?.firstOrNull(),
			details = listOfNotNull(album.productionYear?.toString(), album.genres?.firstOrNull()).joinToString("  ·  ").ifEmpty { null },
			reason = seedArtist?.let { labels.becauseYouListen(it) } ?: labels.recommended,
			coverUrl = image?.getUrl(api, maxWidth = HERO_COVER_WIDTH),
			backgroundUrl = image?.let {
				api.imageApi.getItemImageUrl(itemId = it.item, imageType = it.type, tag = it.tag, maxWidth = HERO_BLUR_WIDTH, blur = HERO_BLUR, quality = 70)
			},
		)
	}

	internal fun JellyfinImage.blurredUrl(): String = api.imageApi.getItemImageUrl(
		itemId = item,
		imageType = type,
		tag = tag,
		maxWidth = BLUR_WIDTH,
		blur = BLUR,
		quality = 70,
	)

	private inline fun <T> safe(name: String, block: () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Unable to load music section: $name")
		null
	}

	private companion object {
		const val SECTION_LIMIT = 30
		const val LONG_SECTION_LIMIT = 60
		const val MOST_PLAYED_LIMIT = 150
		const val RECOMMENDATION_SEEDS = 4
		const val SIMILAR_PER_SEED = 10
		const val RECOMMENDATIONS = 24
		const val HERO_SLIDES = 6
		const val STATION_ARTISTS = 4
		const val STATION_GENRES = 4
		const val STATION_TRACKS = 100
		const val MAX_GENRES = 150
		const val MAX_STATION_ARTISTS = 300

		const val COVER_WIDTH = 360
		const val HERO_COVER_WIDTH = 500
		const val BLUR_WIDTH = 200
		const val BLUR = 16
		const val HERO_BLUR_WIDTH = 480
		const val HERO_BLUR = 12
	}
}

/** Stations page: personal stations, one per genre, one per artist */
data class StationGroups(
	val forYou: List<MusicCard>,
	val genres: List<MusicCard>,
	val artists: List<MusicCard>,
)

/**
 * Translated texts the repository puts on cards (it has no access to resources).
 */
data class MusicLabels(
	val yourMix: String,
	val radio: String,
	val recommended: String,
	val becauseYouListen: (String) -> String,
)
