package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShadowAlgorithmTest {
    private fun solidRgba(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            out[i * 4] = r.toByte()
            out[i * 4 + 1] = g.toByte()
            out[i * 4 + 2] = b.toByte()
            out[i * 4 + 3] = a.toByte()
        }
        return out
    }

    private fun sampleRig(): RigIR {
        val faceRgba = solidRgba(64, 64, 245, 210, 195, 255)
        val bangRgba = solidRgba(64, 64, 80, 90, 120, 255)
        val quadPos = Floats.wrap(floatArrayOf(0f, 0f, 64f, 0f, 64f, 64f, 0f, 64f))
        val quadUv = Floats.wrap(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        val quadIdx = Ints.wrap(intArrayOf(0, 1, 2, 0, 2, 3))
        val geom = MeshGeometry(quadPos, quadUv, quadIdx)
        return RigIR(
            canvas = Canvas(256f, 256f),
            parameters = listOf(
                Parameter("ParamAngleX", "Angle X", -30f, 30f, 0f),
                Parameter("ParamAngleY", "Angle Y", -30f, 30f, 0f),
            ),
            meshes = listOf(
                Mesh("face", "Face", null, geometry = geom, offsets = null, drawOrder = 500f, tile = "tile_face"),
                Mesh("bang1", "Bang 1", null, geometry = geom, offsets = null, drawOrder = 520f, tile = "tile_bang1"),
                Mesh("bang2", "Bang 2", null, geometry = geom, offsets = null, drawOrder = 521f, tile = "tile_bang2"),
            ),
            rootChildren = listOf(ChildRef.MeshRef("face"), ChildRef.MeshRef("bang1"), ChildRef.MeshRef("bang2")),
            textures = Textures(
                tileArt = listOf(
                    TileArt("tile_face", 64, 64, Bytes.wrap(faceRgba)),
                    TileArt("tile_bang1", 64, 64, Bytes.wrap(bangRgba)),
                    TileArt("tile_bang2", 64, 64, Bytes.wrap(bangRgba)),
                )
            ),
        )
    }

    @Test
    fun `raster generation is deterministic and expands for blur`() {
        val src = solidRgba(32, 32, 200, 150, 120, 255)
        val a = ShadowRasterGen.generateSolidShadow(32, 32, src, blurPx = 8f, rgb = 0x886677)
        val b = ShadowRasterGen.generateSolidShadow(32, 32, src, blurPx = 8f, rgb = 0x886677)
        assertEquals(a.digest, b.digest)
        assertTrue(a.width > 16 && a.height > 16)
        assertTrue(a.padLeftPx > 0f)
    }

    @Test
    fun `cast shadow uses isolated offscreen part so overlapping bangs do not double darken`() {
        val rig = sampleRig()
        val cfg = CastShadowConfig(name = "Bangs", casters = listOf("bang1", "bang2"), receivers = listOf("face"))
        val instance = RigModifierInstance("m1", "shadow.cast", 1, listOf(ModifierTarget("mesh", "bang1")), config = cfg.toJson())
        val ctx = ModifierAlgorithmContext(buildJsonObject {
            put(ShadowWorldConfig.CONTEXT_KEY, ShadowWorldConfig(compositeMode = ShadowCompositeMode.OFFSCREEN).toJson())
        })
        val changes = CastShadowAlgorithm().changes(rig, instance, ctx) {}
        assertEquals(2, changes.rasters.size)
        assertTrue(changes.rasters.all { it.mesh.opacity == 1f && it.mesh.blend == ColorBlend.NORMAL })
        assertEquals(1, changes.parts.added.size)
        val part = changes.parts.added.single()
        assertEquals(GroupMode.ISOLATED, part.groupMode)
        assertEquals(ColorBlend.MULTIPLY, part.composite.blend)
        assertEquals(listOf("face"), part.composite.maskedBy)
    }

    @Test
    fun `cast shadow compat merged mode omits isolated part and masks meshes directly`() {
        val rig = sampleRig()
        val cfg = CastShadowConfig(name = "Bangs", casters = listOf("bang1", "bang2"), receivers = listOf("face"))
        val instance = RigModifierInstance("m1", "shadow.cast", 1, listOf(ModifierTarget("mesh", "bang1")), config = cfg.toJson())
        val ctx = ModifierAlgorithmContext(buildJsonObject {
            put(ShadowWorldConfig.CONTEXT_KEY, ShadowWorldConfig(compositeMode = ShadowCompositeMode.COMPAT_MERGED).toJson())
        })
        val changes = CastShadowAlgorithm().changes(rig, instance, ctx) {}
        assertEquals(0, changes.parts.added.size)
        assertTrue(changes.rasters.all { it.mesh.blend == ColorBlend.MULTIPLY && it.mesh.maskedBy == listOf("face") })
    }

    @Test
    fun `form shadow produces invert mask crescent and responds to azimuth`() {
        val rig = sampleRig()
        val cfg = FormShadowConfig(name = "Side", targets = listOf("face"), coverage = 0.3f, cutterExpandPx = 3f)
        val instance = RigModifierInstance("m2", "shadow.form", 1, listOf(ModifierTarget("mesh", "face")), config = cfg.toJson())

        val leftCtx = ModifierAlgorithmContext(buildJsonObject {
            put(ShadowWorldConfig.CONTEXT_KEY, ShadowWorldConfig(azimuth = -45f).toJson())
        })
        val rightCtx = ModifierAlgorithmContext(buildJsonObject {
            put(ShadowWorldConfig.CONTEXT_KEY, ShadowWorldConfig(azimuth = 45f).toJson())
        })
        val leftChanges = FormShadowAlgorithm().changes(rig, instance, leftCtx) {}
        val rightChanges = FormShadowAlgorithm().changes(rig, instance, rightCtx) {}

        val cutter = leftChanges.rasters.first { it.mesh.opacity == 0f }.mesh
        val form = leftChanges.rasters.first { it.mesh.blend == ColorBlend.MULTIPLY }.mesh
        assertTrue(form.invertMask)
        assertEquals(listOf(cutter.id), form.maskedBy)

        val leftCutterDelta = cutter.offsets!!.cells.first().form.deltas.shared()[0]
        val rightCutterDelta = rightChanges.rasters.first { it.mesh.opacity == 0f }.mesh.offsets!!.cells.first().form.deltas.shared()[0]
        assertNotEquals(leftCutterDelta, rightCutterDelta)
    }
}
