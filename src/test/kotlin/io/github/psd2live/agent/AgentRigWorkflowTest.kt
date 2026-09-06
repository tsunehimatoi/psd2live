package io.github.psd2live.agent

import io.github.psd2live.core.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class AgentRigWorkflowTest {
    @Test fun `posed inspect transform overlay log checkout and restore share one revision chain`() = runBlocking {
        val root=Files.createTempDirectory("psd2live-project-geometry-")
        val vm=PSD2LiveViewModel()
        val workspace=ViewModelAgentWorkspace(vm,root.resolve("cache"))
        try {
            val file=Files.write(root.resolve("source.psd"),byteArrayOf(56,66,80,83))
            val layer=WorkspaceSourceLayer(LayerId("face"),"face","",SourceLayerKind.Raster,true,0,
                LayerBounds(0,0,64,64),1f,false,LayerBlend.Normal,ChannelMask.ALL,
                LayerRaster(64,64,ByteArray(64*64*4) { if(it%4==3)-1 else 128.toByte() }),null,null,false)
            val state=PSD2LiveState(projectId=UUID.randomUUID().toString(),inputPath=file.toString(),loadedInputPath=file.toString(),
                atlasSize=256,animationEnabled=false,mouseTrackingEnabled=false)
            val preview=PSD2LivePipeline().buildPreview(WorkspaceSourceArt(64,64,listOf(layer),emptyList()),state.buildConfig())
            vm.installProjectState(state.copy(analysis=preview.analysis,previewModel=preview));vm.attachAgentWorkspace(workspace)
            val target="DeformFeatureDisplacement"
            val channelEdit=workspace.setKeyform(AgentKeyformSetRequest(workspace.snapshot().historyHeadNodeId!!,
                AgentKeyformTargetRef("warp",target),mapOf("ParamAngleX" to -45f,"ParamAngleY" to 0f),
                channels=AgentKeyformChannels(opacity=0.8f)))
            val head=channelEdit.historyNodeId
            fun query(detail:String="summary")=Json.parseToJsonElement("""{"target":{"kind":"warp","id":"$target"},"coordinate":{"ParamAngleX":-45,"ParamAngleY":0},"detail":"$detail"}""").jsonObject
            val summary=workspace.inspectRigGeometry(query())
            assertFalse("points" in summary)
            val before=workspace.inspectRigGeometry(query("points"))
            val pose=mapOf("ParamAngleX" to -45f,"ParamAngleY" to 0f)
            val view=workspace.renderModel(AgentModelViewRequest(parameters=pose,frame=AgentViewFrame.CanvasRect(Bounds(-10f,-10f,74f,74f)),
                annotateDeformerIds=setOf(target),pointIndices=true,output=AgentViewOutputSpec(256)))
            assertEquals(listOf(target),view.annotatedDeformerIds)
            assertTrue(vm.state.value.logEntries.any { it.imageBytes?.contentEquals(view.png)==true && target in it.message })
            fun request(headId:String,ops:String)=Json.parseToJsonElement("""{"target":{"kind":"warp","id":"$target"},"coordinate":{"ParamAngleX":-45,"ParamAngleY":0},"selection":{"center":[0.5,0.5]},"range":{"radius":1},"operations":$ops,"expected_history_head_node_id":"$headId"}""").jsonObject
            val operations="""[{"type":"translate","delta":[0.01,0]},{"type":"bend","axis":"x","amount":0.01}]"""
            val result=workspace.transformRigGeometry(request(head,operations))
            assertNotEquals(head,result.historyNodeId)
            val after=workspace.inspectRigGeometry(query("points"))
            assertNotEquals(before["points"],after["points"])
            assertEquals(0.8f,vm.state.value.rigEdits.keyformSetEdits.single { it.target.id==target }.channels?.opacity)
            assertEquals(result.revisionId,after.getValue("revisionId").jsonPrimitive.content)
            val count=workspace.history().nodes.size
            assertFails { workspace.transformRigGeometry(request(head,operations)) }
            assertEquals(count,workspace.history().nodes.size)
            assertFails { workspace.transformRigGeometry(request(result.historyNodeId,"""[{"type":"translate","delta":[0.01,0]},{"type":"scale","factors":[-1,1]}]""")) }
            assertEquals(count,workspace.history().nodes.size)
            assertEquals(after["points"],workspace.inspectRigGeometry(query("points"))["points"])
            workspace.checkoutHistory(head)
            assertEquals(before["points"],workspace.inspectRigGeometry(query("points"))["points"])
            workspace.checkoutHistory(result.historyNodeId)
            assertEquals(after["points"],workspace.inspectRigGeometry(query("points"))["points"])
            assertTrue(vm.state.value.logEntries.any { it.tag=="Geometry" && it.detail?.contains("operations")==true })
            val destination=root.resolve("rig.psd2live")
            io.github.psd2live.project.ProjectSession(vm).save(workspace,destination)
            io.github.psd2live.project.ProjectSession(vm).open(workspace,destination)
            assertEquals(after["points"],workspace.inspectRigGeometry(query("points"))["points"])
        } finally {
            vm.close();workspace.close();io.github.psd2live.project.ProjectArchive.deleteTemporaryDirectory(root)
        }
    }
}
