package io.github.psd2live.tools

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.CharacterAnalyzer
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.recipe.RigRecipe
import java.awt.image.BufferedImage
import kotlin.test.Test

/**
 * The review material of the generated rig (docs/zh/guide/DEVELOPMENT.md): the sample PSD2LIVE_SAMPLE generated with
 * a new project's recipe and, beside it, with the legacy recipe of a project saved before rig modules, shown in the
 * poses and clips of [ReviewClips]. Per area, `<area>-poses.png` puts the recipes' poses one row above the other, and
 * each clip `<clip>.gif` plays them side by side with physics running. PSD2LIVE_REVIEW lists the areas (eye, brow,
 * mouth, head, hair, body, motion; all by default), PSD2LIVE_REVIEW_SIZE is a frame's side.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=tml ./gradlew test --tests '*RigReviewTool'
 */
class RigReviewTool {
	@Test fun review() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val areas = setting("PSD2LIVE_REVIEW", "eye,brow,mouth,head,hair,body,motion").split(',').map(String::trim).toSet()
		val size = setting("PSD2LIVE_REVIEW_SIZE", "320").toInt()
		// Both from one analysis: a build from the file and one from its analysis do not mesh alike.
		val read = PSD2LivePipeline().buildPreview(sample.path)
		val modern = PSD2LivePipeline().buildPreview(read.analysis, read.config)
		val models = listOf("modules" to modern,
			"legacy" to PSD2LivePipeline().buildPreview(read.analysis, read.config.copy(recipe = RigRecipe.Legacy)))
		val out = output("rig-review/${sample.name}")
		for ((area, poses) in ReviewClips.poses) {
			if (area !in areas) continue
			val frames = models.flatMap { (label, model) ->
				val renderer = Renderer(model, size)
				poses.map { (name, values) -> "$label $name" to renderer.render(values, rect(model, area)) }
			}
			sheet(frames, out.resolve("$area-poses.png"), columns = poses.size)
		}
		for (clip in ReviewClips.clips) {
			if (clip.area !in areas) continue
			val fps = 20f
			val count = (clip.seconds * fps).toInt()
			val tracks = models.map { (label, model) -> label to frames(model, clip, count, fps, size / 2 * 2) }
			ReviewClips.gif((0 until count).map { i -> ReviewClips.beside(tracks.map { (label, frames) -> label to frames[i] }) }, fps,
				out.resolve("${clip.name}.gif"))
		}
	}

	/** [clip] rendered [count] frames at [fps] with the model's physics stepped along, after a second at rest to settle it. */
	private fun frames(model: RigPreviewModel, clip: ReviewClips.Clip, count: Int, fps: Float, size: Int): List<BufferedImage> {
		val puppet = model.rig.puppet
		val available = puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val engine = PhysicsEngine(PhysicsCatalog.active(model.analysis, model.config, available, puppet), PhysicsEngine.ranges(puppet.parameters))
		fun known(values: Map<String, Float>) = values.filterKeys { it in available }
		engine.settle(known(clip.values(0f)), 1f)
		val renderer = Renderer(model, size)
		val rect = rect(model, clip.area)
		return (0 until count).map { i ->
			val input = known(clip.values(i / fps))
			renderer.render(input + engine.step(input, 1f / fps), rect)
		}
	}

	/**
	 * The part of the canvas [area] moves: around the layers of its parts (eyes, brows with the eyes, the mouth), the face
	 * for the head, the character for hair, body and motions; padded so motion stays in frame.
	 */
	private fun rect(model: RigPreviewModel, area: String): Bounds {
		val layers = model.analysis.layers.filter { it.opaquePixels > 0 }
		fun around(tags: Set<SemanticTag>, pad: Float): Bounds? = layers.filter { it.semantic.tag in tags }.map { it.bounds }
			.reduceOrNull(Bounds::union)?.let { b -> framed(b.expanded(pad), 2f) }
		val eyes = setOf(SemanticTag.EYEWHITE, SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
		return when (area) {
			"eye" -> around(eyes, 0.25f)
			"brow" -> around(eyes + SemanticTag.EYEBROW, 0.25f)
			"mouth" -> around(CharacterAnalyzer.MOUTH_TAGS, 1.2f)
			"head" -> framed(model.analysis.anchors.face.expanded(0.35f), 1f)
			"hair" -> layers.filter { it.semantic.tag == SemanticTag.FRONT_HAIR || it.semantic.tag == SemanticTag.BACK_HAIR }.map { it.bounds }
				.reduceOrNull(Bounds::union)?.let { framed(it.expanded(0.06f), 0.8f) }
			else -> null
		} ?: model.analysis.anchors.character
	}

	/** [bounds] grown about its centre to [aspect] (width over height), so the frames of an area keep one shape. */
	private fun framed(bounds: Bounds, aspect: Float): Bounds {
		val width = maxOf(bounds.width, bounds.height * aspect) / 2f
		val height = width / aspect
		return Bounds(bounds.centerX - width, bounds.centerY - height, bounds.centerX + width, bounds.centerY + height)
	}
}
