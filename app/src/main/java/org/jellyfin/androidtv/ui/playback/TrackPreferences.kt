package org.jellyfin.androidtv.ui.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.util.Locale

/**
 * The audio and subtitle tracks picked in the player, kept for the movie or for the whole series
 * (every episode). Another file gets the track that looks the same: same language first, then the
 * same title (signs & songs, commentary...), forced flag, codec and channels.
 */
class TrackPreferences(context: Context) {
	private val preferences = context.getSharedPreferences("track_choices", Context.MODE_PRIVATE)
	private val json = Json { ignoreUnknownKeys = true }

	/** The audio stream index for this item, or null to leave it to the server */
	fun audioFor(item: BaseItemDto?, streams: List<MediaStream>?): Int? {
		val choice = read(item)?.audio ?: return null
		return TrackMatcher.best(choice, streams.orEmpty(), MediaStreamType.AUDIO)?.index
	}

	/** The subtitle stream index for this item, -1 for none, or null to leave it to the server */
	fun subtitleFor(item: BaseItemDto?, streams: List<MediaStream>?): Int? {
		val choices = read(item) ?: return null
		if (choices.subtitlesOff) return -1
		val choice = choices.subtitle ?: return null
		return TrackMatcher.best(choice, streams.orEmpty(), MediaStreamType.SUBTITLE)?.index
	}

	fun rememberAudio(item: BaseItemDto?, stream: MediaStream) {
		val current = read(item) ?: TrackChoices()
		write(item, current.copy(audio = TrackChoice.of(stream)))
	}

	/** Null: subtitles off */
	fun rememberSubtitle(item: BaseItemDto?, stream: MediaStream?) {
		val current = read(item) ?: TrackChoices()
		write(item, current.copy(subtitle = stream?.let(TrackChoice::of), subtitlesOff = stream == null))
	}

	private fun read(item: BaseItemDto?): TrackChoices? {
		val key = key(item) ?: return null
		val value = preferences.getString(key, null) ?: return null
		return runCatching { json.decodeFromString<TrackChoices>(value) }
			.onFailure { Timber.w(it, "Unreadable track choice for %s", key) }
			.getOrNull()
	}

	private fun write(item: BaseItemDto?, choices: TrackChoices) {
		val key = key(item) ?: return
		preferences.edit { putString(key, json.encodeToString(choices)) }
		Timber.i("Tracks remembered for %s: %s", key, choices)
	}

	private companion object {
		/** Movies (and other videos) by their id, episodes by their series; never live TV */
		fun key(item: BaseItemDto?): String? = when {
			item == null -> null
			item.type == BaseItemKind.TV_CHANNEL || item.type == BaseItemKind.PROGRAM -> null
			else -> (item.seriesId ?: item.id).toString()
		}
	}
}

@Serializable
internal data class TrackChoices(
	val audio: TrackChoice? = null,
	val subtitle: TrackChoice? = null,
	val subtitlesOff: Boolean = false,
)

@Serializable
internal data class TrackChoice(
	val language: String? = null,
	val title: String? = null,
	val codec: String? = null,
	val channels: Int? = null,
	val forced: Boolean = false,
	val hearingImpaired: Boolean = false,
	val external: Boolean = false,
	val index: Int = -1,
) {
	companion object {
		fun of(stream: MediaStream) = TrackChoice(
			language = stream.language,
			title = stream.title,
			codec = stream.codec,
			channels = stream.channels,
			forced = stream.isForced,
			hearingImpaired = stream.isHearingImpaired,
			external = stream.isExternal,
			index = stream.index,
		)
	}
}

internal object TrackMatcher {
	/** The stream of this type that looks most like the choice, only among the ones in its language */
	fun best(choice: TrackChoice, streams: List<MediaStream>, type: MediaStreamType): MediaStream? {
		val language = language(choice.language)
		return streams
			.filter { it.type == type && language(it.language) == language }
			// The first one wins a tie (as the server orders them)
			.maxByOrNull { score(choice, it) }
	}

	fun score(choice: TrackChoice, stream: MediaStream): Int {
		var score = 0
		val chosenTitle = title(choice.title)
		val title = title(stream.title)
		when {
			chosenTitle == title -> score += 30
			chosenTitle != null && title != null && (chosenTitle in title || title in chosenTitle) -> score += 15
		}
		if (kind(chosenTitle) == kind(title)) score += 10
		if (choice.forced == stream.isForced) score += 20
		if (choice.hearingImpaired == stream.isHearingImpaired) score += 10
		if (choice.codec != null && choice.codec.equals(stream.codec, ignoreCase = true)) score += 5
		if (choice.channels != null && choice.channels == stream.channels) score += 3
		if (choice.external == stream.isExternal) score += 2
		if (choice.index == stream.index) score += 1
		return score
	}

	/** ISO 639-2/T (3 letters), from 2 letters or the bibliographic codes too; null when unknown */
	fun language(code: String?): String? {
		val value = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
		if (value.isNullOrEmpty() || value in UNKNOWN) return null
		val iso3 = if (value.length == 2) {
			runCatching { Locale(value).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: value
		} else value
		return BIBLIOGRAPHIC[iso3] ?: iso3
	}

	/** Lower case words only: "English (SDH)" is "english sdh" */
	private fun title(value: String?) = value
		?.lowercase(Locale.ROOT)
		?.replace(NOT_WORD, " ")
		?.trim()
		?.takeIf { it.isNotEmpty() }

	private val NOT_WORD = Regex("[^\\p{L}\\p{N}&]+")

	private enum class Kind { SIGNS, COMMENTARY, HEARING_IMPAIRED, OTHER }

	private fun kind(title: String?): Kind = when {
		title == null -> Kind.OTHER
		listOf("sign", "song", "forced", "s&s", "carteles", "forzad").any { it in title } -> Kind.SIGNS
		listOf("comment", "coment").any { it in title } -> Kind.COMMENTARY
		listOf("sdh", "cc", "hearing").any { it in title.split(' ') } -> Kind.HEARING_IMPAIRED
		else -> Kind.OTHER
	}

	private val UNKNOWN = setOf("und", "unk", "unknown", "xx", "zxx")

	private val BIBLIOGRAPHIC = mapOf(
		"alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
		"cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
		"gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
		"per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
	)
}

private val trackPreferences: TrackPreferences
	get() = KoinJavaComponent.get(TrackPreferences::class.java)

/** The audio track picked in the player menu: switched and kept for the movie or every episode of the series */
fun PlaybackController.chooseAudioStream(index: Int) {
	if (!isLiveTv) {
		currentMediaSource?.mediaStreams
			?.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == index }
			?.let { trackPreferences.rememberAudio(currentlyPlayingItem, it) }
	}
	switchAudioStream(index)
}

/** The subtitles picked in the player menu (-1: none): switched and kept for the movie or every episode of the series */
fun PlaybackController.chooseSubtitleIndex(index: Int) {
	if (!isLiveTv) {
		if (index < 0) {
			trackPreferences.rememberSubtitle(currentlyPlayingItem, null)
		} else {
			currentMediaSource?.mediaStreams
				?.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
				?.let { trackPreferences.rememberSubtitle(currentlyPlayingItem, it) }
		}
	}
	setSubtitleIndex(index)
}
