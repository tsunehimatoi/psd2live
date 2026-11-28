package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FormShadowAlgorithmTest {
    @Test
    fun `form shadow preserves receiver alpha and raster coordinates`() {
        val (rig, rgba) = sampleRig()
        val changes = generate(rig, FormShadowConfig(targets = listOf("face"), softnessOverride = 0f))
        val cutter = changes.rasters.single { it.mesh.opacity == 0f }
        val shadow = changes.rasters.single { it.mesh.invertMask }
        assertEquals(listOf(cutter.mesh.id), shadow.mesh.maskedBy)
        assertEquals(rig.meshes.single().offsets, shadow.mesh.offsets)
        assertEquals(rig.meshes.single().geometry!!.positions, shadow.mesh.geometry!!.positions)
        assertEquals(listOf(0f, 0f, 32f, 0f, 32f, 20f, 0f, 20f), shadow.mesh.geometry!!.uvs.shared().toList())
        assertEquals(rgba.indices.filter { it % 4 == 3 }.map { rgba[it] },
            shadow.pixels.read().filterIndexed { index, _ -> index % 4 == 3 })
    }

    @Test
    fun `cutter keys keep the authored angle values and the proxy and softness affect its mask`() {
        val (rig, _) = sampleRig()
        val config = FormShadowConfig(targets = listOf("face"), softnessOverride = 0f)
        val hard = generate(rig, config).rasters.single { it.mesh.opacity == 0f }
        val soft = generate(rig, config.copy(softnessOverride = 4f)).rasters.single { it.mesh.opacity == 0f }
        val cylinder = generate(rig, config.copy(proxy = FormProxyKind.CYLINDER)).rasters.single { it.mesh.opacity == 0f }
        assertEquals(listOf(-12f, 2f, 40f), hard.mesh.offsets!!.axes.single().keys.shared().toList())
        assertTrue(soft.pixels.read().anyAlpha { it in 1..254 })
        assertTrue(!hard.pixels.read().anyAlpha { it in 1..254 })
        assertNotEquals(hard.mesh.offsets, cylinder.mesh.offsets)
    }

    private fun ByteArray.anyAlpha(predicate: (Int) -> Boolean): Boolean =
        indices.step(4).any { predicate(this[it + 3].toInt() and 0xff) }

    private fun generate(rig: RigIR, config: FormShadowConfig) = FormShadowAlgorithm().changes(
        rig,
        RigModifierInstance("side", "shadow.form", 1, listOf(ModifierTarget("mesh", "face")), config = config.toJson()),
        ModifierAlgorithmContext.Empty,
    ) {}

    private fun sampleRig(): Pair<RigIR, ByteArray> {
        val width = 32
        val height = 20
        val rgba = ByteArray(width * height * 4)
        for (y in 3 until 17) for (x in 5 until 27) {
            rgba[(y * width + x) * 4 + 3] = 255.toByte()
        }
        val geometry = MeshGeometry(
            Floats.wrap(floatArrayOf(0f, 0f, 32f, 0f, 32f, 20f, 0f, 20f)),
            Floats.wrap(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)),
            Ints.wrap(intArrayOf(0, 1, 2, 0, 2, 3)),
        )
        val mesh = Mesh("face", "Face", null, geometry = geometry, offsets = null, tile = "faceTile")
        return RigIR(
            canvas = Canvas(96f, 96f),
            parameters = listOf(Parameter("ParamAngleX", "Angle X", -12f, 40f, 2f)),
            meshes = listOf(mesh),
            textures = Textures(uvsAddressPages = false, tileArt = listOf(TileArt("faceTile", width, height, Bytes.wrap(rgba)))),
        ) to rgba
    }
}
