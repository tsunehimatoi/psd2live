package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*
import kotlin.test.*

class EyeLegacyAlgorithmTest {
    private val frame = Bounds(0f, 0f, 40f, 30f)
    private val tuning = EyeLegacyMotion.Tuning(34f, 38f, 75f, 5f, 10f, 10f, 20f)

    @Test
    fun pairedLashUsesSeparateEyesAndPreservesSignedOpenZeros() {
        val bounds = Bounds(0f, 0f, 40f, 20f)
        val white = listOf(Bounds(0f, 10f, 20f, 20f), Bounds(20f, 10f, 40f, 20f))
        val eye = ScaffoldMesh.Geometry.Eye(true, bounds, white, 10f, 0, null, 34f, 38f, 75f)
        val grid = EyeLegacyMotion.blink(floatArrayOf(10f, 18f, 30f, 18f), frame, eye, Side.NONE, 20f)
        assertEquals(listOf(EyeLegacyMotion.leftOpen, EyeLegacyMotion.rightOpen), grid.axes.map { it.parameterId })
        fun cell(left: Int, right: Int) = grid.cells.single { it.coordinate.contentEquals(intArrayOf(left, right)) }.form.positionDeltas
        val closedY = (10f + 10f * 34f / 100f + 10f * 38f / 100f + (18f - 10f) * .75f - 18f) / 30f
        assertEquals(closedY, cell(1, 0)[1], .000001f)
        assertEquals(closedY, cell(0, 1)[3], .000001f)
        assertEquals(0f, cell(0, 1)[1])
        assertEquals(0f, cell(1, 0)[3])

        val negative = EyeLegacyMotion.blink(floatArrayOf(10f, 28f), frame, eye.copy(centroidY = 25f), Side.RIGHT, 20f)
        assertEquals((-0f).toRawBits(), negative.cells.last().form.positionDeltas[1].toRawBits())
    }

    @Test
    fun versionedSourceEmitsOnlyOwnedGeometryChannelsMasksAndParameterRelations() {
        val points = floatArrayOf(12f, 12f, 16f, 12f, 12f, 18f)
        val raw = ScaffoldMesh.RawMesh(points, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
        val mesh = DrawableMesh(points, raw.atlasUvs, raw.triangles)
        val gazeId = ScaffoldHead.gazeId(Side.RIGHT)
        val gaze = Deformer.Warp(gazeId, "Gaze", null, null, 2, 2, true, EyeLegacyMotion.gaze(tuning.copy(gazeX = 0f, gazeY = 0f)))
        val neutral = PuppetModel(emptyList(), emptyList(), listOf(gaze), listOf(
            Drawable(DrawableId("iris"), "iris", gazeId, BlendMode.Normal, emptyList(), mesh, null),
            Drawable(DrawableId("closed"), "closed", null, BlendMode.Normal, emptyList(), mesh, null),
            Drawable(DrawableId("white"), "white", null, BlendMode.Normal, emptyList(), mesh, null),
        ), emptyList(), null)
        val resource = ScaffoldMeshContext.capture(listOf("iris", "closed").map {
            ScaffoldMeshContext.Material(it, it, raw, ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        val measurements = EyeLegacyInputs(20f, tuning, mapOf(
            "iris" to EyeLegacyInputs.Layer(Side.RIGHT, frame, iris = ScaffoldMesh.Geometry.Iris(14f, 14f, 5f, 10f), irisSource = EyeOpeningObservation("iris", "iris", Side.RIGHT, frame, 14f, 14f, "", null, null)),
            "closed" to EyeLegacyInputs.Layer(Side.RIGHT, frame, closedDrawing = true),
        ), resource, listOf(EyeOpeningObservation("white", "white", Side.RIGHT, frame, 14f, 14f, "", null, null)))
        val contract = measurements.encode()
        assertEquals(contract.values, EyeLegacyInputs.decode(contract).encode().values)
        val algorithm = EyeLegacyAlgorithm(EyeLegacyInputs.decode(contract))
        val input: RigIR = PuppetIr.toIr(neutral)
        val definitions = listOf(EyeLegacyMotion.leftOpen, EyeLegacyMotion.rightOpen).map { Parameter(it, it.raw, 0f, 1f, 1f) } +
            listOf(EyeLegacyMotion.gazeX, EyeLegacyMotion.gazeY, EyeLegacyMotion.irisForm).map { Parameter(it, it.raw, -1f, 1f, 0f) }
        val changes = algorithm.canonicalChanges(input, definitions, "Eyes")
        assertEquals(setOf("mesh", "deformer", "parameter", "hierarchy", "relations"), changes.writeKinds())
        val result = changes.apply(input)
        assertSame(input.meshes.last(), result.meshes.last())
        assertEquals(listOf("white"), result.meshes.first().maskedBy)
        val output = PuppetIr.toPuppet(result)
        val track = output.drawables[1].channelGrids[FormChannel.OPACITY]!!
        assertEquals(EyeLegacyMotion.rightOpen, track.axes.single().parameterId)
        assertEquals(listOf(ChannelValue.Scalar(1f), ChannelValue.Scalar(0f)), track.cells.map { it.form })
        val lattice = (output.deformers.single() as Deformer.Warp).geometryGrid!!
        assertEquals(.1f, lattice.cells.last().form.controlPoints[0], .000001f)
        assertEquals(-.2f, lattice.cells.last().form.controlPoints[1], .000001f)
    }
}
