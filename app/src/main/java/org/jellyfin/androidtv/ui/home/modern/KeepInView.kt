// LocalBringIntoViewSpec is still experimental in the Compose version used by the app
@file:OptIn(ExperimentalFoundationApi::class)

package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical lists that only scroll when the focused element would leave the screen.
 *
 * On TVs Compose keeps the focused element at a third of the screen by default, which pushed the
 * top of the album and search pages (cover, search box) out of the screen as soon as they opened.
 */
@Composable
internal fun ProvideKeepInViewScrolling(
	margin: Dp = 48.dp,
	content: @Composable () -> Unit,
) {
	val density = LocalDensity.current
	val spec = remember(density, margin) { KeepInViewBringIntoViewSpec(marginPx = with(density) { margin.toPx() }) }
	CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
