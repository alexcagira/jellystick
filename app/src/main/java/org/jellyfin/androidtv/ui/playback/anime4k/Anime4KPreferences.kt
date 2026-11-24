package org.jellyfin.androidtv.ui.playback.anime4k

import android.content.Context
import androidx.core.content.edit
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The Anime4K mode chosen in the player, kept for the whole series (or the movie): an anime keeps
 * it on, everything else starts without it.
 */
class Anime4KPreferences(context: Context) {
	private val preferences = context.getSharedPreferences("anime4k", Context.MODE_PRIVATE)

	private fun key(item: BaseItemDto) = (item.seriesId ?: item.id).toString()

	fun modeFor(item: BaseItemDto?): Anime4KMode =
		if (item == null) Anime4KMode.OFF else Anime4KMode.of(preferences.getString(key(item), null))

	/** Experimental: Anime4K draws at the TV's 4K instead of 1080p (for everything) */
	var uhd: Boolean
		get() = preferences.getBoolean(KEY_UHD, false)
		set(value) = preferences.edit { putBoolean(KEY_UHD, value) }

	fun remember(item: BaseItemDto?, mode: Anime4KMode) {
		if (item == null) return
		preferences.edit {
			if (mode == Anime4KMode.OFF) remove(key(item)) else putString(key(item), mode.name)
		}
	}

	private companion object {
		/** Not a series id */
		const val KEY_UHD = "_uhd"
	}
}
