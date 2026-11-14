package org.jellyfin.androidtv.ui.music.modern

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.EditableField
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.ModernDialog
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

/**
 * "Add to playlist": the music playlists of the user, and a new one.
 */
@Composable
fun AddToPlaylistDialog(
	visible: Boolean,
	itemIds: List<UUID>,
	onDismissRequest: () -> Unit,
) {
	val repository = koinInject<MusicItemRepository>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var playlists by remember(visible) { mutableStateOf<List<BaseItemDto>?>(null) }
	var newName by remember(visible) { mutableStateOf("") }
	var working by remember(visible) { mutableStateOf(false) }

	LaunchedEffect(visible) {
		if (!visible) return@LaunchedEffect
		playlists = runCatching { repository.playlists() }
			.onFailure { Timber.w(it, "Unable to load the playlists") }
			.getOrDefault(emptyList())
	}

	fun run(message: String, block: suspend () -> Unit) {
		if (working || itemIds.isEmpty()) return
		working = true
		scope.launch {
			val text = try {
				block()
				message
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to change the playlist")
				context.getString(R.string.modern_menu_failed)
			}
			working = false
			Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
			onDismissRequest()
		}
	}

	ModernDialog(
		visible = visible,
		title = stringResource(R.string.music_add_to_playlist),
		onDismissRequest = onDismissRequest,
		maxWidth = 560.dp,
	) {
		Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			EditableField(
				label = stringResource(R.string.music_new_playlist),
				value = newName,
				onValueChange = { newName = it },
				onSubmit = {},
				modifier = Modifier.weight(1f),
			)
			HeroButton(
				text = stringResource(R.string.music_create),
				icon = R.drawable.ic_add,
				primary = false,
				onClick = {
					val name = newName.trim()
					if (name.isNotEmpty()) {
						run(context.getString(R.string.music_added_to_playlist, name)) { repository.createPlaylist(name, itemIds) }
					}
				},
			)
		}
		Spacer(Modifier.height(16.dp))

		val list = playlists
		when {
			list == null || working -> CircularProgressIndicator(color = ModernHomeColors.Accent, modifier = Modifier.size(32.dp))
			list.isEmpty() -> Text(stringResource(R.string.music_no_playlists), color = ModernHomeColors.TextMuted, fontSize = 14.sp)
			else -> LazyColumn(
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier.heightIn(max = 360.dp),
			) {
				items(list, key = { it.id.toString() }) { playlist ->
					PlaylistRow(
						name = playlist.name.orEmpty(),
						count = playlist.childCount,
						onClick = {
							run(context.getString(R.string.music_added_to_playlist, playlist.name.orEmpty())) {
								repository.addToPlaylist(playlist.id, itemIds)
							}
						},
					)
				}
			}
		}
	}
}

@Composable
private fun PlaylistRow(
	name: String,
	count: Int?,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(12.dp)
	val color = if (focused) Color.Black else ModernHomeColors.TextPrimary

	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), shape)
			.padding(horizontal = 18.dp, vertical = 12.dp),
	) {
		Icon(ImageVector.vectorResource(R.drawable.ic_grid), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
		Spacer(Modifier.width(14.dp))
		Text(
			text = name,
			color = color,
			fontSize = 15.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		if (count != null) Text(text = "$count", color = if (focused) Color.Black.copy(alpha = 0.6f) else ModernHomeColors.TextMuted, fontSize = 13.sp)
	}
}

/** "3:45" */
internal fun formatDuration(ticks: Long?): String? {
	val seconds = (ticks ?: return null) / 10_000_000
	if (seconds <= 0) return null
	val hours = seconds / 3600
	val minutes = (seconds % 3600) / 60
	val rest = seconds % 60
	return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
