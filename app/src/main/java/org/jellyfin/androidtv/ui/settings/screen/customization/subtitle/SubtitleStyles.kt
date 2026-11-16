package org.jellyfin.androidtv.ui.settings.screen.customization.subtitle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.core.graphics.TypefaceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences

/** Fonts of the subtitles (system fonts, present on every Android TV / Fire TV) */
enum class SubtitleFont(@StringRes val label: Int, val family: String) {
	DEFAULT(R.string.subtitles_font_default, "sans-serif"),
	CONDENSED(R.string.subtitles_font_condensed, "sans-serif-condensed"),
	SERIF(R.string.subtitles_font_serif, "serif"),
	MONOSPACE(R.string.subtitles_font_monospace, "monospace");

	companion object {
		fun of(value: Int) = entries.getOrElse(value) { DEFAULT }
	}
}

/** Edge of the letters: outline, shadow... ([AUTO]: an outline when the stroke color is visible) */
enum class SubtitleEdge(val value: Int, @StringRes val label: Int) {
	AUTO(-1, R.string.subtitles_edge_auto),
	NONE(CaptionStyleCompat.EDGE_TYPE_NONE, R.string.subtitles_edge_none),
	OUTLINE(CaptionStyleCompat.EDGE_TYPE_OUTLINE, R.string.subtitles_edge_outline),
	DROP_SHADOW(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, R.string.subtitles_edge_shadow),
	RAISED(CaptionStyleCompat.EDGE_TYPE_RAISED, R.string.subtitles_edge_raised),
	DEPRESSED(CaptionStyleCompat.EDGE_TYPE_DEPRESSED, R.string.subtitles_edge_depressed);

	companion object {
		fun of(value: Int) = entries.firstOrNull { it.value == value } ?: AUTO
	}
}

/**
 * A complete look in one press. The size and the position stay as the user set them.
 */
data class SubtitlePreset(
	@StringRes val label: Int,
	@StringRes val description: Int,
	val textColor: Long,
	val backgroundColor: Long,
	val strokeColor: Long,
	val edge: SubtitleEdge,
	val weight: Int,
	val font: SubtitleFont = SubtitleFont.DEFAULT,
) {
	fun isApplied(preferences: UserPreferences) =
		preferences[UserPreferences.subtitlesTextColor] == textColor &&
			preferences[UserPreferences.subtitlesBackgroundColor] == backgroundColor &&
			preferences[UserPreferences.subtitleTextStrokeColor] == strokeColor &&
			preferences[UserPreferences.subtitlesEdgeType] == edge.value &&
			preferences[UserPreferences.subtitlesTextWeight] == weight &&
			preferences[UserPreferences.subtitlesFont] == font.ordinal

	fun applyTo(preferences: UserPreferences) {
		preferences[UserPreferences.subtitlesTextColor] = textColor
		preferences[UserPreferences.subtitlesBackgroundColor] = backgroundColor
		preferences[UserPreferences.subtitleTextStrokeColor] = strokeColor
		preferences[UserPreferences.subtitlesEdgeType] = edge.value
		preferences[UserPreferences.subtitlesTextWeight] = weight
		preferences[UserPreferences.subtitlesFont] = font.ordinal
	}
}

val SubtitlePresets = listOf(
	// White, medium weight, a soft shadow and no box, like on Netflix
	SubtitlePreset(R.string.subtitles_preset_streaming, R.string.subtitles_preset_streaming_description, 0xFFFFFFFF, 0x00FFFFFF, 0xE6000000, SubtitleEdge.DROP_SHADOW, 500),
	SubtitlePreset(R.string.subtitles_preset_classic, R.string.subtitles_preset_classic_description, 0xFFFFFFFF, 0x00FFFFFF, 0xFF000000, SubtitleEdge.OUTLINE, 400),
	SubtitlePreset(R.string.subtitles_preset_bold, R.string.subtitles_preset_bold_description, 0xFFFFFFFF, 0x00FFFFFF, 0xFF000000, SubtitleEdge.DROP_SHADOW, 700),
	SubtitlePreset(R.string.subtitles_preset_box, R.string.subtitles_preset_box_description, 0xFFFFFFFF, 0xBF000000, 0x00FFFFFF, SubtitleEdge.NONE, 500),
	SubtitlePreset(R.string.subtitles_preset_yellow, R.string.subtitles_preset_yellow_description, 0xFFFFE14D, 0x00FFFFFF, 0xFF000000, SubtitleEdge.OUTLINE, 600),
)

/** What the player and the previews draw */
object SubtitleStyles {
	fun edgeType(edge: Int, strokeColor: Int): Int = when {
		edge >= 0 -> edge
		Color.alpha(strokeColor) > 0 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
		else -> CaptionStyleCompat.EDGE_TYPE_NONE
	}

	fun typeface(context: Context, font: Int, weight: Int): Typeface =
		TypefaceCompat.create(context, Typeface.create(SubtitleFont.of(font).family, Typeface.NORMAL), weight, false)

	@JvmStatic
	@OptIn(UnstableApi::class)
	fun captionStyle(context: Context, preferences: UserPreferences): CaptionStyleCompat {
		val strokeColor = preferences[UserPreferences.subtitleTextStrokeColor].toInt()
		return CaptionStyleCompat(
			preferences[UserPreferences.subtitlesTextColor].toInt(),
			preferences[UserPreferences.subtitlesBackgroundColor].toInt(),
			Color.TRANSPARENT,
			edgeType(preferences[UserPreferences.subtitlesEdgeType], strokeColor),
			strokeColor,
			typeface(context, preferences[UserPreferences.subtitlesFont], preferences[UserPreferences.subtitlesTextWeight]),
		)
	}
}
