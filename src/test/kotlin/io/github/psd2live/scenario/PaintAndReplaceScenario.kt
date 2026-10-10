package io.github.psd2live.scenario

import io.github.psd2live.core.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * Painting on a rigged character: a sleeve bound to the skeleton is extended with the brush and its mesh rebuilt, the
 * face is erased without a rebuild, the top's image is replaced and made denser. Rebuilt meshes stay where the art is
 * and keep following their bones; painting never silently regenerates; the project reopens and exports.
 */
class PaintAndReplaceScenario {
	@TempDir lateinit var temp: Path

	@Test fun paintRebuildReplaceUnderTheSkeleton() = Studio.run(temp, Characters.figure()) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val arm = now.document.rigEdits.skeleton!!.bones.single { it.role == BoneRole.UPPER_ARM && it.side == Side.LEFT }
		val sleeve = mesh("sleeve_l")
		val drawn = bounds(sleeve)

		edit("extend the left sleeve with the brush", "source_paint_brush", req("layer_id" to "sleeve_l", "radius" to 8, "rebuild_mesh" to true,
			"color" to listOf(200, 80, 60, 255), "points" to listOf(listOf(300, 150), listOf(320, 156), listOf(338, 158))))
		val painted = bounds(mesh("sleeve_l"))
		expect("painted", mesh("sleeve_l") == sleeve) { "the rebuilt sleeve has another ID" }
		expect("painted", abs(painted[0] - drawn[0]) < 3f && abs(painted[1] - drawn[1]) < 3f) {
			"the rebuilt sleeve moved: bounds ${drawn.toList()} -> ${painted.toList()}"
		}
		expect("painted", painted[3] > drawn[3] + 4f) { "the rebuilt mesh does not cover the painted stroke: ${painted.toList()}" }
		expect("painted", motion(sleeve, arm.parameterId, 45f) > 15.0) { "the rebuilt sleeve no longer follows its bone" }

		edit("erase part of the face", "source_paint_eraser", req("layer_id" to "face", "radius" to 10,
			"points" to listOf(listOf(180, 100), listOf(200, 110))))
		expect("erased", !updateGenerated("update after painting")) { "painting changed what the generators make" }

		val image = Characters.disc(temp, "top.png", 64)
		edit("replace the top's image", "layer_replace_image", req("layer_id" to "top",
			"png_base64" to java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(image)), "fit" to "stretch"))
		val upper = now.document.rigEdits.skeleton!!.bones.single { it.role == BoneRole.UPPER_BODY }
		expect("replaced", motion(mesh("top"), upper.parameterId, 20f) > 5.0) { "the replaced top no longer bends with the upper body" }
		edit("make the top denser", "layer_set_pixel_density", req("layer_ids" to listOf("top"), "density" to 2.0))
		reopen()
		expect("reopened", motion(mesh("sleeve_l"), arm.parameterId, 45f) > 15.0) { "the reopened sleeve no longer follows its bone" }
		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		exportMatchesEditor(this)
	}
}
