package org.jellyfin.androidtv.ui.playback.anime4k

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the video with Anime4K. The player decodes into a SurfaceTexture of this renderer; each
 * frame is copied to a texture (MAIN, the video's size), goes through the passes of the mode
 * ([Anime4KPlanner]) in half float textures, and is scaled to the screen (Catmull-Rom up, an area
 * filter down). Runs on the thread of the GLSurfaceView (OpenGL ES 3.0).
 *
 * Anything that doesn't work here (no ES 3.0, no float textures, a shader the driver refuses)
 * reports [Listener.onFailure] and the video goes back to the normal output. Too slow (frames of
 * the player skipped) reports [Listener.onTooSlow].
 */
internal class Anime4KRenderer(
	private val readShader: (String) -> String,
	private val listener: Listener,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
	interface Listener {
		/** The surface the player decodes into (called on the GL thread) */
		fun onSurfaceReady(surface: Surface)
		fun onFailure(reason: String)
		fun onTooSlow()
		fun requestRender()
	}

	/** The mode to draw with, changed from the main thread */
	@Volatile
	var mode: Anime4KMode = Anime4KMode.OFF

	/** The video's size (the decoded picture, before the aspect ratio) */
	@Volatile
	var videoSize: TextureSize? = null

	/** Until then (System.nanoTime) the 4K test pattern shows instead of the video */
	@Volatile
	var testPatternUntil: Long = 0L

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null
	private var oesTexture = 0
	private val transform = FloatArray(16)
	private val frameAvailable = AtomicBoolean(false)
	private var hasFrame = false
	private var output = TextureSize(1, 1)
	private var failed = false

	private var vertexBuffer = 0
	private var vertexArray = 0
	private var copyProgram: CopyProgram? = null
	private var finalProgram: FinalProgram? = null
	private var patternProgram: PatternProgram? = null

	private var chain: Anime4KChain? = null
	private var passPrograms: List<PassProgram> = emptyList()
	private var plan: List<Anime4KPlanner.Step> = emptyList()
	private var planKey: Triple<Anime4KChain, TextureSize, TextureSize>? = null
	private val pool = mutableListOf<GlTexture>()

	// Frames the player gave and frames drawn, to see if the TV keeps up
	private val framesProduced = AtomicInteger(0)
	private var framesLatched = 0
	private var windowStart = 0L
	private var slowWindows = 0
	private var checkFrom = 0L

	// Renderer

	override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
		// A new context (the first one, or after the old one was lost): everything again
		pool.clear()
		chain = null
		passPrograms = emptyList()
		planKey = null
		hasFrame = false
		try {
			setUp()
		} catch (err: Exception) {
			fail(err.message ?: err.javaClass.simpleName)
		}
	}

	override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
		output = TextureSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
	}

	override fun onDrawFrame(gl: GL10?) {
		if (failed) return clear()
		val surfaceTexture = surfaceTexture ?: return clear()
		try {
			if (frameAvailable.getAndSet(false)) {
				surfaceTexture.updateTexImage()
				surfaceTexture.getTransformMatrix(transform)
				hasFrame = true
				framesLatched++
			}
			if (System.nanoTime() < testPatternUntil) {
				// Lines one and two pixels wide, at the size of the screen buffer
				GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
				GLES30.glViewport(0, 0, output.width, output.height)
				GLES30.glBindVertexArray(vertexArray)
				patternProgram!!.draw(output)
				checkFrom = System.nanoTime() + WARM_UP_NANOS
				resetWindow()
				return
			}
			val video = videoSize
			if (!hasFrame || video == null) return clear()

			val chain = chainFor(mode) ?: return clear()
			draw(chain, video)
			checkError("draw")
			watchSpeed()
		} catch (err: Exception) {
			fail(err.message ?: err.javaClass.simpleName)
		}
	}

	override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
		framesProduced.incrementAndGet()
		frameAvailable.set(true)
		listener.requestRender()
	}

	/** The GL thread is going away (view removed): its objects go with the context */
	fun release() {
		surface?.release()
		surface = null
		surfaceTexture?.release()
		surfaceTexture = null
		for (texture in pool) texture.delete()
		pool.clear()
	}

	// Setup

	private fun setUp() {
		val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
		if (!version.contains("OpenGL ES 3")) throw IllegalStateException("OpenGL ES 3 needed ($version)")

		// Float textures that can be drawn into (the networks need values below 0 and over 1)
		val test = GlTexture.create(4, 4) ?: throw IllegalStateException("no half float render targets")
		test.delete()

		vertexArray = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
		GLES30.glBindVertexArray(vertexArray)
		vertexBuffer = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
		// One triangle that covers the screen
		val vertices = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
		val buffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
		buffer.position(0)
		GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buffer, GLES30.GL_STATIC_DRAW)
		GLES30.glEnableVertexAttribArray(0)
		GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

		copyProgram = CopyProgram.create()
		finalProgram = FinalProgram.create()
		patternProgram = PatternProgram.create()

		oesTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
		checkError("setup")

		surface?.release()
		surfaceTexture?.release()
		val texture = SurfaceTexture(oesTexture)
		texture.setOnFrameAvailableListener(this)
		surfaceTexture = texture
		val newSurface = Surface(texture)
		surface = newSurface
		Timber.i("Anime4K ready: %s, %s", version, GLES30.glGetString(GLES30.GL_RENDERER))
		listener.onSurfaceReady(newSurface)
	}

	/** The passes of [mode], compiled once */
	private fun chainFor(mode: Anime4KMode): Anime4KChain? {
		if (mode == Anime4KMode.OFF) return null
		chain?.takeIf { it.mode == mode }?.let { return it }

		for (program in passPrograms) GLES30.glDeleteProgram(program.id)
		val loaded = Anime4KChain.load(mode, readShader)
		passPrograms = loaded.passes.map { pass -> PassProgram.create(pass) }
		chain = loaded
		planKey = null
		// Give the new mode a moment before judging its speed (the first frames compile and allocate)
		checkFrom = System.nanoTime() + WARM_UP_NANOS
		resetWindow()
		Timber.i("Anime4K %s: %d passes", mode, loaded.passes.size)
		return loaded
	}

	// Drawing

	private fun draw(chain: Anime4KChain, video: TextureSize) {
		val key = Triple(chain, video, output)
		if (planKey != key) {
			plan = Anime4KPlanner.plan(chain, video, output)
			planKey = key
		}

		GLES30.glBindVertexArray(vertexArray)
		GLES30.glDisable(GLES30.GL_BLEND)

		// The frame of the player, top row first (as mpv has it)
		val main = acquire(video, emptyList())
		main.focus()
		copyProgram!!.draw(oesTexture, transform)

		val textures = mutableMapOf(HookPass.MAIN to main, HookPass.NATIVE to main)
		val lastStep = plan.lastOrNull()
		for (step in plan) {
			val inputs = step.inputs.mapValues { (_, name) -> textures.getValue(name) }
			// The picture that goes to the screen needs no more than 8 bits: half the memory to
			// write and read (it can be 4K)
			val toScreen = step === lastStep && step.target == HookPass.MAIN
			val target = acquire(step.size, inputs.values.toList(), halfFloat = !toScreen)
			target.focus()
			passPrograms[step.index].draw(inputs)
			val old = textures.put(step.target, target)
			if (old != null && old !in textures.values) pool += old
		}

		// To the screen
		val result = textures.getValue(HookPass.MAIN)
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
		GLES30.glViewport(0, 0, output.width, output.height)
		finalProgram!!.draw(result, output)

		for (texture in textures.values.toSet()) if (texture !in pool) pool += texture
	}

	private fun acquire(size: TextureSize, inUse: List<GlTexture>, halfFloat: Boolean = true): GlTexture {
		val index = pool.indexOfFirst {
			it.width == size.width && it.height == size.height && it.halfFloat == halfFloat && it !in inUse
		}
		if (index >= 0) return pool.removeAt(index)
		// Sizes change with the video or the mode: what is left of other sizes goes
		if (pool.size > MAX_POOLED) pool.removeAll { texture -> texture.delete(); true }
		return GlTexture.create(size.width, size.height, halfFloat) ?: throw IllegalStateException("texture ${size.width}x${size.height}")
	}

	private fun clear() {
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
		GLES30.glViewport(0, 0, output.width, output.height)
		GLES30.glClearColor(0f, 0f, 0f, 1f)
		GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
	}

	// Speed: the frames the player gave against the ones drawn

	private fun watchSpeed() {
		val now = System.nanoTime()
		if (now < checkFrom) return resetWindow()
		if (now - windowStart < WINDOW_NANOS) return
		val produced = framesProduced.get()
		val skipped = produced - framesLatched
		// Paused, or a slow video: nothing to judge
		if (produced >= MIN_WINDOW_FRAMES) {
			val slow = skipped.toFloat() / produced > MAX_SKIPPED
			slowWindows = if (slow) slowWindows + 1 else 0
			if (slow) Timber.w("Anime4K %s: %d of %d frames skipped", mode, skipped, produced)
			if (slowWindows >= SLOW_WINDOWS) {
				slowWindows = 0
				checkFrom = now + WARM_UP_NANOS
				listener.onTooSlow()
			}
		}
		resetWindow()
	}

	private fun resetWindow() {
		windowStart = System.nanoTime()
		framesProduced.set(0)
		framesLatched = 0
	}

	private fun fail(reason: String) {
		if (failed) return
		failed = true
		Timber.e("Anime4K failed: %s", reason)
		listener.onFailure(reason)
	}

	private fun checkError(where: String) {
		val error = GLES30.glGetError()
		if (error != GLES30.GL_NO_ERROR) throw IllegalStateException("GL error 0x${Integer.toHexString(error)} ($where)")
	}

	private companion object {
		const val WARM_UP_NANOS = 3_000_000_000L
		const val WINDOW_NANOS = 4_000_000_000L
		const val MIN_WINDOW_FRAMES = 40
		/** More than this share of frames skipped: the TV doesn't keep up */
		const val MAX_SKIPPED = 0.15f
		const val SLOW_WINDOWS = 2
		const val MAX_POOLED = 24
	}
}

// OpenGL objects

/** A half float (or 8-bit) texture with its framebuffer */
private class GlTexture(val id: Int, val fbo: Int, val width: Int, val height: Int, val halfFloat: Boolean) {
	fun focus() {
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
		GLES30.glViewport(0, 0, width, height)
	}

	fun delete() {
		GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
		GLES30.glDeleteTextures(1, intArrayOf(id), 0)
	}

	companion object {
		fun create(width: Int, height: Int, halfFloat: Boolean = true): GlTexture? {
			val id = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
			if (halfFloat) {
				GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
			} else {
				GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
			}
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
			val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
			GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
			GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, id, 0)
			val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
			val texture = GlTexture(id, fbo, width, height, halfFloat)
			if (!complete) {
				texture.delete()
				return null
			}
			return texture
		}
	}
}

private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 a_pos;
out vec2 v_pos;
void main() {
	v_pos = a_pos * 0.5 + 0.5;
	gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

private fun compile(type: Int, source: String, name: String): Int {
	val shader = GLES30.glCreateShader(type)
	GLES30.glShaderSource(shader, source)
	GLES30.glCompileShader(shader)
	val status = IntArray(1)
	GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
	if (status[0] == 0) {
		val log = GLES30.glGetShaderInfoLog(shader)
		GLES30.glDeleteShader(shader)
		throw IllegalStateException("shader $name: $log")
	}
	return shader
}

private fun link(fragment: String, name: String): Int {
	val vertexShader = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER, "vertex")
	val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragment, name)
	val program = GLES30.glCreateProgram()
	GLES30.glAttachShader(program, vertexShader)
	GLES30.glAttachShader(program, fragmentShader)
	GLES30.glBindAttribLocation(program, 0, "a_pos")
	GLES30.glLinkProgram(program)
	GLES30.glDeleteShader(vertexShader)
	GLES30.glDeleteShader(fragmentShader)
	val status = IntArray(1)
	GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
	if (status[0] == 0) {
		val log = GLES30.glGetProgramInfoLog(program)
		GLES30.glDeleteProgram(program)
		throw IllegalStateException("program $name: $log")
	}
	return program
}

/** The player's frame (external texture) to MAIN, flipped so the top row comes first */
private class CopyProgram(val id: Int) {
	private val texture = GLES30.glGetUniformLocation(id, "u_tex")
	private val matrix = GLES30.glGetUniformLocation(id, "u_st")

	fun draw(oesTexture: Int, transform: FloatArray) {
		GLES30.glUseProgram(id)
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES30.glUniform1i(texture, 0)
		GLES30.glUniformMatrix4fv(matrix, 1, false, transform, 0)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
	}

	companion object {
		private const val SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES u_tex;
uniform mat4 u_st;
in vec2 v_pos;
out vec4 frag_color;
void main() {
	vec2 uv = (u_st * vec4(v_pos.x, 1.0 - v_pos.y, 0.0, 1.0)).xy;
	frag_color = vec4(texture(u_tex, uv).rgb, 1.0);
}
"""

		fun create() = CopyProgram(link(SHADER, "copy"))
	}
}

/** MAIN to the screen: Catmull-Rom when it grows, an area filter when it shrinks */
private class FinalProgram(val id: Int) {
	private val main = GLES30.glGetUniformLocation(id, "u_main")
	private val mainSize = GLES30.glGetUniformLocation(id, "u_main_size")
	private val outSize = GLES30.glGetUniformLocation(id, "u_out_size")

	fun draw(texture: GlTexture, output: TextureSize) {
		GLES30.glUseProgram(id)
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.id)
		GLES30.glUniform1i(main, 0)
		GLES30.glUniform2f(mainSize, texture.width.toFloat(), texture.height.toFloat())
		GLES30.glUniform2f(outSize, output.width.toFloat(), output.height.toFloat())
		GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
	}

	companion object {
		private const val SHADER = """#version 300 es
precision highp float;
uniform sampler2D u_main;
uniform vec2 u_main_size;
uniform vec2 u_out_size;
in vec2 v_pos;
out vec4 frag_color;

vec4 catmullRom(vec2 p) {
	vec2 samplePos = p * u_main_size;
	vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
	vec2 f = samplePos - texPos1;
	vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	vec2 w3 = f * f * (-0.5 + 0.5 * f);
	vec2 w12 = w1 + w2;
	vec2 offset12 = w2 / w12;
	vec2 texPos0 = (texPos1 - 1.0) / u_main_size;
	vec2 texPos3 = (texPos1 + 2.0) / u_main_size;
	vec2 texPos12 = (texPos1 + offset12) / u_main_size;
	vec4 r = texture(u_main, vec2(texPos0.x, texPos0.y)) * w0.x * w0.y;
	r += texture(u_main, vec2(texPos12.x, texPos0.y)) * w12.x * w0.y;
	r += texture(u_main, vec2(texPos3.x, texPos0.y)) * w3.x * w0.y;
	r += texture(u_main, vec2(texPos0.x, texPos12.y)) * w0.x * w12.y;
	r += texture(u_main, vec2(texPos12.x, texPos12.y)) * w12.x * w12.y;
	r += texture(u_main, vec2(texPos3.x, texPos12.y)) * w3.x * w12.y;
	r += texture(u_main, vec2(texPos0.x, texPos3.y)) * w0.x * w3.y;
	r += texture(u_main, vec2(texPos12.x, texPos3.y)) * w12.x * w3.y;
	r += texture(u_main, vec2(texPos3.x, texPos3.y)) * w3.x * w3.y;
	return r;
}

void main() {
	vec2 p = vec2(v_pos.x, 1.0 - v_pos.y);
	vec2 scale = u_out_size / u_main_size;
	vec4 c;
	if (scale.x < 0.999 || scale.y < 0.999) {
		vec2 o = 0.25 / u_out_size;
		c = 0.25 * (texture(u_main, p + vec2(-o.x, -o.y)) + texture(u_main, p + vec2(o.x, -o.y))
			+ texture(u_main, p + vec2(-o.x, o.y)) + texture(u_main, p + vec2(o.x, o.y)));
	} else if (scale.x > 1.001 || scale.y > 1.001) {
		c = catmullRom(p);
	} else {
		c = texture(u_main, p);
	}
	frag_color = vec4(clamp(c.rgb, 0.0, 1.0), 1.0);
}
"""

		fun create() = FinalProgram(link(SHADER, "final"))
	}
}

/**
 * The 4K test: four bands of black and white lines, 1 and 2 pixels wide, vertical then horizontal.
 * With 4K reaching the TV, the four show lines; with the picture reduced to 1080p on the way, the
 * 1-pixel bands turn into flat grey (or moiré) and only the 2-pixel ones keep lines.
 */
private class PatternProgram(val id: Int) {
	private val size = GLES30.glGetUniformLocation(id, "u_size")

	fun draw(output: TextureSize) {
		GLES30.glUseProgram(id)
		GLES30.glUniform2f(size, output.width.toFloat(), output.height.toFloat())
		GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
	}

	companion object {
		private const val SHADER = """#version 300 es
precision highp float;
uniform vec2 u_size;
in vec2 v_pos;
out vec4 frag_color;
void main() {
	vec2 p = floor(gl_FragCoord.xy);
	float band = floor(v_pos.x * 4.0);
	float v;
	if (band < 1.0) v = mod(p.x, 2.0);
	else if (band < 2.0) v = mod(floor(p.x / 2.0), 2.0);
	else if (band < 3.0) v = mod(p.y, 2.0);
	else v = mod(floor(p.y / 2.0), 2.0);
	// A red line between the bands
	float edge = abs(fract(v_pos.x * 4.0 + 0.5) - 0.5) * u_size.x / 4.0;
	frag_color = edge < 3.0 ? vec4(0.9, 0.1, 0.1, 1.0) : vec4(vec3(v), 1.0);
}
"""

		fun create() = PatternProgram(link(SHADER, "pattern"))
	}
}

/** A pass of Anime4K, with where its textures go */
private class PassProgram(val id: Int, pass: HookPass) {
	private class Sampler(val name: String, val raw: Int, val size: Int, val pt: Int)

	private val samplers = pass.samplers.map { name ->
		Sampler(
			name = name,
			raw = GLES30.glGetUniformLocation(id, "${name}_raw"),
			size = GLES30.glGetUniformLocation(id, "${name}_size"),
			pt = GLES30.glGetUniformLocation(id, "${name}_pt"),
		)
	}

	fun draw(inputs: Map<String, GlTexture>) {
		GLES30.glUseProgram(id)
		samplers.forEachIndexed { unit, sampler ->
			val texture = inputs.getValue(sampler.name)
			GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.id)
			GLES30.glUniform1i(sampler.raw, unit)
			GLES30.glUniform2f(sampler.size, texture.width.toFloat(), texture.height.toFloat())
			GLES30.glUniform2f(sampler.pt, 1f / texture.width, 1f / texture.height)
		}
		GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
	}

	companion object {
		fun create(pass: HookPass) = PassProgram(link(pass.fragmentShader(), pass.description), pass)
	}
}
