package io.github.psd2live.core

import io.github.psd2live.agent.AgentRigGeometry
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class RigGeometryToolsTest {
    @Test fun `unkeyed mesh edits preserve rest data and seed the neutral pose unchanged`() {
        val base=floatArrayOf(0f,0f,2f,0f,0f,4f,2f,4f)
        val mesh=Drawable(DrawableId("mesh"),"mesh",null,BlendMode.Normal,emptyList(),
            DrawableMesh(base,FloatArray(8),intArrayOf(0,1,2,1,3,2)),null)
        val model=PuppetModel(listOf(Parameter(ParameterId("x"),"x",0f,1f,0f)),emptyList(),emptyList(),listOf(mesh),emptyList(),null)
        val g=RigGeometryTools.geometry(model,"mesh","mesh",emptyMap())
        val changed=RigGeometryTools.transform(g,ops("""[{"type":"translate","delta":[0.1,0.2],"selection":{"indices":[0,1]}}]"""))
        assertEquals(0.2f,changed[0],1e-6f);assertEquals(0.8f,changed[1],1e-6f)
        assertEquals(base[4],changed[4])
        val edit=RigKeyformSetEdit(RigTargetRef(RigTargetKind.ART_MESH,"mesh"),mapOf("x" to 1f),
            RigKeyformGeometryEdit(positionDeltas=changed.indices.map { changed[it]-base[it] }))
        val edited=RigEditOverlay.Empty.setKeyform(edit).applyTo(model)
        assertContentEquals(base,edited.drawables.single().mesh!!.positions)
        assertContentEquals(changed,RigGeometryTools.geometry(edited,"mesh","mesh",mapOf("x" to 1f)).points)
        assertContentEquals(base,RigGeometryTools.geometry(edited,"mesh","mesh",emptyMap()).points)
    }

    private fun geometry(): RigGeometryTools.Geometry {
        val p=FloatArray(9*9*2)
        for(r in 0..8)for(c in 0..8) { val i=(r*9+c)*2;p[i]=c/8f;p[i+1]=r/8f }
        return RigGeometryTools.Geometry(p,p.copyOf(),p.copyOf(),8,8,emptyList(),1,"grid",null)
    }
    private fun ops(s:String)=Json.parseToJsonElement(s).jsonArray

    @Test fun `ordered bows and rotation act on all points and leave baseline immutable`() {
        val g=geometry()
        val a=RigGeometryTools.transform(g,ops("""[{"type":"bend","axis":"x","amount":0.1},{"type":"rotate","degrees":90}]"""))
        val b=RigGeometryTools.transform(g,ops("""[{"type":"rotate","degrees":90},{"type":"bend","axis":"x","amount":0.1}]"""))
        assertFalse(a.contentEquals(b))
        assertContentEquals(g.base,g.points)
        val bowed=RigGeometryTools.transform(g,ops("""[{"type":"bend","axis":"x","amount":0.1},{"type":"bend","axis":"y","amount":0.1}]"""))
        assertTrue(bowed[80]>0.5f && bowed[81]>0.5f)
    }

    @Test fun `point line region and index selections keep unselected points exact`() {
        val g=geometry()
        val moved=RigGeometryTools.transform(g,ops("""[{"type":"translate","delta":[0.02,0],"selection":{"center":[0,0.5],"radius":0.25}}]"""))
        assertEquals(0.02f,moved[72],1e-6f)
        assertEquals(g.points[80],moved[80])
        val line=RigGeometryTools.transform(g,ops("""[{"type":"translate","delta":[0,0.1],"selection":{"line":[0,0.5,1,0.5],"radius":0.1}}]"""))
        assertEquals(0.6f,line[81],1e-6f);assertEquals(0f,line[1])
        val indices=RigGeometryTools.transform(g,ops("""[{"type":"translate","delta":[0.1,0],"selection":{"indices":[0]}}]"""))
        assertEquals(0.1f,indices[0]);for(i in 2 until indices.size)assertEquals(g.points[i],indices[i])
        val compressed=RigGeometryTools.transform(g,ops("""[{"type":"scale","factors":[1,0.8],"pivot":[0.5,0.5],"selection":{"rect":[0,0.5,1,1]}}]"""))
        assertEquals(0.25f,compressed[37]);assertEquals(0.9f,compressed.last(),1e-6f)
    }

    @Test fun `shared selection and range apply to every ordered operation`() {
        val g=geometry()
        val operations=ops("""[{"type":"translate","delta":[0.02,0]},{"type":"translate","delta":[0,0.03]}]""")
        val selection=Json.parseToJsonElement("""{"center":[0,0.5]}""").jsonObject
        val range=Json.parseToJsonElement("""{"radius":0.25}""").jsonObject
        val result=RigGeometryTools.transform(g,operations,selection,range)
        assertEquals(0.02f,result[72],1e-6f); assertEquals(0.53f,result[73],1e-6f)
        assertEquals(g.points[80],result[80]); assertEquals(g.points[81],result[81])
        assertFailsWith<IllegalArgumentException> { RigGeometryTools.transform(g,ops("""[{"type":"bezier_set"}]""")) }
    }

    @Test fun `summary is bounded paged data matches pose and edits survive overlay`() {
        val g=geometry()
        val warp=Deformer.Warp(DeformerId("w"),"w",null,null,8,8,true,
            KeyformGrid(listOf(KeyformAxis(ParameterId("x"),floatArrayOf(-1f,0f,1f))),(-1..1).map { x ->
                KeyformCell(intArrayOf(x+1),WarpLatticeForm(g.points.mapIndexed { i,v -> if(i%2==0)v+x*0.1f else v }.toFloatArray())) }))
        val model=PuppetModel(parameters=listOf(Parameter(ParameterId("x"),"x",-1f,1f,0f)),deformers=listOf(warp),drawables=emptyList(),parts=emptyList(),rootChildren=emptyList(),rootPartId=null)
        fun args(detail:String,offset:Int=0)=Json.parseToJsonElement("""{"target":{"kind":"warp","id":"w"},"coordinate":{"x":0.5},"detail":"$detail","offset":$offset,"limit":3}""").jsonObject
        val summary=AgentRigGeometry.inspect(model,args("summary"),"r")
        assertFalse(summary.getValue("nativeBezier").jsonObject.getValue("available").jsonPrimitive.boolean);assertNull(summary["points"]);assertEquals(81,summary.getValue("pointCount").jsonPrimitive.int)
        val page=AgentRigGeometry.inspect(model,args("points",3),"r")
        assertEquals(6,page.getValue("nextOffset").jsonPrimitive.int)
        assertEquals(0.425f,page.getValue("points").jsonArray.first().jsonArray[1].jsonPrimitive.float,1e-6f)
        val current=RigGeometryTools.geometry(model,"warp","w",mapOf("x" to 0.5f))
        val changed=RigGeometryTools.transform(current,ops("""[{"type":"translate","delta":[0.01,0]}]"""))
        val edited=RigEditOverlay.Empty.setKeyform(RigKeyformSetEdit(RigTargetRef(RigTargetKind.WARP_DEFORMER,"w"),mapOf("x" to 0.5f),RigKeyformGeometryEdit(controlPoints=changed.toList()))).applyTo(model)
        assertEquals(changed[0],RigGeometryTools.geometry(edited,"warp","w",mapOf("x" to 0.5f)).points[0],1e-6f)
        assertEquals(0f,RigGeometryTools.geometry(edited,"warp","w",mapOf("x" to 0f)).points[0],1e-6f)
        assertFailsWith<IllegalArgumentException> { RigGeometryTools.transform(g,ops("""[{"type":"scale","factors":[-1,1]}]""")) }
    }
}
