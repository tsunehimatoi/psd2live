package io.github.psd2live.core

import io.github.psd2live.core.fit.MouthFit
import io.github.psd2live.core.fit.MouthOptions
import io.github.psd2live.core.fit.MouthRig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.scenario.Studio
import io.github.psd2live.scenario.Oracles
import io.github.psd2live.scenario.exportMatchesEditor
import io.github.psd2live.scenario.req
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouthOutlineContourTest {
    @TempDir lateinit var temp: Path

    @Test fun generatedOutlinesSurviveRebuildHistorySaveAndCubismReadback() = Studio.run(
        temp, checks = setOf(Studio.Check.REPLAY, Studio.Check.UNDO),
    ) {
        importPsd(Characters.mouthPsd(temp), PipelineConfig(
            atlasSize = 256, generatePhysics = false, exportMoc3 = false, mouthOutlineEnabled = true,
        ))
        assertEquals(2, now.model.analysis.layers.count { MouthLipLayer.isLipId(it.source.id.raw) })
        edit("change the closed drawing", "settings_update", req("changes" to mapOf("mouthShape" to "flat")))
        val before = puppet
        reopen()
        Oracles.sameRig("reopen", "mouth outlines", before, puppet)
        exportMatchesEditor(this)
    }

    private fun rig(curve: MouthCurve = MouthCurve.preset("smile")): MouthRig {
        val upper = List(MouthFit.SAMPLES) { i ->
            val u = MouthFit.u(i)
            if (i == 0 || i == MouthFit.SAMPLES - 1) 0f else -0.18f - 0.10f * sqrt(u * (1f - u))
        }
        val lower = List(MouthFit.SAMPLES) { i ->
            val u = MouthFit.u(i)
            if (i == 0 || i == MouthFit.SAMPLES - 1) 0f else 0.15f + 0.60f * sqrt(u * (1f - u)) + 0.02f * u
        }
        return MouthRig(MouthFit(0f, 0f, 1f, 0f, 100f, upper, lower), MouthOptions(exactClose = true, curve = curve))
    }

    @Test fun steepCornersCloseAlongOneContinuousSeamAtAndBetweenSamples() {
        val rig = rig()
        for (i in 0..384) {
            val (upper, lower) = rig.edges(rig.pose(0f, 0f), i / 384f)
            assertEquals(upper, lower, 1e-5f, "closed gap at $i")
        }
        val seam = (0..384).map { rig.edges(rig.pose(0f, 0f), it / 384f).first }
        assertTrue(seam.zipWithNext().all { (a, b) -> abs(b - a) < 0.002f }, "a corner folds away from the closed seam")
        assertTrue(seam.all { it >= -1e-5f }, "open contour leaves a hook outside the closed drawing")
    }

    @Test fun closedFormsInterpolateOnlyTheTargetContourWithoutSourceSamplingResidue() {
        val rig = rig()
        for (form in listOf(-1f, 0f, 1f)) {
            val pose = rig.pose(form, 0f)
            for (i in 0 until 64) {
                val a = rig.edges(pose, i / 64f)
                val b = rig.edges(pose, (i + 1) / 64f)
                for (t in listOf(0.25f, 0.5f, 0.75f)) {
                    val actual = rig.edges(pose, (i + t) / 64f)
                    assertEquals(a.first + (b.first - a.first) * t, actual.first, 1e-6f)
                    assertEquals(a.second + (b.second - a.second) * t, actual.second, 1e-6f)
                }
            }
        }
    }

    @Test fun theClosedDrawingFollowsTheSelectedCurveInsteadOfTheOpenContourMidpoint() {
        for (name in MouthCurve.presets) {
            val curve = MouthCurve.preset(name)
            val rig = rig(curve)
            for (i in 0..64) {
                val u = i / 64f
                val expected = curve.yAt(u) * rig.fit.opening * 0.6f
                assertEquals(expected, rig.edges(rig.pose(0f, 0f), u).first, 1e-5f, "$name at $u")
            }
        }
    }

    @Test fun theOriginalOpenContourSurvivesResamplingAndTheOpeningNeverInverts() {
        val rig = rig()
        for (i in 0..192) {
            val u = i / 192f
            val (upper, lower) = rig.edges(rig.pose(0f, 1f), u)
            assertEquals(MouthFit.sample(rig.fit.upper, u), upper, 1e-6f)
            assertEquals(MouthFit.sample(rig.fit.lower, u), lower, 1e-6f)
            for (open in listOf(0f, 0.05f, 0.15f, 0.35f, 0.5f, 0.75f, 1f)) {
                val edges = rig.edges(rig.pose(0f, open), u)
                assertTrue(edges.first <= edges.second, "inverted opening at $u, $open")
            }
        }
    }
}
