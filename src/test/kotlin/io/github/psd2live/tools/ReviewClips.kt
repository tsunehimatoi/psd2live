package io.github.psd2live.tools

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The poses and moving clips the rig is reviewed with (docs/zh/guide/DEVELOPMENT.md): the same for a generated model
 * and for any finished Cubism model, so one can be watched beside the other. Every value is a standard Cubism
 * parameter ID; a model without one simply does not move it.
 */
internal object ReviewClips {
	/** One clip: [seconds] long, and the parameter values at a time in it. */
	class Clip(val name: String, val area: String, val seconds: Float, val values: (Float) -> Map<String, Float>)

	/** A blink's openness at [t] seconds after it starts: closing fast, a short hold, opening slower. */
	fun blink(t: Float, close: Float = 0.08f, hold: Float = 0.04f, open: Float = 0.16f): Float = when {
		t < 0f -> 1f
		t < close -> 1f - smooth(t / close)
		t < close + hold -> 0f
		t < close + hold + open -> smooth((t - close - hold) / open)
		else -> 1f
	}

	private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

	private fun brows(name: String, value: Float) = mapOf("ParamBrowL$name" to value, "ParamBrowR$name" to value)
	private fun eyes(open: Float, smile: Float = 0f) = mapOf("ParamEyeLOpen" to open, "ParamEyeROpen" to open, "ParamEyeLSmile" to smile, "ParamEyeRSmile" to smile)

	/** Mouth openness of speech at [t]: syllables of varying size, mostly small, as talking opens the mouth. */
	private fun talk(t: Float): Float {
		val syllable = (t * 7f)
		val phase = syllable - syllable.toInt()
		val size = listOf(0.35f, 0.6f, 0.25f, 0.85f, 0.4f, 0.15f, 0.7f, 0.3f)[syllable.toInt() % 8]
		return (sin(phase * PI).toFloat() * size).coerceAtLeast(0f)
	}

	/** The vowels A I U E O, each held a moment, with the mouth open and form a speaker gives them. */
	private val vowels = listOf("A" to (0.95f to 0.2f), "I" to (0.3f to 0.8f), "U" to (0.35f to -0.6f), "E" to (0.55f to 0.5f), "O" to (0.75f to -0.3f))

	val clips: List<Clip> = listOf(
		Clip("blink", "eye", 1.6f) { t -> eyes(blink(t - 0.2f) * blink(t - 0.95f, 0.07f, 0.02f, 0.14f)) },
		Clip("slow-blink", "eye", 2.4f) { t -> eyes(blink(t - 0.2f, 0.6f, 0.3f, 0.9f)) },
		Clip("smile-blink", "eye", 1.6f) { t -> eyes(blink(t - 0.2f), smile = 1f) + mapOf("ParamMouthForm" to 1f) },
		Clip("gaze", "eye", 3f) { t ->
			val a = t / 3f * 2f * PI.toFloat()
			mapOf("ParamEyeBallX" to sin(a), "ParamEyeBallY" to sin(2f * a) * 0.8f)
		},
		Clip("talk", "mouth", 3f) { t -> mapOf("ParamMouthOpenY" to talk(t), "ParamMouthForm" to 0.3f * sin(t * 1.3f)) },
		Clip("vowels", "mouth", 5f) { t ->
			val i = (t / 1f).toInt().coerceAtMost(vowels.lastIndex)
			val local = t - i
			val (open, form) = vowels[i].second
			val k = smooth(local / 0.25f) * (1f - smooth((local - 0.75f) / 0.25f))
			mapOf("ParamMouthOpenY" to open * k, "ParamMouthForm" to form * k, "Param${vowels[i].first}" to k)
		},
		Clip("brows", "brow", 3f) { t ->
			val a = t / 3f * 2f * PI.toFloat()
			mapOf("ParamBrowLY" to sin(a), "ParamBrowRY" to sin(a), "ParamBrowLAngle" to sin(2f * a) * 0.8f, "ParamBrowRAngle" to sin(2f * a) * 0.8f)
		},
		Clip("turn", "head", 4f) { t ->
			val a = t / 4f * 2f * PI.toFloat()
			mapOf("ParamAngleX" to 30f * sin(a), "ParamAngleY" to 20f * sin(2f * a))
		},
		Clip("nod-tilt", "head", 3f) { t ->
			val a = t / 3f * 2f * PI.toFloat()
			mapOf("ParamAngleY" to 25f * sin(2f * a), "ParamAngleZ" to 20f * sin(a))
		},
		Clip("shake", "hair", 2.5f) { t ->
			// A quick shake that stops: what the hair does after the head is still shows its weight.
			val k = if (t < 1.2f) 1f else 0f
			mapOf("ParamAngleX" to 30f * sin(t * 2f * PI.toFloat() * 1.6f) * k, "ParamAngleZ" to 12f * sin(t * 2f * PI.toFloat() * 1.6f) * k)
		},
		Clip("body", "body", 4f) { t ->
			val a = t / 4f * 2f * PI.toFloat()
			mapOf("ParamBodyAngleX" to 10f * sin(a), "ParamBodyAngleZ" to 8f * sin(a + 0.6f), "ParamAngleX" to 12f * sin(a - 0.3f), "ParamBreath" to (0.5f + 0.5f * sin(3f * a)))
		},
		Clip("idle", "motion", 8f) { t ->
			// Breathing, a small figure-eight of the head, and blinks at uneven times.
			val open = blink(t - 1.4f) * blink(t - 4.6f) * blink(t - 4.95f, 0.07f, 0.02f, 0.14f) * blink(t - 7.2f)
			mapOf("ParamBreath" to (0.5f + 0.5f * sin(t / 3.2f * 2f * PI.toFloat())), "ParamAngleX" to 6f * sin(t / 8f * 2f * PI.toFloat()),
				"ParamAngleY" to 4f * sin(t / 4f * 2f * PI.toFloat()), "ParamAngleZ" to 3f * sin(t / 8f * 2f * PI.toFloat() + 1f),
				"ParamBodyAngleX" to 3f * sin(t / 8f * 2f * PI.toFloat() - 0.6f)) + eyes(open)
		},
	)

	/** Every hair chain parameter a generated rig may have ([io.github.psd2live.core.fit.HairChain]), set by [value] of region and segment. */
	private fun chains(value: (region: String, segment: Int) -> Float, bounce: Float = 0f): Map<String, Float> = buildMap {
		for (kind in io.github.psd2live.core.fit.HairKind.entries) for (n in 1..12) {
			for (region in listOf("", "L", "C", "R")) for (k in 1..4) put("${kind.prefix}${kind.word}$n$region$k", value(region, k))
			put("${kind.prefix}${kind.word}${n}Y", bounce)
		}
	}

	/** The static poses of each area: a label and the values, swept at the parameter's quarter points and combined. */
	val poses: Map<String, List<Pair<String, Map<String, Float>>>> = mapOf(
		"expression" to io.github.psd2live.core.ModelExpressions.standard.map { e ->
			// Each mood over the rest pose, as a host applies it: overwritten, added to the default, or multiplying it.
			e.name to e.entries.associate { entry ->
				val rest = if (entry.parameterId == "ParamEyeLOpen" || entry.parameterId == "ParamEyeROpen") 1f else 0f
				entry.parameterId to when (entry.blend) {
					io.github.psd2live.core.ExpressionBlend.OVERWRITE -> entry.value
					io.github.psd2live.core.ExpressionBlend.ADD -> rest + entry.value
					io.github.psd2live.core.ExpressionBlend.MULTIPLY -> rest * entry.value
				}
			}
		},
		"hair" to listOf("rest" to emptyMap(), "swing +" to chains({ _, _ -> 1f }), "swing -" to chains({ _, _ -> -1f }),
			"root only" to chains({ _, k -> if (k == 1) 1f else 0f }), "tip only" to chains({ _, k -> if (k >= 2) 1f else 0f }),
			"left lock" to chains({ r, _ -> if (r == "L") 1f else 0f }), "locks apart" to chains({ r, _ -> when (r) { "L" -> -1f; "R" -> 1f; else -> 0f } }),
			"bounce +" to chains({ _, _ -> 0f }, 1f), "bounce -" to chains({ _, _ -> 0f }, -1f)),
		"eye" to listOf(1f, 0.75f, 0.5f, 0.25f, 0.1f, 0f).map { "open $it" to eyes(it) } +
			listOf(0.5f, 0f).map { "open $it smile" to eyes(it, 1f) } +
			listOf(-1f to 0f, 1f to 0f, 0f to 1f, 0f to -1f).map { (x, y) -> "gaze $x,$y" to mapOf("ParamEyeBallX" to x, "ParamEyeBallY" to y) } +
			listOf("wide" to eyes(2f), "angry" to mapOf("ParamEyeLForm" to 1f, "ParamEyeRForm" to 1f), "sad" to mapOf("ParamEyeLForm" to -1f, "ParamEyeRForm" to -1f),
				"squint" to mapOf("ParamEyeLSquint" to 1f, "ParamEyeRSquint" to 1f), "angry half" to eyes(0.5f) + mapOf("ParamEyeLForm" to 1f, "ParamEyeRForm" to 1f)),
		"brow" to listOf(-1f, 0f, 1f).flatMap { y -> listOf(1f, 0f).map { open -> "brow $y open $open" to eyes(open) + mapOf("ParamBrowLY" to y, "ParamBrowRY" to y) } } +
			listOf(-1f, -0.5f, 0.5f, 1f).map { "angle $it" to brows("Angle", it) } + listOf(-1f, 1f).map { "form $it" to brows("Form", it) } +
			listOf(-1f, 1f).map { "x $it" to brows("X", it) } + listOf("wide" to eyes(2f),
				"angry" to brows("Angle", -1f) + brows("Y", -0.6f) + mapOf("ParamEyeLForm" to 1f, "ParamEyeRForm" to 1f),
				"sad" to brows("Angle", 1f) + brows("Form", 0.5f) + mapOf("ParamEyeLForm" to -1f, "ParamEyeRForm" to -1f),
				"surprised" to brows("Y", 1f) + eyes(1.6f), "smile closed" to eyes(0f, 1f)),
		"mouth" to listOf(-1f, 0f, 1f).flatMap { form -> listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { open -> "form $form open $open" to mapOf("ParamMouthForm" to form, "ParamMouthOpenY" to open) } } +
			listOf('A', 'I', 'U', 'E', 'O').map { "vowel $it" to mapOf("Param$it" to 1f) } +
			listOf("mouth x" to mapOf("ParamMouthX" to 1f), "pucker" to mapOf("ParamMouthPuckerWiden" to -1f), "widen" to mapOf("ParamMouthPuckerWiden" to 1f),
				"shrug" to mapOf("ParamMouthShrug" to 1f), "press" to mapOf("ParamMouthPressLipOpen" to -1f), "parted" to mapOf("ParamMouthPressLipOpen" to 1f),
				"cheek puff" to mapOf("ParamCheekPuff" to 1f), "turned open" to mapOf("ParamMouthOpenY" to 1f, "ParamAngleX" to 30f)),
		"head" to listOf(-30f, -15f, 0f, 15f, 30f).flatMap { x -> listOf(-30f, 0f, 30f).map { y -> "x $x y $y" to mapOf("ParamAngleX" to x, "ParamAngleY" to y) } },
		"body" to listOf(-10f, 0f, 10f).flatMap { x -> listOf(-10f, 0f, 10f).map { z -> "body x $x z $z" to mapOf("ParamBodyAngleX" to x, "ParamBodyAngleZ" to z) } } +
			listOf("bust up" to mapOf("ParamBustY" to 1f), "bust down" to mapOf("ParamBustY" to -1f), "bust side" to mapOf("ParamBustX" to 1f),
				"cloth swing" to chains({ _, _ -> 1f }), "cloth swing -" to chains({ _, _ -> -1f }), "hem flare" to chains({ _, _ -> 0f }, 1f),
				"hem in" to chains({ _, _ -> 0f }, -1f)),
	)

	/** [frames] written as a looping GIF at [fps] into [file]. */
	fun gif(frames: List<BufferedImage>, fps: Float, file: File) {
		val writer = ImageIO.getImageWritersByFormatName("gif").next()
		file.outputStream().use { stream ->
			ImageIO.createImageOutputStream(stream).use { output ->
				writer.output = output
				writer.prepareWriteSequence(null)
				val delay = (100f / fps).toInt().coerceAtLeast(2).toString()
				for ((index, frame) in frames.withIndex()) {
					val type = ImageTypeSpecifier.createFromRenderedImage(frame)
					val metadata = writer.getDefaultImageMetadata(type, null)
					val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
					val control = IIOMetadataNode("GraphicControlExtension").apply {
						setAttribute("disposalMethod", "none"); setAttribute("userInputFlag", "FALSE")
						setAttribute("transparentColorFlag", "FALSE"); setAttribute("delayTime", delay); setAttribute("transparentColorIndex", "0")
					}
					root.appendChild(control)
					if (index == 0) root.appendChild(IIOMetadataNode("ApplicationExtensions").apply {
						appendChild(IIOMetadataNode("ApplicationExtension").apply {
							setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0")
							userObject = byteArrayOf(1, 0, 0)
						})
					})
					metadata.setFromTree(metadata.nativeMetadataFormatName, root)
					writer.writeToSequence(IIOImage(frame, null, metadata), null)
				}
				writer.endWriteSequence()
			}
		}
		println("wrote $file")
	}

	/** Frames side by side, each labelled, as one image. */
	fun beside(frames: List<Pair<String, BufferedImage>>): BufferedImage {
		val width = frames.sumOf { it.second.width }
		val height = frames.maxOf { it.second.height } + 18
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		g.color = java.awt.Color.DARK_GRAY; g.fillRect(0, 0, width, 18)
		g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 13)
		var x = 0
		for ((label, frame) in frames) {
			g.color = java.awt.Color.WHITE; g.drawString(label, x + 4, 14)
			g.drawImage(frame, x, 18, null)
			x += frame.width
		}
		g.dispose()
		return image
	}

	@Suppress("unused") private fun ease(x: Float) = 1f - exp(-abs(x))
}
