package io.github.psd2live.scenario

import io.github.psd2live.core.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * The first session with a new character: a PSD is imported, its parts classified, generation settings changed after
 * the fact, the skeleton proposed and applied, an arm-waving motion added, the settings changed back, the project
 * saved and reopened, and the model exported. At every step the rig rebuilds from the document alone, and at the end
 * the exported moc3 moves as the editor does.
 */
class GenerateAndRigScenario {
	@TempDir lateinit var temp: Path

	private val parts = mapOf(
		"back_hair" to ("back_hair" to "none"), "leg_r" to ("legwear" to "right"), "leg_l" to ("legwear" to "left"),
		"skirt" to ("bottomwear" to "none"), "top" to ("topwear" to "none"), "sleeve_r" to ("handwear" to "right"),
		"sleeve_l" to ("handwear" to "left"), "face" to ("face" to "none"), "front_hair" to ("front_hair" to "none"),
	)

	@Test fun importClassifyRigAnimateSaveAndExport() = Studio.run(temp) {
		importPsd(Characters.figurePsd(temp), PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false))
		val ids = now.document.source.layers.associate { it.name to it.id.raw }
		edits("classify every part", *parts.map { (name, role) ->
			"layer_classify" to req("layer_id" to ids.getValue(name), "type" to "preset", "role" to role.first, "side" to role.second)
		}.toTypedArray())
		edit("turn on features and hair physics", "settings_update", req("changes" to mapOf(
			"featureDisplacementEnabled" to true, "generatePhysics" to true, "physicsBackHair" to true)))
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val skeleton = requireNotNull(now.document.rigEdits.skeleton)
		val armL = skeleton.bones.single { it.role == BoneRole.UPPER_ARM && it.side == Side.LEFT }
		val armR = skeleton.bones.single { it.role == BoneRole.UPPER_ARM && it.side == Side.RIGHT }
		fun limbs(label: String) {
			expect(label, motion(mesh(ids.getValue("sleeve_l")), armL.parameterId, 45f) > 15.0) { "the left upper arm does not move the left sleeve" }
			expect(label, motion(mesh(ids.getValue("sleeve_r")), armR.parameterId, 45f) > 15.0) { "the right upper arm does not move the right sleeve" }
			expect(label, motion(mesh(ids.getValue("sleeve_r")), armL.parameterId, 45f) < 1.0) { "the left arm moves the right sleeve" }
		}
		limbs("skeleton applied")

		edit("add the Wave motion", "motion_seed_builtin", req("builtin" to "Wave", "id" to "wave"))
		val wave = now.document.rigEdits.motionClips.single { it.id == "wave" }
		val sleeve = mesh(ids.getValue("sleeve_r")) to mesh(ids.getValue("sleeve_l"))
		val swing = (0..20).maxOf { i ->
			val pose = MotionClips.sampleAll(wave, wave.duration * i / 20.0, false).mapKeys { it.key.raw }
			maxOf(Oracles.motion(puppet, sleeve.first, pose), Oracles.motion(puppet, sleeve.second, pose))
		}
		expect("Wave", swing > 15.0) { "the Wave motion never raises an arm (largest sleeve motion $swing px)" }

		edit("turn features off again", "settings_update", req("changes" to mapOf("featureDisplacementEnabled" to false)))
		limbs("features off")
		reopen()
		limbs("reopened")
		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		exportMatchesEditor(this)
	}
}
