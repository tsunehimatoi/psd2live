package io.github.psd2live.tools

import io.github.psd2live.algorithms.binding.MeshSplitAlgorithm
import io.github.psd2live.core.AtlasPagePng
import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.meshSplitFixture
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.model.Bytes
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic raster-export comparison, gated by PSD2LIVE_TOOLS=1; no source PSD or proprietary renderer. */
class MeshSplitVisualTool {
    @Test fun masksTexturesAndGlueAtBlendEndpoints() {
        requireTools()
        val out = output("mesh-split")
        val fixture = meshSplitFixture()
        val textures = fixture.textures.copy(pages = fixture.textures.pages.mapIndexed { page, texture ->
            val art = BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until art.height) for (x in 0 until art.width) {
                val alpha = if ((x / 3 + y / 3) % 2 == 0) 255 else 150
                art.setRGB(x, y, (alpha shl 24) or ((x * 255 / art.width) shl 16) or
                    ((y * 255 / art.height) shl 8) or if (page == 0) 200 else 60)
            }
            texture.copy(png = Bytes.wrap(AtlasPagePng.canonical(art)))
        })
        val instance = RigModifierInstance("split", "mesh.split", 1, listOf(ModifierTarget("mesh", "islands")))
        val frames = mutableListOf<Pair<String, BufferedImage>>()
        val report = StringBuilder("Renderer: IrFrameRenderer (CPU deformation + raster export)\n")
        var differingTotal = 0
        for (inverted in listOf(false, true)) {
            val before = fixture.copy(textures = textures, meshes = fixture.meshes.map {
                if (it.id == "consumer") it.copy(invertMask = inverted) else it
            })
            val after = MeshSplitAlgorithm().evaluate(before, instance, ModifierAlgorithmContext()) {}
            val frame = FrameSpec(-.5f, -.5f, 6f, 9f, 240, 360, 0xffeeeeee.toInt())
            IrFrameRenderer.open(before, false).use { original ->
                IrFrameRenderer.open(after, false).use { split ->
                    for (blend in listOf(0f, .5f, 1f)) {
                        val a = original.render(mapOf("blend" to blend), 0f, frame)
                        val b = split.render(mapOf("blend" to blend), 0f, frame)
                        assertTrue(a.argb.any { it != frame.background }, "The synthetic fixture must render visible pixels")
                        val difference = IntArray(a.argb.size) { index ->
                            if (a.argb[index] == b.argb[index]) 0xff000000.toInt() else 0xffff00ff.toInt()
                        }
                        val differing = a.argb.indices.count { a.argb[it] != b.argb[it] }
                        differingTotal += differing
                        val label = "${if (inverted) "inverted" else "normal"} blend=$blend"
                        report.appendLine("$label: $differing / ${a.argb.size} pixels differ")
                        fun image(pixels: IntArray) = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_ARGB).apply {
                            setRGB(0, 0, width, height, pixels, 0, width)
                        }
                        frames += "Before $label" to image(a.argb)
                        frames += "After $label" to image(b.argb)
                        frames += "Difference $label" to image(difference)
                    }
                }
            }
        }
        sheet(frames, out.resolve("comparison.png"), columns = 3)
        out.resolve("report.txt").writeText(report.toString())
        println(report)
        assertEquals(0, differingTotal, "Synthetic split rendering changed; inspect build/tools/mesh-split/comparison.png")
    }
}
