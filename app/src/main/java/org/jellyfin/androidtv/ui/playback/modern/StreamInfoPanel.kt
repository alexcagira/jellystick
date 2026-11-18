package org.jellyfin.androidtv.ui.playback.modern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodingInfo
import org.koin.compose.koinInject
import timber.log.Timber

/** The panel reads the player again this often */
private const val REFRESH_MS = 1_000L

/** What the server is doing (transcoding progress) is asked this often */
private const val SESSION_REFRESH_MS = 3_000L

private val PanelShape = RoundedCornerShape(22.dp)
private val PanelTop = Color(0xC70B0F16)
private val PanelBottom = Color(0x9E0B0F16)
private val Warning = Color(0xFFFFB347)

/**
 * Stream information (☰ in the player): a translucent glass panel on the right with how the file
 * plays and why, updated every second while it shows. It doesn't take the focus, the remote keeps
 * controlling the player under it.
 */
@Composable
internal fun StreamInfoPanel(
	overlay: ModernPlayerOverlay,
	visible: Boolean,
	modifier: Modifier = Modifier,
) {
	val api = koinInject<ApiClient>()
	var sections by remember { mutableStateOf(emptyList<StreamInfoSection>()) }
	var transcoding by remember { mutableStateOf<TranscodingInfo?>(null) }

	// The player, every second
	LaunchedEffect(visible) {
		while (visible) {
			sections = overlay.streamInfo(transcoding)
			delay(REFRESH_MS)
		}
	}

	// The server's session, only when it is working on the stream (not on direct play)
	LaunchedEffect(visible) {
		transcoding = null
		while (visible) {
			if (overlay.playMethod() != PlayMethod.DIRECT_PLAY) {
				transcoding = fetchTranscoding(api, overlay)
				sections = overlay.streamInfo(transcoding)
			}
			delay(SESSION_REFRESH_MS)
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		AnimatedVisibility(
			visible = visible,
			enter = fadeIn() + slideInHorizontally { width -> width / 6 },
			exit = fadeOut() + slideOutHorizontally { width -> width / 6 },
			modifier = Modifier.align(Alignment.CenterEnd),
		) {
			Column(
				modifier = Modifier
					.padding(vertical = 28.dp, horizontal = 32.dp)
					.width(430.dp)
					.fillMaxHeight()
					.clip(PanelShape)
					.background(Brush.verticalGradient(listOf(PanelTop, PanelBottom)))
					.border(1.dp, ModernHomeColors.GlassBorder, PanelShape)
					.padding(horizontal = 22.dp, vertical = 18.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Text(
					text = stringResource(R.string.stream_info_title),
					color = ModernHomeColors.TextPrimary,
					fontSize = 17.sp,
					fontWeight = FontWeight.Bold,
				)
				overlay.state.item?.let { item ->
					Text(
						text = playerTitle(item),
						color = ModernHomeColors.TextSecondary,
						fontSize = 12.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				for (section in sections) StreamInfoBlock(section)

				Spacer(Modifier.weight(1f))
				Text(
					text = stringResource(R.string.stream_info_close_hint),
					color = ModernHomeColors.TextMuted,
					fontSize = 11.sp,
				)
			}
		}
	}
}

@Composable
private fun StreamInfoBlock(section: StreamInfoSection) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Text(
			text = section.title.uppercase(),
			color = ModernHomeColors.Accent,
			fontSize = 10.5.sp,
			fontWeight = FontWeight.Bold,
			letterSpacing = 1.sp,
		)
		Spacer(Modifier.height(3.dp))
		for (row in section.rows) {
			Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
				Text(
					text = row.label,
					color = ModernHomeColors.TextMuted,
					fontSize = 12.sp,
					maxLines = 1,
					modifier = Modifier.width(104.dp),
				)
				Text(
					text = row.value,
					color = if (row.warning) Warning else ModernHomeColors.TextPrimary,
					fontSize = 12.sp,
					fontWeight = FontWeight.Medium,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

/** What the server says about the stream of this device (null when it can't tell) */
private suspend fun fetchTranscoding(api: ApiClient, overlay: ModernPlayerOverlay): TranscodingInfo? = try {
	withContext(Dispatchers.IO) {
		val sessions = api.sessionApi.getSessions(deviceId = api.deviceInfo.id).content
		val itemId = overlay.state.item?.id
		(sessions.firstOrNull { it.nowPlayingItem?.id == itemId } ?: sessions.firstOrNull())?.transcodingInfo
	}
} catch (error: CancellationException) {
	throw error
} catch (error: Exception) {
	Timber.w(error, "Unable to read the session of the stream")
	null
}
