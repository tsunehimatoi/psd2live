package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.test.*
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import io.github.psd2live.history.WorkspaceHistoryTree

class NativeInteriorEffectTest {
    @TempDir lateinit var directory: Path
    private fun preview(): RigPreviewModel {
        val w=96;val h=144
        val raster=LayerRaster(w,h,ByteArray(w*h*4) { if(it%4==3)255.toByte()else 140.toByte() })
        val layer=WorkspaceSourceLayer(LayerId("cloth"),"cloth","",SourceLayerKind.Raster,true,0,LayerBounds(12,8,w,h),1f,false,LayerBlend.Normal,ChannelMask.ALL,raster,null,null,false)
        return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128,168,listOf(layer),emptyList()),PipelineConfig(atlasSize=512,meshSpacing=16))
    }
    private fun request(target: String,motion: Boolean=true,parameters: List<String>?=null)=buildJsonObject {
        put("target",target);put("enabled",true)
        put("motion",InteriorMotionSettings(enabled=motion).toJson())
        put("settings",NativeInteriorAuthoring.settingsJson(InteriorSurfaceSettings(contour=.35f)))
        parameters?.let { put("parameters",JsonArray(it.map(::JsonPrimitive))) }
    }
    private fun error(a: FloatArray,b: FloatArray)=(a.indices step 2).maxOf { hypot((a[it]-b[it]).toDouble(),(a[it+1]-b[it+1]).toDouble()) }

    @Test fun nativeRecipeRetainsTheOriginalMeshAndReplaysAndExportsTheSameMotion() {
        val p=preview();val authored=p.authored.rig.puppet;val d=authored.drawables.first { it.mesh!=null }
        val overlay=NativeInteriorAuthoring.put(p,RigEditOverlay(),request(d.id.raw))
        val effect=overlay.interiorEffects.single();val model=DocumentGenerators.generate(authored,overlay)
        val toggled=NativeInteriorAuthoring.put(p,overlay,buildJsonObject { put("target",d.id.raw);put("enabled",false);put("state","captured-state") }).interiorEffects.single()
        assertFalse(toggled.enabled)
        val node = "interiorEffect:${effect.id}"
        assertTrue("document:generators" in DocumentGenerators.changed(RigEditOverlay(), overlay))
        assertTrue(node in DocumentGenerators.stale(RigEditOverlay(), overlay))
        val disabledOverlay = overlay.copy(interiorEffects = listOf(toggled))
        assertTrue("document:$node" in DocumentGenerators.changed(overlay, disabledOverlay))
        val stale = DocumentGenerators.stale(overlay, disabledOverlay)
        assertTrue(node in stale)
        assertTrue(DocumentGenerators.PHYSICS in stale)
        assertTrue(DocumentGenerators.MOTIONS in stale)
        assertTrue(DocumentGenerators.changed(overlay, overlay).isEmpty())
        assertTrue("document:generators" in DocumentGenerators.changed(overlay, RigEditOverlay()))
        assertSame(effect.bindings,toggled.bindings,"MCP context fields must not turn a toggle into a rebake")
        assertSame(d.mesh,model.drawables.single { it.id==d.id }.mesh)
        assertSame(d.geometryGrid,model.drawables.single { it.id==d.id }.geometryGrid)
        assertEquals(d.parentDeformerId,model.drawables.single { it.id==d.id }.parentDeformerId)
        val copy=NativeInteriorEffect.fromJson(Json.parseToJsonElement(effect.toJson().toString()).jsonObject)
        assertEquals(effect.toJson(),copy.toJson())
        val reopened=DocumentGenerators.generate(authored,overlay.copy(interiorEffects=listOf(copy)))
        val exported=deletionExport(reopened)
        val evaluator=CpuDeformationEvaluator()
        val engine=PhysicsEngine(effect.groups,PhysicsEngine.ranges(model.parameters),60f)
        engine.settle(effect.sources.associate { it.id.raw to it.default })
        for(i in 0..240) {
            val values=effect.sources.mapIndexed { a,v -> val q=(if(a==0)sin(i*.073f) else cos(i*.051f))*.9f;v.id.raw to (v.default+q*(if(q<0)v.default-v.min else v.max-v.default)) }.toMap()
            val pose=(values+engine.step(values,1f/60)).mapKeys { ParameterId(it.key) }
            val a=evaluator.evaluate(model,pose).worldPositions.getValue(d.id)
            assertTrue(error(a,evaluator.evaluate(reopened,pose).worldPositions.getValue(d.id))<.0001)
            assertTrue(error(a,evaluator.evaluate(exported,pose).worldPositions.getValue(d.id))<.003)
        }
        val disabled=effect.enabled(false)
        val off=DocumentGenerators.generate(authored,overlay.copy(interiorEffects=listOf(disabled)))
        assertEquals(d.blendShapes,off.drawables.single { it.id==d.id }.blendShapes)
    }
    @Test fun existingNonzeroAsymmetricSourcesStayNeutralAndKeepNativeBlendZero() {
        val original=preview()
        val x=Parameter(ParameterId("CustomX"),"X",-10f,50f,5f)
        val y=Parameter(ParameterId("CustomY"),"Y",-8f,20f,2f)
        val base=original.authored.rig.copy(puppet=original.authored.rig.puppet.copy(parameters=original.authored.rig.puppet.parameters+listOf(x,y)))
        val p=original.copy(rig=base,sources=PreviewRigSources.of(base))
        val d=base.puppet.drawables.first { it.mesh!=null };val evaluator=CpuDeformationEvaluator()
        for(dynamic in listOf(false,true)) {
            val effect=NativeInteriorAuthoring.put(p,RigEditOverlay(),request(d.id.raw,dynamic,listOf(x.id.raw,y.id.raw))).interiorEffects.single()
            val model=effect.apply(base.puppet)
            assertTrue(effect.bindings.all { it.keys[it.neutralIndex]==0f })
            val values=mapOf(x.id.raw to x.default,y.id.raw to y.default)
            val output=PhysicsEngine(effect.groups,PhysicsEngine.ranges(model.parameters),60f).settle(values,3f)
            val pose=(values+output).mapKeys { ParameterId(it.key) }
            val before=evaluator.evaluate(base.puppet,pose).worldPositions.getValue(d.id)
            val after=evaluator.evaluate(model,pose).worldPositions.getValue(d.id)
            assertTrue(error(before,after)<.005,"dynamic=$dynamic nonzero default must remain neutral: ${error(before,after)}")
            assertEquals(x,model.parameters.single { it.id==x.id })
            assertEquals(y,model.parameters.single { it.id==y.id })
            val exported=deletionExport(model)
            assertTrue(error(after,evaluator.evaluate(exported,pose).worldPositions.getValue(d.id))<.003,"static or dynamic source binding must export unchanged")
            for(a in listOf(-10f,5f,33f,50f))for(b in listOf(-8f,2f,12f,20f)) {
                val keyed=mapOf(x.id to a,y.id to b)
                assertTrue(error(evaluator.evaluate(model,keyed).worldPositions.getValue(d.id),evaluator.evaluate(exported,keyed).worldPositions.getValue(d.id))<.003)
            }

        }
    }
    @Test fun realProjectArchiveAndHistoryRestoreGeneratedFieldsAndPhysics() = runBlocking {
        val p=preview();val base=p.authored.rig
        val target=base.puppet.drawables.first { it.mesh!=null }.id.raw
        val generated=NativeInteriorAuthoring.put(p,RigEditOverlay(),request(target))
        val checkpoint=RigCheckpoint.encode(AuthoredRig(base,emptyList()),"native-interior-test")
        val before=WorkspaceDocument(p.analysis.source as WorkspaceSourceArt,emptyMap(),emptySet(),emptyMap(),emptyMap(),RigEditOverlay(authoringJournal=listOf(checkpoint)))
        val after=before.copy(rigEdits=before.rigEdits.copy(interiorEffects=generated.interiorEffects))
        val revision=WorkspaceRevisions.of(before);val history=WorkspaceHistoryTree(before,revision,revision)
        val first=history.head().node.id;val next=WorkspaceRevisions.of(after)
        history.commit(first,after,next,next,"Interior shape","user")
        val store=WorkspaceStore(directory.resolve("store"));store.persistHistory("interior",history.state())
        val file=directory.resolve("interior.psd2live")
        ProjectRepository(writeHeadCache=false).save(ProjectSaveCapture("interior",history.state(),JsonObject(emptyMap()),null,store),file)
        ProjectRepository(writeHeadCache=false).open(file).use { opened ->
            val loaded=opened.history.head().snapshot
            assertEquals(next,WorkspaceRevisions.of(loaded))
            assertEquals(generated.interiorEffects.single().toJson(),loaded.rigEdits.interiorEffects.single().toJson())
            assertTrue(opened.history.selectionAt(first).snapshot.rigEdits.interiorEffects.isEmpty())
            val restored=loaded.rigEdits.applyTo(base.puppet)
            assertTrue(restored.drawables.single { it.id.raw==target }.blendShapes.isNotEmpty())
            val groups=PhysicsCatalog.active(p.analysis,p.config.copy(rigEdits=loaded.rigEdits),restored.parameters.map { it.id.raw }.toSet())
            assertEquals(4,groups.count { it.id.startsWith(generated.interiorEffects.single().id) })
        }
    }
    @Test fun changedMeshKeepsTheRecipeButNeverAppliesStaleVertexData() {
        val p=preview();val base=p.authored.rig.puppet;val d=base.drawables.first { it.mesh!=null }
        val effect=NativeInteriorAuthoring.put(p,RigEditOverlay(),request(d.id.raw)).interiorEffects.single()
        val original=d.mesh!!
        val changed=d.copy(mesh=DrawableMesh(original.positions.copyOf().also { it[0]+=.2f },original.uvs,original.indices))
        val model=base.copy(drawables=base.drawables.map { if(it.id==d.id)changed else it })
        assertNotNull(effect.issue(model))
        assertSame(model,effect.apply(model))
        assertTrue(effect.bindings.isNotEmpty(),"the recipe stays available for explicit regeneration")
    }
}
