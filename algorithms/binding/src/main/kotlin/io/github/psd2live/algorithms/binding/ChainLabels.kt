package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Host-provided display words; parameter IDs and physics slots remain stable. */
object ChainLabels {
    fun validate(context: ModifierAlgorithmContext) {
        val labels = context.values["chain_labels"] ?: return
        require(labels is JsonObject && labels.values.all { it is JsonPrimitive && it.isString }) {
            "Chain labels must be an object of strings"
        }
    }

    private fun joinIndex(kind: String, index: String): String {
        val last = kind.lastOrNull()
        val separator = if (last != null && last.code < 0x80) " " else ""
        return "$kind$separator$index"
    }

    fun formatParameter(kindWord: String, index: Int, suffix: String, word: (String) -> String): String {
        val movement = if (suffix == "Y") word("Y") else {
            val region = suffix.takeWhile { it.isLetter() }
            val segment = suffix.drop(region.length)
            listOfNotNull(
                region.takeIf { it.isNotEmpty() }?.let(word),
                word("segment").replace("{segment}", segment),
            ).joinToString(" ")
        }
        return "${joinIndex(word(kindWord), index.toString())} $movement"
    }

    fun parameter(context: ModifierAlgorithmContext, chain: ChainMotion, native: String): String {
        val labels = context.values["chain_labels"]?.jsonObject ?: return native.removePrefix(chain.kind.prefix)
        return formatParameter(chain.kind.word, chain.index, chain.suffix(native)) { key ->
            labels[key]?.jsonPrimitive?.content ?: key
        }
    }

    fun physics(context: ModifierAlgorithmContext, name: String): String {
        val labels = context.values["chain_labels"]?.jsonObject ?: return name
        val tokens = name.split(' ')
        if (tokens.size >= 2 && tokens[1].all(Char::isDigit)) {
            val head = joinIndex(labels[tokens[0]]?.jsonPrimitive?.content ?: tokens[0], tokens[1])
            val tail = tokens.drop(2).map { labels[it]?.jsonPrimitive?.content ?: it }
            return (listOf(head) + tail).joinToString(" ")
        }
        return tokens.joinToString(" ") { labels[it]?.jsonPrimitive?.content ?: it }
    }
}
