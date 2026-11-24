package org.jellyfin.androidtv.ui.playback.anime4k

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Anime4K (https://github.com/bloc97/Anime4K, MIT) in the player: its shaders are mpv "user
 * shaders" (the files in assets/anime4k, as published). This file reads them and turns each pass
 * into an OpenGL ES 3.0 fragment shader; [Anime4KRenderer] runs the passes on every frame.
 *
 * No Android here: the same code is checked on a computer (the passes compiled and run in WebGL 2).
 */

/** The modes of the player menu, from the lightest */
enum class Anime4KMode(
	/** Shader files of assets/anime4k, in the order mpv would get them */
	val shaders: List<String>,
	/** Highest video the mode is for: above it the lighter [fallback] runs instead */
	val maxSourceHeight: Int = Int.MAX_VALUE,
) {
	OFF(emptyList()),

	/** Sharper lines and, for SD and 720p, an upscale that keeps them sharp (Difference of Gaussians) */
	SHARP(
		listOf(
			"Anime4K_Deblur_DoG.glsl",
			"Anime4K_Upscale_DoG_x2.glsl",
		)
	),

	/** [SHARP] with darker, thinner line art (before the upscale: a quarter of the pixels to work on) */
	SHARP_LINES(
		listOf(
			"Anime4K_Deblur_DoG.glsl",
			"Anime4K_Darken_HQ.glsl",
			"Anime4K_Thin_HQ.glsl",
			"Anime4K_Upscale_DoG_x2.glsl",
		)
	),

	/** The neural network ones (restore and upscale, small models): only light enough for SD and 720p */
	CNN(
		listOf(
			"Anime4K_Clamp_Highlights.glsl",
			"Anime4K_Restore_CNN_S.glsl",
			"Anime4K_Upscale_CNN_x2_S.glsl",
		),
		maxSourceHeight = 720,
	);

	/** The next lighter mode (too slow on this TV, or a video too big for the mode) */
	val fallback: Anime4KMode
		get() = when (this) {
			CNN -> SHARP_LINES
			SHARP_LINES -> SHARP
			SHARP, OFF -> OFF
		}

	/** The mode that runs for a video of [height] lines */
	fun forSourceHeight(height: Int): Anime4KMode {
		var mode = this
		while (mode != OFF && height > mode.maxSourceHeight) mode = mode.fallback
		return mode
	}

	companion object {
		fun of(name: String?): Anime4KMode = entries.firstOrNull { it.name == name } ?: OFF
	}
}

/** One pass of an mpv user shader */
class HookPass(
	val description: String,
	/** Where mpv runs it: MAIN, or PREKERNEL (after all the MAIN ones) */
	val hook: String,
	/** Textures it reads ("HOOKED" is the hooked one, MAIN here) */
	val binds: List<String>,
	/** Texture it writes, the hooked one (MAIN) when null */
	val save: String?,
	/** Size of what it writes, mpv's reverse Polish expressions ("MAIN.w 2 *"), the hooked size when null */
	val width: String?,
	val height: String?,
	/** Runs only when this expression isn't 0 ("OUTPUT.w MAIN.w / 1.200 >") */
	val condition: String?,
	/** The GLSL of the pass, with its `vec4 hook()` */
	val body: String,
) {
	/** mpv runs the MAIN hooks first, PREKERNEL ones just before scaling to the screen */
	val stage: Int get() = if (hook == HOOK_PREKERNEL) 1 else 0

	/** Texture the pass writes */
	val target: String get() = save ?: MAIN

	/** Distinct textures it reads, each with its own sampler (HOOKED included) */
	val samplers: List<String> get() = binds.distinct()

	/**
	 * The pass as a GLSL ES 3.00 fragment shader. For every texture NAME it reads, the names mpv
	 * gives: NAME_raw (the sampler), NAME_size, NAME_pt (one texel), NAME_pos (where this pixel is
	 * in it), NAME_tex(pos) and NAME_texOff(offset in texels). HOOKED and the hooked texture (MAIN)
	 * are the same: either name works when one of them is bound.
	 */
	fun fragmentShader(): String = buildString {
		appendLine("#version 300 es")
		appendLine("precision highp float;")
		appendLine("precision highp int;")
		appendLine("precision highp sampler2D;")
		appendLine("in vec2 v_pos;")
		appendLine("out vec4 frag_color;")
		for (sampler in samplers) {
			appendLine("uniform sampler2D ${sampler}_raw;")
			appendLine("uniform vec2 ${sampler}_size;")
			appendLine("uniform vec2 ${sampler}_pt;")
		}
		val hooked = if (hook == HOOK_PREKERNEL) MAIN else hook
		val names = samplers.associateWith { it }.toMutableMap()
		if (HOOKED in samplers && hooked !in samplers) names[hooked] = HOOKED
		if (hooked in samplers && HOOKED !in samplers) names[HOOKED] = hooked
		for ((name, sampler) in names) {
			if (name != sampler) {
				appendLine("#define ${name}_raw ${sampler}_raw")
				appendLine("#define ${name}_size ${sampler}_size")
				appendLine("#define ${name}_pt ${sampler}_pt")
			}
			appendLine("#define ${name}_pos v_pos")
			appendLine("#define ${name}_mul 1.0")
			appendLine("#define ${name}_tex(pos) (texture(${sampler}_raw, (pos)))")
			appendLine("#define ${name}_texOff(off) ${name}_tex(${name}_pos + ${name}_pt * vec2(off))")
		}
		appendLine("#line 1")
		appendLine(body.trim())
		appendLine()
		appendLine("void main() {")
		appendLine("\tfrag_color = hook();")
		appendLine("}")
	}

	companion object {
		const val MAIN = "MAIN"
		const val HOOKED = "HOOKED"
		const val NATIVE = "NATIVE"
		const val OUTPUT = "OUTPUT"
		const val HOOK_PREKERNEL = "PREKERNEL"
	}
}

/** Reads mpv user shader files: a pass starts with its `//!` lines, its GLSL follows */
object MpvHookParser {
	fun parse(source: String): List<HookPass> {
		val passes = mutableListOf<HookPass>()
		var header = mutableMapOf<String, MutableList<String>>()
		val body = StringBuilder()
		var inPass = false
		var bodyStarted = false

		fun finish() {
			if (!inPass) return
			val hook = header["HOOK"]?.firstOrNull() ?: return
			passes += HookPass(
				description = header["DESC"]?.firstOrNull().orEmpty(),
				hook = hook,
				binds = header["BIND"].orEmpty(),
				save = header["SAVE"]?.firstOrNull(),
				width = header["WIDTH"]?.firstOrNull(),
				height = header["HEIGHT"]?.firstOrNull(),
				condition = header["WHEN"]?.firstOrNull(),
				body = body.toString(),
			)
		}

		for (line in source.lineSequence()) {
			val trimmed = line.trim()
			if (trimmed.startsWith("//!")) {
				// The first header line after some GLSL starts the next pass
				if (bodyStarted) {
					finish()
					header = mutableMapOf()
					body.clear()
					bodyStarted = false
				}
				inPass = true
				val directive = trimmed.removePrefix("//!")
				val key = directive.substringBefore(' ').uppercase()
				val value = directive.substringAfter(' ', "").trim()
				header.getOrPut(key) { mutableListOf() } += value
			} else if (inPass) {
				if (trimmed.isNotEmpty() && !trimmed.startsWith("//")) bodyStarted = true
				body.appendLine(line)
			}
		}
		finish()
		return passes
	}
}

/** Width and height of a texture */
data class TextureSize(val width: Int, val height: Int)

/** mpv's size expressions: reverse Polish, with NAME.w / NAME.h and + - * / > < = ! */
object RpnExpression {
	fun evaluate(expression: String, size: (String) -> TextureSize?): Double {
		val stack = ArrayDeque<Double>()
		fun pop(): Double = stack.removeLastOrNull() ?: throw IllegalArgumentException("Bad expression: $expression")

		for (token in expression.trim().split(Regex("\\s+"))) {
			if (token.isEmpty()) continue
			val number = token.toDoubleOrNull()
			when {
				number != null -> stack.addLast(number)
				token == "+" -> { val b = pop(); stack.addLast(pop() + b) }
				token == "-" -> { val b = pop(); stack.addLast(pop() - b) }
				token == "*" -> { val b = pop(); stack.addLast(pop() * b) }
				token == "/" -> { val b = pop(); stack.addLast(pop() / b) }
				token == ">" -> { val b = pop(); stack.addLast(if (pop() > b) 1.0 else 0.0) }
				token == "<" -> { val b = pop(); stack.addLast(if (pop() < b) 1.0 else 0.0) }
				token == "=" -> { val b = pop(); stack.addLast(if (pop() == b) 1.0 else 0.0) }
				token == "!" -> stack.addLast(if (pop() == 0.0) 1.0 else 0.0)
				token.contains('.') -> {
					val name = token.substringBeforeLast('.')
					val texture = size(name) ?: throw IllegalArgumentException("Unknown texture $name in $expression")
					stack.addLast(
						when (token.substringAfterLast('.')) {
							"w", "width" -> texture.width.toDouble()
							"h", "height" -> texture.height.toDouble()
							else -> throw IllegalArgumentException("Bad size $token in $expression")
						}
					)
				}

				else -> throw IllegalArgumentException("Bad token $token in $expression")
			}
		}
		return stack.lastOrNull() ?: throw IllegalArgumentException("Empty expression")
	}

	fun evaluateSize(expression: String, size: (String) -> TextureSize?): Int =
		max(1, evaluate(expression, size).roundToInt())
}

/**
 * The passes of a mode, read once: mpv's order (all MAIN hooks of every file in order, then the
 * PREKERNEL ones).
 */
class Anime4KChain(val mode: Anime4KMode, val passes: List<HookPass>) {
	companion object {
		fun load(mode: Anime4KMode, read: (String) -> String): Anime4KChain {
			val passes = mode.shaders.flatMap { file -> MpvHookParser.parse(read(file)) }
			return Anime4KChain(mode, passes.sortedBy { it.stage })
		}
	}
}

/**
 * What a frame of the chain does, without OpenGL: for each pass that runs, the textures it reads,
 * the one it writes and its size. [Anime4KRenderer] follows it with real textures.
 */
object Anime4KPlanner {
	class Step(val pass: HookPass, val index: Int, val inputs: Map<String, String>, val target: String, val size: TextureSize)

	/**
	 * [source]: the video's size; [output]: the screen's. The names of the textures in the plan are
	 * the mpv ones; MAIN and NATIVE start as the video.
	 */
	fun plan(chain: Anime4KChain, source: TextureSize, output: TextureSize): List<Step> {
		val sizes = mutableMapOf(HookPass.MAIN to source, HookPass.NATIVE to source)
		val steps = mutableListOf<Step>()
		chain.passes.forEachIndexed { index, pass ->
			fun size(name: String): TextureSize? = when (name) {
				HookPass.OUTPUT -> output
				HookPass.HOOKED -> sizes[HookPass.MAIN]
				else -> sizes[name]
			}
			if (pass.condition != null && RpnExpression.evaluate(pass.condition, ::size) == 0.0) return@forEachIndexed
			val hooked = sizes.getValue(HookPass.MAIN)
			val inputs = pass.samplers.associateWith { name -> if (name == HookPass.HOOKED) HookPass.MAIN else name }
			if (inputs.values.any { it !in sizes }) return@forEachIndexed
			val width = pass.width?.let { RpnExpression.evaluateSize(it, ::size) } ?: hooked.width
			val height = pass.height?.let { RpnExpression.evaluateSize(it, ::size) } ?: hooked.height
			val size = TextureSize(width, height)
			steps += Step(pass, index, inputs, pass.target, size)
			sizes[pass.target] = size
		}
		return steps
	}
}
