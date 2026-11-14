package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.Text
import kotlin.math.cos
import kotlin.math.sin

private val FlagWidth = 18.dp
private val FlagHeight = 12.dp
private val flagShape = RoundedCornerShape(2.dp)

/**
 * Audio language flags shown in the bottom left corner of posters.
 */
@Composable
internal fun LanguageFlags(
	languages: List<LanguageBadge>,
	modifier: Modifier = Modifier,
) {
	if (languages.isEmpty()) return

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (language in languages) LanguageFlag(language)
	}
}

@Composable
internal fun LanguageFlag(
	language: LanguageBadge,
	modifier: Modifier = Modifier,
) {
	val painter = language.flag?.let { flagPainters[it] }

	if (painter != null) {
		Box(
			modifier = modifier
				.size(FlagWidth, FlagHeight)
				.clip(flagShape)
				.drawBehind(painter)
				.border(0.5.dp, Color.Black.copy(alpha = 0.35f), flagShape),
		)
	} else {
		// No flag for this language: show its code instead ("ARA")
		Box(
			modifier = modifier
				.height(FlagHeight)
				.defaultMinSize(minWidth = FlagWidth)
				.background(ModernHomeColors.BadgeBackground, flagShape)
				.border(0.5.dp, ModernHomeColors.BadgeBorder, flagShape)
				.padding(horizontal = 2.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = language.label,
				color = ModernHomeColors.TextPrimary,
				fontSize = 7.sp,
				lineHeight = 8.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
			)
		}
	}
}

// Flags, simplified so they stay recognizable at a few dp

private val flagPainters: Map<String, DrawScope.() -> Unit> = mapOf(
	"gb" to flag { unionJack() },
	"es" to flag { weightedHorizontalStripes(listOf(Color(0xFFAA151B) to 1f, Color(0xFFF1BF00) to 2f, Color(0xFFAA151B) to 1f)) },
	"mx" to flag {
		verticalStripes(listOf(Color(0xFF006847), Color.White, Color(0xFFCE1126)))
		drawCircle(color = Color(0xFF8C6A2F), radius = size.height * 0.16f, center = center)
	},
	"jp" to flag {
		drawRect(Color.White)
		drawCircle(color = Color(0xFFBC002D), radius = size.height * 0.3f, center = center)
	},
	"fr" to flag { verticalStripes(listOf(Color(0xFF002654), Color.White, Color(0xFFED2939))) },
	"de" to flag { horizontalStripes(listOf(Color.Black, Color(0xFFDD0000), Color(0xFFFFCE00))) },
	"it" to flag { verticalStripes(listOf(Color(0xFF009246), Color.White, Color(0xFFCE2B37))) },
	"pt" to flag {
		drawRect(Color(0xFFFF0000))
		drawRect(Color(0xFF006600), size = Size(size.width * 0.4f, size.height))
		drawCircle(color = Color(0xFFFFE000), radius = size.height * 0.2f, center = Offset(size.width * 0.4f, size.height / 2))
	},
	"br" to flag {
		drawRect(Color(0xFF009C3B))
		polygon(
			Color(0xFFFFDF00),
			listOf(
				Offset(size.width * 0.5f, size.height * 0.12f),
				Offset(size.width * 0.92f, size.height * 0.5f),
				Offset(size.width * 0.5f, size.height * 0.88f),
				Offset(size.width * 0.08f, size.height * 0.5f),
			),
		)
		drawCircle(color = Color(0xFF002776), radius = size.height * 0.22f, center = center)
	},
	"ru" to flag { horizontalStripes(listOf(Color.White, Color(0xFF0039A6), Color(0xFFD52B1E))) },
	"cn" to flag {
		drawRect(Color(0xFFEE1C25))
		val yellow = Color(0xFFFFDE00)
		star(Offset(size.width * 0.17f, size.height * 0.27f), size.height * 0.16f, yellow)
		star(Offset(size.width * 0.33f, size.height * 0.1f), size.height * 0.055f, yellow)
		star(Offset(size.width * 0.4f, size.height * 0.2f), size.height * 0.055f, yellow)
		star(Offset(size.width * 0.4f, size.height * 0.35f), size.height * 0.055f, yellow)
		star(Offset(size.width * 0.33f, size.height * 0.45f), size.height * 0.055f, yellow)
	},
	"kr" to flag {
		drawRect(Color.White)
		val radius = size.height * 0.25f
		val topLeft = Offset(center.x - radius, center.y - radius)
		drawArc(color = Color(0xFFCD2E3A), startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = topLeft, size = Size(radius * 2, radius * 2))
		drawArc(color = Color(0xFF0047A0), startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = topLeft, size = Size(radius * 2, radius * 2))
	},
	"in" to flag {
		horizontalStripes(listOf(Color(0xFFFF9933), Color.White, Color(0xFF138808)))
		drawCircle(color = Color(0xFF000080), radius = size.height * 0.12f, center = center, style = Stroke(width = size.height * 0.04f))
	},
	"nl" to flag { horizontalStripes(listOf(Color(0xFFAE1C28), Color.White, Color(0xFF21468B))) },
	"se" to flag { nordicCross(Color(0xFF006AA7), Color(0xFFFECC00)) },
	"no" to flag { nordicCross(Color(0xFFBA0C2F), Color.White, Color(0xFF00205B)) },
	"dk" to flag { nordicCross(Color(0xFFC8102E), Color.White) },
	"fi" to flag { nordicCross(Color.White, Color(0xFF002F6C)) },
	"is" to flag { nordicCross(Color(0xFF02529C), Color.White, Color(0xFFDC1E35)) },
	"pl" to flag { horizontalStripes(listOf(Color.White, Color(0xFFDC143C))) },
	"tr" to flag {
		drawRect(Color(0xFFE30A17))
		drawCircle(color = Color.White, radius = size.height * 0.25f, center = Offset(size.width * 0.36f, size.height / 2))
		drawCircle(color = Color(0xFFE30A17), radius = size.height * 0.2f, center = Offset(size.width * 0.41f, size.height / 2))
		star(Offset(size.width * 0.58f, size.height / 2), size.height * 0.12f, Color.White)
	},
	"gr" to flag {
		val blue = Color(0xFF0D5EAF)
		weightedHorizontalStripes(List(9) { index -> (if (index % 2 == 0) blue else Color.White) to 1f })
		val canton = size.height * 5f / 9f
		drawRect(blue, size = Size(canton, canton))
		val bar = size.height / 9f
		drawRect(Color.White, topLeft = Offset(0f, (canton - bar) / 2), size = Size(canton, bar))
		drawRect(Color.White, topLeft = Offset((canton - bar) / 2, 0f), size = Size(bar, canton))
	},
	"cat" to flag {
		val red = Color(0xFFDA121A)
		val yellow = Color(0xFFFCDD09)
		weightedHorizontalStripes(List(9) { index -> (if (index % 2 == 0) yellow else red) to 1f })
	},
	"eus" to flag {
		drawRect(Color(0xFFD52B1E))
		val green = Color(0xFF009B48)
		val stroke = size.height * 0.14f
		drawLine(green, Offset.Zero, Offset(size.width, size.height), strokeWidth = stroke)
		drawLine(green, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = stroke)
		drawRect(Color.White, topLeft = Offset(center.x - stroke / 2, 0f), size = Size(stroke, size.height))
		drawRect(Color.White, topLeft = Offset(0f, center.y - stroke / 2), size = Size(size.width, stroke))
	},
	"gal" to flag {
		drawRect(Color.White)
		drawLine(Color(0xFF0099CC), Offset.Zero, Offset(size.width, size.height), strokeWidth = size.height * 0.25f)
	},
	"ua" to flag { horizontalStripes(listOf(Color(0xFF0057B7), Color(0xFFFFD700))) },
	"cz" to flag {
		horizontalStripes(listOf(Color.White, Color(0xFFD7141A)))
		polygon(Color(0xFF11457E), listOf(Offset.Zero, Offset(size.width * 0.5f, size.height / 2), Offset(0f, size.height)))
	},
	"hu" to flag { horizontalStripes(listOf(Color(0xFFCE2939), Color.White, Color(0xFF477050))) },
	"ro" to flag { verticalStripes(listOf(Color(0xFF002B7F), Color(0xFFFCD116), Color(0xFFCE1126))) },
	"th" to flag {
		val red = Color(0xFFA51931)
		weightedHorizontalStripes(listOf(red to 1f, Color.White to 1f, Color(0xFF2D2A4A) to 2f, Color.White to 1f, red to 1f))
	},
	"vn" to flag {
		drawRect(Color(0xFFDA251D))
		star(center, size.height * 0.3f, Color(0xFFFFFF00))
	},
	"id" to flag { horizontalStripes(listOf(Color(0xFFFF0000), Color.White)) },
)

/** Lets the map infer the receiver of the drawing lambdas */
private fun flag(painter: DrawScope.() -> Unit): DrawScope.() -> Unit = painter

// Colors and offsets are value classes, which can't be used as vararg: lists are used instead

private fun DrawScope.horizontalStripes(colors: List<Color>) =
	weightedHorizontalStripes(colors.map { it to 1f })

private fun DrawScope.weightedHorizontalStripes(stripes: List<Pair<Color, Float>>) {
	val total = stripes.sumOf { it.second.toDouble() }.toFloat()
	var top = 0f
	for ((color, weight) in stripes) {
		val height = size.height * weight / total
		// Slight overlap so no hairline gap shows between stripes
		drawRect(color, topLeft = Offset(0f, top), size = Size(size.width, height + 0.5f))
		top += height
	}
}

private fun DrawScope.verticalStripes(colors: List<Color>) {
	val width = size.width / colors.size
	colors.forEachIndexed { index, color ->
		drawRect(color, topLeft = Offset(width * index, 0f), size = Size(width + 0.5f, size.height))
	}
}

/** Scandinavian cross, shifted towards the hoist */
private fun DrawScope.nordicCross(background: Color, cross: Color, inner: Color? = null) {
	drawRect(background)
	val crossX = size.width * 0.36f
	val bar = size.height * 0.24f
	drawRect(cross, topLeft = Offset(crossX - bar / 2, 0f), size = Size(bar, size.height))
	drawRect(cross, topLeft = Offset(0f, center.y - bar / 2), size = Size(size.width, bar))

	if (inner != null) {
		val innerBar = bar * 0.5f
		drawRect(inner, topLeft = Offset(crossX - innerBar / 2, 0f), size = Size(innerBar, size.height))
		drawRect(inner, topLeft = Offset(0f, center.y - innerBar / 2), size = Size(size.width, innerBar))
	}
}

private fun DrawScope.unionJack() {
	val red = Color(0xFFC8102E)
	drawRect(Color(0xFF012169))

	val diagonal = size.height * 0.2f
	drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), strokeWidth = diagonal)
	drawLine(Color.White, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = diagonal)
	drawLine(red, Offset.Zero, Offset(size.width, size.height), strokeWidth = diagonal / 3)
	drawLine(red, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = diagonal / 3)

	val cross = size.height / 3
	drawRect(Color.White, topLeft = Offset(center.x - cross / 2, 0f), size = Size(cross, size.height))
	drawRect(Color.White, topLeft = Offset(0f, center.y - cross / 2), size = Size(size.width, cross))

	val redCross = size.height / 5
	drawRect(red, topLeft = Offset(center.x - redCross / 2, 0f), size = Size(redCross, size.height))
	drawRect(red, topLeft = Offset(0f, center.y - redCross / 2), size = Size(size.width, redCross))
}

private fun DrawScope.polygon(color: Color, points: List<Offset>) {
	val path = Path()
	points.forEachIndexed { index, point ->
		if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
	}
	path.close()
	drawPath(path = path, color = color)
}

/** Five-pointed star pointing up */
private fun DrawScope.star(center: Offset, radius: Float, color: Color) {
	val points = List(10) { index ->
		val pointRadius = if (index % 2 == 0) radius else radius * 0.382f
		val angle = Math.toRadians(-90.0 + index * 36.0)
		Offset(
			x = center.x + (pointRadius * cos(angle)).toFloat(),
			y = center.y + (pointRadius * sin(angle)).toFloat(),
		)
	}
	polygon(color, points)
}
