package org.jellyfin.androidtv.ui.music.share

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.ModernDialog
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.koin.compose.koinInject

/**
 * Turn the shared queue on and off. While it is on, a QR code takes the phones of everyone on the
 * Wi-Fi to the page where they add songs.
 */
@Composable
fun SharedQueueDialog(
	visible: Boolean,
	onDismissRequest: () -> Unit,
) {
	val server = koinInject<SharedQueueServer>()
	val state by server.state.collectAsState()

	ModernDialog(
		visible = visible,
		title = stringResource(R.string.shared_queue_title),
		onDismissRequest = onDismissRequest,
		maxWidth = 760.dp,
	) {
		val url = state.url
		when {
			!state.running -> {
				Text(
					text = stringResource(R.string.shared_queue_description),
					color = ModernHomeColors.TextSecondary,
					fontSize = 15.sp,
				)
				Spacer(Modifier.height(20.dp))
				HeroButton(
					text = stringResource(R.string.shared_queue_start),
					icon = R.drawable.ic_users,
					primary = true,
					onClick = server::start,
				)
			}

			url == null -> {
				Text(
					text = stringResource(R.string.shared_queue_no_network),
					color = ModernHomeColors.TextSecondary,
					fontSize = 15.sp,
				)
				Spacer(Modifier.height(20.dp))
				HeroButton(text = stringResource(R.string.shared_queue_stop), icon = null, primary = false, onClick = server::stop)
			}

			else -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
				val qr = remember(url) { qrCode(url, QR_SIZE_PX) }
				Box(
					modifier = Modifier
						.size(230.dp)
						.background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp))
						.padding(12.dp),
				) {
					Image(bitmap = qr, contentDescription = url, filterQuality = FilterQuality.None, modifier = Modifier.size(206.dp))
				}

				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.shared_queue_scan),
						color = ModernHomeColors.TextPrimary,
						fontSize = 17.sp,
						fontWeight = FontWeight.SemiBold,
					)
					Spacer(Modifier.height(6.dp))
					Text(
						text = url,
						color = ModernHomeColors.Accent,
						fontSize = 14.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(6.dp))
					Text(
						text = stringResource(R.string.shared_queue_links),
						color = ModernHomeColors.TextSecondary,
						fontSize = 13.sp,
					)
					Spacer(Modifier.height(4.dp))
					Text(
						text = stringResource(R.string.shared_queue_troubleshoot),
						color = ModernHomeColors.TextMuted,
						fontSize = 12.sp,
					)
					Spacer(Modifier.height(16.dp))

					Text(
						text = stringResource(R.string.shared_queue_added).uppercase(),
						color = ModernHomeColors.TextMuted,
						fontSize = 11.sp,
						fontWeight = FontWeight.Bold,
						letterSpacing = 1.sp,
					)
					Spacer(Modifier.height(6.dp))
					if (state.guestSongs.isEmpty()) {
						Text(text = stringResource(R.string.shared_queue_nobody), color = ModernHomeColors.TextSecondary, fontSize = 13.sp)
					} else {
						for (song in state.guestSongs.take(VISIBLE_SONGS)) {
							Text(
								text = stringResource(R.string.shared_queue_song, song.name, song.addedBy),
								color = ModernHomeColors.TextSecondary,
								fontSize = 13.sp,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
						if (state.guestSongs.size > VISIBLE_SONGS) {
							Text(text = "+${state.guestSongs.size - VISIBLE_SONGS}", color = ModernHomeColors.TextMuted, fontSize = 13.sp)
						}
					}

					Spacer(Modifier.height(18.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
						HeroButton(text = stringResource(R.string.shared_queue_done), icon = null, primary = true, onClick = onDismissRequest)
						HeroButton(text = stringResource(R.string.shared_queue_stop), icon = null, primary = false, onClick = server::stop)
					}
				}
			}
		}
	}
}

/** QR code as a black on white image */
private fun qrCode(text: String, size: Int): ImageBitmap {
	val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
	val pixels = IntArray(matrix.width * matrix.height) { index ->
		if (matrix.get(index % matrix.width, index / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
	}
	return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val QR_SIZE_PX = 512
private const val VISIBLE_SONGS = 5
