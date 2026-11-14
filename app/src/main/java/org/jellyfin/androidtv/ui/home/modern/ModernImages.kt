package org.jellyfin.androidtv.ui.home.modern

import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.apiclient.seriesPrimaryImage
import org.jellyfin.androidtv.util.apiclient.seriesThumbImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/**
 * Image selection shared by the modern home and details screens.
 */
internal object ModernImageSizes {
	const val POSTER = 360
	const val LANDSCAPE = 560
	const val HERO = 1920
	const val LOGO = 700
	const val PERSON = 420
	// Blurred images are requested small (quick for the server to blur and to download) and
	// scaled up on screen, which keeps them soft
	const val BACKGROUND = 200
	const val BACKGROUND_BLUR = 16
	const val GLASS = 480
	const val GLASS_BLUR = 7
}

internal fun BaseItemDto.posterImage(): JellyfinImage? = when (type) {
	BaseItemKind.EPISODE -> seriesPrimaryImage ?: itemImages[ImageType.PRIMARY]
	BaseItemKind.SEASON -> itemImages[ImageType.PRIMARY] ?: seriesPrimaryImage
	else -> itemImages[ImageType.PRIMARY]
}

internal fun BaseItemDto.backdropImage(): JellyfinImage? =
	itemBackdropImages.firstOrNull() ?: parentBackdropImages.firstOrNull()

internal fun BaseItemDto.logoImage(): JellyfinImage? =
	itemImages[ImageType.LOGO] ?: parentImages[ImageType.LOGO]

internal fun BaseItemDto.landscapeImage(): JellyfinImage? = when (type) {
	BaseItemKind.EPISODE -> itemImages[ImageType.PRIMARY]
		?: parentImages[ImageType.THUMB]
		?: seriesThumbImage
		?: parentBackdropImages.firstOrNull()

	else -> itemImages[ImageType.THUMB]
		?: itemBackdropImages.firstOrNull()
		?: itemImages[ImageType.PRIMARY]
}

/**
 * Blurred backdrop rendered by the server. Compose' Modifier.blur only works on Android 12+ and
 * the Fire TV Stick 4K Max runs Fire OS 8 (Android 11), so the server does the blurring instead.
 */
internal fun BaseItemDto.blurredBackdropUrl(
	api: ApiClient,
	maxWidth: Int = ModernImageSizes.BACKGROUND,
	blur: Int = ModernImageSizes.BACKGROUND_BLUR,
): String? = backdropImage()?.let { image ->
	api.imageApi.getItemImageUrl(
		itemId = image.item,
		imageType = ImageType.BACKDROP,
		tag = image.tag,
		imageIndex = image.index,
		maxWidth = maxWidth,
		quality = 70,
		blur = blur,
	)
}
