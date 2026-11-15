package org.jellyfin.androidtv.ui.collections

import android.util.Base64
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.toFileInfo
import java.util.UUID

/**
 * Set the poster of an item (collection, playlist) from JPEG bytes. Needs an administrator.
 * The server expects the image encoded as base64 (raw bytes as a fallback for other versions).
 */
internal suspend fun ApiClient.uploadPrimaryImage(itemId: UUID, jpeg: ByteArray) {
	val base64 = Base64.encode(jpeg, Base64.NO_WRAP)
	try {
		imageApi.setItemImage(itemId, ImageType.PRIMARY, base64.toFileInfo("image/jpeg"))
	} catch (err: InvalidStatusException) {
		if (err.status != 400) throw err
		imageApi.setItemImage(itemId, ImageType.PRIMARY, jpeg.toFileInfo("image/jpeg"))
	}
}
