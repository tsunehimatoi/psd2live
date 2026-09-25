package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.drawable.MeshPointRef
import org.umamo.format.cmo3.model.drawable.PointOnCurve
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CControllerExtension
import org.umamo.format.cmo3.model.gen.CTopologyObserverExtension
import org.umamo.format.cmo3.model.gen.Effect
import org.umamo.format.cmo3.model.gen.TargetPoint
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.DeformPathPoint
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Cmo3DeformPathsObserverTest {
    @Test fun topologyObserverTracksCurrentControllerOnFreshAndEditedExport() {
        val drawable = Drawable(
            DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(),
            DrawableMesh(
                floatArrayOf(1f, 1f, 2f, 1f, 1f, 2f),
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
                intArrayOf(0, 1, 2),
            ), null,
        )
        val path = DeformPath(
            "a1111111-1111-4111-8111-111111111111", drawable.id,
            listOf(
                DeformPathPoint(0, 1, 2, 1f, 0f, 0f),
                DeformPathPoint(0, 1, 2, 0f, 1f, 0f),
            ),
        )
        val puppet = PuppetModel(
            parameters = emptyList(), parts = emptyList(), deformers = emptyList(),
            drawables = listOf(drawable), deformPaths = listOf(path),
            rootChildren = emptyList(), rootPartId = null,
            canvasWidth = 64f, canvasHeight = 64f,
        )
        val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(2, 2, ByteArray(16) { 0xFF.toByte() })), 2, 2)
        val converted = Cmo3Conversion.freshCmo3(puppet, listOf(page), mapOf("mesh" to 0), "test", 0L, 0x42)
        val model = Cmo3.read(Cmo3.write(converted.model))

        fun checkObserver() {
            val source = Cmo3GraphIndex(model.root as CModelSource).drawableSources.single()
            val extensions = Cmo3Import.elementsOf(source._extensions)
            val controller = extensions.filterIsInstance<CControllerExtension>().single()
            val observer = extensions.filterIsInstance<CTopologyObserverExtension>().single()
            assertSame(source, observer._owner)
            assertEquals(listOf(controller), Cmo3Import.elementsOf(observer.observers))
            val form = Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CArtMeshForm>().single()
            val targets = Cmo3Import.elementsOf(controller.targetPoints).filterIsInstance<TargetPoint>()
            assertEquals(3, targets.size)
            targets.forEachIndexed { vertex, target ->
                val point = target._point as MeshPointRef
                assertSame(source, point._artMeshSource)
                assertSame(form, point.keyForm)
                assertSame(form.positions, point.positions)
                assertSame(form.coordType, point.coordType)
                assertEquals(vertex, point._index)
                assertEquals(vertex.toLong(), point.pointUid)
                assertEquals(Cmo3Import.uuidOf(source.guid), Cmo3Import.uuidOf(point.artMeshGuid))
                val effect = Cmo3Import.elementsOf(target.effects).single() as Effect
                val onCurve = effect.effectorPt as PointOnCurve
                assertEquals(Cmo3Import.uuidOf((Cmo3Import.elementsOf(controller.controlCurves).single() as org.umamo.format.cmo3.model.gen.CControllerCurve).curveId), Cmo3Import.uuidOf(onCurve.curveId))
                assertEquals(1f, effect.weight)
                assertTrue(onCurve.totalT < 1f, "open-curve totalT must stay below the last point index")
                if (vertex == 1) assertEquals(0f, onCurve.distance)
                if (vertex == 2) {
                    assertEquals(1f, onCurve.distance)
                    assertEquals(0f, onCurve.totalT)
                }
            }
        }

        checkObserver()
        val originalSource = Cmo3GraphIndex(model.root as CModelSource).drawableSources.single()
        val originalController = Cmo3Import.elementsOf(originalSource._extensions).filterIsInstance<CControllerExtension>().single()
        val imported = Cmo3Import.fromModelSource(model.root as CModelSource)
        assertEquals(1, imported.deformPaths.size)
        Cmo3Export.apply(imported.copy(deformPaths = imported.deformPaths.map { it.copy(width = it.width + 0.2f) }), model)
        val updatedSource = Cmo3GraphIndex(model.root as CModelSource).drawableSources.single()
        val updatedController = Cmo3Import.elementsOf(updatedSource._extensions).filterIsInstance<CControllerExtension>().single()
        assertNotSame(originalController, updatedController)
        val rewritten = Cmo3.read(Cmo3.write(model))
        val source = Cmo3GraphIndex(rewritten.root as CModelSource).drawableSources.single()
        val extensions = Cmo3Import.elementsOf(source._extensions)
        val controller = extensions.filterIsInstance<CControllerExtension>().single()
        val observer = extensions.filterIsInstance<CTopologyObserverExtension>().single()
        assertSame(source, observer._owner)
        assertEquals(listOf(controller), Cmo3Import.elementsOf(observer.observers))

        val withoutPath = Cmo3Import.fromModelSource(rewritten.root as CModelSource).copy(deformPaths = emptyList())
        Cmo3Export.apply(withoutPath, rewritten)
        val removed = Cmo3.read(Cmo3.write(rewritten))
        val remaining = Cmo3Import.elementsOf(Cmo3GraphIndex(removed.root as CModelSource).drawableSources.single()._extensions)
        assertEquals(0, remaining.filterIsInstance<CControllerExtension>().size)
        assertEquals(0, remaining.filterIsInstance<CTopologyObserverExtension>().size)
    }
}
