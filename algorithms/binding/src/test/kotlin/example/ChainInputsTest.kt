package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*
import kotlin.test.*

class ChainInputsTest {
    private fun inputs(): List<Pair<ModifierAlgorithmContext, (ModifierAlgorithmContext) -> Any>> {
        val raw = ModifierAlgorithmContext()
        return listOf(
            CreatureInputs(CreatureInputs.Measurements(emptyList(), emptyList(), "physics", "sway", "bounce"), raw).context() to CreatureInputs::decode,
            RibbonsInputs(RibbonsInputs.Measurements(emptyList(), emptySet(), emptyList(), "physics", "sway", "bounce"), raw).context() to RibbonsInputs::decode,
            SkirtInputs(SkirtInputs.Measurements(listOf(0f, 0f, 10f, 20f), emptyList(), emptySet(), emptyList(), "physics", "sway", "flare"), raw).context() to SkirtInputs::decode,
        )
    }

    @Test fun localizedInputsPreserveMeasurementsAndLegacyInputsRemainReadable() {
        for ((context, decode) in inputs()) {
            val labels = buildJsonObject { put("Ear", "耳朵"); put("segment", "第 {segment} 段") }
            val localized = ModifierAlgorithmContext(JsonObject(context.values + ("chain_labels" to labels)), context.resources)
            assertEquals(decode(context), decode(localized))
        }
    }

    @Test fun unknownFieldsAndMalformedLabelsRemainRejected() {
        for ((context, decode) in inputs()) {
            assertFailsWith<IllegalArgumentException> {
                decode(ModifierAlgorithmContext(JsonObject(context.values + ("unknown" to JsonPrimitive("value")))))
            }
            for (invalid in listOf(JsonNull, JsonPrimitive("labels"), buildJsonObject { put("Ear", 1) })) {
                assertFailsWith<IllegalArgumentException> {
                    decode(ModifierAlgorithmContext(JsonObject(context.values + ("chain_labels" to invalid))))
                }
            }
        }
    }
}
