package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.Part
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import kotlin.test.*

class RigModifierStackTest {
    private val target = ModifierTarget("part", "body")
    private val other = ModifierTarget("part", "other")
    private val authored = RigIR(Canvas(100f, 100f), emptyList(), parts = listOf(Part("body", "Body", emptyList()), Part("other", "Other", emptyList())))
    private fun entry(id: String, type: String, enabled: Boolean = true, targets: List<ModifierTarget> = listOf(target)) =
        RigModifierInstance(id, type, 1, targets, enabled)
    private fun transform(type: String, change: (Int) -> Int, requires: Set<String> = emptySet(), family: String? = null) =
        RigModifierDefinition(type, 1, requires, family, composableKinds = setOf("part"), evaluate = { rig, instance, checkpoint ->
            checkpoint()
            rig.copy(parts = rig.parts.map { if (ModifierTarget("part", it.id) in instance.targets) it.copy(drawOrder = change(it.drawOrder)) else it })
        })
    private val add = transform("add", { it + 10 })
    private val multiply = transform("multiply", { it * 2 })
    private val registry = RigModifierRegistry(listOf(add, multiply))
    private val evaluator = RigModifierEvaluator(registry)

    @Test fun sequenceChangesTheEvaluatedResultAndLeavesAuthoredStateUntouched() {
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "multiply")))
        assertEquals(1020, evaluator.evaluate(authored, stack).rig.parts.first().drawOrder)
        assertEquals(1010, evaluator.evaluate(authored, stack.move("b", 0)).rig.parts.first().drawOrder)
        assertEquals(500, authored.parts.first().drawOrder)
    }

    @Test fun motionDependenciesSuspendConsumersRejectMovesAndSurviveApplication() {
        val provider = entry("motion", "clip").copy(outputAliases = mapOf("motion" to "existing-motion"))
        val motionId = provider.outputId("motion")
        val clip = io.github.psd2live.format.model.Clip(motionId, "Training", "Idle", "training", 1f, 30f, true, curves = emptyList())
        val producer = RigModifierDefinition("clip", 1, motionOutputs = { setOf(it.outputId("motion")) },
            evaluate = { rig, _, _ -> rig.copy(clips = rig.clips + clip) })
        val consumer = RigModifierDefinition("train", 1, motionInputs = { setOf(motionId) },
            evaluate = { rig, _, _ -> rig.copy(parts = rig.parts.map { it.copy(drawOrder = it.drawOrder + 1) }) })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(producer, consumer)))
        val stack = RigModifierStack(listOf(provider, entry("simulation", "train")))
        val expected = engine.evaluate(authored, stack)
        assertTrue(expected.entries.all { it.status == ModifierStatus.ACTIVE })
        assertFailsWith<IllegalArgumentException> { engine.evaluate(authored, stack.move("simulation", 0)) }
        for (missing in listOf(stack.update("motion") { it.copy(enabled = false) }, stack.remove("motion"))) {
            val result = engine.evaluate(authored, missing)
            assertEquals(ModifierStatus.MISSING_DEPENDENCY, result.entries.last().status)
            assertEquals(setOf("motion:$motionId"), result.entries.last().missingDependencies)
            assertEquals(authored, result.rig)
        }
        val applied = engine.applyThrough(authored, stack, "motion")
        assertEquals(expected.rig, engine.evaluate(applied.authored, applied.stack).rig)
        assertEquals(expected.rig, engine.evaluate(authored, stack).rig)
    }

    @Test fun anObjectOverrideCannotMoveAheadOfItsReferencedGenerator() {
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "multiply"))).withOverrides("b", listOf(buildJsonObject {
            put("op", "structure")
            putJsonArray("providers") { add("a") }
        }))
        evaluator.validateOrder(stack)
        val failure = assertFailsWith<IllegalArgumentException> { evaluator.validateOrder(stack.move("b", 0)) }
        assertContains(failure.message.orEmpty(), "b must follow a")
        evaluator.validateOrder(stack.remove("a"))
    }

    @Test fun migratedOutputAliasesRoundTripAndCannotBeChangedOrInherited() {
        val stack = RigModifierStack().add("motion", 1, listOf(target), outputAliases = mapOf("motion" to "Idle"))
        val restored = RigModifierStack.fromJson(stack.toJson())
        val entry = restored.instances.single()
        assertEquals("Idle", entry.outputId("motion"))
        assertEquals("motion", entry.localOutputName("Idle"))
        assertEquals("another", entry.localOutputName(entry.outputId("another")))
        assertFailsWith<IllegalArgumentException> { restored.update(entry.id) { it.copy(outputAliases = emptyMap()) } }
        assertEquals("Idle", restored.update(entry.id) { it.copy(enabled = false) }.instances.single().outputId("motion"))
        val added = restored.remove(entry.id).add("motion", 1, listOf(target)).instances.single()
        assertNotEquals("Idle", added.outputId("motion"))
        assertTrue(added.outputAliases.isEmpty())
    }

    @Test fun multipleInstancesKeepTheirCapturedTargets() {
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "add", targets = listOf(other))))
        val result = evaluator.evaluate(authored, stack)
        assertEquals(listOf(510, 510), result.rig.parts.map { it.drawOrder })
        assertEquals(listOf("body"), result.entries.first().contributions.map { it.id })
    }

    @Test fun disableAndRemoveDoNotRunTheAlgorithm() {
        val stack = RigModifierStack(listOf(entry("a", "add", false), entry("b", "multiply")))
        val result = evaluator.evaluate(authored, stack)
        assertEquals(1000, result.rig.parts.first().drawOrder)
        assertEquals(ModifierStatus.DISABLED, result.entries.first().status)
        assertEquals(result.rig, evaluator.evaluate(authored, stack.remove("a")).rig)
    }

    @Test fun removedInstanceKeepsDormantOverridesAndCannotBeReused() {
        val commands = listOf(buildJsonObject { put("drawOrder", 12) })
        val stack = RigModifierStack().add("add", 1, listOf(target)).withOverrides("m1", commands)
        val removed = stack.remove("m1")
        val added = removed.add("add", 1, listOf(target))
        assertEquals("m2", added.instances.single().id)
        assertEquals(commands, added.overrides["m1"])
        assertNull(added.overrides["m2"])
        assertFailsWith<IllegalArgumentException> { removed.copy(instances = stack.instances) }
    }

    @Test fun dormantOverrideDoesNotLeakOntoAnotherInstance() {
        val edits = listOf(buildJsonObject { put("value", 3) })
        val replay = RigModifierEvaluator(registry) { rig, instance, commands ->
            ModifierOverrideResult(if (commands.isEmpty()) rig else rig.copy(parts = rig.parts.map {
                if (ModifierTarget("part", it.id) in instance.targets) it.copy(drawOrder = commands.single().getValue("value").jsonPrimitive.int) else it
            }))
        }
        val stack = RigModifierStack(listOf(entry("a", "add"))).withOverrides("a", edits)
        assertEquals(3, replay.evaluate(authored, stack).rig.parts.first().drawOrder)
        assertEquals(500, replay.evaluate(authored, stack.update("a") { it.copy(enabled = false) }).rig.parts.first().drawOrder)
        assertEquals(510, replay.evaluate(authored, stack.remove("a").add("add", 1, listOf(target))).rig.parts.first().drawOrder)
    }

    @Test fun missingDependencySuspendsTheWholeDependentChain() {
        val child = transform("child", { it * 2 }, setOf("add"))
        val grandchild = transform("grandchild", { it - 30 }, setOf("child"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, child, grandchild)))
        val stack = RigModifierStack(listOf(entry("a", "add", false), entry("b", "child"), entry("c", "grandchild")))
        val result = engine.evaluate(authored, stack)
        assertEquals(authored, result.rig)
        assertEquals(listOf(ModifierStatus.DISABLED, ModifierStatus.MISSING_DEPENDENCY, ModifierStatus.MISSING_DEPENDENCY), result.entries.map { it.status })
        assertEquals(setOf("add"), result.entries[1].missingDependencies)
        assertEquals(setOf("child"), result.entries[2].missingDependencies)
    }

    @Test fun dependenciesMustCoverEveryTargetAndPrecedeTheConsumer() {
        val child = transform("child", { it * 2 }, setOf("add"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, child)))
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "child")))
        assertFailsWith<IllegalArgumentException> { engine.evaluate(authored, stack.move("b", 0)) }
        val wrongTarget = stack.update("a") { it.copy(targets = listOf(other)) }
        assertEquals(ModifierStatus.MISSING_DEPENDENCY, engine.evaluate(authored, wrongTarget).entries.last().status)
    }

    @Test fun registryRejectsCyclesUnknownDependenciesAndDuplicateVersions() {
        assertFailsWith<IllegalArgumentException> { RigModifierRegistry(listOf(add, add)) }
        assertFailsWith<IllegalArgumentException> { RigModifierRegistry(listOf(transform("a", { it }, setOf("unknown")))) }
        assertFailsWith<IllegalArgumentException> {
            RigModifierRegistry(listOf(transform("a", { it }, setOf("b")), transform("b", { it }, setOf("a"))))
        }
    }

    @Test fun mutuallyExclusiveBasesAreCheckedPerTarget() {
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(transform("a", { it + 1 }, family = "base"), transform("b", { it + 2 }, family = "base"))))
        val stack = RigModifierStack(listOf(entry("a", "a"), entry("b", "b")))
        assertFailsWith<IllegalArgumentException> { engine.evaluate(authored, stack) }
        assertEquals(listOf(501, 502), engine.evaluate(authored, stack.update("b") { it.copy(targets = listOf(other)) }).rig.parts.map { it.drawOrder })
    }

    @Test fun applyingPrefixPreservesFinalResultAndOrdinaryEditableContent() {
        val child = transform("child", { it * 2 }, setOf("add"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, child)))
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "child")))
        val applied = engine.applyThrough(authored, stack, "a")
        assertEquals(510, applied.authored.parts.first().drawOrder)
        assertEquals(listOf("b"), applied.stack.instances.map { it.id })
        assertEquals(engine.evaluate(authored, stack).rig, applied.result.rig)
        assertEquals(ModifierStatus.ACTIVE, applied.result.entries.single().status)
        val edited = applied.authored.copy(parts = applied.authored.parts.map { it.copy(drawOrder = 20) })
        assertEquals(40, engine.evaluate(edited, applied.stack).rig.parts.first().drawOrder)
    }

    @Test fun applyingPrefixConsumesDisabledInstancesWithoutApplyingTheirEffects() {
        val edits = listOf(buildJsonObject { put("value", 42) })
        val stack = RigModifierStack(listOf(entry("a", "add", false), entry("b", "multiply"))).withOverrides("a", edits)
        val applied = evaluator.applyThrough(authored, stack, "a")
        assertEquals(authored, applied.authored)
        assertEquals(setOf("a"), applied.stack.retiredIds)
        assertTrue(applied.stack.appliedCapabilities.isEmpty())
        assertEquals(edits, applied.stack.overrides["a"], "disabled overrides were not baked and must remain dormant")
        assertEquals(1000, applied.result.rig.parts.first().drawOrder)
    }

    @Test fun applyingPrefixConsumesOverridesOnlyForThatPrefix() {
        val commands = listOf(buildJsonObject { put("value", 42) })
        val engine = RigModifierEvaluator(registry) { rig, _, edits ->
            ModifierOverrideResult(if (edits.isEmpty()) rig else rig.copy(parts = rig.parts.map { it.copy(drawOrder = 42) }))
        }
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "multiply"))).withOverrides("a", commands).withOverrides("b", commands)
        val applied = engine.applyThrough(authored, stack, "a")
        assertEquals(42, applied.authored.parts.first().drawOrder)
        assertFalse("a" in applied.stack.overrides)
        assertEquals(commands, applied.stack.overrides["b"])
        assertEquals(engine.evaluate(authored, stack).rig, applied.result.rig)
    }

    @Test fun applyingBrokenPrefixIsRejectedWithoutChangingItsInput() {
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, transform("child", { it * 2 }, setOf("add")))))
        val stack = RigModifierStack(listOf(entry("b", "child")))
        assertFailsWith<IllegalArgumentException> { engine.applyThrough(authored, stack, "b") }
        assertEquals(listOf("b"), stack.instances.map { it.id })
        assertTrue(stack.retiredIds.isEmpty())
    }

    @Test fun parameterDefinitionsCanBeSharedButIncompatibleDefinitionsFail() {
        val parameter = Parameter("ParamShared", "Shared", -1f, 1f, 0f)
        val definition = RigModifierDefinition("parameter", 1, parameters = { listOf(parameter) }, evaluate = { rig, _, _ ->
            if (rig.parameters.any { it.id == parameter.id }) rig else rig.copy(parameters = rig.parameters + parameter)
        })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(definition)))
        val stack = RigModifierStack(listOf(entry("a", "parameter"), entry("b", "parameter")))
        val result = engine.evaluate(authored, stack)
        assertEquals(listOf(parameter), result.rig.parameters)
        assertEquals(ModifierChange.ADDED, result.entries.first().contributions.single().change)
        assertEquals(ModifierChange.REUSED, result.entries.last().contributions.single().change)
        assertFailsWith<IllegalArgumentException> { engine.evaluate(authored.copy(parameters = listOf(parameter.copy(max = 2f))), stack) }
    }

    @Test fun explicitParameterExtensionsReportModificationAndDoNotAuthorizeUnrelatedOverrides() {
        val parameter = Parameter("P", "P", 0f, 1f, 1f)
        val extended = parameter.copy(max = 2f)
        val definition = RigModifierDefinition("extend", 1, parameters = { listOf(extended) },
            composableKinds = setOf("parameter"),
            resolveParameter = { _, before, desired -> desired.takeIf { before.copy(max = desired.max) == desired } },
            evaluate = { rig, _, _ -> rig.copy(parameters = listOf(extended)) })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(definition))) { rig, _, commands ->
            ModifierOverrideResult(if (commands.isEmpty()) rig else rig.copy(parameters = listOf(extended.copy(default = .5f))))
        }
        val input = authored.copy(parameters = listOf(parameter))
        val stack = RigModifierStack(listOf(entry("a", "extend"), entry("b", "extend")))
        val result = engine.evaluate(input, stack)
        assertEquals(listOf(extended), result.rig.parameters)
        assertEquals(listOf(ModifierContribution("parameter", "P", ModifierChange.MODIFIED)), result.entries.first().contributions)
        assertEquals(listOf(ModifierContribution("parameter", "P", ModifierChange.REUSED)), result.entries.last().contributions)
        assertEquals(result.rig, engine.applyThrough(input, stack, "a").let { engine.evaluate(it.authored, it.stack).rig })
        assertFailsWith<IllegalArgumentException> {
            engine.evaluate(input, stack.withOverrides("a", listOf(buildJsonObject { put("bad", true) })))
        }
        assertEquals(listOf(parameter), input.parameters)
    }

    @Test fun adaptersCannotSilentlyChangeSharedParameterDefinitions() {
        val parameter = Parameter("P", "P", 0f, 1f, 0f)
        val definition = RigModifierDefinition("bad", 1, evaluate = { rig, _, _ -> rig.copy(parameters = listOf(parameter.copy(max = 2f))) })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(definition)))
        assertFailsWith<IllegalArgumentException> { engine.evaluate(authored.copy(parameters = listOf(parameter)), RigModifierStack(listOf(entry("a", "bad")))) }
    }

    @Test fun missingTargetsUnknownVersionsAndCancellationFailWithoutPublishingResults() {
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "multiply")))
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(authored, stack.update("a") { it.copy(targets = listOf(ModifierTarget("mesh", "missing"))) }) }
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(authored, stack.copy(instances = listOf(entry("a", "add").copy(version = 2)))) }
        var checkpoints = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            evaluator.evaluate(authored, stack) { if (++checkpoints == 4) throw java.util.concurrent.CancellationException() }
        }
        assertEquals(500, authored.parts.first().drawOrder)
        assertEquals(listOf("a", "b"), stack.instances.map { it.id })
    }

    @Test fun stateRoundTripKeepsVersionsOrderTargetsDisabledStateAndDormantOverrides() {
        val edits = listOf(buildJsonObject { put("op", "example"); put("value", 2) })
        val stack = RigModifierStack().add("add", 1, listOf(target), buildJsonObject { put("amount", 10) }, "recommended")
            .withOverrides("m1", edits).remove("m1").add("multiply", 1, listOf(other)).update("m2") { it.copy(enabled = false) }
        assertEquals(stack, RigModifierStack.fromJson(stack.toJson()))
        assertEquals("m3", RigModifierStack.fromJson(stack.toJson()).add("add", 1, listOf(target)).instances.last().id)
    }

    @Test fun outputIdsAreStableAndDoNotAliasDistinctLocalNames() {
        val a = entry("a", "add"); val b = entry("b", "add")
        assertEquals(a.outputId("眉:左"), a.outputId("眉:左"))
        assertNotEquals(a.outputId("a:b"), a.outputId("a_b"))
        assertNotEquals(a.outputId("x"), b.outputId("x"))
        assertFailsWith<IllegalArgumentException> { a.outputId("") }
    }

    @Test fun incompatibleWritesAreRejectedAndCompositionRequiresBothProvidersToOptIn() {
        val replace = RigModifierDefinition("replace", 1, evaluate = { rig, _, _ ->
            rig.copy(parts = rig.parts.map { it.copy(name = "Replaced") })
        })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, replace)))
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "replace")))
        val failure = assertFailsWith<IllegalArgumentException> { engine.evaluate(authored, stack) }
        assertTrue(failure.message.orEmpty().contains("incompatible writes"))
        assertEquals("Body", authored.parts.first().name)
    }

    @Test fun layerReferencesNeedAnExplicitSourceResolver() {
        val layer = ModifierTarget("layer", "source-layer")
        val stack = RigModifierStack(listOf(entry("a", "add", targets = listOf(layer))))
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate(authored, stack) }
        assertEquals(authored, RigModifierEvaluator(registry, sourceTargetExists = { it == layer }).evaluate(authored, stack).rig)
    }

    @Test fun appliedCapabilitiesAndOverridesSurviveSerialization() {
        val stack = RigModifierStack(listOf(entry("a", "add"), entry("b", "multiply")))
        val applied = evaluator.applyThrough(authored, stack, "a")
        val restored = RigModifierStack.fromJson(applied.stack.toJson())
        assertEquals(applied.stack, restored)
        assertEquals(applied.result.rig, evaluator.evaluate(applied.authored, restored).rig)
    }
    @Test fun maskReportsKeepEachStagesActualSourcesAndInversion() {
        fun mesh(id: String) = io.github.psd2live.format.model.Mesh(id, id, null, geometry = null, offsets = null)
        val input = authored.copy(meshes = listOf(mesh("overlay"), mesh("face"), mesh("neck")))
        fun masks(type: String, sources: List<String>, inverted: Boolean = false) = RigModifierDefinition(type, 1,
            composableKinds = setOf("mesh", "relations"), evaluate = { rig, _, _ ->
                rig.copy(meshes = rig.meshes.map { if (it.id == "overlay") it.copy(maskedBy = sources, invertMask = inverted) else it })
            })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(masks("faceMask", listOf("face"), true),
            masks("neckMask", listOf("neck")), masks("clearMask", emptyList()))))
        val stack = RigModifierStack(listOf(entry("a", "faceMask"), entry("b", "neckMask"), entry("c", "clearMask")))
        val result = engine.evaluate(input, stack)
        val relations = result.entries.map { it.contributions.single { it.kind == "relations" } }
        assertEquals(listOf(ModifierChange.ADDED, ModifierChange.MODIFIED, ModifierChange.REMOVED), relations.map { it.change })
        assertEquals(ModifierMaskRelation("overlay", emptyList(), listOf("face"), false, true), relations[0].mask)
        assertEquals(ModifierMaskRelation("overlay", listOf("face"), listOf("neck"), true, false), relations[1].mask)
        assertEquals(ModifierMaskRelation("overlay", listOf("neck"), emptyList(), false, false), relations[2].mask)
        val report = result.report().getValue("entries").jsonArray.first().jsonObject.getValue("contributions").jsonArray
            .map { it.jsonObject }.single { it["kind"]?.jsonPrimitive?.content == "relations" }.getValue("mask").jsonObject
        assertEquals("face", report.getValue("after").jsonArray.single().jsonPrimitive.content,
            "a contribution must describe its stage, even when a later stage replaces or removes the relation")
        assertTrue(report.getValue("after_inverted").jsonPrimitive.boolean)
        assertEquals(result.rig, engine.applyThrough(input, stack, "a").result.rig)
    }

}
