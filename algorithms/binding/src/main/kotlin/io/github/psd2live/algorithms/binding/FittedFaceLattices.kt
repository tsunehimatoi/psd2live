package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId

/**
 * Styled lattice stage of fitted-face binding. Input uses canonical scaffold IDs and neutral frames;
 * the caller resolves instance aliases and merges generated residuals at the binding boundary.
 */
object FittedFaceLattices {
    const val SCHEMA = "fitted-face-lattices/1"

    data class Inputs(
        val source: FittedHeadTurnSource,
        val head: Bounds,
        val container: DepthTurnMotion.Container,
        val face: Bounds,
        val frames: Map<DeformerId, Bounds>,
        val fittedEyes: Set<Side>,
        val fittedBrows: Set<Side>,
    ) {
        init {
            require(frames.size <= 20_000)
            require((frames.values + face + head).all {
                it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f
            })
            require(listOf(container.pivotX, container.pivotY, container.strength, container.shellTurn,
                container.shellCrown, container.shellTilt).all(Float::isFinite))
        }

        fun encode() = ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("source", source.encode())
            put("head", bounds(head))
            put("container", FittedHeadTurnSource.tuple(container.pivotX, container.pivotY, container.strength,
                container.shellTurn, container.shellCrown, container.shellTilt))
            put("face", bounds(face))
            put("frames", JsonObject(frames.mapKeys { it.key.raw }.mapValues { bounds(it.value) }))
            put("fitted_eyes", JsonArray(fittedEyes.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }))
            put("fitted_brows", JsonArray(fittedBrows.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }))
        })

        companion object {
            fun decode(context: ModifierAlgorithmContext): Inputs {
                val value = context.values
                require(value.getValue("schema").jsonPrimitive.content == SCHEMA) { "Unknown fitted face lattice schema" }
                val container = FittedHeadTurnSource.readTuple(value.getValue("container"), 6)
                return Inputs(FittedHeadTurnSource.decode(value.getValue("source").jsonObject),
                    readBounds(value.getValue("head")),
                    DepthTurnMotion.Container(container[0], container[1], container[2], container[3], container[4], container[5]),
                    readBounds(value.getValue("face")),
                    value.getValue("frames").jsonObject.mapKeys { DeformerId(it.key) }.mapValues { readBounds(it.value) },
                    readSides(value.getValue("fitted_eyes")), readSides(value.getValue("fitted_brows")))
            }

            private fun readSides(value: JsonElement) = value.jsonArray.mapTo(linkedSetOf()) { Side.valueOf(it.jsonPrimitive.content) }
            private fun bounds(value: Bounds) = FittedHeadTurnSource.tuple(value.left, value.top, value.right, value.bottom)
            private fun readBounds(value: JsonElement): Bounds {
                val (left, top, right, bottom) = FittedHeadTurnSource.readTuple(value, 4)
                return Bounds(left, top, right, bottom)
            }
        }
    }

    fun changes(input: RigIR, source: Inputs, checkpoint: () -> Unit = {}): ModifierModelChanges {
        checkpoint()
        val turn = source.source.styledTurn() ?: return ModifierModelChanges()
        val before = PuppetIr.toPuppet(RigIR(input.canvas, parameters = emptyList(), deformers = input.deformers)).deformers
        val children = before.groupBy { it.parent }
        val descendants = linkedSetOf<DeformerId>()
        val pending = ArrayDeque<DeformerId>()
        pending += ScaffoldHead.faceId
        while (pending.isNotEmpty()) {
            checkpoint()
            for (child in children[pending.removeFirst()].orEmpty()) {
                require(child.id != ScaffoldHead.faceId && descendants.add(child.id)) { "Cyclic fitted face hierarchy" }
                pending += child.id
            }
        }
        val after = DepthTurnMotion.deformers(before, turn, source.head, source.face, source.container, source.frames,
            descendants, source.fittedEyes.featureLabels(), source.fittedBrows.featureLabels(), source.source.landmarks, checkpoint)
        val updates = input.deformers.indices.mapNotNull { i ->
            if (after[i] === before[i]) return@mapNotNull null
            val replacement = PuppetIr.deformerToIr(after[i])
            val original = input.deformers[i]
            replacement.takeUnless { it == original }?.let { ModifierObjectUpdate(original, it) }
        }
        return ModifierModelChanges(deformers = ModifierObjectChanges(updated = updates))
    }

    private fun Set<Side>.featureLabels(): Set<String> = mapNotNullTo(linkedSetOf()) { side ->
        when (side) {
            Side.LEFT -> "L"
            Side.RIGHT -> "R"
            Side.NONE -> null
        }
    }
}
