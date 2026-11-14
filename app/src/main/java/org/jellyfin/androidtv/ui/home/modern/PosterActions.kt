package org.jellyfin.androidtv.ui.home.modern

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.details.modern.MediaInfoDialog
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

/**
 * Poster held with OK: what the menu is about.
 *
 * @param playsOnClick the card plays when clicked ("Continue watching"), so the menu offers the details page
 */
internal data class PosterMenuTarget(
	val item: BaseItemDto,
	val title: String,
	val subtitle: String?,
	val playsOnClick: Boolean,
)

internal fun HomeCard.menuTarget() = PosterMenuTarget(
	item = item,
	title = title,
	subtitle = subtitle,
	playsOnClick = playOnClick,
)

/**
 * State of the poster menu and of the dialogs it opens (file info, identify).
 */
@Stable
internal class PosterActionsState {
	var menu by mutableStateOf<PosterMenuTarget?>(null)
	var mediaInfoItem by mutableStateOf<BaseItemDto?>(null)
	var identifyItem by mutableStateOf<BaseItemDto?>(null)

	// Last values, so the dialogs keep their content while they fade out
	var lastMenu: PosterMenuTarget? = null
		private set
	var lastMediaInfoItem: BaseItemDto? = null
		private set
	var lastIdentifyItem: BaseItemDto? = null
		private set

	fun open(target: PosterMenuTarget) {
		lastMenu = target
		menu = target
	}

	fun showMediaInfo(item: BaseItemDto) {
		lastMediaInfoItem = item
		mediaInfoItem = item
	}

	fun showIdentify(item: BaseItemDto) {
		lastIdentifyItem = item
		identifyItem = item
	}
}

@Composable
internal fun rememberPosterActionsState() = remember { PosterActionsState() }

/**
 * Menu shown when holding OK on a poster, by type:
 * - Movies: Play now, Mark as watched, Mark as unwatched, Identify, File info
 * - Series: Play now, Mark as watched, Mark as unwatched, Go to show, Identify
 * - Episodes: Play now, Mark as watched, Mark as unwatched, Go to season, Go to show, Identify (the series), File info
 *
 * "Identify" only shows for administrators.
 */
@Composable
internal fun PosterActionsDialogs(
	state: PosterActionsState,
	onPlay: (BaseItemDto) -> Unit,
	onOpen: (UUID) -> Unit,
	onChanged: () -> Unit,
) {
	val actions = koinInject<ItemActions>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	fun runAction(name: String, block: suspend () -> Unit) {
		scope.launch {
			try {
				block()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Poster action failed: $name")
				Toast.makeText(context, R.string.modern_menu_failed, Toast.LENGTH_SHORT).show()
			}
		}
	}

	fun setPlayed(item: BaseItemDto, played: Boolean) = runAction("played") {
		actions.setPlayed(item.id, played)
		onChanged()
	}

	val target = state.lastMenu
	val item = target?.item
	val options = buildList {
		if (target == null || item == null) return@buildList

		add(ModernMenuOption(stringResource(R.string.modern_menu_play_now), R.drawable.ic_play) { onPlay(item) })
		add(ModernMenuOption(stringResource(R.string.modern_home_mark_watched), R.drawable.ic_watch) { setPlayed(item, true) })
		add(ModernMenuOption(stringResource(R.string.modern_menu_mark_unwatched), R.drawable.ic_unwatch) { setPlayed(item, false) })

		val seasonId = item.seasonId
		val seriesId = item.seriesId
		if (item.type == BaseItemKind.EPISODE && seasonId != null) {
			add(ModernMenuOption(stringResource(R.string.modern_home_go_to_season), R.drawable.ic_folder) { onOpen(seasonId) })
		}
		when {
			item.type == BaseItemKind.SERIES -> add(ModernMenuOption(stringResource(R.string.modern_home_go_to_show), R.drawable.ic_tv) { onOpen(item.id) })
			seriesId != null -> add(ModernMenuOption(stringResource(R.string.modern_home_go_to_show), R.drawable.ic_tv) { onOpen(seriesId) })
			// Cards that play when clicked: the details page is only reachable from here
			target.playsOnClick -> add(ModernMenuOption(stringResource(R.string.modern_home_more_info), R.drawable.ic_info) { onOpen(item.id) })
		}

		val identifyId = item.identifyTargetId
		if (identifyId != null && actions.canIdentify) {
			add(ModernMenuOption(stringResource(R.string.modern_menu_identify), R.drawable.ic_search) {
				runAction("identify") {
					val identifyItem = if (identifyId == item.id) item else actions.fullItem(identifyId)
					state.showIdentify(identifyItem)
				}
			})
		}

		if (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.EPISODE) {
			add(ModernMenuOption(stringResource(R.string.modern_menu_file_info), R.drawable.ic_info) {
				runAction("file info") { state.showMediaInfo(actions.fullItem(item.id)) }
			})
		}
	}

	ModernMenuDialog(
		visible = state.menu != null,
		title = target?.title.orEmpty(),
		subtitle = target?.subtitle,
		options = options,
		onDismissRequest = { state.menu = null },
	)

	MediaInfoDialog(
		visible = state.mediaInfoItem != null,
		item = state.lastMediaInfoItem,
		onDismissRequest = { state.mediaInfoItem = null },
	)

	IdentifyDialog(
		visible = state.identifyItem != null,
		item = state.lastIdentifyItem,
		onDismissRequest = { state.identifyItem = null },
		onIdentified = { name ->
			state.identifyItem = null
			Toast.makeText(context, context.getString(R.string.modern_identify_done, name), Toast.LENGTH_LONG).show()
			scope.launch {
				// The server refreshes the metadata in the background
				onChanged()
				delay(IDENTIFY_REFRESH_DELAY_MS)
				onChanged()
			}
		},
	)
}

private const val IDENTIFY_REFRESH_DELAY_MS = 6_000L
