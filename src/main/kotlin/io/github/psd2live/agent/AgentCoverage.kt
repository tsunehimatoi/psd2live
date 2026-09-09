package io.github.psd2live.agent

import kotlinx.serialization.json.*
import java.awt.image.BufferedImage

/** Caller supplies the semantic expectation: every pixel in this view should be covered. */
internal fun measureCoverage(image: BufferedImage, alphaThreshold: Int): JsonObject {
    require(alphaThreshold in 1..255)
    var uncovered=0; var left=image.width; var top=image.height; var right=0; var bottom=0
    for(y in 0 until image.height) for(x in 0 until image.width) {
        if((image.getRGB(x,y) ushr 24) < alphaThreshold) {
            uncovered++; left=minOf(left,x); top=minOf(top,y); right=maxOf(right,x+1); bottom=maxOf(bottom,y+1)
        }
    }
    return buildJsonObject {
        put("pixelCount",image.width*image.height); put("uncoveredPixelCount",uncovered)
        put("uncoveredFraction",uncovered.toDouble()/(image.width*image.height))
        put("alphaThreshold",alphaThreshold)
        if(uncovered>0) put("uncoveredPixelBounds",JsonArray(listOf(left,top,right,bottom).map(::JsonPrimitive)))
        put("assumption","Every pixel in the requested rectangle is expected to be covered by the selected layers. Outside silhouettes and intentional partings count as uncovered; choose the region accordingly.")
        put("scope","Coverage of the selected rendered layers at one pose, including their masks. Does not infer hair/scalp semantics, occlusion by excluded foreground objects, or certify all motion.")
    }
}
