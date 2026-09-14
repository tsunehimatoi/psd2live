package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import org.umamo.interop.cmo3.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class DeformPathTest {
    private val positions=floatArrayOf(0f,0f,100f,0f,0f,100f,100f,100f)
    private val indices=intArrayOf(0,1,3,0,3,2)
    private fun model()=PuppetModel(listOf(Parameter(ParameterId("Sway"),"Sway",-1f,1f,0f)),emptyList(),emptyList(),
        listOf(Drawable(DrawableId("hair"),"Hair",null,BlendMode.Normal,emptyList(),
            DrawableMesh(positions.copyOf(),floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),indices),null)),
        listOf(OrgChild.Drawable(DrawableId("hair"))),null,canvasWidth=100f,canvasHeight=100f)
    private fun path()=DeformPath("988b244f-9f34-45f9-b68c-2859d09a750c",DrawableId("hair"),listOf(
        DeformPathTools.bind(positions,indices,0f,0f),DeformPathTools.bind(positions,indices,0f,100f)),20f)

    @Test fun bindingPreservesInteriorAndExteriorPositionsAndFollowsMesh() {
        for((x,y) in listOf(25f to 50f,130f to -20f)) {
            val binding=DeformPathTools.bind(positions,indices,x,y)
            val p=binding.position(positions)
            assertEquals(x,p.first,1e-4f);assertEquals(y,p.second,1e-4f)
            val translated=FloatArray(positions.size) {positions[it]+if(it%2==0) 12f else -3f}
            val moved=binding.position(translated)
            assertEquals(x+12f,moved.first,1e-4f);assertEquals(y-3f,moved.second,1e-4f)
        }
    }

    @Test fun identityTranslationAndRotationArePreservedWithoutChangingInput() {
        val p=path();val original=DeformPathTools.positions(p,positions)
        assertContentEquals(positions,DeformPathTools.deform(positions,listOf(p),p.id,original))
        val translated=DeformPathTools.deform(positions,listOf(p),p.id,original.map {it.first+15f to it.second-7f})
        val rotated=DeformPathTools.deform(positions,listOf(p),p.id,original.map {-it.second to it.first})
        for(i in positions.indices step 2) {
            assertEquals(positions[i]+15f,translated[i],1e-4f);assertEquals(positions[i+1]-7f,translated[i+1],1e-4f)
            assertEquals(-positions[i+1],rotated[i],1e-4f);assertEquals(positions[i],rotated[i+1],1e-4f)
        }
        assertEquals(100f,positions[6])
    }

    @Test fun stationaryPathPinsOtherSideAndEditLevelsAreIndependent() {
        val p=path();val fixed=p.copy(id="fixed",points=listOf(DeformPathTools.bind(positions,indices,100f,0f),DeformPathTools.bind(positions,indices,100f,100f)))
        val moved=listOf(0f to 0f,30f to 100f)
        val result=DeformPathTools.deform(positions,listOf(p,fixed),p.id,moved)
        assertEquals(100f,result[2]);assertEquals(0f,result[3]);assertEquals(100f,result[6]);assertEquals(100f,result[7])
        val solo=DeformPathTools.deform(positions,listOf(p),p.id,moved)
        val otherLevel=DeformPathTools.deform(positions,listOf(p,fixed.copy(editLevel=3)),p.id,moved)
        assertContentEquals(solo,otherLevel)
    }

    @Test fun historyRoundtripAndPathOnlyEditingDoNotChangeGeometry() {
        val base=model();val encoded=DeformPathJournal.encode(path())
        val next=RigAuthoringJournal.apply(base,Json.parseToJsonElement(encoded.toString()).jsonObject)
        assertEquals(listOf(path()),next.deformPaths)
        assertSame(base.drawables.single(),next.drawables.single())
        val restored=RigEditOverlay(authoringJournal=listOf(encoded)).applyTo(base)
        assertEquals(next.deformPaths,restored.deformPaths)
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.compile(base,JsonArray(listOf(encoded,
            DeformPathJournal.encode(path().copy(points=listOf(DeformPathPoint(8,9,10,1f,0f,0f),path().points.last())))))) }
        assertTrue(base.deformPaths.isEmpty())
    }

    @Test fun interiorHandlesDoNotJumpAfterCapture() {
        val p=path().copy(points=listOf(DeformPathTools.bind(positions,indices,30f,20f),DeformPathTools.bind(positions,indices,30f,80f)))
        val targets=listOf(30f to 20f,45f to 80f)
        val moved=DeformPathTools.deform(positions,listOf(p),p.id,targets)
        p.points.zip(targets).forEach { (point,target) ->
            val actual=point.position(moved)
            assertEquals(target.first,actual.first,.004f);assertEquals(target.second,actual.second,.004f)
        }
    }

    @Test fun parentLocalCoordinatesAndNativeCanvasWidthsRoundtrip() {
        val base=model()
        val local=FloatArray(positions.size) {positions[it]/100f}
        val warp=Deformer.Warp(DeformerId("parent"),"Parent",null,null,1,1,true,
            KeyformGrid(emptyList(),listOf(KeyformCell(intArrayOf(),WarpLatticeForm(positions)))))
        val drawable=base.drawables.single().copy(parentDeformerId=warp.id,geometryGrid=KeyformGrid(emptyList(),
            listOf(KeyformCell(intArrayOf(),MeshDeltaForm(local.indices.map {local[it]-positions[it]}.toFloatArray())))))
        val p=path().copy(width=.2f)
        val parented=base.copy(deformers=listOf(warp),drawables=listOf(drawable),deformPaths=listOf(p))
        val png=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB),"png",it)}.toByteArray()
        val converted=Cmo3Conversion.freshCmo3(parented,listOf(Cmo3Conversion.AtlasPage(png,100,100)),mapOf("hair" to 0),"parent-test",0,42)
        val loaded=Cmo3.read(Cmo3.write(converted.model))
        val native=org.umamo.interop.cmo3.Cmo3GraphIndex(loaded.root as CModelSource).drawableSources.single()
        val controller=Cmo3Import.elementsOf(native._extensions).filterIsInstance<org.umamo.format.cmo3.model.gen.CControllerExtension>().single()
        val curve=Cmo3Import.elementsOf(controller.controlCurves).filterIsInstance<org.umamo.format.cmo3.model.gen.CControllerCurve>().single()
        assertEquals(20f,curve.lineWidth,.001f)
        val point=Cmo3Import.elementsOf(curve._curvePoints).filterIsInstance<org.umamo.format.cmo3.model.gen.CControllerPoint>().last()
        assertEquals(1f,(point.posOnLocalOfDefaultKeyform as org.umamo.format.cmo3.model.type.GVector2).y,.0001f)
        assertEquals(100f,(point.pointOnCanvasForRecovery as org.umamo.format.cmo3.model.type.GVector2).y,.001f)
        val restored=Cmo3Import.fromModelSource(loaded.root as CModelSource)
        assertEquals(p.width,restored.deformPaths.single().width,.00001f)
        assertEquals(p.points,restored.deformPaths.single().points)
    }

    @Test fun cmo3CarriesEditablePathsAndBakedKeyformsAndSupportsPathOnlyDeletion() {
        val p=path();val base=model().copy(deformPaths=listOf(p))
        val moved=DeformPathTools.deform(positions,listOf(p),p.id,listOf(0f to 0f,30f to 100f))
        val keyed=applyKeyformSet(base,RigKeyformSetEdit(RigTargetRef(RigTargetKind.ART_MESH,"hair"),mapOf("Sway" to 1f),
            RigKeyformGeometryEdit(positionDeltas=moved.indices.map {moved[it]-positions[it]})))
        val texture=BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB).apply {
            for(y in 0 until height) for(x in 0 until width) setRGB(x,y,if((x/10+y/10)%2==0) 0xff20aacc.toInt() else 0xffdd8866.toInt())
        }
        val png=ByteArrayOutputStream().also { ImageIO.write(texture,"png",it) }.toByteArray()
        val exported=Cmo3Conversion.freshCmo3(keyed,listOf(Cmo3Conversion.AtlasPage(png,100,100)),mapOf("hair" to 0),"path-test",0,42)
        val bytes=Cmo3.write(exported.model)
        java.io.File("build/deform-path-fixture.cmo3").writeBytes(bytes)
        val loaded=Cmo3.read(bytes)
        val restored=Cmo3Import.fromModelSource(loaded.root as CModelSource)
        assertEquals(listOf(p),restored.deformPaths)
        val before=RigGeometryTools.geometry(keyed,"mesh","hair",mapOf("Sway" to 1f)).points
        val after=RigGeometryTools.geometry(restored,"mesh","hair",mapOf("Sway" to 1f)).points
        before.indices.forEach {assertEquals(before[it],after[it],1e-3f)}
        val moc=org.umamo.interop.moc3.export.Moc3Export.toMocDocument(keyed).document
        val runtime=org.umamo.interop.moc3.import.Moc3Import.fromMocDocument(org.umamo.format.moc3.Moc3.read(org.umamo.format.moc3.Moc3.write(moc)),null)
        assertTrue(runtime.deformPaths.isEmpty(),"Editor handles are baked, not runtime objects")
        for(value in listOf(-1f,0f,.5f,1f)) {
            val pose=mapOf(ParameterId("Sway") to value)
            val evaluator=org.umamo.render.eval.CpuDeformationEvaluator()
            val expected=evaluator.evaluate(keyed,pose).worldPositions.getValue(DrawableId("hair"))
            val actual=evaluator.evaluate(runtime,pose).worldPositions.getValue(DrawableId("hair"))
            expected.indices.forEach {assertEquals(expected[it],actual[it],.001f)}
        }
        Cmo3Export.apply(restored.copy(deformPaths=emptyList()),loaded)
        val deleted=Cmo3Import.fromModelSource(Cmo3.read(Cmo3.write(loaded)).root as CModelSource)
        assertTrue(deleted.deformPaths.isEmpty())
        assertNotNull(deleted.drawables.single().geometryGrid)
    }

    @Test fun invalidPathsAndDegenerateTrianglesAreRejected() {
        assertFailsWith<IllegalArgumentException> {path().copy(width=Float.NaN)}
        assertFailsWith<IllegalArgumentException> {path().copy(hardness=2f)}
        assertFailsWith<IllegalArgumentException> {path().copy(closed=true)}
        assertFailsWith<IllegalArgumentException> {DeformPathTools.bind(floatArrayOf(0f,0f,0f,0f,0f,0f),intArrayOf(0,1,2),1f,1f)}
    }
}
