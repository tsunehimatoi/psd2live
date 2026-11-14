package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSourceArt
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceBuildPlanTest {
    private val source = WorkspaceSourceArt(16, 16, emptyList(), emptyList())
    private val target = listOf(ModifierTarget("mesh", "mesh"))
    private fun subConfig(value: JsonElement = JsonPrimitive(1)) = buildJsonObject {
        put("wide", true)
        putJsonObject("subConfigs") {
            putJsonObject("eye.wide") {
                putJsonArray("targets") { add("mesh") }
                putJsonObject("values") { put("strength", value) }
            }
        }
    }
    private val prefix = RigModifierInstance("prefix", "probe", 1, target)
    private val part = RigModifierInstance("part", "eye", 1, target, config = subConfig())
    private val suffix = RigModifierInstance("suffix", "probe", 1, target)
    private val stack = RigModifierStack(listOf(prefix, part, suffix))
    private fun inputs(stack: RigModifierStack = this.stack): WorkspaceBuildInputs {
        val overlay = RigEditOverlay(modifierStack = stack.toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay)
        return WorkspaceBuildInputs(document, PipelineConfig(rigEdits = overlay), 7, "en", false)
    }
    private fun contracts(vararg stacks: RigModifierStack) = stacks.flatMap { it.instances }.mapTo(HashSet(), ModifierBuildIdentity::of)
    private fun changedPart(config: JsonObject = subConfig(JsonPrimitive(2))) = stack.copy(instances = listOf(prefix, part.copy(config = config), suffix))
    private fun assertFull(plan: WorkspaceBuildPlan, reason: WorkspaceBuildReason) {
        assertTrue(plan.fullyInvalidated)
        assertEquals(setOf(reason), plan.stages.mapTo(HashSet()) { it.reason })
        assertTrue(plan.modifiers.all { it.action == WorkspaceBuildAction.INVALIDATE })
    }

    @Test fun scalarSubfeatureChangeChecksOnlyAuthorInputsAndOrderedPrefix() {
        val changed = changedPart()
        val plan = WorkspaceBuildPlanner.plan(inputs(), inputs(changed), contracts(stack))
        assertEquals(WorkspaceBuildPilot.PART_SUB_CONFIG, plan.pilot)
        assertEquals(listOf("prefix", "part", "suffix"), plan.modifiers.map { it.id })
        assertEquals(listOf(WorkspaceBuildReason.UNCHANGED_PREFIX, WorkspaceBuildReason.PART_SUB_CONFIG_CHANGED,
            WorkspaceBuildReason.ORDERED_SUCCESSOR), plan.modifiers.map { it.reason })
        assertEquals(setOf(WorkspaceBuildStage.SOURCE_PIXELS, WorkspaceBuildStage.SOURCE_TOPOLOGY, WorkspaceBuildStage.AUTHOR_INPUT),
            plan.stages.filter { it.action == WorkspaceBuildAction.CHECK_EXISTING_KEY }.mapTo(HashSet()) { it.stage })
        assertEquals(stack.toJson(), inputs().document.rigEdits.modifierStack)
    }

    @Test fun appendedSplitInvalidatesTopologyAtlasAndTargetWithoutSortingExistingStack() {
        val split = RigModifierInstance("split", "mesh.split", 1, target)
        val changed = stack.copy(instances = stack.instances + split, nextId = 9)
        val plan = WorkspaceBuildPlanner.plan(inputs(), inputs(changed), contracts(stack, changed))
        assertEquals(WorkspaceBuildPilot.APPEND_MESH_SPLIT, plan.pilot)
        assertEquals(listOf("prefix", "part", "suffix", "split"), plan.modifiers.map { it.id })
        assertTrue(plan.modifiers.dropLast(1).all { it.action == WorkspaceBuildAction.CHECK_EXISTING_KEY })
        assertEquals(WorkspaceBuildAction.INVALIDATE, plan.modifiers.last().action)
        assertTrue(plan.stages.filter { it.stage in setOf(WorkspaceBuildStage.MODIFIERS, WorkspaceBuildStage.ATLAS, WorkspaceBuildStage.TARGET) }
            .all { it.action == WorkspaceBuildAction.INVALIDATE })
    }

    @Test fun unknownReadContractsAndDisabledCacheAlwaysInvalidateEvenIdenticalInputs() {
        assertFull(WorkspaceBuildPlanner.plan(inputs(), inputs()), WorkspaceBuildReason.UNKNOWN_READS)
        assertFull(WorkspaceBuildPlanner.plan(inputs(), inputs(), contracts(stack), cacheEnabled = false), WorkspaceBuildReason.CACHE_DISABLED)
        assertFull(WorkspaceBuildPlanner.plan(null, inputs(), contracts(stack)), WorkspaceBuildReason.FIRST_BUILD)
        val known = WorkspaceBuildPlanner.plan(inputs(), inputs(), contracts(stack))
        assertTrue(known.stages.all { it.action == WorkspaceBuildAction.CHECK_EXISTING_KEY })
    }

    @Test fun sourceAndAllCapturedExternalInputsInvalidateThePlan() {
        val before = inputs()
        val after = inputs(changedPart())
        val cases = listOf(
            after.copy(document = after.document.copy(source = source.copy(widthPx = 32))) to WorkspaceBuildReason.SOURCE_CHANGED,
            after.copy(pluginRevision = 8) to WorkspaceBuildReason.PLUGINS_CHANGED,
            after.copy(language = "zh") to WorkspaceBuildReason.LANGUAGE_CHANGED,
            after.copy(rawSourceRotations = true) to WorkspaceBuildReason.EXECUTION_CHANGED,
            after.copy(config = after.config.copy(atlasSize = 128)) to WorkspaceBuildReason.CONFIG_CHANGED,
            after.copy(document = after.document.copy(settings = buildJsonObject { put("futureSetting", 1) })) to WorkspaceBuildReason.DOCUMENT_CHANGED,
            after.copy(document = after.document.copy(meshSource = source.copy(heightPx = 32))) to WorkspaceBuildReason.DOCUMENT_CHANGED,
        )
        for ((candidate, reason) in cases) assertFull(WorkspaceBuildPlanner.plan(before, candidate, contracts(stack)), reason)
    }

    @Test fun identityProvidersOverridesAndOrderChangesNeverUseTheLocalPilot() {
        val changed = changedPart()
        val variants = listOf(
            changed.copy(instances = changed.instances.reversed()),
            changed.copy(instances = listOf(prefix, changed.instances[1].copy(enabled = false), suffix)),
            changed.copy(instances = listOf(prefix, changed.instances[1].copy(targets = listOf(ModifierTarget("mesh", "other"))), suffix)),
            changed.copy(instances = listOf(prefix, changed.instances[1].copy(parameterProviders = mapOf("p" to "prefix")), suffix)),
            changed.copy(overrides = mapOf("part" to listOf(buildJsonObject { put("op", "edit") }))),
            changed.copy(retiredIds = setOf("retired")),
        )
        for (candidate in variants) assertFull(WorkspaceBuildPlanner.plan(inputs(), inputs(candidate), contracts(stack, candidate)), WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
    }

    @Test fun exactPluginIdentityIsPartOfTheReadContract() {
        val pin = ModifierPluginReference("example", "1", "a".repeat(64))
        val pinned = stack.copy(instances = listOf(prefix, part.copy(plugin = pin), suffix))
        val changed = pinned.copy(instances = listOf(prefix, pinned.instances[1].copy(config = subConfig(JsonPrimitive(2))), suffix))
        assertFull(WorkspaceBuildPlanner.plan(inputs(pinned), inputs(changed), contracts(stack)), WorkspaceBuildReason.UNKNOWN_READS)
        assertEquals(WorkspaceBuildPilot.PART_SUB_CONFIG, WorkspaceBuildPlanner.plan(inputs(pinned), inputs(changed), contracts(pinned)).pilot)
        val upgraded = changed.copy(instances = listOf(prefix, changed.instances[1].copy(plugin = pin.copy(digest = "b".repeat(64))), suffix))
        assertFull(WorkspaceBuildPlanner.plan(inputs(pinned), inputs(upgraded), contracts(pinned, upgraded)), WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
    }

    @Test fun nestedTargetsDependenciesAndUnknownValuesDoNotCountAsScalarSubfeatureEdits() {
        val old = part.config.getValue("subConfigs").jsonObject
        val child = old.getValue("eye.wide").jsonObject
        val edits = listOf(
            JsonObject(child + ("targets" to buildJsonArray { add("other") })),
            JsonObject(child + ("dependencies" to buildJsonObject { put("rig", "prefix") })),
            JsonObject(child + ("values" to buildJsonObject { put("strength", "2") })),
            JsonObject(child + ("values" to buildJsonObject { put("strength", 2); put("newKnob", 1) })),
        )
        for (edit in edits) {
            val config = JsonObject(part.config + ("subConfigs" to JsonObject(old + ("eye.wide" to edit))))
            assertFull(WorkspaceBuildPlanner.plan(inputs(), inputs(changedPart(config)), contracts(stack)), WorkspaceBuildReason.UNSUPPORTED_EDIT)
        }
    }

    @Test fun validStackStructuralEditsReturnConservativePlansInsteadOfThrowingDuringComparison() {
        val split = RigModifierInstance("split", "mesh.split", 1, target)
        val appended = stack.copy(instances = stack.instances + split, overrides = mapOf("split" to listOf(buildJsonObject { put("op", "edit") })))
        assertFull(WorkspaceBuildPlanner.plan(inputs(), inputs(appended), contracts(stack, appended)), WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
        val owned = stack.copy(overrides = mapOf("part" to listOf(buildJsonObject { put("op", "edit") })))
        val replaced = stack.copy(instances = listOf(prefix, part.copy(id = "replacement"), suffix))
        assertFull(WorkspaceBuildPlanner.plan(inputs(owned), inputs(replaced), contracts(stack, replaced)), WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
    }

    @Test fun ordinaryJournalOrOverlayChangesCannotBeHiddenInsideAStackEdit() {
        val after = inputs(changedPart())
        val overlay = after.document.rigEdits.copy(authoringJournal = listOf(buildJsonObject { put("op", "future_edit") }))
        val changed = after.copy(document = after.document.copy(rigEdits = overlay), config = after.config.copy(rigEdits = overlay))
        assertFull(WorkspaceBuildPlanner.plan(inputs(), changed, contracts(stack)), WorkspaceBuildReason.DOCUMENT_CHANGED)
        assertFailsWith<IllegalArgumentException> { after.copy(config = after.config.copy(rigEdits = RigEditOverlay())) }
    }

    @Test fun bundledReadContractsAcceptCanonicalAliasesButRejectHistoricalAndForeignPackages() {
        val scaffold = RigModifierInstance("scaffold", "rig.scaffold", 2, target)
        val current = part.copy(plugin = BuiltinModifierPlugins.reference("eye", 1))
        val foreign = part.copy(id = "foreign", plugin = ModifierPluginReference("foreign", "1.0.0", "a".repeat(64)))
        val unknown = prefix.copy(type = "future.algorithm")
        val definitions = WorkspaceBuildPlanner.completeBundledReads(inputs(RigModifierStack(listOf(scaffold, current, foreign, unknown))))
        assertTrue(ModifierBuildIdentity.of(scaffold) in definitions)
        assertTrue(ModifierBuildIdentity.of(current) in definitions)
        assertFalse(ModifierBuildIdentity.of(foreign) in definitions)
        assertFalse(ModifierBuildIdentity.of(unknown) in definitions)
        val historical = current.copy(plugin = current.plugin!!.copy(digest = "b".repeat(64)))
        assertTrue(WorkspaceBuildPlanner.completeBundledReads(inputs(RigModifierStack(listOf(historical)))).isEmpty())
    }
}
