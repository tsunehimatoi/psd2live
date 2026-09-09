package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class RigAuthoringTest {
    private fun command(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun model(): PuppetModel {
        val points=floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)
        val warp=Deformer.Warp(DeformerId("head"),"Head",null,null,1,1,true,
            KeyformGrid(emptyList(),listOf(KeyformCell(intArrayOf(),WarpLatticeForm(points)))))
        val mesh=Drawable(DrawableId("hair"),"Hair",warp.id,BlendMode.Normal,emptyList(),
            DrawableMesh(points,points.copyOf(),intArrayOf(0,1,3,0,3,2)),null)
        return PuppetModel(emptyList(),listOf(Part(PartId("folder"),"Folder",emptyList())),listOf(warp),listOf(mesh),
            listOf(OrgChild.Part(PartId("folder")),OrgChild.Drawable(mesh.id)),null)
    }

    @Test fun batchKeepsBindingAndRejectsInvalidTailAtomically() {
        val base=model()
        val edits=listOf(command("""{"action":"rename","kind":"mesh","id":"hair","name":"Bang"}"""),
            command("""{"action":"move","kind":"mesh","id":"hair","parent_id":"folder"}"""))
        val result=RigStructureEdits.apply(base,edits)
        assertEquals("Bang",result.drawables.single().name)
        assertEquals(DeformerId("head"),result.drawables.single().parentDeformerId)
        assertEquals(listOf(OrgChild.Drawable(DrawableId("hair"))),result.parts.single().children)
        assertFailsWith<IllegalArgumentException> { RigStructureEdits.apply(base,edits + command("""{"action":"move","kind":"part","id":"folder","parent_id":"folder"}""")) }
        assertEquals("Hair",base.drawables.single().name)
        assertTrue(base.parts.single().children.isEmpty())
    }

    @Test fun rebindRequiresExplicitLocalSemantics() {
        assertFailsWith<IllegalArgumentException> { RigStructureEdits.apply(model(),listOf(command("""{"action":"bind","kind":"mesh","id":"hair","parent_id":null}"""))) }
        val result=RigStructureEdits.apply(model(),listOf(command("""{"action":"bind","kind":"mesh","id":"hair","parent_id":null,"space":"local"}""")))
        assertNull(result.drawables.single().parentDeformerId)
    }

    @Test fun journalReplaysCreateThenRenameWithoutDuplicatingWarp() {
        val warp=RigWarpEdit("bang","Bang","head",listOf("hair"),1,1)
        val journal=listOf(JsonObject(warp.toJson() + ("action" to JsonPrimitive("create_warp"))),
            command("""{"action":"rename","kind":"warp","id":"bang","name":"Sway"}"""))
        val overlay=RigEditOverlay(warpEdits=listOf(warp),structureEdits=journal)
        val result=overlay.applyTo(model())
        assertEquals(2,result.deformers.size)
        assertEquals("Sway",result.deformers.last().name)
        assertEquals(DeformerId("bang"),result.drawables.single().parentDeformerId)
        assertEquals("Sway",overlay.applyTo(model()).deformers.last().name)
    }

    @Test fun swayPinsRootAndBendsTipWithoutMutatingInput() {
        val base=model(); val g=RigGeometryTools.geometry(base,"warp","head",emptyMap())
        val result=RigGeometryTools.transform(g,Json.parseToJsonElement("""[{"type":"sway","root":[0.5,0],"tip":[0.5,1],"degrees":20,"softness":1}]""").jsonArray)
        assertContentEquals(g.points.take(4).toFloatArray(),result.take(4).toFloatArray())
        assertTrue(result[4]<g.points[4]); assertTrue(result[6]<g.points[6])
        assertEquals(0f,g.points[4])
        val d=RigGeometryDiagnostics.compare(g.points,result,RigGeometryDiagnostics.lattice(1,1))
        assertEquals(2,d.getValue("changedPointCount").jsonPrimitive.int)
        assertEquals(0,d.getValue("flippedTriangleCount").jsonPrimitive.int)
    }

    @Test fun diagnosticFindsFlippedAndCollapsedTriangles() {
        val before=floatArrayOf(0f,0f,1f,0f,0f,1f)
        val flipped=RigGeometryDiagnostics.compare(before,floatArrayOf(0f,0f,1f,0f,0f,-1f),intArrayOf(0,1,2))
        assertEquals(1,flipped.getValue("flippedTriangleCount").jsonPrimitive.int)
        val collapsed=RigGeometryDiagnostics.compare(before,floatArrayOf(0f,0f,1f,0f,0f,0f),intArrayOf(0,1,2))
        assertEquals(1,collapsed.getValue("collapsedTriangleCount").jsonPrimitive.int)
    }

    @Test fun landmarksMatchRequestedPointsAndRetainPins() {
        val g=RigGeometryTools.geometry(model(),"warp","head",emptyMap())
        val result=RigGeometryTools.transform(g,Json.parseToJsonElement("""[{"type":"landmarks","from":[[0,0],[1,1]],"to":[[0,0],[1.2,0.8]]}]""").jsonArray)
        assertEquals(0f,result[0]);assertEquals(0f,result[1])
        assertEquals(1.2f,result[6],1e-6f);assertEquals(0.8f,result[7],1e-6f)
        assertEquals(1f,g.points[6])
    }

    @Test fun legacyWarpThenRebindThenCreateReplaysInOrder() {
        val old=RigWarpEdit("old","Old","head",listOf("hair"),1,1)
        val next=RigWarpEdit("next","Next","head",listOf("hair"),1,1)
        val overlay=RigEditOverlay(warpEdits=listOf(old,next),structureEdits=listOf(
            command("""{"action":"bind","kind":"mesh","id":"hair","parent_id":"head","space":"local"}"""),
            JsonObject(next.toJson() + ("action" to JsonPrimitive("create_warp")))))
        assertEquals(DeformerId("next"),overlay.applyTo(model()).drawables.single().parentDeformerId)
    }
}
