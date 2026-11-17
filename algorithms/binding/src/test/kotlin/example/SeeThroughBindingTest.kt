package example

import io.github.psd2live.algorithms.binding.SeeThroughBinding
import org.umamo.runtime.model.*
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.export.Moc3VersionDowngrade
import kotlin.test.*

class SeeThroughBindingTest {
    private val binding = SeeThroughBinding({ "$it window" })
    private val candidates = listOf(SeeThroughBinding.Candidate("eye", true, listOf("hair")))
    private val settings = SeeThroughBinding.Settings(false, true, .6f, 10, compositeMasks = true)
    private fun id(id: DrawableId) = DrawableId("generated:${id.raw}")
    private fun fixture(inverted: Boolean = false): PuppetModel {
        val eye = Drawable(DrawableId("eye"), "eye", null, BlendMode.Normal, emptyList(), null, null, drawOrder = 100f)
        val order = KeyformGrid<ChannelValue>(listOf(KeyformAxis(ParameterId("turn"), floatArrayOf(-1f, 1f))),
            listOf(KeyformCell(intArrayOf(0), ChannelValue.Scalar(200.25f)), KeyformCell(intArrayOf(1), ChannelValue.Scalar(220.75f))))
        val geometry = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(floatArrayOf(2f, 3f)))))
        val hair = eye.copy(id = DrawableId("hair"), name = "hair", parentDeformerId = DeformerId("parent"),
            maskedBy = listOf(DrawableId("clip")), invertMask = inverted, blendMode = BlendMode.Multiply,
            drawOrder = 200.25f, geometryGrid = geometry, channelGrids = ChannelGrids(mapOf(FormChannel.DRAW_ORDER to order)),
            blendShapes = listOf(BlendShapeBinding(ParameterId("shape"), floatArrayOf(0f, 1f), 0,
                listOf(MeshForm(floatArrayOf(0f, 0f), 200.25f), MeshForm(floatArrayOf(1f, 2f), 205.25f)))))
        val clip = eye.copy(id = DrawableId("clip"))
        val parent = Part(PartId("organization"), "organization", listOf(OrgChild.Drawable(hair.id)))
        return PuppetModel(emptyList(), listOf(parent), emptyList(), listOf(eye, hair, clip),
            listOf(OrgChild.Drawable(eye.id), OrgChild.Part(parent.id), OrgChild.Drawable(clip.id)), null).withDerivedRenderRoot()
    }

    @Test fun nestedWindowsPreserveExistingClippingMotionBlendAndOrganization() {
        for (inverted in listOf(false, true)) {
            val source = fixture(inverted)
            val original = source.drawables.single { it.id.raw == "hair" }
            val result = binding.apply(source, candidates, settings, ::id)
            assertTrue(result.notes.isEmpty())
            assertEquals(mapOf("hair" to listOf("eye")), result.windows)
            val meshes = result.model.drawables.associateBy { it.id.raw }
            val retained = meshes.getValue("hair")
            val copy = meshes.getValue("generated:hair")
            for (mesh in listOf(retained, copy)) {
                assertEquals(original.maskedBy, mesh.maskedBy)
                assertEquals(inverted, mesh.invertMask)
                assertSame(original.geometryGrid, mesh.geometryGrid)
                assertEquals(original.parentDeformerId, mesh.parentDeformerId)
                assertEquals(BlendMode.Normal, mesh.blendMode)
                assertContentEquals(original.blendShapes.single().forms.last()!!.positionDeltas,
                    mesh.blendShapes.single().forms.last()!!.positionDeltas)
            }
            assertEquals(.4f, copy.opacity, .0001f)
            assertEquals(original.id, copy.textureSourceId)
            val groups = result.model.parts.filter { it.groupMode == PartGroupMode.Isolated }
            assertEquals(2, groups.size)
            assertEquals(setOf(true, false), groups.map { it.composite.invertMask }.toSet())
            for (group in groups) {
                assertEquals(listOf(DrawableId("eye")), group.composite.maskedBy)
                assertEquals(BlendMode.Multiply, group.composite.blendMode)
            }
            val outside = groups.single { it.composite.invertMask }
            assertSame(original.channelGrids[FormChannel.DRAW_ORDER], outside.channelGrids[FormChannel.DRAW_ORDER])
            assertEquals(205.25f, outside.blendShapes.single().forms.last()!!.drawOrder)
            assertEquals(groups.map { OrgChild.Part(it.id) }.toSet(), result.model.parts.first().children.toSet())
            assertFalse(OrgChild.Drawable(original.id) in result.model.rootChildren)
        }
    }

    @Test fun refreshRetainsNestedMasksAndFollowsSourceMotion() {
        val result = binding.apply(fixture(true), candidates, settings, ::id)
        val changed = result.model.copy(drawables = result.model.drawables.map {
            if (it.id.raw == "hair") it.copy(opacity = .5f) else it
        })
        val refreshed = binding.refresh(changed, result.model, result.copies, result.windows, candidates, settings)
        val copy = refreshed.drawables.single { it.id.raw == "generated:hair" }
        assertEquals(listOf(DrawableId("clip")), copy.maskedBy)
        assertTrue(copy.invertMask)
        assertEquals(.2f, copy.opacity, .0001f)
        assertEquals(2, refreshed.parts.count { it.groupMode == PartGroupMode.Isolated })
    }

    @Test fun simpleWindowsNeedNoCompositeAndUnsupportedTargetsAreExplicit() {
        val source = fixture()
        val simple = source.copy(drawables = source.drawables.map { it.copy(maskedBy = emptyList(), invertMask = false) })
        val result = binding.apply(simple, candidates, settings.copy(compositeMasks = false), ::id)
        assertTrue(result.notes.isEmpty())
        assertEquals(source.parts.size, result.model.parts.size)
        assertTrue(result.model.drawables.single { it.id.raw == "hair" }.invertMask)
        val unsupported = binding.apply(source, candidates, settings.copy(compositeMasks = false), ::id)
        assertSame(source, unsupported.model)
        assertEquals(SeeThroughBinding.DropReason.BANGS_CLIPPED, unsupported.notes.single().reason)
    }

    @Test fun partIdCollisionDoesNotOverwriteAnExistingPart() {
        val source = fixture()
        val occupied = source.copy(parts = source.parts + Part(PartId(id(DrawableId("hair:window-outside")).raw), "existing", emptyList()))
        val result = binding.apply(occupied, candidates, settings, ::id)
        assertSame(occupied, result.model)
        assertEquals(SeeThroughBinding.DropReason.ID_TAKEN, result.notes.single().reason)
    }
    @Test fun previewKeepsCompositeWhileOldExportReportsItsLoss() {
        val result = binding.apply(fixture(), candidates, settings, ::id)
        assertTrue(RuntimeFeature.PartComposite in result.model.unsupportedFeaturesInUse(RuntimeTarget.Cubism50))
        val downgraded = Moc3VersionDowngrade.stripForCmo3(result.model, RuntimeTarget.Cubism50)
        assertTrue(downgraded.notices.filterIsInstance<ExportNotice.FeatureStripped>()
            .any { it.feature == RuntimeFeature.PartComposite && it.subjects.size == 2 })
        assertEquals(2, result.model.parts.count { it.groupMode == PartGroupMode.Isolated })
    }

}
