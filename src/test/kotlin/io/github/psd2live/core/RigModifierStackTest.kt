package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class RigModifierStackTest {
    private val target = ModifierTarget("part", "body")
    private val other = ModifierTarget("part", "other")
    private val authored = RigIR(Canvas(100f, 100f), emptyList(), parts = listOf(Part("body", "Body", emptyList()), Part("other", "Other", emptyList())))
    private fun entry(id: String, type: String, enabled: Boolean = true, targets: List<ModifierTarget> = listOf(target)) =
        RigModifierInstance(id, type, 1, targets, enabled)
    private fun transform(type: String, change: (Int) -> Int, requires: Set<String> = emptySet(), family: String? = null) =
        RigModifierDefinition(type, 1, requires, family, composableKinds = setOf("part"), plugin = ModifierAlgorithmPlugin { rig, instance, checkpoint ->
            checkpoint()
            rig.copy(parts = rig.parts.map { if (ModifierTarget("part", it.id) in instance.targets) it.copy(drawOrder = change(it.drawOrder)) else it })
        })
    private val add = transform("add", { it + 10 })
    private val multiply = transform("multiply", { it * 2 })
    private val registry = RigModifierRegistry(listOf(add, multiply))
    private val evaluator = RigModifierEvaluator(registry)

    @Test fun cachedPrefixesMatchColdEvaluationAndDoNotPublishFailures() {
        val calls = ArrayList<String>()
        val counted = RigModifierDefinition("counted", 1, composableKinds = setOf("part"), plugin = ModifierAlgorithmPlugin { rig, instance, _ ->
            calls += instance.id
            require(instance.config["fail"]?.jsonPrimitive?.boolean != true)
            rig.copy(parts = rig.parts.map { it.copy(drawOrder = it.drawOrder + 1) })
        })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(counted)))
        val cache = RigModifierEvaluationCache()
        val prefix = RigModifierStack(listOf(entry("a", "counted")))
        val complete = prefix.copy(instances = prefix.instances + entry("b", "counted"))
        engine.evaluate(authored, prefix, cache)
        val warm = engine.evaluate(authored, complete, cache)
        assertEquals(listOf("a", "b"), calls)
        assertEquals(engine.evaluate(authored, complete), warm)
        val snapshot = cache.snapshot
        assertFailsWith<IllegalArgumentException> {
            engine.evaluate(authored, complete.update("b") { it.copy(config = buildJsonObject { put("fail", true) }) }, cache)
        }
        assertSame(snapshot, cache.snapshot)
        for (next in listOf(complete.move("b", 0), complete.update("a") { it.copy(enabled = false) }, complete.remove("a"))) {
            assertEquals(engine.evaluate(authored, next), engine.evaluate(authored, next, cache))
        }
        calls.clear()
        engine.evaluate(authored, complete, cache, "different source")
        assertEquals(listOf("a", "b"), calls)
        val edited = authored.copy(parts = authored.parts.map { it.copy(drawOrder = 70) })
        assertEquals(engine.evaluate(edited, complete), engine.evaluate(edited, complete, cache, "different source"))
    }

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
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig.copy(clips = rig.clips + clip) })
        val consumer = RigModifierDefinition("train", 1, motionInputs = { setOf(motionId) },
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig.copy(parts = rig.parts.map { it.copy(drawOrder = it.drawOrder + 1) }) })
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

    @Test fun capabilitiesBelongToTheSelectedVersionAndSurviveApplication() {
        val capability = transform("feature", { it })
        val provider = add.copy(version = 2, provides = setOf("feature"))
        val consumer = transform("consumer", { it * 2 }, setOf("feature"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(add, provider, capability, consumer)))
        val original = RigModifierStack(listOf(entry("provider", "add"), entry("consumer", "consumer")))
        val missing = engine.evaluate(authored, original)
        assertEquals(ModifierStatus.MISSING_DEPENDENCY, missing.entries.last().status)
        assertEquals(ModifierStatus.MISSING_DEPENDENCY, engine.evaluate(authored, original.move("consumer", 0)).entries.first().status)

        val supported = original.copy(instances = listOf(original.instances.first().copy(version = 2), original.instances.last()))
        val expected = engine.evaluate(authored, supported)
        assertEquals(1020, expected.rig.parts.first().drawOrder)
        assertFailsWith<IllegalArgumentException> { engine.evaluate(authored, supported.move("consumer", 0)) }
        val applied = engine.applyThrough(authored, supported, "provider")
        val restored = RigModifierStack.fromJson(applied.stack.toJson())
        assertEquals(setOf("feature"), restored.appliedCapabilities.single().provides)
        val withoutProvider = RigModifierEvaluator(RigModifierRegistry(listOf(add, capability, consumer)))
        assertEquals(expected.rig, withoutProvider.evaluate(applied.authored, restored).rig)

        val historical = applied.stack.copy(appliedCapabilities = applied.stack.appliedCapabilities.map { it.copy(provides = null) })
        assertEquals(expected.rig, engine.evaluate(applied.authored, RigModifierStack.fromJson(historical.toJson())).rig)
    }

    @Test fun keyformOwnershipRecordsOnlyNewKeysAndAxes() {
        val original = listOf(KeyAxis("x", Floats.values(0f)), KeyAxis("y", Floats.values(0f)))
        val widened = listOf(KeyAxis("x", Floats.values(0f, 1f)), KeyAxis("y", Floats.values(0f, 2f)))
        val delta = MeshOffsets(Floats.Empty)
        val mesh = Mesh("mesh", "Mesh", null, geometry = null,
            offsets = KeyGrid(original, listOf(KeyCell(Ints.values(0, 0), delta))))
        val opacity = KeyGrid<ChannelValue>(listOf(KeyAxis("y", Floats.values(0f))),
            listOf(KeyCell(Ints.values(0), ChannelValue.Scalar(1f))))
        val output = mesh.copy(offsets = KeyGrid(widened, listOf(
            KeyCell(Ints.values(0, 0), delta), KeyCell(Ints.values(0, 1), delta), KeyCell(Ints.values(1, 1), delta),
        )), channels = mapOf(Channel.OPACITY to opacity))
        val extend = RigModifierDefinition("extend", 1, plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig.copy(meshes = listOf(output)) })
        val result = RigModifierEvaluator(RigModifierRegistry(listOf(extend))).evaluate(authored.copy(meshes = listOf(mesh)),
            RigModifierStack(listOf(entry("extend", "extend", targets = listOf(ModifierTarget("mesh", mesh.id))))))
        assertEquals(listOf(
            ModifierKeyform("mesh", mesh.id, "y", key = mapOf("x" to 0f, "y" to 2f)),
            ModifierKeyform("mesh", mesh.id, "x", key = mapOf("x" to 1f, "y" to 2f)),
            ModifierKeyform("mesh", mesh.id, "y", channel = "opacity"),
        ), result.entries.single().ownedKeyforms)
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
        val definition = RigModifierDefinition("parameter", 1, parameters = { listOf(parameter) }, plugin = ModifierAlgorithmPlugin { rig, _, _ ->
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
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig.copy(parameters = listOf(extended)) })
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
        val definition = RigModifierDefinition("bad", 1, plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig.copy(parameters = listOf(parameter.copy(max = 2f))) })
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
        val replace = RigModifierDefinition("replace", 1, plugin = ModifierAlgorithmPlugin { rig, _, _ ->
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
            composableKinds = setOf("mesh", "relations"), plugin = ModifierAlgorithmPlugin { rig, _, _ ->
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

    @Test fun directChangesRejectUndeclaredWritesBeforeReturningACandidate() {
        val algorithm = object : ChangingModifierAlgorithmPlugin {
            override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                                 checkpoint: () -> Unit) = ModifierModelChanges(parts = ModifierObjectChanges(
                updated = listOf(ModifierObjectUpdate(input.parts.first(), input.parts.first().copy(drawOrder = 123)))))
        }
        val definition = RigModifierDefinition("direct", 1, plugin = algorithm, changeWrites = setOf("physics"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(definition)))
        val stack = RigModifierStack(listOf(entry("direct1", "direct")))
        val failure = assertFailsWith<IllegalArgumentException> { engine.evaluate(authored, stack) }
        assertTrue(failure.message.orEmpty().contains("Undeclared change sections"))
        val allowed = RigModifierEvaluator(RigModifierRegistry(listOf(definition.copy(changeWrites = setOf("part")))))
        assertEquals(123, allowed.evaluate(authored, stack).rig.parts.first().drawOrder)
        assertNotEquals(123, authored.parts.first().drawOrder)
    }
    @Test fun parallelSuccessorRechecksActualReadsBeforeApplyingInStackOrder() {
        val caller = Thread.currentThread()
        val reads = java.util.concurrent.ConcurrentLinkedQueue<Pair<Float, Boolean>>()
        val physics = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit) =
                ModifierModelChanges(physicsFps = ModifierFieldChange(input.physics.fps, 120f))
        }
        val motion = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
                val fps = requireNotNull(input.physics.fps)
                reads += fps to (Thread.currentThread() == caller)
                val clip = Clip(instance.outputId("motion"), "Read", "Read", "read", 1f, fps, false, curves = emptyList())
                return ModifierModelChanges(clips = ModifierObjectChanges(added = listOf(clip)))
            }
        }
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(
            RigModifierDefinition("physics", 1, plugin = physics, changeWrites = setOf("physics")),
            RigModifierDefinition("motion", 1, plugin = motion, changeWrites = setOf("motion")),
        )))
        val input = authored.copy(physics = Physics(fps = 30f))
        val result = engine.evaluate(input, RigModifierStack(listOf(entry("a", "physics"), entry("b", "motion"))))
        val expectedReads = if (java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() >= 2)
            listOf(30f to false, 120f to true) else listOf(120f to true)
        assertEquals(expectedReads, reads.toList())
        assertEquals(120f, result.rig.clips.single().fps)
        assertEquals(listOf("a", "b"), result.entries.map { it.instance.id })
    }

    @Test fun directReadCacheReusesOnlyUnchangedObservedInputsAfterUpstreamEdits() {
        var calls = 0
        val algorithm = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
                calls++
                val part = requireNotNull(input.part("other"))
                return ModifierModelChanges(parts = ModifierObjectChanges(updated =
                    listOf(ModifierObjectUpdate(part, part.copy(drawOrder = part.drawOrder + 1)))))
            }
        }
        val upstream = RigModifierDefinition("upstream", 1, composableKinds = setOf("part"), plugin = ModifierAlgorithmPlugin { rig, instance, _ ->
            rig.copy(parts = rig.parts.map { if (it.id == instance.targets.single().id)
                it.copy(drawOrder = instance.config.getValue("order").jsonPrimitive.content.toInt()) else it })
        })
        val direct = RigModifierDefinition("direct", 1, plugin = algorithm,
            composableKinds = setOf("part"), changeWrites = setOf("part"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(upstream, direct)))
        val cache = RigModifierEvaluationCache()
        fun stack(order: Int, targetId: String = "body") = RigModifierStack(listOf(
            entry("a", "upstream", targets = listOf(ModifierTarget("part", targetId))).copy(config = buildJsonObject { put("order", order) }),
            entry("b", "direct", targets = listOf(other))))
        engine.evaluate(authored, stack(10), cache)
        val reused = engine.evaluate(authored, stack(20), cache)
        assertEquals(1, calls)
        assertEquals(20, reused.rig.parts.first().drawOrder)
        val renamed = authored.copy(parts = authored.parts.map {
            if (it.id == "body") it.copy(name = "Renamed") else it
        })
        val afterRename = engine.evaluate(renamed, stack(20), cache, context = "renamed")
        assertEquals(1, calls)
        assertEquals("Renamed", afterRename.rig.parts.first().name)
        val edited = renamed.copy(parts = renamed.parts.map {
            if (it.id == "other") it.copy(drawOrder = 40) else it
        })
        assertEquals(41, engine.evaluate(edited, stack(20), cache, context = "edited").rig.parts.last().drawOrder)
        assertEquals(2, calls)
        val changed = engine.evaluate(authored, stack(30, "other"), cache)
        assertEquals(3, calls)
        assertEquals(31, changed.rig.parts.last().drawOrder)
        assertEquals(changed, engine.evaluate(authored, stack(30, "other")))
    }
    @Test fun resolvedCapabilitiesInvalidateReadsAndSurviveApplyingTheirProvider() {
        val observed = mutableListOf<ModifierUpstreamCapability>()
        val provider = RigModifierDefinition("provider", 3, provides = setOf("shape"), appliedConfig = { it.config },
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig })
        val algorithm = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
                val dependency = input.providers("shape").single()
                observed += dependency
                val body = requireNotNull(input.part("body"))
                return ModifierModelChanges(parts = ModifierObjectChanges(updated = listOf(ModifierObjectUpdate(body,
                    body.copy(drawOrder = dependency.config.getValue("order").jsonPrimitive.content.toInt())))))
            }
        }
        val consumer = RigModifierDefinition("consumer", 1, requires = setOf("shape"), providers = { mapOf("shape" to "source") },
            plugin = algorithm, changeWrites = setOf("part"))
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(provider, consumer)))
        fun stack(order: Int) = RigModifierStack(listOf(
            RigModifierInstance("source", "provider", 3, listOf(target), config = buildJsonObject { put("order", order) },
                outputAliases = mapOf("root" to "preserved-root")), entry("consumer1", "consumer")))
        val cache = RigModifierEvaluationCache()
        engine.evaluate(authored, stack(10), cache)
        val changed = engine.evaluate(authored, stack(20), cache)
        assertEquals(2, observed.size)
        assertEquals(20, changed.rig.parts.first().drawOrder)
        assertEquals("shape", observed.last().capability)
        assertEquals("provider", observed.last().type)
        assertEquals(3, observed.last().version)
        assertEquals("preserved-root", observed.last().outputAliases["root"])
        val applied = engine.applyThrough(authored, stack(20), "source")
        assertEquals(changed.rig, applied.result.rig)
        assertEquals(observed[1], observed.last())
        val pinned = ModifierPluginReference("example.provider", "1.2.3", "content")
        val persisted = applied.stack.copy(appliedCapabilities = applied.stack.appliedCapabilities.map { it.copy(plugin = pinned) })
        assertEquals(pinned, RigModifierStack.fromJson(persisted.toJson()).appliedCapabilities.single().plugin)
    }
    @Test fun capabilityAliasesParticipateInDependencyCycleValidation() {
        val a = RigModifierDefinition("a", 1, provides = setOf("cap-a"), requires = setOf("cap-b"), plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig })
        val b = RigModifierDefinition("b", 1, provides = setOf("cap-b"), requires = setOf("cap-a"), plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig })
        assertFailsWith<IllegalArgumentException> { RigModifierRegistry(listOf(a, b)) }
        RigModifierRegistry(listOf(a, b.copy(requires = emptySet())))
    }
}
