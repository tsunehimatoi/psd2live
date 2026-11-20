package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ChainLabelsTest {
    @Test fun labelsLocalizeSegmentsRegionsAndBounceWithoutChangingIds() {
        val regionChain = ChainMotion("hair", ChainKind.BACK, 2, Bounds(0f, 0f, 20f, 40f), 0f, 3)
        val singleChain = ChainMotion("hair", ChainKind.BACK, 1, Bounds(0f, 0f, 10f, 40f), 0f, 1)

        val zh = ModifierAlgorithmContext(buildJsonObject {
            putJsonObject("chain_labels") {
                put("Back", "后发"); put("L", "左"); put("Y", "弹跳"); put("segment", "第{segment}段")
            }
        })
        assertEquals("后发1 第2段", ChainLabels.parameter(zh, singleChain, singleChain.segment(0, 1).raw))
        assertEquals("后发2 左 第1段", ChainLabels.parameter(zh, regionChain, regionChain.segment(0, 0).raw))
        assertEquals("后发2 弹跳", ChainLabels.parameter(zh, regionChain, regionChain.bounce.raw))
        assertEquals("后发2 左", ChainLabels.physics(zh, "Back 2 L"))

        val en = ModifierAlgorithmContext(buildJsonObject {
            putJsonObject("chain_labels") {
                put("Back", "Back hair"); put("L", "L"); put("Y", "Bounce"); put("segment", "Seg {segment}")
            }
        })
        assertEquals("Back hair 1 Seg 2", ChainLabels.parameter(en, singleChain, singleChain.segment(0, 1).raw))
        assertEquals("Back hair 2 L Seg 1", ChainLabels.parameter(en, regionChain, regionChain.segment(0, 0).raw))
        assertEquals("Back hair 2 L", ChainLabels.physics(en, "Back 2 L"))

        val ja = ModifierAlgorithmContext(buildJsonObject {
            putJsonObject("chain_labels") {
                put("Back", "後ろ髪"); put("L", "左"); put("Y", "弾み"); put("segment", "第{segment}段")
            }
        })
        assertEquals("後ろ髪1 第2段", ChainLabels.parameter(ja, singleChain, singleChain.segment(0, 1).raw))
        assertEquals("後ろ髪2 弾み", ChainLabels.parameter(ja, regionChain, regionChain.bounce.raw))

        val ko = ModifierAlgorithmContext(buildJsonObject {
            putJsonObject("chain_labels") {
                put("Back", "뒷머리"); put("L", "좌"); put("Y", "바운스"); put("segment", "{segment}단")
            }
        })
        assertEquals("뒷머리1 2단", ChainLabels.parameter(ko, singleChain, singleChain.segment(0, 1).raw))
        assertEquals("뒷머리2 좌 1단", ChainLabels.parameter(ko, regionChain, regionChain.segment(0, 0).raw))

        assertEquals("Back2L1", ChainLabels.parameter(ModifierAlgorithmContext(), regionChain, regionChain.segment(0, 0).raw))
        assertEquals("ParamHairBack2L1", regionChain.segment(0, 0).raw)
    }
}
