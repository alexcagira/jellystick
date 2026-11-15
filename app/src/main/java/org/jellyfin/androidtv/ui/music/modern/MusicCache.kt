package org.jellyfin.androidtv.ui.music.modern

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.ui.home.modern.slim
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * What the music page showed last time, per library: the page opens with it at once (from memory,
 * or from disk after a restart) while the fresh rows load in the background.
 */
@Serializable
data class CachedMusic(
	/** Server + session the content belongs to */
	val key: String,
	val hero: List<MusicHeroSlide>,
	val sections: List<MusicSection>,
)

class MusicCache(private val context: Context) {
	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}
	private val mutex = Mutex()
	private val memory = HashMap<UUID, CachedMusic>()

	private fun file(libraryId: UUID) = File(context.cacheDir, "modern_music_$libraryId.json")

	suspend fun read(libraryId: UUID, key: String): CachedMusic? = withContext(Dispatchers.IO) {
		mutex.withLock {
			memory[libraryId]?.takeIf { it.key == key }?.let { return@withContext it }

			try {
				val file = file(libraryId)
				if (!file.exists()) return@withContext null
				json.decodeFromString<CachedMusic>(file.readText())
					.takeIf { it.key == key }
					?.also { memory[libraryId] = it }
			} catch (err: Exception) {
				Timber.w(err, "Unable to read the music cache")
				null
			}
		}
	}

	suspend fun write(libraryId: UUID, music: CachedMusic) = withContext(Dispatchers.IO) {
		mutex.withLock {
			val trimmed = music.copy(
				hero = music.hero.map { it.copy(album = it.album.slim()) },
				sections = music.sections.map { section -> section.copy(cards = section.cards.map { it.copy(item = it.item?.slim()) }) },
			)
			memory[libraryId] = trimmed

			try {
				file(libraryId).writeText(json.encodeToString(trimmed))
			} catch (err: Exception) {
				Timber.w(err, "Unable to write the music cache")
			}
		}
	}
}
