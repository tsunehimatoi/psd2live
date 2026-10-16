package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class InteriorKeyformTest {
    @TempDir lateinit var temp: Path
    companion object {
        private val x = Parameter(ParameterId("SurfaceX"),"Original X",-30f,30f,0f)
        private val y = Parameter(ParameterId("SurfaceY"),"Original Y",-30f,30f,0f)
        private val raster = InteriorRaster(96,96,ByteArray(96*96*4) { if (it%4 == 3) 255.toByte() else 100 })
        private val mesh: DrawableMesh by lazy {
            val xy = (0..8).flatMap { j -> (0..8).flatMap { i -> listOf(i*12f,j*12f) } }.toFloatArray()
            val indices = (0..7).flatMap { j -> (0..7).flatMap { i -> val a=j*9+i; listOf(a,a+1,a+10,a,a+10,a+9) } }.toIntArray()
            DrawableMesh(xy,FloatArray(xy.size),indices)
        }
        private val drawable by lazy { Drawable(DrawableId("cloth"),"Test cloth",null,BlendMode.Normal,emptyList(),mesh,null,opacity=.73f) }
        private val model by lazy { PuppetModel(listOf(x,y),emptyList(),emptyList(),listOf(drawable),listOf(OrgChild.Drawable(drawable.id)),null,canvasWidth=96f,canvasHeight=96f) }
        private val settings = InteriorSurfaceSettings(contour = .35f)
        private val guides = listOf(InteriorGuide("curve",listOf(InteriorPoint(28f,18f),InteriorPoint(50f,42f),InteriorPoint(35f,78f)),radius=16f))
        private val edit by lazy { InteriorAuthoring.prepare("surface","Interior surface",drawable,raster,listOf(InteriorParameter(x),InteriorParameter(y)),settings,guides) }
        private val evaluator = CpuDeformationEvaluator()
        private fun geometry(m: PuppetModel,a: Float,b: Float) = evaluator.evaluate(m,mapOf(x.id to a,y.id to b)).worldPositions.getValue(drawable.id)
    }

    @Test fun productionEvaluatorMatchesExactSurfaceAtCombinedInteriorPoses() {
        val generated = InteriorGenerator.apply(model,edit)
        val plan = InteriorSurface.compile(InteriorMesh(mesh.positions,mesh.indices),raster,settings,guides)
        val random = Random(412)
        var maximum = 0.0
        for ((a,b) in listOf(0f to 0f,-30f to 30f,30f to -30f) + List(91) { random.nextFloat()*60-30 to random.nextFloat()*60-30 }) {
            val expected = plan.evaluate(a/30,b/30).positions(); val actual = geometry(generated,a,b)
            for (i in expected.indices step 2) maximum=max(maximum,hypot((expected[i]-actual[i]).toDouble(),(expected[i+1]+actual[i+1]).toDouble()))
        }
        println("native random max=$maximum px; cells=${edit.bake.grid.cells.size}; sampled max=${edit.bake.errorPixels}")
        assertTrue(maximum <= .05, "ordinary evaluator differs by $maximum px")
        assertSame(drawable.mesh,generated.drawables.single().mesh)
        assertEquals(.73f,generated.drawables.single().opacity)
        assertContentEquals(mesh.positions,geometry(generated,0f,0f).mapIndexed { i,v -> if (i%2==0) v else -v }.toFloatArray())
    }

    @Test fun existingParameterIdentityAndUnrelatedAxesSurviveAndCollisionsAreAtomic() {
        val axis = Parameter(ParameterId("Existing"),"Authored",-1f,1f,0f)
        val previous = KeyformGrid(listOf(KeyformAxis(axis.id,floatArrayOf(-1f,0f,1f))),listOf(-2f,0f,2f).mapIndexed { i,v ->
            KeyformCell(intArrayOf(i),MeshDeltaForm(FloatArray(mesh.positions.size) { if (it%2==0) v else 0f }))
        })
        val source = model.copy(parameters=model.parameters+axis,drawables=listOf(drawable.copy(geometryGrid=previous)))
        val generated = InteriorGenerator.apply(source,edit)
        assertSame(x,generated.parameters.first())
        assertSame(previous.axes.first(),generated.drawables.single().geometryGrid!!.axes.first())
        val base = geometry(InteriorGenerator.apply(model,edit),14.2f,-21.7f)
        val actual = evaluator.evaluate(generated,mapOf(x.id to 14.2f,y.id to -21.7f,axis.id to 1f)).worldPositions.getValue(drawable.id)
        for (i in actual.indices) assertEquals(base[i]+if(i%2==0)2f else 0f,actual[i],.0001f)
        assertFailsWith<IllegalArgumentException> { InteriorGenerator.apply(generated,edit) }
        assertSame(previous,source.drawables.single().geometryGrid)
        val wrong = model.copy(drawables=listOf(drawable.copy(mesh=DrawableMesh(mesh.positions.copyOf().also { it[0]+=1 },mesh.uvs,mesh.indices))))
        assertFailsWith<IllegalArgumentException> { InteriorGenerator.apply(wrong,edit) }
    }

    @Test fun documentGeneratorDeclaresOnlyItsOwnOutputsAndReplaysWithoutAccumulation() {
        val overlay = InteriorAuthoring.put(RigEditOverlay(),model,edit)
        val graph = DocumentGenerators.graph(overlay)
        assertEquals("interior:surface",DocumentGenerators.owner(graph,DocumentGenerators.keyform("mesh","cloth",x.id.raw))?.id)
        assertNull(DocumentGenerators.owner(graph,"parameter:${x.id.raw}"),"existing parameters must not be claimed")
        val a = overlay.applyTo(model); val b = overlay.applyTo(model)
        assertContentEquals(geometry(a,12f,-16f),geometry(b,12f,-16f))
        assertTrue("interior:surface" in DocumentGenerators.stale(RigEditOverlay(),overlay))
        val duplicate = RigInteriorEdit.fromJson(JsonObject(edit.toJson()+mapOf("id" to JsonPrimitive("other"))))
        assertFailsWith<IllegalArgumentException> { InteriorAuthoring.put(overlay,model,duplicate) }
        val created = RigInteriorEdit("created",edit.name,edit.target,edit.parameters.map { it.copy(create=true) },settings,guides,edit.registration,edit.bake)
        val empty = model.copy(parameters=emptyList())
        val added = InteriorAuthoring.put(RigEditOverlay(),empty,created)
        val restored = added.applyTo(empty)
        assertEquals(setOf(x,y),restored.parameters.toSet())
        assertEquals("interior:created",DocumentGenerators.owner(DocumentGenerators.graph(added),"parameter:${x.id.raw}")?.id)
    }

    @Test fun recipeRoundTripHasStableRevisionAndNativeMoc3Output() {
        val copy = RigInteriorEdit.fromJson(Json.parseToJsonElement(edit.toJson().toString()).jsonObject)
        assertEquals(edit,copy); assertEquals(edit.toString(),copy.toString())
        val source = InteriorGenerator.apply(model,edit); val reopened = InteriorGenerator.apply(model,copy)
        assertContentEquals(geometry(source,17f,-12f),geometry(reopened,17f,-12f))
        val exported = io.github.psd2live.core.deletionExport(source)
        for ((a,b) in listOf(0f to 0f,17f to -12f,-23f to 21f,30f to 30f)) {
            val expected=geometry(source,a,b); val actual=geometry(exported,a,b)
            for (i in expected.indices) assertEquals(expected[i],actual[i],.002f,"MOC3 roundtrip")
        }
    }

    @Test fun savedProjectAndHistoryRestoreTheSameGeneratedForms() = runBlocking {
        val checkpoint=RigCheckpoint.encode(AuthoredRig(BuiltRig(model,emptyMap(),emptyMap(),emptyMap(),96f,96f,48f,48f,emptyList()),emptyList()),"interior-test")
        val before=WorkspaceDocument(WorkspaceSourceArt(96,96,emptyList(),emptyList()),emptyMap(),emptySet(),emptyMap(),emptyMap(),RigEditOverlay(authoringJournal=listOf(checkpoint)))
        val after=before.copy(rigEdits=before.rigEdits.copy(interiorEdits=listOf(edit)))
        val r=WorkspaceRevisions.of(before); val tree=WorkspaceHistoryTree(before,r,r); val root=tree.head().node.id
        val revision=WorkspaceRevisions.of(after)
        tree.commit(root,after,revision,revision,"Add interior surface","user")
        assertNotEquals(r,revision)
        val store=WorkspaceStore(temp.resolve("store"));store.persistHistory("interior",tree.state())
        val repository=ProjectRepository(writeHeadCache=false); val file=temp.resolve("interior.psd2live")
        repository.save(ProjectSaveCapture("interior",tree.state(),JsonObject(emptyMap()),null,store),file)
        repository.open(file).use { opened ->
            val loaded=opened.history.head().snapshot
            assertEquals(revision,WorkspaceRevisions.of(loaded))
            assertEquals(edit,loaded.rigEdits.interiorEdits.single())
            val authored=requireNotNull(loaded.rigEdits.authoredFromCheckpoint()).first.rig.puppet
            assertContentEquals(geometry(after.rigEdits.applyTo(model),17f,-22f),geometry(loaded.rigEdits.applyTo(authored),17f,-22f))
            assertTrue(opened.history.selectionAt(root).snapshot.rigEdits.interiorEdits.isEmpty())
        }
    }

    @Test fun sourceRegistrationHandlesRotationAndNonUniformScaleAndFailedSamplingPublishesNothing() {
        val map=InteriorRegistration(1.2,.35,-.2,.8,23.0,17.0)
        val points=FloatArray(mesh.positions.size) { i -> val j=i/2*2
            (if(i%2==0)map.a*mesh.positions[j]+map.c*mesh.positions[j+1]+map.tx else map.b*mesh.positions[j]+map.d*mesh.positions[j+1]+map.ty).toFloat()
        }
        val placed=drawable.copy(mesh=DrawableMesh(points,mesh.uvs,mesh.indices))
        val recipe=InteriorAuthoring.prepare("placed","Placed",placed,raster,edit.parameters,settings,guides,map)
        val rig=InteriorGenerator.apply(model.copy(drawables=listOf(placed)),recipe)
        val expected=InteriorSurface.compile(InteriorMesh(mesh.positions,mesh.indices),raster,settings,guides).evaluate(.61f,-.73f).positions()
        val actual=geometry(rig,.61f*30,-.73f*30)
        for(i in expected.indices step 2) {
            assertEquals((map.a*expected[i]+map.c*expected[i+1]+map.tx).toFloat(),actual[i],.08f)
            assertEquals(-(map.b*expected[i]+map.d*expected[i+1]+map.ty).toFloat(),actual[i+1],.08f)
        }
        assertFailsWith<IllegalArgumentException> { InteriorKeyforms.bake(mesh,raster,settings,guides,tolerancePixels=1e-8,maxSegments=2) }
        assertSame(mesh,drawable.mesh)
    }

    @Test fun asymmetricExistingParameterRangesUseTheirOwnNeutralAndNativeSnapping() {
        val a=Parameter(x.id,"Asymmetric X",-12f,60f,3f)
        val b=Parameter(y.id,"Asymmetric Y",0f,9f,2f)
        val recipe=InteriorAuthoring.prepare("asymmetric","Surface",drawable,raster,listOf(InteriorParameter(a),InteriorParameter(b)),settings,guides)
        val current=InteriorGenerator.apply(model.copy(parameters=listOf(a,b)),recipe)
        val plan=InteriorSurface.compile(InteriorMesh(mesh.positions,mesh.indices),raster,settings,guides)
        val expected=plan.evaluate(-10f/15,3f/7).positions();val actual=geometry(current,-7f,5f)
        for(i in expected.indices step 2) assertTrue(hypot((actual[i]-expected[i]).toDouble(),(actual[i+1]+expected[i+1]).toDouble()) <= .05)
        val collision=RigInteriorEdit("wrong",edit.name,edit.target,edit.parameters.map { it.copy(create=true) },settings,guides,edit.registration,edit.bake)
        assertFailsWith<IllegalArgumentException> { InteriorAuthoring.put(RigEditOverlay(),model,collision) }
    }

    @Test fun authorCorrectionsAreCapturedAsGeneratedOverridesAndPersistOnReplay() {
        val overlay=RigEditOverlay(interiorEdits=listOf(edit));val shown=overlay.applyTo(model)
        val command=buildJsonObject {
            put("op","canvas_geometry");put("kind","mesh");put("id","cloth")
            put("key",buildJsonObject { put(x.id.raw,30);put(y.id.raw,0) })
            val points=RigGeometryTools.geometry(shown,"mesh","cloth",mapOf(x.id.raw to 30f,y.id.raw to 0f)).points
            put("points",JsonArray(points.mapIndexed { i,v -> JsonPrimitive(v+if(i%2==0)1f else 0f) }))
        }
        val captured=GeneratedOverrides.capture(shown,overlay,JsonArray(listOf(command)))
        assertEquals(GeneratedOverrides.OP,captured.single().jsonObject.getValue("op").jsonPrimitive.content)
        val corrected=overlay.copy(authoringJournal=captured.map { it.jsonObject }).applyTo(model)
        val a=geometry(shown,30f,0f);val b=geometry(corrected,30f,0f)
        assertTrue(a.indices.any { abs(a[it]-b[it])>.5f })
    }
}
