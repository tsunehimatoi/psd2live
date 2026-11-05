package io.github.psd2live.application

import io.github.psd2live.core.modifier.ModifierPluginStore
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class WorkspacePluginOperationsTest {
    @Test fun pluginQueriesAndDynamicModifierRequestsHaveStrictContracts() {
        val root = Files.createTempDirectory("plugin-operations-")
        val preferences = Preferences.userRoot().node("psd2live-test/plugins/${UUID.randomUUID()}")
        try {
            val store = ModifierPluginStore(root, preferences)
            val result = WorkspacePluginManagement.execute("plugin_list", JsonObject(emptyMap()), store)
            validateWorkspaceResult("plugin_list", WorkspacePluginSchemas.result, result)
            for (operation in WorkspacePluginSchemas.jobs) {
                checkOperationSchema(WorkspacePluginSchemas.request(operation))
                checkOperationSchema(WorkspaceJobResultSchemas.result(operation))
            }
            val add = buildJsonObject {
                put("state", "test"); put("type", "example-python/fade")
                put("config", buildJsonObject { put("factor", .5) })
                putJsonArray("targets") { addJsonObject { put("kind", "mesh"); put("id", "mesh") } }
            }
            validateOperationSchema(add, WorkspaceModifierSchemas.request("modifier_add"))
            assertFailsWith<WorkspaceValidationException> {
                validateOperationSchema(JsonObject(add + ("typo" to JsonPrimitive(true))), WorkspaceModifierSchemas.request("modifier_add"))
            }
        } finally { preferences.removeNode(); ModifierPluginStore.deleteTree(root) }
    }
}
