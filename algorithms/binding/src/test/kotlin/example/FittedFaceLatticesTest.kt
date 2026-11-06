package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.fit.FaceLandmarks
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.fit.Landmark
import io.github.psd2live.core.fit.TurnStyle
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class FittedFaceLatticesTest {
    private val face = Bounds(20f, 30f, 140f, 180f)
    private val head = Bounds(0f, 0f, 160f, 200f)
    private val scaffold = ScaffoldHead.build(ScaffoldHead.Input(
        Bounds(0f, 0f, 160f, 320f), head, face, ScaffoldHead.Point(80f, 130f),
        ScaffoldHead.Point(80f, 130f), 0f, featureDisplacement = false,
        regions = listOf(
            ScaffoldHead.Region(ScaffoldHead.Feature.NOSE, Side.NONE, Bounds(65f, 90f, 95f, 120f), "nose"),
            ScaffoldHead.Region(ScaffoldHead.Feature.EYE, Side.LEFT, Bounds(93f, 76f, 126f, 84f), "left eye"),
            ScaffoldHead.Region(ScaffoldHead.Feature.EYE, Side.RIGHT, Bounds(34f, 76f, 67f, 84f), "right eye"),
        ),
    ), { it })
    private val input = PuppetIr.toIr(PuppetModel(
        listOf(Parameter(ParameterId("ParamAngleX"), "Yaw", -45f, 45f, 0f),
            Parameter(ParameterId("ParamAngleY"), "Pitch", -30f, 30f, 0f)),
        listOf(Part(PartId("PartBody"), "Body", emptyList()), Part(PartId("PartHead"), "Head", emptyList()),
            Part(PartId("PartFace"), "Face", emptyList())), scaffold.deformers,
        listOf(Drawable(DrawableId("authored-face"), "Authored face", ScaffoldHead.contourId, BlendMode.Normal,
            emptyList(), null, null, opacity = .7f)), emptyList(), null,
        canvasWidth = 160f, canvasHeight = 320f,
    ))
    private val marks = FaceLandmarks(listOf(
        Landmark("eye.L.inner", "eye", 94f, 80f), Landmark("eye.L.outer", "eye", 125f, 80f),
        Landmark("eye.R.inner", "eye", 66f, 80f), Landmark("eye.R.outer", "eye", 35f, 80f),
    ), (0 until 120).map {
        val angle = 2 * PI * it / 120
        80f + (60 * cos(angle)).toFloat() to 105f + (75 * sin(angle)).toFloat()
    }, emptyMap())

    @Test fun transportedStylesKeepCanonicalLatticeIdentityAndScope() {
        val outputs = (listOf(null) + TurnStyle.PRESETS).map { preset ->
            val source = source(preset)
            val transported = FittedFaceLattices.Inputs.decode(source.encode())
            assertEquals(source.encode(), transported.encode())
            val changes = FittedFaceLattices.changes(input, transported)
            assertEquals(setOf("DeformFaceNinePose", "DeformFaceContour", "DeformNoseShapeBoth", "DeformEyeShapeL"),
                changes.deformers.updated.map { it.before.id }.toSet())
            val actual = changes.apply(input)
            assertSame(input.meshes.single(), actual.meshes.single())
            assertEquals(input, actual.copy(deformers = input.deformers))
            assertEquals(input.deformers.map { it.id }, actual.deformers.map { it.id })
            assertSame(input.deformers.first(), actual.deformers.first())
            val skin = PuppetIr.toPuppet(actual).deformers.single { it.id == ScaffoldHead.contourId } as Deformer.Warp
            assertEquals(ScaffoldHead.faceId, skin.parent)
            assertEquals(16, skin.rows)
            assertEquals(16, skin.columns)
            val grid = assertNotNull(skin.geometryGrid)
            assertContentEquals(FaceTurn.YAW_KEYS, grid.axes[0].keys)
            assertContentEquals(FaceTurn.PITCH_KEYS, grid.axes[1].keys)
            assertEquals(45, grid.cells.size)
            assertTrue(grid.cells.all { cell -> cell.form.controlPoints.all(Float::isFinite) })
            actual.deformers
        }
        assertEquals(outputs.size, outputs.distinct().size)
    }

    @Test fun unavailableStyleLeavesLatticesUnchangedAndRetainsRawDepthTurn() {
        val source = source(null)
        val withoutEyes = source.copy(source = source.source.copy(landmarks = FaceLandmarks(emptyList(), marks.contour, emptyMap())))
        val transported = FittedFaceLattices.Inputs.decode(withoutEyes.encode())
        assertTrue(FittedFaceLattices.changes(input, transported).deformers.isEmpty)
        assertEquals(source.source.depthTurn().delta(84f, 88f, HeadShell.SKIN, 45f, 30f),
            transported.source.depthTurn().delta(84f, 88f, HeadShell.SKIN, 45f, 30f))
    }

    private fun source(preset: String?) = FittedFaceLattices.Inputs(
        FittedHeadTurnSource(80f, 95f, 60f, 80f, 180f, 0.45f, 1f, marks, TurnStyle.Setting(preset = preset)),
        head, DepthTurnMotion.Container(80f, 130f, 1f, 5f, 2f, 2f),
        face, scaffold.frames, setOf(Side.LEFT), emptySet(),
    )
}
