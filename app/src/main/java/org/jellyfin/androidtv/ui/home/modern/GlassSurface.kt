package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import org.koin.compose.koinInject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The image behind the floating navigation bar: a blurred copy of the hero backdrop together with
 * the area (in pixels, from the top left of the screen) where the hero draws it.
 */
@Immutable
data class GlassBackdrop(
	val url: String?,
	val width: Float,
	val height: Float,
)

/**
 * Frosted glass surface. Real backdrop blur needs Android 12, the Fire TV Stick 4K Max runs
 * Android 11, so the blurred hero image is drawn inside the shape at the exact position it has on
 * screen, which gives the same look.
 */
@Composable
internal fun Modifier.glassSurface(
	backdrop: GlassBackdrop?,
	shape: Shape,
): Modifier {
	val bitmap = rememberBackdropBitmap(backdrop?.url)
	var origin by remember { mutableStateOf(Offset.Zero) }

	return this
		.onGloballyPositioned { coordinates -> origin = coordinates.positionInRoot() }
		.clip(shape)
		.drawBehind {
			val image = bitmap
			if (image != null && backdrop != null && backdrop.width > 0f && backdrop.height > 0f) {
				// Same geometry as the hero: ContentScale.Crop aligned to the top center
				val scale = max(backdrop.width / image.width, backdrop.height / image.height)
				val drawWidth = image.width * scale
				val drawHeight = image.height * scale
				val left = (backdrop.width - drawWidth) / 2f - origin.x
				val top = -origin.y

				drawImage(
					image = image,
					dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
					dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
					filterQuality = FilterQuality.Low,
				)
			}

			drawRect(ModernHomeColors.GlassTint)
			drawRect(
				Brush.verticalGradient(
					0f to Color.White.copy(alpha = 0.12f),
					1f to Color.White.copy(alpha = 0.02f),
				)
			)
		}
		.border(1.dp, ModernHomeColors.GlassBorder, shape)
}

@Composable
private fun rememberBackdropBitmap(url: String?): ImageBitmap? {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()

	// The previous image stays visible while the next one loads
	val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
		if (url == null) {
			value = null
			return@produceState
		}

		val request = ImageRequest.Builder(context)
			.data(url)
			.size(Size.ORIGINAL)
			.allowHardware(false)
			.build()

		val result = imageLoader.execute(request)
		if (result is SuccessResult) value = result.image.toBitmap().asImageBitmap()
	}

	return bitmap
}
