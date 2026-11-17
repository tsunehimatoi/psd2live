package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ChainLabelsTest {
    @Test fun labelsLocalizeSegmentsRegionsAndBounceWithoutChangingIds() {
        val chain = ChainMotion("hair", ChainKind.BACK, 2, Bounds(0f, 0f, 20f, 40f), 0f, 3)
        val context = ModifierAlgorithmContext(buildJsonObject {
            putJsonObject("chain_labels") {
                put("Back", "后发"); put("L", "左"); put("Y", "弹跳"); put("segment", "第 {segment} 段")
            }
        })
        assertEquals("后发 2 左 第 1 段", ChainLabels.parameter(context, chain, chain.segment(0, 0).raw))
        assertEquals("后发 2 弹跳", ChainLabels.parameter(context, chain, chain.bounce.raw))
        assertEquals("后发 2 左", ChainLabels.physics(context, "Back 2 L"))
        assertEquals("Back2L1", ChainLabels.parameter(ModifierAlgorithmContext(), chain, chain.segment(0, 0).raw))
        assertEquals("ParamHairBack2L1", chain.segment(0, 0).raw)
    }
}
