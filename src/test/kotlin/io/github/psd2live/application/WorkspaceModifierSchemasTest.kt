package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspaceModifierSchemasTest {
    @TempDir lateinit var temp: Path

    private fun configuration(type: String): JsonObject = when (type) {
        "rig.scaffold" -> JsonObject(emptyMap())
        "body/legacy" -> buildJsonObject { put("scaffold", "structure"); put("strength", 1f); putJsonObject("tuning") { put("breathLift", 2f) } }
        "motion" -> MotionClips.toJson(MotionClip("template", "Motion", curves = listOf(MotionCurve("P", listOf(MotionKey(0f, 0f), MotionKey(1f, 1f))))))
        "physics" -> RigPhysicsEdit("template", "Physics", listOf(PhysicsInput("P")), listOf(PhysicsOutput("Q"))).toJson()
        "swing" -> RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P")).toJson()
        "simulation" -> RigSimEdit("template", "Simulation", SimKind.CLOTH, listOf("mesh")).copy(bake =
            SimBakeResult("fingerprint", mapOf("mesh" to 3), emptyList(), listOf(SimBakedMode(
                SimBakedAxis("P", floatArrayOf(-1f, 0f, 1f), mapOf("mesh" to List(3) { FloatArray(6) })), 1f, 1f)))).toJson()
        else -> if (RigLegacyBindings.find(type) != null) buildJsonObject { put("scaffold", "structure");
            if (RigLegacyBindings.spec(type).module?.base != null) put("base", "eye")
            if (RigLegacyBindings.spec(type).related.isNotEmpty()) put("dependencies", JsonObject(RigLegacyBindings.spec(type).related.associateWith { JsonPrimitive("eye") })) } else error(type)
    }

    private fun addition(type: String) = buildJsonObject {
        put("type", type); put("id", "chosen"); put("config", configuration(type)); put("state", "state")
        putJsonArray("targets") { addJsonObject { put("kind", if (type == "swing") "deformer" else "mesh"); put("id", "target") } }
    }

    @Test fun everyRegisteredAlgorithmHasAStrictConfigurationAndMutationSchema() {
        for (type in WorkspaceModifierSchemas.types) {
            val request = addition(type)
            val schema = WorkspaceModifierSchemas.request("modifier_add")
            checkOperationSchema(schema)
            validateOperationSchema(request, schema)
            assertFailsWith<WorkspaceValidationException> {
                validateOperationSchema(JsonObject(request + ("unknown" to JsonPrimitive(true))), schema)
            }
            val config = request.getValue("config").jsonObject
            RigLegacyBindings.find(type)?.module?.physicsGroups?.takeIf { it.isNotEmpty() }?.let { groups ->
                val rule = RigPhysicsEdit("template", "Pendulum", listOf(PhysicsInput("A")), listOf(PhysicsOutput("B"))).toJson()
                validateOperationSchema(JsonObject(request + ("config" to JsonObject(config + ("physics" to buildJsonObject { put(groups.first(), rule) })))), schema)
                assertFailsWith<WorkspaceValidationException> {
                    validateOperationSchema(JsonObject(request + ("config" to JsonObject(config + ("physics" to buildJsonObject { put("unknown", rule) })))), schema)
                }
            }
            assertFailsWith<WorkspaceValidationException> {
                validateOperationSchema(JsonObject(request + ("config" to JsonObject(config + ("unknown" to JsonPrimitive(true))))), schema)
            }
        }
        assertEquals(setOf("swing", "simulation", "physics", "motion", "rig.scaffold", "body/legacy", "face/legacy",
            "eye/legacy", "brow/legacy", "mouth/legacy", "hair/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "eye.smile", "eye.squash", "eye.wide", "eye.lid-fade", "eye.highlight", "eye.aftershock", "brow.shape", "brow.spring", "eye.emotion", "mouth.vowel", "eye.gaze-lid", "brow.blink", "brow.lid-coupling", "mouth.cheek-lid", "mouth.capture", "mouth.tongue", "mouth.jaw", "mouth.interior-depth", "mouth.spring", "expression.cheek", "expression.gloom", "expression.toggles", "face.skin-clip", "face.neck", "face.depth-turn", "eye.see-through", "brow.see-through", "hair/chains", "body.bust", "body.arms", "outfit.skirt", "outfit.ribbons", "outfit.creature"), WorkspaceModifierSchemas.types.toSet())
    }

    @Test fun modifierJobsAndDiscriminatedSchemasParticipateInTheOrdinaryAtomicBatchContract() {
        HeadlessPresentation().use { presentation -> WorkspaceHost(presentation, temp.resolve("store")).use { host ->
            presentation.attach(host)
            WorkspaceOperations(host).use { operations ->
                val members = batchMemberSchemas(operations.registry)
                for (id in WorkspaceModifierEdits.supported) {
                    val definition = operations.registry.definition(id)
                    assertTrue(definition.jobBacked && definition.batchable)
                    assertNotNull(definition.jobResultSchema)
                    assertNotNull(members[id])
                }
                for (type in WorkspaceModifierSchemas.types) {
                    val request = addition(type)
                    validateOperationSchema(JsonObject(request - "state"), members.getValue("modifier_add"))
                    assertFailsWith<WorkspaceValidationException> { validateOperationSchema(request, members.getValue("modifier_add")) }
                }
                val group = buildJsonObject {
                    put("group", "recommended"); put("entries", JsonArray(listOf(JsonObject(addition("motion") - "state"))))
                }
                validateOperationSchema(group, members.getValue("modifier_add_group"))
                val catalog = buildJsonObject { putJsonArray("types") { RigModifierAlgorithms.registry.definitions.forEach { entry ->
                    addJsonObject { put("type", entry.type); put("version", entry.version)
                        put("requires", JsonArray(entry.requires.map(::JsonPrimitive))); put("exclusive_family", JsonNull) }
                } } }
                validateOperationSchema(catalog, operations.registry.definition("modifier_catalog").resultSchema, "result")
                val tools = io.github.psd2live.agent.AgentToolCatalog(operations.registry, host, io.github.psd2live.agent.AgentToolProfile.CORE)
                assertNotNull(tools.tools["modifier"], "the ordinary core profile must publish the modifier family")
            }
        } }
    }
}
