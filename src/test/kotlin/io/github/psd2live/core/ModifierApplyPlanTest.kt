package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierApplyPlanTest {
    private val target = ModifierTarget("part", "body")
    private fun definition(type: String, requires: Set<String> = emptySet()) = RigModifierDefinition(type, 1,
        requires = requires, plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig })
    private val registry = RigModifierRegistry(listOf(definition("base"), definition("child", setOf("base")), definition("unused")))
    private fun entry(id: String, type: String, enabled: Boolean = true) = RigModifierInstance(id, type, 1, listOf(target), enabled)
    private val stack = RigModifierStack(listOf(entry("a", "base"), entry("off", "unused", false), entry("b", "child")))
    private fun reports(conflict: String? = null) = stack.instances.associate { instance -> instance.id to buildJsonObject {
        put("status", if (instance.enabled) "active" else "disabled")
        putJsonArray("override_issues") { if (instance.id == conflict) addJsonObject { put("kind", "orphaned") } }
    } }

    @Test fun prefixPlanNamesDisabledEntriesAndRetainedProviderEdges() {
        val planned = ModifierApplyPlan.prepare(stack, "off", reports(), registry)
        assertEquals(listOf("a", "off"), planned.getValue("consumed").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("a"), planned.getValue("materialized").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("off"), planned.getValue("disabled").jsonArray.map { it.jsonPrimitive.content })
        val edge = planned.getValue("dependencies").jsonArray.single().jsonObject
        assertEquals("a", edge.getValue("provider").jsonPrimitive.content)
        assertEquals("b", edge.getValue("consumer").jsonPrimitive.content)
        assertTrue(planned.getValue("can_apply").jsonPrimitive.boolean)
        val response = JsonObject(planned + buildJsonObject {
            put("state", "s"); put("project_id", "p"); put("history_node_id", "h"); put("revision", "r")
        })
        io.github.psd2live.application.validateOperationSchema(response,
            io.github.psd2live.application.WorkspaceModifierSchemas.applyPlan, "result")
    }

    @Test fun selectedPlanIncludesIncomingAndOutgoingMotionDependencies() {
        val source = definition("source").copy(motionOutputs = { setOf("first-clip") })
        val middle = definition("middle").copy(motionInputs = { setOf("first-clip") }, motionOutputs = { setOf("second-clip") })
        val consumer = definition("consumer").copy(motionInputs = { setOf("second-clip") })
        val registry = RigModifierRegistry(listOf(source, middle, consumer))
        val stack = RigModifierStack(listOf(entry("a", "source"), entry("b", "middle"), entry("c", "consumer")))
        val reports = stack.instances.associate { it.id to buildJsonObject { put("status", "active"); putJsonArray("override_issues") {} } }
        val planned = ModifierApplyPlan.prepare(stack, "b", reports, registry, scope = "selected")
        assertEquals(setOf("a" to "b", "b" to "c"), planned.getValue("dependencies").jsonArray.map { value ->
            val edge = value.jsonObject
            edge.getValue("provider").jsonPrimitive.content to edge.getValue("consumer").jsonPrimitive.content
        }.toSet())
        assertEquals(listOf("b"), planned.getValue("consumed").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun selectedPlanIncludesUpstreamObjectsAndParametersUsedByAuthorCorrections() {
        val stages = RigModifierStack(listOf(entry("source", "base"), entry("edit", "unused")),
            overrides = mapOf("edit" to listOf(buildJsonObject {
                put("op", "generated_override"); put("target", "warp:shape")
                putJsonObject("key") { put("P", .35f) }
            })))
        val evidence = stages.instances.associate { instance -> instance.id to buildJsonObject {
            put("status", "active")
            putJsonArray("contributions") {
                if (instance.id == "source") for ((kind, id) in listOf("deformer" to "shape", "parameter" to "P")) {
                    addJsonObject { put("kind", kind); put("id", id); put("change", "added") }
                }
            }
        } }
        val plan = ModifierApplyPlan.prepare(stages, "edit", evidence, registry, scope = "selected")
        assertEquals(setOf("authoring:deformer:shape", "authoring:parameter:P"),
            plan.getValue("dependencies").jsonArray.map { it.jsonObject.getValue("reason").jsonPrimitive.content }.toSet())
        assertTrue(plan.getValue("dependencies").jsonArray.all {
            it.jsonObject.getValue("provider").jsonPrimitive.content == "source"
        })
    }

    @Test fun orphanedDownstreamCorrectionAlsoBlocksApplyingThePrefix() {
        val plan = ModifierApplyPlan.prepare(stack, "a", reports("b"), registry)
        assertFalse(plan.getValue("can_apply").jsonPrimitive.boolean)
        assertEquals("b", plan.getValue("blockers").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun disabledCorrectionsAreDormantAndDoNotBlockApplication() {
        val edits = listOf(buildJsonObject { put("op", "generated_override") })
        val plan = ModifierApplyPlan.prepare(stack.copy(overrides = mapOf("a" to edits, "off" to edits)), "off", reports("off"), registry)
        assertEquals(1, plan.getValue("baked_overrides").jsonPrimitive.int)
        assertEquals(1, plan.getValue("dormant_overrides").jsonPrimitive.int)
        assertTrue(plan.getValue("can_apply").jsonPrimitive.boolean)
    }

    @Test fun unresolvedDownstreamOverridesCannotBeHiddenByBakingAnUpstreamProvider() {
        val engine = RigModifierEvaluator(registry, applyOverrides = { rig, instance, _ ->
            ModifierOverrideResult(rig, listOf(GeneratedOverrideIssue(GeneratedOverrideIssueKind.ORPHANED,
                instance.id, "mesh:mouth", emptyMap(), 1, 1, GeneratedOverrideOrphanReason.MISSING_KEYFORM)))
        })
        val input = RigIR(Canvas(100f, 100f), emptyList(), parts = listOf(Part("body", "Body", emptyList())))
        val withCorrection = stack.copy(overrides = mapOf("b" to listOf(buildJsonObject { put("op", "generated_override") })))
        assertFailsWith<IllegalArgumentException> { engine.applyThrough(input, withCorrection, "a") }
    }
}
