package example

import io.github.psd2live.algorithms.binding.SeeThroughBinding
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.*
import org.umamo.runtime.model.DrawableId
import kotlin.math.abs
import kotlin.test.*

class SeeThroughPixelsTest {
    private fun quad(id: String, width: Float, height: Float, order: Float, color: Rgb) =
        Mesh(id, id, null, geometry = MeshGeometry(
            Floats.values(0f, 0f, width, 0f, 0f, height, width, height),
            Floats.values(.5f, .5f, .5f, .5f, .5f, .5f, .5f, .5f), Ints.values(0, 1, 2, 1, 3, 2)),
            offsets = null, page = 0, drawOrder = order, multiply = color)

    @Test fun rendersEyeWindowOnlyInsideTheOriginalPositiveOrInvertedMask() {
        for (inverted in listOf(false, true)) for (opacity in listOf(0f, .6f, 1f)) {
            val meshes = listOf(
                quad("background", 16f, 16f, 0f, Rgb.Black),
                quad("eye", 16f, 8f, 100f, Rgb(0f, 1f, 0f)),
                quad("old-mask", 8f, 16f, 50f, Rgb.White).copy(visible = false),
                quad("hair", 16f, 16f, 200f, Rgb(1f, 0f, 0f))
                    .copy(maskedBy = listOf("old-mask"), invertMask = inverted))
            val source = RigIR(Canvas(16f, 16f), emptyList(), meshes = meshes,
                rootChildren = meshes.map { ChildRef.MeshRef(it.id) },
                textures = Textures(pages = listOf(TexturePage(1, 1))))
            val built = SeeThroughBinding({ "$it copy" }).apply(PuppetIr.toPuppet(source),
                listOf(SeeThroughBinding.Candidate("eye", true, listOf("hair"))),
                SeeThroughBinding.Settings(false, true, opacity, 10, compositeMasks = true),
                { DrawableId("generated:${it.raw}") })
            assertTrue(built.notes.isEmpty())
            val rig = PuppetIr.toIr(built.model).copy(textures = source.textures)
            val pose = PoseGeometry(rig.meshes.associate { it.id to it.geometry!!.positions.shared() },
                rig.meshes.associate { it.id to it.opacity }, rig.meshes.associate { it.id to it.drawOrder })
            val colors = IrColors(rig)
            val pixels = SoftwareRasterizer(rig) { intArrayOf(-1) }.render(pose, colors.at(emptyMap()),
                FrameSpec(0f, 0f, 16f, 16f, 16, 16), parts = colors.parts(emptyMap())).argb
            for (y in listOf(3, 11)) for (x in listOf(3, 11)) {
                val oldMask = (x < 8) != inverted
                val eye = y < 8
                val expectedRed = if (oldMask) (if (eye) 1f - opacity else 1f) else 0f
                val expectedGreen = if (eye) (if (oldMask) opacity else 1f) else 0f
                val pixel = pixels[y * 16 + x]
                val label = "inverted=$inverted opacity=$opacity pixel=($x,$y)"
                assertTrue(abs(((pixel ushr 16) and 255) - expectedRed * 255) <= 1, "$label red")
                assertTrue(abs(((pixel ushr 8) and 255) - expectedGreen * 255) <= 1, "$label green")
                assertEquals(0, pixel and 255, "$label blue")
                assertEquals(255, pixel ushr 24, "$label alpha")
            }
        }
    }
}
