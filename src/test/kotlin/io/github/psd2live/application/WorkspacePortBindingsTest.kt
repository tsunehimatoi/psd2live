package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspacePortBindingsTest {
    @TempDir lateinit var temporary: Path

    @Test fun sourceAndObservationCatalogsRunWithoutACompleteBackendOrGui() = runBlocking<Unit> {
        val requests = mutableListOf<JsonObject>()
        val source = object : WorkspaceSourcePort {
            override suspend fun importImages(state: String, paths: List<Path>, parentDeformerId: String?): WorkspaceMutationResult = error("Unexpected image import")
            override suspend fun createArtwork(arguments: JsonObject): WorkspaceMutationResult {
                requests += arguments
                return WorkspaceMutationResult("head", "revision", listOf("Artwork"), "Created artwork",
                    state = "load:1:0", projectId = "project")
            }
            override suspend fun importPsd(path: String, discardUnsaved: Boolean): WorkspaceMutationResult = error("Unexpected import")
            override suspend fun importCmo3(path: Path, mode: io.github.psd2live.core.Cmo3ImportMode,
                                            discardUnsaved: Boolean): WorkspaceMutationResult = error("Unexpected import")
            override suspend fun splitArtwork(arguments: JsonObject, author: MutationAuthor): WorkspaceMutationResult = error("Unexpected split")
            override suspend fun splitMeshComponents(state: String, splits: List<JsonObject>): WorkspaceMutationResult = error("Unexpected component split")
            override suspend fun splitDepth(state: String, request: JsonObject): WorkspaceMutationResult = error("Unexpected depth split")
            override suspend fun paintSource(arguments: JsonObject): WorkspaceMutationResult = error("Unexpected paint")
            override suspend fun commitPaintRaster(state: String, request: WorkspacePaintRaster): WorkspaceMutationResult = error("Unexpected raster commit")
            override suspend fun deleteLayer(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult = error("Unexpected deletion")
            override suspend fun restoreDeletedLayers(layerIds: List<String>?, expectedState: String, taskId: String?): WorkspaceMutationResult = error("Unexpected restoration")
            override suspend fun classifyLayer(layerId: String, fields: JsonObject, expectedState: String): WorkspaceMutationResult = error("Unexpected classification")
        }
        val observation = object : WorkspaceRenderPort {
            override fun captureObservation() = object : WorkspaceObservationStub() {
                override fun snapshot(): WorkspaceProjectSnapshot = error("Unexpected state read")
                override suspend fun observeAuthoring(arguments: JsonObject): WorkspaceWorkflowResult {
                    requests += arguments
                    return WorkspaceWorkflowResult(buildJsonObject { put("kind", arguments.getValue("kind")) })
                }
            }
            override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Unexpected layer render")
            override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Unexpected context render")
            override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView = error("Unexpected model render")
        }
        assertFalse(WorkspaceBackend::class.java.isInstance(source))
        assertFalse(WorkspaceBackend::class.java.isInstance(observation))
        val catalog = WorkspaceCommands()
        registerSourceCommands(catalog, source)
        registerObservationCommands(catalog, observation)
        val artwork = buildJsonObject {
            put("width", 16); put("height", 16)
            putJsonArray("layers") { add(buildJsonObject {
                put("path", temporary.resolve("port-art.png").toString()); put("name", "Artwork")
            }) }
        }
        val created = catalog.invoke("asset_create_artwork", artwork)
        assertEquals("load:1:0", created.data.getValue("state").jsonPrimitive.content)
        assertEquals(listOf("Artwork"), created.data.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
        val inspect = buildJsonObject {
            putJsonArray("rect") { add(0); add(0); add(16); add(16) }
            putJsonArray("states") { add("head") }
            putJsonArray("poses") { add(buildJsonObject {}) }
        }
        val observed = catalog.invoke("view_compare_history", inspect)
        assertEquals("history", observed.data.getValue("kind").jsonPrimitive.content)
        assertEquals(listOf(artwork, JsonObject(inspect + ("kind" to JsonPrimitive("history")))), requests)
        assertFailsWith<IllegalArgumentException> { catalog.get("physics_put") }
    }
}
