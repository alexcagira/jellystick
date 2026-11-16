package org.jellyfin.androidtv.ui.playback.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.compose.koinInject
import kotlinx.coroutines.CancellationException
import java.util.UUID
import kotlin.math.roundToInt

private val thumbnailShape = RoundedCornerShape(6.dp)

/**
 * Preview image of [positionMs] from the server trickplay tiles. Draws an empty frame when the
 * item has no trickplay images.
 */
@Composable
internal fun TrickplayThumbnail(
	item: BaseItemDto?,
	mediaSourceId: String?,
	positionMs: Long,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val imageLoader = koinInject<ImageLoader>()

	// The item of the player can come without the trickplay information: asked once to the server
	val trickplay by produceState(item?.trickplay?.takeIf { it.isNotEmpty() }, item?.id) {
		if (item == null || !item.trickplay.isNullOrEmpty()) return@produceState
		value = TrickplayInfoCache.get(item.id) ?: runCatching { api.userLibraryApi.getItem(item.id).content.trickplay }
			.onFailure { if (it is CancellationException) throw it }
			.getOrNull()
			?.also { TrickplayInfoCache.put(item.id, it) }
	}

	// The images of the version being played, otherwise those of another version of the file (the
	// server may have made them only for one, e.g. not for the 4K Dolby Vision one)
	val sources = trickplay.orEmpty().filterValues { it.isNotEmpty() }
	val playedSource = mediaSourceId?.toUUIDOrNull()
	val sourceId = sources.keys.firstOrNull { it == mediaSourceId || (playedSource != null && it.toUUIDOrNull() == playedSource) }
		?: sources.keys.firstOrNull()
	val info = sourceId?.let { id -> sources[id]?.values?.minByOrNull { it.width } }

	// Tile (image with a grid of thumbnails) and position of the thumbnail inside it
	val tileSize = info?.let { it.tileWidth * it.tileHeight }?.takeIf { it > 0 }
	val thumbnail = if (info != null && tileSize != null && info.interval > 0) (positionMs / info.interval).toInt() else null
	val tileIndex = if (thumbnail != null && tileSize != null) thumbnail / tileSize else null
	val tileUrl = if (info != null && tileIndex != null && item != null && sourceId != null) {
		api.trickplayApi.getTrickplayTileImageUrl(
			itemId = item.id,
			width = info.width,
			index = tileIndex,
			mediaSourceId = sourceId.toUUIDOrNull(),
		)
	} else null

	val tile by produceState<ImageBitmap?>(initialValue = null, tileUrl) {
		if (tileUrl == null) {
			value = null
			return@produceState
		}

		val request = ImageRequest.Builder(context)
			.data(tileUrl)
			.size(Size.ORIGINAL)
			.allowHardware(false)
			.httpHeaders(
				NetworkHeaders.Builder().apply {
					set(
						key = "Authorization",
						value = AuthorizationHeaderBuilder.buildHeader(
							api.clientInfo.name,
							api.clientInfo.version,
							api.deviceInfo.id,
							api.deviceInfo.name,
							api.accessToken,
						),
					)
				}.build()
			)
			.build()

		val result = imageLoader.execute(request)
		if (result is SuccessResult) value = result.image.toBitmap().asImageBitmap()
	}

	Box(
		modifier = modifier
			.clip(thumbnailShape)
			.background(Color.Black.copy(alpha = 0.6f))
			.drawBehind {
				val image = tile ?: return@drawBehind
				if (info == null || tileSize == null || thumbnail == null) return@drawBehind

				val offset = thumbnail % tileSize
				val x = (offset % info.tileWidth) * info.width
				val y = (offset / info.tileWidth) * info.height
				if (x + info.width > image.width || y + info.height > image.height) return@drawBehind

				drawImage(
					image = image,
					srcOffset = IntOffset(x, y),
					srcSize = IntSize(info.width, info.height),
					dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
				)
			}
			.border(1.5.dp, Color.White.copy(alpha = 0.85f), thumbnailShape),
	)
}

/** Trickplay information asked to the server, by item (a few items per playback session) */
private object TrickplayInfoCache {
	private const val MAX_ITEMS = 20
	private val items = LinkedHashMap<UUID, Map<String, Map<String, TrickplayInfoDto>>>()

	@Synchronized
	fun get(id: UUID) = items[id]

	@Synchronized
	fun put(id: UUID, value: Map<String, Map<String, TrickplayInfoDto>>) {
		items[id] = value
		while (items.size > MAX_ITEMS) items.remove(items.keys.first())
	}
}
