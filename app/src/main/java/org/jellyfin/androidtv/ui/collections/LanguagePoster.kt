package org.jellyfin.androidtv.ui.collections

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jellyfin.androidtv.ui.home.modern.drawFlag
import java.io.ByteArrayOutputStream

/**
 * Poster made for a language collection: the flag of the language, its name and "Films" or
 * "TV Shows", on a dark background in the style of the app.
 */
object LanguagePoster {
	const val WIDTH = 1000
	const val HEIGHT = 1500

	private const val FLAG_WIDTH = 620f
	private const val FLAG_HEIGHT = 414f

	fun draw(flag: String?, title: String, subtitle: String): ImageBitmap {
		val image = ImageBitmap(WIDTH, HEIGHT)
		val size = Size(WIDTH.toFloat(), HEIGHT.toFloat())

		CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
			drawRect(
				Brush.verticalGradient(
					0f to Color(0xFF1C2A40),
					0.55f to Color(0xFF0D1422),
					1f to Color(0xFF05070C),
				)
			)
			// Soft light behind the flag
			drawCircle(
				brush = Brush.radialGradient(
					0f to Color(0x3341B6E6),
					1f to Color.Transparent,
					center = Offset(size.width / 2, 560f),
					radius = 620f,
				),
				radius = 620f,
				center = Offset(size.width / 2, 560f),
			)

			val left = (size.width - FLAG_WIDTH) / 2
			val top = 360f
			val flagShape = Path().apply {
				addRoundRect(RoundRect(left, top, left + FLAG_WIDTH, top + FLAG_HEIGHT, CornerRadius(28f)))
			}

			if (flag != null) {
				clipPath(flagShape) {
					inset(left, top, size.width - left - FLAG_WIDTH, size.height - top - FLAG_HEIGHT) {
						if (!drawFlag(flag)) drawRect(Color(0xFF2A3A55))
					}
				}
			} else {
				drawPath(flagShape, Color(0xFF2A3A55))
			}
			drawPath(flagShape, Color.White.copy(alpha = 0.35f), style = Stroke(4f))

			// Accent line above the text
			drawRect(Color(0xFF41B6E6), topLeft = Offset(size.width / 2 - 60f, 900f), size = Size(120f, 8f))
		}

		val bitmap = image.asAndroidBitmap()
		val canvas = android.graphics.Canvas(bitmap)

		val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt()
			textAlign = Paint.Align.CENTER
			typeface = typeface(800)
			textSize = fitTextSize(title, 128f, WIDTH - 140f)
		}
		canvas.drawText(title, WIDTH / 2f, 1060f, titlePaint)

		val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFF41B6E6.toInt()
			textAlign = Paint.Align.CENTER
			typeface = typeface(700)
			textSize = 58f
			letterSpacing = 0.25f
		}
		canvas.drawText(subtitle.uppercase(), WIDTH / 2f, 1170f, subtitlePaint)

		return image
	}

	/**
	 * Poster of a streaming service collection: a screen with a play sign (no brand logos), the
	 * name of the service and "TV Shows".
	 */
	fun drawService(title: String, subtitle: String): ImageBitmap {
		val image = ImageBitmap(WIDTH, HEIGHT)
		val size = Size(WIDTH.toFloat(), HEIGHT.toFloat())
		// Each service its own colour, always the same one
		val accent = SERVICE_COLORS[Math.floorMod(title.lowercase().hashCode(), SERVICE_COLORS.size)]

		CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
			drawRect(
				Brush.verticalGradient(
					0f to accent.copy(alpha = 0.55f).compositeOver(Color(0xFF0D1422)),
					0.55f to Color(0xFF0D1422),
					1f to Color(0xFF05070C),
				)
			)
			drawCircle(
				brush = Brush.radialGradient(
					0f to accent.copy(alpha = 0.35f),
					1f to Color.Transparent,
					center = Offset(size.width / 2, 560f),
					radius = 620f,
				),
				radius = 620f,
				center = Offset(size.width / 2, 560f),
			)

			// Screen with a play sign and its stand
			val screenWidth = 560f
			val screenHeight = 360f
			val left = (size.width - screenWidth) / 2
			val top = 340f
			drawRoundRect(
				color = Color.White.copy(alpha = 0.10f),
				topLeft = Offset(left, top),
				size = Size(screenWidth, screenHeight),
				cornerRadius = CornerRadius(36f),
			)
			drawRoundRect(
				color = Color.White.copy(alpha = 0.85f),
				topLeft = Offset(left, top),
				size = Size(screenWidth, screenHeight),
				cornerRadius = CornerRadius(36f),
				style = Stroke(14f),
			)
			val centerX = size.width / 2
			val centerY = top + screenHeight / 2
			val play = Path().apply {
				moveTo(centerX - 50f, centerY - 70f)
				lineTo(centerX + 80f, centerY)
				lineTo(centerX - 50f, centerY + 70f)
				close()
			}
			drawPath(play, Color.White)
			drawRoundRect(
				color = Color.White.copy(alpha = 0.85f),
				topLeft = Offset(centerX - 110f, top + screenHeight + 40f),
				size = Size(220f, 14f),
				cornerRadius = CornerRadius(7f),
			)

			drawRect(accent, topLeft = Offset(size.width / 2 - 60f, 900f), size = Size(120f, 8f))
		}

		val bitmap = image.asAndroidBitmap()
		val canvas = android.graphics.Canvas(bitmap)
		val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFFFFFFFF.toInt()
			textAlign = Paint.Align.CENTER
			typeface = typeface(800)
			textSize = fitTextSize(title, 128f, WIDTH - 140f)
		}
		canvas.drawText(title, WIDTH / 2f, 1060f, titlePaint)
		val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = accent.toArgb()
			textAlign = Paint.Align.CENTER
			typeface = typeface(700)
			textSize = 58f
			letterSpacing = 0.25f
		}
		canvas.drawText(subtitle.uppercase(), WIDTH / 2f, 1170f, subtitlePaint)

		return image
	}

	private val SERVICE_COLORS = listOf(
		Color(0xFF41B6E6),
		Color(0xFFE5484D),
		Color(0xFF8E6CEF),
		Color(0xFF2FBF71),
		Color(0xFFF2A93B),
		Color(0xFFEF6FB4),
		Color(0xFF3E7BFA),
		Color(0xFF16B3A6),
	)

	/** JPEG bytes of a poster for uploading it to the server */
	fun encode(image: ImageBitmap): ByteArray = ByteArrayOutputStream().use { stream ->
		image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 92, stream)
		stream.toByteArray()
	}

	private fun Paint.fitTextSize(text: String, maxSize: Float, maxWidth: Float): Float {
		textSize = maxSize
		val width = measureText(text)
		return if (width <= maxWidth) maxSize else maxSize * maxWidth / width
	}

	private fun typeface(weight: Int): Typeface =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, weight, false)
		else Typeface.create("sans-serif-black", Typeface.NORMAL)
}
