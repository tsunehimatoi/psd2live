package io.github.psd2live.application

import io.github.psd2live.agent.AGENT_TOOL_FAMILIES
import io.github.psd2live.agent.inputSchema
import kotlinx.serialization.json.*
import kotlin.test.*

class PluginDevMcpContractTest {
    private val allOps = listOf(
        "plugin_dev_contract",
        "plugin_dev_scaffold",
        "plugin_dev_list",
        "plugin_dev_register",
        "plugin_dev_build",
        "plugin_dev_detach",
        "modifier_code_plan",
        "modifier_code_apply",
    )

    @Test
    fun allPluginDevAndCodeSwapSchemasAreStrictAndValid() {
        for (op in allOps) {
            val req = WorkspacePluginDevSchemas.request(op)
            val res = WorkspacePluginDevSchemas.result(op)
            checkOperationSchema(req)
            checkOperationSchema(res)
            if (op != "plugin_dev_contract") {
                assertEquals(JsonPrimitive(false), req["additionalProperties"], "$op request must disallow additionalProperties")
            }
        }
        for (jobOp in WorkspacePluginDevSchemas.jobs) {
            checkOperationSchema(WorkspaceJobResultSchemas.result(jobOp))
            assertNotNull(WorkspaceJobResultSchemas.operationOutput(jobOp), "Missing job output schema for $jobOp")
        }
    }

    @Test
    fun requestValidationRejectsUnknownFieldsAndAcceptsValidRequests() {
        val validScaffold = buildJsonObject {
            put("target_dir", "/tmp/my-plugin")
            put("plugin_id", "com.example.myplugin")
            put("name", "My Plugin")
        }
        validateOperationSchema(validScaffold, WorkspacePluginDevSchemas.request("plugin_dev_scaffold"))
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(
                JsonObject(validScaffold + ("unexpected" to JsonPrimitive(true))),
                WorkspacePluginDevSchemas.request("plugin_dev_scaffold"),
            )
        }

        val validApply = buildJsonObject {
            put("state", "s1")
            put("plan_token", "tok_123")
            put("request_id", "req_123")
        }
        validateOperationSchema(validApply, WorkspacePluginDevSchemas.request("modifier_code_apply"))
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(
                JsonObject(validApply - "request_id"),
                WorkspacePluginDevSchemas.request("modifier_code_apply"),
            )
        }
    }

    @Test
    fun agentToolFamiliesExposePluginDevAndCodeSwap() {
        val pluginDevFamily = assertNotNull(AGENT_TOOL_FAMILIES.firstOrNull { it.name == "plugin_dev" })
        val modifierFamily = assertNotNull(AGENT_TOOL_FAMILIES.firstOrNull { it.name == "modifier" })

        val defs = allOps.map { id ->
            WorkspaceOperationDefinition(
                id = id,
                description = id,
                requestSchema = WorkspacePluginDevSchemas.request(id),
                kind = if (id in setOf("plugin_dev_contract", "plugin_dev_list", "modifier_code_plan")) {
                    WorkspaceOperationKind.QUERY
                } else {
                    WorkspaceOperationKind.SESSION
                },
                workspaceBound = id.startsWith("modifier_"),
                jobBacked = id in WorkspacePluginDevSchemas.jobs,
                resultSchema = WorkspacePluginDevSchemas.result(id),
                jobResultSchema = if (id in WorkspacePluginDevSchemas.jobs) WorkspacePluginDevSchemas.result(id) else null,
            )
        }

        val pluginDevMembers = pluginDevFamily.members(defs)
        assertEquals(
            setOf("plugin_dev_contract", "plugin_dev_scaffold", "plugin_dev_list", "plugin_dev_register", "plugin_dev_build", "plugin_dev_detach"),
            pluginDevMembers.map { it.id }.toSet(),
        )
        checkOperationSchema(pluginDevFamily.inputSchema(pluginDevMembers))

        val modifierMembers = modifierFamily.members(defs)
        assertEquals(setOf("modifier_code_plan", "modifier_code_apply"), modifierMembers.map { it.id }.toSet())
        checkOperationSchema(modifierFamily.inputSchema(modifierMembers))
    }
}
