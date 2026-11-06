package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.algorithms.binding.ScaffoldHead.Feature
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.ScaffoldParts.Slot
import io.github.psd2live.algorithms.binding.Side
import org.umamo.runtime.model.*
import kotlin.test.*

class ScaffoldHeadTest {
    private val body = Bounds(0f, 0f, 200f, 400f)
    private val head = Bounds(40f, 20f, 160f, 140f)
    private val face = Bounds(60f, 40f, 140f, 120f)
    private val leftEye = Bounds(70f, 55f, 90f, 75f)
    private val rightEye = Bounds(110f, 55f, 130f, 75f)

    @Test fun headBranchUsesDistinctCanvasPivotAndAlignedHeadCoordinates() {
        val result = ScaffoldHead.build(input(), { it })
        val rotation = result.deformers.filterIsInstance<Deformer.Rotation>().single()
        assertEquals(ScaffoldHead.breathId, rotation.parent)
        assertEquals(12f, rotation.baseAngle)
        val pivot = assertNotNull(rotation.geometryGrid).cells.single().form
        assertEquals(0.45f, pivot.originX)
        assertEquals(0.25f, pivot.originY)
        assertEquals(0f, pivot.angle)
        assertEquals(1f, pivot.scale)
        val points = lattice(result, "DeformHeadContainer")
        assertContentEquals(floatArrayOf(-60f, -70f), points.take(2).toFloatArray())
        assertContentEquals(floatArrayOf(60f, 50f), points.takeLast(2).toFloatArray())
        assertEquals(Bounds(100f, 90f, 101f, 91f), result.frames[rotation.id])
        val facePoints = lattice(result, "DeformFaceNinePose")
        assertContentEquals(floatArrayOf(1f / 6f, 1f / 6f), facePoints.take(2).toFloatArray())
        assertContentEquals(floatArrayOf(5f / 6f, 5f / 6f), facePoints.takeLast(2).toFloatArray())
    }

    @Test fun featureAndHairParentsFollowRestRoutingAndPreserveOrder() {
        val result = ScaffoldHead.build(input().copy(
            regions = listOf(
                ScaffoldHead.Region(Feature.IRIS, Side.LEFT, leftEye, "iris", "gaze"),
                ScaffoldHead.Region(Feature.EYE, Side.LEFT, leftEye, "left eye"),
                ScaffoldHead.Region(Feature.EYE, Side.RIGHT, rightEye, "right eye"),
                ScaffoldHead.Region(Feature.MOUTH, Side.NONE, face, "mouth"),
            ),
            frontHair = ScaffoldHead.Hair(head, sway = false),
            backHair = ScaffoldHead.Hair(head, sway = true),
        ), { it })
        val afterMainChain = result.deformers.drop(6)
        assertEquals(listOf("DeformEyes", "DeformEyeShapeL", "DeformEyeShapeR", "DeformMouthShapeBoth",
            "DeformIrisPreserveL", "DeformEyeGazeL", "DeformHairFrontFollow", "DeformHairBackFollow", "DeformHairBackPhysics"),
            afterMainChain.map { it.id.raw })
        assertEquals(listOf("DeformFeatureDisplacement", "DeformEyes", "DeformEyes", "DeformFeatureDisplacement",
            "DeformEyeShapeL", "DeformIrisPreserveL", "DeformHeadContainer", "DeformHeadContainer", "DeformHairBackFollow"),
            afterMainChain.map { it.parent?.raw })
        assertEquals(leftEye.union(rightEye).expanded(0.04f), result.frames[DeformerId("DeformEyes")])
        assertContentEquals(floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f), lattice(result, "DeformEyeGazeL").take(6).toFloatArray())
    }

    @Test fun sourcePartTreeKeepsEmptySlotsAndPlacesUnknownAfterBody() {
        val unknown = DrawableId("unknown")
        val body = DrawableId("body")
        val faceA = DrawableId("faceA")
        val faceB = DrawableId("faceB")
        val tree = ScaffoldParts.build(listOf(
            ScaffoldParts.Material(unknown, Slot.UNKNOWN, 0),
            ScaffoldParts.Material(faceB, Slot.FACE, 2),
            ScaffoldParts.Material(body, Slot.BODY, 3),
            ScaffoldParts.Material(faceA, Slot.FACE, 1),
        ), { it })
        assertEquals(listOf("PartHead", "PartHairBack", "PartFace", "PartHairFront", "PartHeadAccessories", "PartExtra", "PartBody"),
            tree.parts.map { it.id.raw })
        assertEquals(listOf("PartHead", "PartExtra", "PartBody").map { OrgChild.Part(PartId(it)) }, tree.rootChildren)
        assertEquals(listOf(faceA, faceB).map { OrgChild.Drawable(it) }, tree.parts.single { it.id == Slot.FACE.id }.children)
        assertEquals(listOf(body, unknown).map { OrgChild.Drawable(it) }, tree.parts.single { it.id == Slot.BODY.id }.children)
    }

    private fun input() = ScaffoldHead.Input(
        body, head, face, ScaffoldHead.Point(100f, 90f), ScaffoldHead.Point(90f, 100f),
        12f, featureDisplacement = true, regions = emptyList(),
    )

    private fun lattice(result: ScaffoldHead.Result, id: String): FloatArray {
        val warp = result.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw == id }
        val grid = assertNotNull(warp.geometryGrid)
        assertTrue(grid.axes.isEmpty())
        return grid.cells.single().form.controlPoints
    }
}
