package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Last focused child of a row or bar: the D-pad moving into the row goes back to it, like
 * focusRestorer().
 *
 * focusRestorer() doesn't work on rows whose focus is put back by code when a page shows again: it
 * also sends every requestFocus() on one of its children to the child it saved, and keeps that one
 * with the saved state of the page. Coming back from Stations the focus always landed on Genres.
 * Here a requestFocus() goes where it was asked, and nothing outlives the page.
 */
@Stable
internal class FocusMemory {
	private val requesters = HashMap<Any, FocusRequester>()
	private var last: Any? = null

	/** Modifier of a child, in front of its clickable */
	fun child(key: Any): Modifier = Modifier
		.focusRequester(requesters.getOrPut(key) { FocusRequester() })
		.onFocusChanged { if (it.isFocused) last = key }

	/** Modifier of the row, instead of focusRestorer().focusGroup() */
	val group: Modifier = Modifier
		.focusProperties {
			onEnter = {
				// Only D-pad moves: a requestFocus() comes in with Enter. A child that isn't there
				// anymore (scrolled out) leaves the focus to the closest one.
				if (requestedFocusDirection != FocusDirection.Enter) {
					last?.let { requesters[it] }?.tryRequestFocus()
				}
			}
		}
		.focusGroup()
}

@Composable
internal fun rememberFocusMemory(): FocusMemory = remember { FocusMemory() }
