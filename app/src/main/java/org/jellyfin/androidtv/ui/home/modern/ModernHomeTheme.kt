// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R

/**
 * Colors and sizes of the modern home screen. Tweak these to change the look of the whole screen.
 */
internal object ModernHomeColors {
	val Background = Color(0xFF06080D)
	/** Darkening on top of the blurred ambient backdrop (top, middle, bottom of the screen) */
	val AmbientScrimTop = Color(0x52000000)
	val AmbientScrimMiddle = Color(0x8C000000)
	val AmbientScrimBottom = Color(0xC7000000)
	val Accent = Color(0xFF41B6E6)
	val TextPrimary = Color(0xFFFFFFFF)
	val TextSecondary = Color(0xFFC7CEDA)
	val TextMuted = Color(0xFF8C97A8)
	val CardSurface = Color(0xFF1A2436)
	val NewLabel = Color(0xFFE11D2A)
	val BadgeBackground = Color(0xB3000000)
	val BadgeBorder = Color(0x99FFFFFF)
	val GlassTint = Color(0x730C1018)
	val GlassBorder = Color(0x2EFFFFFF)
	val PillActive = Color(0x2EFFFFFF)
	val FocusRing = Color(0xFFFFFFFF)
	val RankOutline = Color(0x8CB5C7DE)
	val ProgressTrack = Color(0x4DFFFFFF)
	val RatingStar = Color(0xFFF5C518)
	val FocusScrim = Color(0x99000000)
}

internal object ModernHomeDimens {
	/** Horizontal safe area for TVs (same as the rest of the app) */
	val ScreenPaddingHorizontal = 48.dp
	val ScreenPaddingTop = 18.dp

	val PosterWidth = 118.dp
	val PosterHeight = 177.dp
	val LandscapeWidth = 240.dp
	val LandscapeHeight = 135.dp
	val CardSpacing = 14.dp
	val CardCornerRadius = 8.dp

	val RankNumberWidth = 76.dp
	val RankNumberWidthWide = 150.dp
	val RankNumberOverlap = 20.dp

	val SectionSpacing = 8.dp
	val FocusScale = 1.07f
	val FocusBorderWidth = 2.5.dp
}

/** Condensed display font used for section titles and rank numbers (SIL Open Font License) */
internal val BebasNeue = FontFamily(Font(R.font.bebas_neue))

/**
 * Disables automatic vertical scrolling when focus moves. The home screen scrolls rows into place
 * itself (see ModernHomeScreen) so the focused row always lands at the same position.
 */
internal object NoAutoScrollBringIntoViewSpec : BringIntoViewSpec {
	override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Keeps the focused card inside the visible area with a margin at both edges, scrolling as little
 * as possible (less motion is easier to follow on a TV).
 */
internal class KeepInViewBringIntoViewSpec(
	private val marginPx: Float,
) : BringIntoViewSpec {
	override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
		val leadingEdge = offset
		val trailingEdge = offset + size
		val start = marginPx
		val end = containerSize - marginPx

		return when {
			// Item bigger than the visible area: align to start
			size > end - start -> leadingEdge - start
			leadingEdge < start -> leadingEdge - start
			trailingEdge > end -> trailingEdge - end
			else -> 0f
		}
	}
}
