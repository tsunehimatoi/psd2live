package io.github.psd2live.ui.views

import io.github.psd2live.core.CubismSdkFrame
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import kotlin.test.*

class PreviewInformationStateTest {
    @Test fun `animated overlays use frame pose while manual edits use live values`() {
        val values=mapOf(ParameterId("x") to 0.2f)
        val frame=CubismSdkFrame(BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),mapOf(ParameterId("x") to 0.8f))
        assertEquals(frame.parameters,informationPreviewPose(values,frame,true))
        assertEquals(values,informationPreviewPose(values,frame,false))
        assertEquals(values,informationPreviewPose(values,frame.copy(animationEnabled=false),true))
        assertEquals(values,informationPreviewPose(values,null,true))
    }

    @Test fun `rotation selection includes descendants and default layer shows all warps`() {
        val rotation=Deformer.Rotation(DeformerId("r"),"r",null,null,0f,null)
        fun warp(id:String,parent:String?)=Deformer.Warp(DeformerId(id),id,parent?.let(::DeformerId),null,1,1,true,null)
        val model=PuppetModel(emptyList(),emptyList(),listOf(rotation,warp("a","r"),warp("b","a"),warp("other",null)),emptyList(),emptyList(),null)
        assertEquals(setOf("a","b","other"),informationWarpIds(model,"r",false))
        assertEquals(setOf("a","b"),informationWarpIds(model,"r",true))
        assertEquals(setOf("a"),informationWarpIds(model,"a",true))
    }
}
