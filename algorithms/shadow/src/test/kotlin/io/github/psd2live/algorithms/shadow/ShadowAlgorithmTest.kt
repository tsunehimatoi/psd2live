package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.*
import io.github.psd2live.bridge.PuppetIr
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
            authoring = Authoring(runtime = "Cubism53"),
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
        val reshaped = ShadowRasterGen.generateSolidShadow(16, 64, src, blurPx = 8f, rgb = 0x886677)
        assertNotEquals(a.width, reshaped.width)
    }

    @Test
    fun `a rotated tile maps to source pixels and the blur does not inherit atlas page scale`() {
        val mesh = sampleRig().meshes.first()
        val geometry = requireNotNull(mesh.geometry)
        val placement = TilePlacement(0, 130f, 65f, 0.5f, 0.75f, 90f)
        fun packed(width: Int, height: Int): Mesh {
            val uv = geometry.uvs.toArray()
            for (i in uv.indices step 2) {
                val x = uv[i] * 64f
                val y = uv[i + 1] * 64f
                uv[i] = (placement.x - y * placement.scaleY) / width
                uv[i + 1] = (placement.y + x * placement.scaleX) / height
            }
            return mesh.copy(geometry = geometry.copy(uvs = Floats.wrap(uv)))
        }
        val raster = ShadowRasterGen.generateSolidShadow(64, 64, solidRgba(64, 64, 200, 150, 120, 255), 4f, 0x886677)
        val expanded = listOf(256, 1024).map { size ->
            val caster = packed(size, size / 2)
            val rig = sampleRig().copy(textures = Textures(
                tilePages = listOf(PageSize(size, size / 2)),
                tiles = listOf(TextureTile(mesh.tile!!, mesh.name, 64, 64, placement)),
            ))
            val local = ShadowGeometry.rasterPixels(rig, caster, 64, 64)!!
            assertEquals(64f, local[2], 1e-4f)
            assertEquals(64f, local[5], 1e-4f)
            ShadowGeometry.paddedGeometry(caster.geometry!!, local, 64, 64, raster)
        }
        for (geometryWithPadding in expanded) {
            assertEquals(-raster.padLeftPx, geometryWithPadding.positions.shared()[0], 1e-4f)
            assertEquals(64f + raster.padRightPx, geometryWithPadding.positions.shared()[2], 1e-4f)
            assertEquals(raster.width.toFloat(), geometryWithPadding.uvs.shared()[2], 1e-4f)
            assertEquals(raster.height.toFloat(), geometryWithPadding.uvs.shared()[5], 1e-4f)
        }
    }

    @Test
    fun `the same canvas projection survives a scaled and rotated parent frame`() {
        val plain = sampleRig().copy(parameters = emptyList())
        val rotation = Deformer.Rotation("rotation", "Rotation", null, null, 0f,
            KeyGrid(emptyList(), listOf(KeyCell(Ints.wrap(intArrayOf()), Pivot(100f, 30f, 37f, 2.5f)))))
        val caster = plain.meshes[1].copy(parent = rotation.id)
        val rig = plain.copy(meshes = plain.meshes.map { if (it.id == caster.id) caster else it }, deformers = listOf(rotation))
        val geom = requireNotNull(caster.geometry)
        val (shifted, grid) = ShadowGeometry.projectCast(caster, geom, ShadowOffset2D(8f, 12f), 0f, 0f, rig, PuppetIr.toPuppet(rig))
        assertEquals(null, grid)
        val mapping = ShadowGeometry.spaceMapping(rig, caster)
        val before = mapping.localToWorld(geom.positions.shared())
        val after = mapping.localToWorld(shifted.positions.shared())
        for (i in before.indices step 2) {
            assertEquals(8f, after[i] - before[i], 1e-4f)
            assertEquals(-12f, after[i + 1] - before[i + 1], 1e-4f)
        }
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
    fun `legacy mode composites one receiver across the union of independent caster masks`() {
        val rig = sampleRig()
        val cfg = CastShadowConfig(name = "Bangs", casters = listOf("bang1", "bang2"), receivers = listOf("face"))
        val instance = RigModifierInstance("m1", "shadow.cast", 1, listOf(ModifierTarget("mesh", "bang1")), config = cfg.toJson())
        val ctx = ModifierAlgorithmContext(buildJsonObject {
            put(ShadowWorldConfig.CONTEXT_KEY, ShadowWorldConfig(compositeMode = ShadowCompositeMode.COMPAT_MERGED).toJson())
        })
        val changes = CastShadowAlgorithm().changes(rig, instance, ctx) {}
        assertEquals(0, changes.parts.added.size)
        val masks = changes.rasters.filter { it.mesh.opacity == 0f }
        assertEquals(2, masks.size)
        val clipped = changes.rasters.single { it.mesh.blend == ColorBlend.MULTIPLY }
        assertEquals("face", clipped.owner.id)
        assertEquals(masks.map { it.mesh.id }, clipped.mesh.maskedBy)
    }

    @Test
    fun `auto selects a legacy representation on a Cubism 5 point 0 project`() {
        val rig = sampleRig().let { it.copy(authoring = it.authoring.copy(runtime = "Cubism50")) }
        val cfg = CastShadowConfig(casters = listOf("bang1"), receivers = listOf("face"))
        val instance = RigModifierInstance("m1", "shadow.cast", 1, listOf(ModifierTarget("mesh", "bang1")), config = cfg.toJson())
        val changes = CastShadowAlgorithm().changes(rig, instance, ModifierAlgorithmContext.Empty) {}
        assertTrue(changes.parts.added.isEmpty())
        assertEquals(1, changes.rasters.count { it.mesh.blend == ColorBlend.MULTIPLY })
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
