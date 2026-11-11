package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.Part
import io.github.psd2live.format.model.RigIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModifierTargetValidationTest {
    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size get() = values.size
        override fun get(index: Int): T = values[index].also { reads++ }
    }

    private class CountedRig(size: Int = 32) {
        val ids = List(size) { "item-$it" }
        val meshes = CountedList(ids.map { Mesh(it, it, null, geometry = null, offsets = null) })
        val deformers = CountedList(ids.map { Deformer.Rotation(it, it, null, null, 0f, null) })
        val parts = CountedList(ids.map { Part(it, it, emptyList()) })
        val rig = RigIR(Canvas(100f, 100f), emptyList(), meshes = meshes, deformers = deformers, parts = parts)
        fun reads() = listOf(meshes.reads, deformers.reads, parts.reads)
    }

    private fun instance(targets: List<ModifierTarget>, id: String = "check") =
        RigModifierInstance(id, "check", 1, targets)

    private fun engine(validate: (RigIR, RigModifierInstance) -> Unit = { _, _ -> },
                       sourceTargetExists: (ModifierTarget) -> Boolean = { false },
                       plugin: ModifierAlgorithmPlugin = ModifierAlgorithmPlugin { rig, _, _ -> rig }) =
        RigModifierEvaluator(RigModifierRegistry(listOf(
            RigModifierDefinition("check", 1, validate = validate, plugin = plugin),
        )), sourceTargetExists = sourceTargetExists)

    @Test fun repeatedTargetsScanEachReachedKindOnce() {
        val counted = CountedRig()
        val targets = counted.ids.flatMap { id ->
            listOf("mesh", "deformer", "part").map { ModifierTarget(it, id) }
        }
        var validated = false
        val evaluator = engine(validate = { _, _ ->
            // Capture here, before later evaluation stages can traverse the rig.
            // The old per-target scans required 528 element reads per 32-element list.
            assertEquals(listOf(32, 32, 32), counted.reads())
            validated = true
        })

        evaluator.evaluate(counted.rig, RigModifierStack(listOf(instance(targets))))
        assertTrue(validated)
    }

    @Test fun singleTargetOfEachKindKeepsShortCircuitScans() {
        val counted = CountedRig()
        val targets = listOf("mesh", "deformer", "part").map { ModifierTarget(it, counted.ids.first()) }
        var validated = false
        val evaluator = engine(validate = { _, _ ->
            assertEquals(listOf(1, 1, 1), counted.reads())
            validated = true
        })

        evaluator.evaluate(counted.rig, RigModifierStack(listOf(instance(targets))))
        assertTrue(validated)
    }

    @Test fun firstMissingMeshStopsBeforeLaterLayersAndKinds() {
        val counted = CountedRig()
        val layerCalls = ArrayList<ModifierTarget>()
        val before = ModifierTarget("layer", "before")
        val between = ModifierTarget("layer", "between")
        val targets = listOf(before, ModifierTarget("mesh", counted.ids.first()), between,
            ModifierTarget("mesh", "missing"), ModifierTarget("layer", "later")) +
            listOf("deformer", "part").flatMap { kind -> counted.ids.take(2).map { ModifierTarget(kind, it) } }
        val evaluator = engine(validate = { _, _ -> error("Missing targets must stop validation") },
            sourceTargetExists = { layerCalls += it; true })

        val failure = assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(counted.rig, RigModifierStack(listOf(instance(targets))))
        }
        assertEquals("check: missing mesh missing", failure.message)
        assertEquals(listOf(before, between), layerCalls)
        assertEquals(listOf(32, 0, 0), counted.reads())
    }

    @Test fun firstMissingLayerStopsBeforeLaterHostKinds() {
        val counted = CountedRig()
        val layerCalls = ArrayList<ModifierTarget>()
        val before = ModifierTarget("layer", "before")
        val missing = ModifierTarget("layer", "missing")
        val targets = listOf(before, missing) + listOf("mesh", "deformer", "part").flatMap { kind ->
            counted.ids.take(2).map { ModifierTarget(kind, it) }
        } + ModifierTarget("layer", "later")
        val evaluator = engine(validate = { _, _ -> error("Missing targets must stop validation") },
            sourceTargetExists = { layerCalls += it; it != missing })

        val failure = assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(counted.rig, RigModifierStack(listOf(instance(targets))))
        }
        assertEquals("check: missing layer missing", failure.message)
        assertEquals(listOf(before, missing), layerCalls)
        assertEquals(listOf(0, 0, 0), counted.reads())
    }

    @Test fun indexesRefreshWhenTheSameEvaluatorReceivesAnotherRig() {
        val counted = CountedRig(2)
        val stack = RigModifierStack(listOf(instance(counted.ids.map { ModifierTarget("mesh", it) })))
        var validations = 0
        val evaluator = engine(validate = { _, _ -> validations++ })

        evaluator.evaluate(counted.rig, stack)
        val changed = counted.rig.copy(meshes = counted.rig.meshes.take(1))
        val failure = assertFailsWith<IllegalArgumentException> { evaluator.evaluate(changed, stack) }
        assertEquals("check: missing mesh item-1", failure.message)
        evaluator.evaluate(counted.rig, stack)
        assertEquals(2, validations)
    }

    @Test fun indexesRefreshAfterAnEarlierModifierChangesTheRig() {
        val counted = CountedRig(2)
        val targets = counted.ids.map { ModifierTarget("mesh", it) }
        val validated = ArrayList<String>()
        val evaluator = engine(validate = { _, instance -> validated += instance.id },
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig.copy(meshes = rig.meshes.take(1)) })
        val stack = RigModifierStack(listOf(instance(targets, "first"), instance(targets, "second")))

        val failure = assertFailsWith<IllegalArgumentException> { evaluator.evaluate(counted.rig, stack) }
        assertEquals("second: missing mesh item-1", failure.message)
        assertEquals(listOf("first"), validated)
    }
}
