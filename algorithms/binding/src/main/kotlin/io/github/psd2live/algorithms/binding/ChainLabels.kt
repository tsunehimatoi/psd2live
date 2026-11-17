package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Host-provided display words; parameter IDs and physics slots remain stable. */
internal object ChainLabels {
    fun validate(context: ModifierAlgorithmContext) {
        val labels = context.values["chain_labels"] ?: return
        require(labels is JsonObject && labels.values.all { it is JsonPrimitive && it.isString }) {
            "Chain labels must be an object of strings"
        }
    }

    fun parameter(context: ModifierAlgorithmContext, chain: ChainMotion, native: String): String {
        val labels = context.values["chain_labels"]?.jsonObject ?: return native.removePrefix(chain.kind.prefix)
        fun word(key: String) = labels[key]?.jsonPrimitive?.content ?: key
        val suffix = chain.suffix(native)
        val movement = if (suffix == "Y") word("Y") else {
            val region = suffix.takeWhile { it.isLetter() }
            val segment = suffix.drop(region.length)
            listOfNotNull(region.takeIf { it.isNotEmpty() }?.let(::word),
                word("segment").replace("{segment}", segment)).joinToString(" ")
        }
        return "${word(chain.kind.word)} ${chain.index} $movement"
    }

    fun physics(context: ModifierAlgorithmContext, name: String): String {
        val labels = context.values["chain_labels"]?.jsonObject ?: return name
        return name.split(' ').joinToString(" ") { labels[it]?.jsonPrimitive?.content ?: it }
    }
}
