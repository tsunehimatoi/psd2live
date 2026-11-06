package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BodyStance
import io.github.psd2live.algorithms.binding.LegacyBodyMotion
import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.ScaffoldBody
import io.github.psd2live.i18n.tr
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.*

class ScaffoldBodyParityTest {
    private val canvas = Bounds(-11f, 5f, 222f, 400f)
    private val body = Bounds(31.3f, 42.7f, 171.4f, 249.1f)

    @Test fun standingDefaultMatchesTheProjectedLegacyLegLattice() {
        val bones = listOf(
            bone("left-thigh", null, BoneRole.THIGH, Side.LEFT, 124f, 199f, 126f, 280f),
            bone("left-shin", "left-thigh", BoneRole.SHIN, Side.LEFT, 126f, 280f, 128f, 361f),
            bone("right-thigh", null, BoneRole.THIGH, Side.RIGHT, 81f, 201f, 75f, 278f),
            bone("right-shin", "right-thigh", BoneRole.SHIN, Side.RIGHT, 75f, 278f, 78f, 363f),
        )
        val spec = SkeletonSpec(bones = bones)
        val unturned = compare(BodyStanceSource.of(spec, canvas, tuning = RigTuning(kneesInRest = 0f)))
        val inward = compare(BodyStanceSource.of(spec, canvas, tuning = RigTuning(kneesInRest = 25f)))
        assertEquals(listOf("DeformLegs", "DeformBodyXY", "DeformBodyLean"), inward.map { it.id.raw })
        assertNotEquals(PuppetIr.deformerToIr(unturned.first()), PuppetIr.deformerToIr(inward.first()),
            "Neutral knee tuning must change the standing leg grid")
        assertEquals(unturned.drop(1).map(PuppetIr::deformerToIr), inward.drop(1).map(PuppetIr::deformerToIr))
    }

    @Test fun leglessBodyUsesCanvasPixelsAndNormalizedLeanWithoutALegWarp() {
        val output = compare(BodyStanceSource.of(SkeletonSpec(), canvas, strength = .72f, tuning = RigTuning(turnDegrees = 19f, leanDegrees = 37f)))
        assertEquals(listOf("DeformBodyXY", "DeformBodyLean"), output.map { it.id.raw })
    }

    private fun compare(stance: BodyStance): List<org.umamo.runtime.model.Deformer.Warp> {
        val legacy = RigBuilder.bodyWarps(stance, body, PartId("PartBody"))
        val model = PuppetModel(StandardParameters.all, emptyList(), legacy, emptyList(), emptyList(), null)
        val expected = BindingProjection.retain(model, emptySet()).deformers.map(PuppetIr::deformerToIr)
        val standing = stance.legsFrame?.let { frame ->
            ScaffoldBody.Standing(frame, stance.legs.map { leg ->
                ScaffoldBody.Leg(ScaffoldBody.Joint(leg.hipX, leg.hipY), ScaffoldBody.Joint(leg.kneeX, leg.kneeY),
                    ScaffoldBody.Joint(leg.ankleX, leg.ankleY), leg.floorY)
            }, stance.tuning.kneesInRest.toDouble())
        }
        val actual = ScaffoldBody.build(body, standing, { tr(it) })
        assertEquals(expected, actual.map(PuppetIr::deformerToIr))
        val moving = LegacyBodyMotion.deformers(actual, body, stance, 1f, 100f)
        assertEquals(legacy.map(PuppetIr::deformerToIr), moving.map(PuppetIr::deformerToIr))
        return actual
    }

    private fun bone(id: String, parent: String?, role: BoneRole, side: Side, hx: Float, hy: Float, tx: Float, ty: Float) =
        SkeletonBone(id, id, parent, role, side, hx, hy, tx, ty)
}
