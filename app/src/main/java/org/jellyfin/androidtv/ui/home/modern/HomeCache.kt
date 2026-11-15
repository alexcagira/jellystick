package org.jellyfin.androidtv.ui.home.modern

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File

/**
 * What the home screen showed last time, saved so the next start can show it instantly while
 * the fresh rows load in the background.
 */
@Serializable
data class CachedHome(
	/** Server + session the content belongs to */
	val key: String,
	val hero: List<HeroSlide>,
	val sections: List<HomeSection>,
)

class HomeCache(private val context: Context) {
	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}
	private val mutex = Mutex()

	private val file: File
		get() = File(context.cacheDir, CACHE_FILE)

	suspend fun read(key: String): CachedHome? = withContext(Dispatchers.IO) {
		mutex.withLock {
			try {
				if (!file.exists()) return@withContext null
				json.decodeFromString<CachedHome>(file.readText()).takeIf { it.key == key }
			} catch (err: Exception) {
				Timber.w(err, "Unable to read the home cache")
				null
			}
		}
	}

	suspend fun write(home: CachedHome) = withContext(Dispatchers.IO) {
		mutex.withLock {
			try {
				val slim = home.copy(
					hero = home.hero.map { it.copy(item = it.item.slim()) },
					sections = home.sections.map { section -> section.copy(cards = section.cards.map { it.copy(item = it.item.slim()) }) },
				)
				file.writeText(json.encodeToString(slim))
			} catch (err: Exception) {
				Timber.w(err, "Unable to write the home cache")
			}
		}
	}

	private companion object {
		const val CACHE_FILE = "modern_home.json"
	}
}

/**
 * Only what the cards need from an item (play, menus, details): the cache stays small and quick
 * to read on a Fire TV.
 */
internal fun BaseItemDto.slim() = copy(
	mediaStreams = null,
	mediaSources = null,
	chapters = null,
	people = null,
	trickplay = null,
	overview = null,
	externalUrls = null,
	remoteTrailers = null,
	studios = null,
	taglines = null,
)
