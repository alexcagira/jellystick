package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Duration of the vertical move between rows */
private const val ROW_SCROLL_MS = 320

/**
 * Moves rows of a vertical list into place with a short eased animation, like the streaming apps.
 *
 * `animateScrollToItem` measures the target while it scrolls, which looks jumpy when going from a
 * row to the next one. Here the distance is known before starting: the row is on screen (going
 * down), or it was on screen before and its size was remembered (going up).
 */
internal class SmoothListScroller(
	private val state: LazyListState,
) {
	private val sizes = HashMap<Int, Int>()

	suspend fun scrollToItem(index: Int) {
		rememberSizes()

		val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
		if (visible != null) {
			if (visible.offset != 0) state.animateScrollBy(visible.offset.toFloat(), tween<Float>(ROW_SCROLL_MS, easing = FastOutSlowInEasing))
			return
		}

		val distance = distanceAbove(index)
		if (distance == null) {
			state.animateScrollToItem(index)
			return
		}

		state.animateScrollBy(distance, tween<Float>(ROW_SCROLL_MS, easing = FastOutSlowInEasing))

		// A row changed size after a refresh: finish the move with the regular animation
		val arrived = state.firstVisibleItemIndex == index && state.firstVisibleItemScrollOffset == 0
		if (!arrived) state.animateScrollToItem(index)
	}

	private fun rememberSizes() {
		for (item in state.layoutInfo.visibleItemsInfo) sizes[item.index] = item.size
	}

	/** Distance to a row above the screen (negative), null when one of the rows was never measured */
	private fun distanceAbove(index: Int): Float? {
		val firstVisible = state.firstVisibleItemIndex
		if (index > firstVisible) return null

		// Above the screen: add up the rows in between
		var distance = state.firstVisibleItemScrollOffset.toFloat()
		for (row in index until firstVisible) distance += sizes[row] ?: return null
		return -distance
	}
}

@Composable
internal fun rememberSmoothListScroller(state: LazyListState): SmoothListScroller =
	remember(state) { SmoothListScroller(state) }
