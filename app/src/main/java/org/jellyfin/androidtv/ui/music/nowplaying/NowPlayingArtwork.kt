package org.jellyfin.androidtv.ui.music.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

internal const val NOW_PLAYING_COVER_WIDTH = 640
private const val LOAD_TIMEOUT_MS = 4_000L

/** Cover of a song: its own image, the album's or the parent's */
internal fun BaseItemDto.songImage(): JellyfinImage? =
	itemImages[ImageType.PRIMARY] ?: albumPrimaryImage ?: parentImages[ImageType.PRIMARY]

internal fun ApiClient.songCoverUrl(image: JellyfinImage): String = image.getUrl(this, maxWidth = NOW_PLAYING_COVER_WIDTH)

/** Blurred cover used as the background of "Now playing" and its screensaver */
internal fun ApiClient.songBackgroundUrl(image: JellyfinImage): String = imageApi.getItemImageUrl(
	itemId = image.item,
	imageType = image.type,
	tag = image.tag,
	maxWidth = 300,
	blur = 18,
	quality = 70,
)

/**
 * The url to show for an image that changes: the new one only once it is loaded, the previous one
 * stays until then. With the next cover already in the cache the change is immediate, never blank.
 */
@Composable
internal fun rememberLoadedUrl(url: String?): String? {
	val imageLoader = koinInject<ImageLoader>()
	val context = LocalContext.current
	var shownUrl by remember { mutableStateOf(url) }

	LaunchedEffect(url) {
		if (url != null && url != shownUrl) {
			withTimeoutOrNull(LOAD_TIMEOUT_MS) {
				imageLoader.execute(ImageRequest.Builder(context).data(url).build())
			}
		}
		shownUrl = url
	}

	return shownUrl
}

/**
 * Loads the cover and the blurred background of the next song into the cache while the current one
 * plays, so they are ready the moment it starts.
 */
@Composable
internal fun PrefetchSongArtwork(song: BaseItemDto?) {
	val api = koinInject<ApiClient>()
	val imageLoader = koinInject<ImageLoader>()
	val context = LocalContext.current
	val image = song?.songImage()

	LaunchedEffect(image) {
		if (image == null) return@LaunchedEffect
		for (url in listOf(api.songCoverUrl(image), api.songBackgroundUrl(image))) {
			imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
		}
	}
}

/** ⏩ / ⏭ of the remote: 1 (next song), ⏪ / ⏮: -1 (previous song), other keys: null */
internal fun KeyEvent.songSkip(): Int? = when (key) {
	Key.MediaFastForward, Key.MediaNext -> 1
	Key.MediaRewind, Key.MediaPrevious -> -1
	else -> null
}

/**
 * Next or previous song with the ⏪ / ⏩ (and ⏮ / ⏭) keys. Returns true when the key was one of them.
 * Only the first press counts, holding the key does not skip several songs.
 */
internal fun MediaManager.handleSongSkip(event: KeyEvent): Boolean {
	val skip = event.songSkip() ?: return false
	if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
		if (skip > 0) nextAudioItem() else prevAudioItem()
	}
	return true
}
