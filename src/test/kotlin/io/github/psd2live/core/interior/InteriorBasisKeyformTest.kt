package io.github.psd2live.core.interior

import io.github.psd2live.core.deletionExport
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

class InteriorBasisKeyformTest {
    @Test fun nativeLimitedAdditiveRowsAndMoc3KeepTheSameGeometry() {
        val mesh=DrawableMesh(floatArrayOf(0f,0f,50f,0f,50f,50f,0f,50f,25f,25f),FloatArray(10),intArrayOf(0,1,4,1,2,4,2,3,4,3,0,4))
        val raster=InteriorRaster(51,51,ByteArray(51*51*4) { if(it%4==3)255.toByte() else 100 })
        val source=InteriorMesh(mesh.positions,mesh.indices)
        val settings=InteriorSurfaceSettings(contour=.35f)
        val bake=InteriorBasisKeyforms.bake(source,raster,settings,emptyList(),.25,maxRawBytes=1_000_000)
        val immutable=bake.x.values.map { it.copyOf() }
        val parameters=listOf(Parameter(ParameterId("X"),"X",-30f,30f,0f,ParameterKind.BLEND_SHAPE),Parameter(ParameterId("Y"),"Y",-30f,30f,0f,ParameterKind.BLEND_SHAPE))
        val drawable=Drawable(DrawableId("cloth"),"Cloth",null,BlendMode.Normal,emptyList(),mesh,null,opacity=.73f,
            multiplyColor=ColorRgb(.8f,.9f,1f),screenColor=ColorRgb(.1f,.02f,.03f))
        val base=PuppetModel(parameters,emptyList(),emptyList(),listOf(drawable),listOf(OrgChild.Drawable(drawable.id)),null)
        val candidate=InteriorBasisKeyforms.apply(base,drawable.id,parameters,bake)
        val reopened=deletionExport(candidate)
        val evaluator=CpuDeformationEvaluator()
        for((a,b) in listOf(0f to 0f,-1f to 1f,.37f to -.71f,.99f to .001f,-.08f to .43f)) {
            val displacement=InteriorBasisKeyforms.displacements(bake,a,b)
            val expected=FloatArray(displacement.size) { mesh.positions[it]+displacement[it] }
            for(model in listOf(candidate,reopened)) {
                val frame=evaluator.evaluate(model,mapOf(parameters[0].id to a*30,parameters[1].id to b*30))
                val actual=frame.worldPositions.getValue(drawable.id)
                for(i in expected.indices) assertEquals(expected[i],if(i%2==0)actual[i] else -actual[i],.0001f)
                assertEquals(.73f,frame.opacity.getValue(drawable.id),.0001f)
            }
        }
        assertSame(mesh,candidate.drawables.single().mesh)
        assertNull(base.drawables.single().geometryGrid)
        assertTrue(base.drawables.single().blendShapes.isEmpty())
        assertEquals(parameters,candidate.parameters)
        for(i in immutable.indices)assertContentEquals(immutable[i],bake.x.values[i],"sampling must not mutate stored forms")
        assertFailsWith<IllegalArgumentException> {
            InteriorBasisKeyforms.apply(base.copy(parameters=parameters.map { it.copy(kind=ParameterKind.NORMAL) }),drawable.id,
                parameters.map { it.copy(kind=ParameterKind.NORMAL) },bake)
        }
        assertFailsWith<IllegalArgumentException> { InteriorBasisKeyforms.apply(candidate,drawable.id,parameters,bake) }
        val transposed=InteriorBasisKeyforms.bake(source,raster,settings,emptyList(),.25,maxRawBytes=1_000_000,swapAxes=true)
        val other=InteriorBasisKeyforms.apply(base,drawable.id,parameters,transposed)
        val exact=InteriorSurface.compile(source,raster,settings)
        for((a,b) in listOf(.37f to -.71f,-.08f to .43f)) {
            val frame=evaluator.evaluate(other,mapOf(parameters[0].id to a*30,parameters[1].id to b*30))
            val p=frame.worldPositions.getValue(drawable.id);val truth=exact.evaluate(a,b).positions()
            for(i in p.indices)assertEquals(truth[i],if(i%2==0)p[i] else -p[i],.25f,"transposed authoring must retain original axes")
        }
    }
}
