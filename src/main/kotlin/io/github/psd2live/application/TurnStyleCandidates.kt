package io.github.psd2live.application

import io.github.psd2live.core.recipe.RigModules
import kotlin.math.abs
import kotlin.random.Random

internal object TurnStyleCandidates {
    enum class Distance { ANCHOR, NEAR, MEDIUM, FAR }
    data class Option(val values: Map<String, Float>, val distance: Distance? = null)

    private val knobs get() = RigModules.FACE_DEPTH_TURN.knobs
    private const val PRESET = "style"

    fun defaultValues(): Map<String, Float> = knobs.associate { it.id to it.default }

    fun initialBatch(
        defaultValues: Map<String, Float> = defaultValues(),
        random: Random = Random.Default,
    ): List<Option> {
        val baseDefaults = defaultValues() + defaultValues
        val presets = (0..defaultsPresetMax()).shuffled(random)
        return List(9) { index ->
            if (index == 0) return@List Option(baseDefaults)
            val base = defaultValues() + (PRESET to presets[index % presets.size].toFloat())
            Option(perturb(base, random.nextDouble(0.11, 0.23).toFloat(), random))
        }
    }

    fun randomBatch(random: Random = Random.Default): List<Option> {
        val defaults = defaultValues()
        val presets = (0..defaultsPresetMax()).shuffled(random)
        return List(9) { index ->
            val base = defaults + (PRESET to presets[index % presets.size].toFloat())
            Option(perturb(base, random.nextDouble(0.11, 0.23).toFloat(), random))
        }
    }

    fun nearbyBatch(
        anchor: Option,
        random: Random = Random.Default,
        anchorIndex: Int = 4,
    ): List<Option> {
        val keep = anchorIndex.coerceIn(0, 8)
        val distances = (
            List(4) { Distance.NEAR } +
                List(3) { Distance.MEDIUM } +
                listOf(Distance.FAR)
            ).shuffled(random).iterator()
        return List(9) { index ->
            if (index == keep) return@List anchor.copy(distance = Distance.ANCHOR)
            val distance = distances.next()
            val radius = when (distance) {
                Distance.NEAR -> random.nextDouble(0.04, 0.07)
                Distance.MEDIUM -> random.nextDouble(0.10, 0.15)
                Distance.FAR -> random.nextDouble(0.21, 0.28)
                Distance.ANCHOR -> error("The anchor slot already holds the anchor")
            }
            Option(perturb(anchor.values, radius.toFloat(), random), distance)
        }
    }

    private fun defaultsPresetMax() = knobs.first { it.id == PRESET }.range.endInclusive.toInt()

    /** The largest fractional change across the sliders is exactly [radius], even at a range boundary. */
    private fun perturb(base: Map<String, Float>, radius: Float, random: Random): Map<String, Float> {
        val sliders = knobs.filter { it.id != PRESET }
        val direction = sliders.associateWith { random.nextDouble(-1.0, 1.0).toFloat() }
        val magnitude = direction.values.maxOf(::abs)
        if (magnitude == 0f) return perturb(base, radius, random)
        return base + sliders.associate { knob ->
            val value = base.getValue(knob.id)
            val range = knob.range
            val offset = direction.getValue(knob) / magnitude * radius * (range.endInclusive - range.start)
            val next = (value + offset).takeIf { it in range } ?: (value - offset)
            knob.id to next.coerceIn(range)
        }
    }
}
