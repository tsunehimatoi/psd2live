package io.github.psd2live.ui

import io.github.psd2live.ui.views.ParameterPanelRow
import io.github.psd2live.ui.views.buildParameterPanelRows
import org.umamo.runtime.model.*
import kotlin.test.*

class SelectedParameterTest {
    private val a = ParameterId("A")
    private val b = ParameterId("B")
    private fun mesh(id: String, keys: FloatArray) = Drawable(
        id = DrawableId(id), name = id, parentDeformerId = null, blendMode = BlendMode.Normal,
        maskedBy = emptyList(), mesh = null, geometryGrid = null,
        channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(listOf(KeyformAxis(a, keys)), emptyList()))),
    )
    private fun model() = PuppetModel(
        parameters = listOf(Parameter(a, "A", -1f, 1f, 0f), Parameter(b, "B", -1f, 1f, 0f)),
        parts = emptyList(), deformers = listOf(Deformer.Rotation(
            DeformerId("rotation"), "Rotation", null, null, 0f, null,
            blendShapes = listOf(BlendShapeBinding(b, floatArrayOf(0f, 1f), 0, listOf(null, null))),
        )), drawables = listOf(mesh("mesh1", floatArrayOf(-1f, 0f)), mesh("mesh2", floatArrayOf(0f, 1f))),
        rootChildren = emptyList(), rootPartId = null,
        parameterTree = listOf(ParameterNode.Group(ParameterGroupId("folder"), "Folder", false, listOf(ParameterNode.Param(a), ParameterNode.Param(b)))),
        parameterLinks = listOf(ParameterLink(a, b)),
    )
    @Test fun selectedMarksDoNotIncludeOtherMeshes() {
        val model = model()
        assertEquals(listOf(-1f, 0f, 1f), model.parameterKeyMarks().getValue(a).gridKeys)
        assertEquals(listOf(-1f, 0f), model.parameterKeyMarks(ParameterKeyOwner("mesh", "mesh1")).getValue(a).gridKeys)
        assertEquals(setOf(b), model.parameterKeyMarks(ParameterKeyOwner("deformer", "rotation")).keys)
        assertEquals(listOf(0f, 1f), model.parameterKeyMarks(ParameterKeyOwner("deformer", "rotation")).getValue(b).blendKeys)
        assertTrue(model.parameterKeyMarks(ParameterKeyOwner("mesh", "missing")).isEmpty())
    }
    @Test fun selectionResolvesLayerMappingAndDeformerPriority() {
        val model = model()
        val mapping = mapOf("mesh1" to "psd-layer")
        assertEquals(ParameterKeyOwner("mesh", "mesh1"), model.selectedParameterOwner("psd-layer", null, mapping))
        assertEquals(ParameterKeyOwner("deformer", "rotation"), model.selectedParameterOwner("psd-layer", "rotation", mapping))
        assertNull(model.selectedParameterOwner(null, null, emptyMap()))
        assertNull(model.selectedParameterOwner("missing", null, mapping))
    }
    @Test fun relatedFilterKeepsLinkedPartnerAndOpensFolderWithoutChangingIt() {
        val model = model()
        val rows = buildParameterPanelRows(model, "", mapOf("folder" to false), setOf(b))
        assertEquals(2, rows.size)
        assertTrue(assertIs<ParameterPanelRow.Folder>(rows.first()).open)
        val pair = assertIs<ParameterPanelRow.Linked>(rows.last())
        assertEquals(a, pair.horizontal.id)
        assertEquals(b, pair.vertical.id)
        assertFalse((model.parameterTree.single() as ParameterNode.Group).initiallyOpen)
        assertTrue(buildParameterPanelRows(model, "", emptyMap(), emptySet()).isEmpty())
        assertTrue(buildParameterPanelRows(model, "unmatched", emptyMap(), setOf(b)).isEmpty())
        assertEquals(1, buildParameterPanelRows(model, "", mapOf("folder" to false)).size)
    }

    @Test fun explicitParameterPointsReplaceSliderMarksWithoutChangingComponentMarks() {
        val model = model()
        val authored = model.copy(parameters = model.parameters.map { if (it.id == a) it.copy(keys = listOf(0.25f)) else it })
        assertEquals(listOf(0.25f), authored.parameterKeyMarks().getValue(a).gridKeys)
        assertEquals(listOf(-1f, 0f), authored.parameterKeyMarks(ParameterKeyOwner("mesh", "mesh1")).getValue(a).gridKeys)
    }
}
