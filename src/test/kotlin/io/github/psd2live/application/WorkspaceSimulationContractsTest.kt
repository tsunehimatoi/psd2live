package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class WorkspaceSimulationContractsTest {
    private val identity = buildJsonObject { put("project_id", "project"); put("state", "load:1:0"); put("history_node_id", "head") }
    private val angle = ParameterId("Drive")
    private fun strand(): PuppetModel {
        val positions = FloatArray(28) { i -> if (i % 2 == 0) (i / 2 % 2) * 10f else (i / 4) * 10f }
        val indices = (0 until 6).flatMap { row -> val a = row * 2; listOf(a, a + 1, a + 3, a, a + 3, a + 2) }.toIntArray()
        val mesh = DrawableMesh(positions, FloatArray(positions.size), indices)
        val keys = floatArrayOf(-30f, 0f, 30f)
        val geometry = KeyformGrid(listOf(KeyformAxis(angle, keys)), keys.mapIndexed { i, x ->
            KeyformCell(intArrayOf(i), MeshDeltaForm(FloatArray(positions.size) { if (it % 2 == 0) x else 0f })) })
        val drawable = Drawable(DrawableId("strand"), "Strand", null, BlendMode.Normal, emptyList(), mesh, geometry)
        return PuppetModel(listOf(Parameter(angle, "Drive", -30f, 30f, 0f)), emptyList(), emptyList(), listOf(drawable),
            listOf(OrgChild.Drawable(drawable.id)), null, vertexGroups = listOf(VertexGroup("root", drawable.id, VertexGroupKind.PIN,
                FloatArray(mesh.vertexCount) { if (it < 2) 1f else 0f })))
    }
    private fun edit() = RigSimEdit("sway", "Sway", SimKind.HAIR, listOf("strand"),
        inputs = listOf(PhysicsInput(angle.raw, type = PhysicsSourceType.X)), autoBake = false)
    private fun schema(id: String) = requireNotNull(WorkspaceSimulationResultSchemas.forOperation(id))

    @Test fun realBakingAndTimeSimulationProduceTypedDiagnosticsWithoutLeakingKeyformArrays() {
        val model = strand(); val edit = edit()
        val bake = SimBaker.bake(model, edit, SimBaker.Options(duration = 1f))
        assertTrue(bake.modes.isNotEmpty())
        val summary = bake.summary()
        validateOperationSchema(JsonObject(identity + summary), schema("simulation_bake"))
        validateOperationSchema(JsonObject(identity + ("bake" to summary)), schema("simulation_put"))
        validateOperationSchema(identity, schema("simulation_put"))
        val failed = QualityInspection.combine(QualityFence.OBSERVATION, listOf(QualityCheckResult("simulation:sway", "Bake failed",
            listOf(QualityFinding.message(QualityRule.SIMULATION_BAKE_ERROR, "simulation:sway", "No input motion")), complete = false)))
        validateOperationSchema(JsonObject(identity + mapOf("bake_error" to JsonPrimitive("No input motion"), "quality" to failed.toJson())), schema("simulation_put"))
        val report = SimAuthoring.report(model, edit, hold = 0f, release = 0.2f, wind = 1200f to 0f)
        validateOperationSchema(report, schema("simulation_simulate"))
        assertEquals(listOf(angle.raw, "wind"), report.getValue("phases").jsonArray.map { it.jsonObject.getValue("input").jsonPrimitive.content })
        for (bad in listOf(JsonObject(identity + ("bake" to bake.toJson())),
            JsonObject(identity + mapOf("bake" to summary, "bake_error" to JsonPrimitive("Both outcomes"))),
            JsonObject(identity + ("bake" to JsonObject(summary - "fit_r2"))))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(bad, schema("simulation_put")) }
        }
    }

    @Test fun inspectionRetainsSimulationOverridesSummaryAndStalenessWhileRejectingStalenessWithoutABake() {
        val model = strand()
        val edited = edit().copy(groups = mapOf(VertexGroupKind.PIN to "root"), glueRoles = mapOf("strand|other" to GlueRole.IGNORE),
            inputRanges = mapOf(angle.raw to SimInputRange(-15f, 15f)), modes = 1, keys = 3, enabled = false, vertical = false,
            staticInputs = listOf(angle.raw), blendShapes = true, exaggeration = 1.5f,
            outputNames = mapOf("ParamSway" to "Sway"), outputs = mapOf("ParamSway" to SimOutput(id = "CustomSway", range = 10f, gain = 1.2f)))
        val schema = WorkspaceSimulationResultSchemas.inspectedSimulation
        validateOperationSchema(edited.toJson(), schema)
        val bake = SimBaker.bake(model, edit(), SimBaker.Options(duration = 1f))
        val inspected = JsonObject(edited.toJson() + mapOf("bake" to bake.summary(), "bake_stale" to JsonPrimitive(true)))
        validateOperationSchema(inspected, schema)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(edited.toJson() + ("bake_stale" to JsonPrimitive(true))), schema) }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(inspected + ("bake" to bake.toJson())), schema) }
    }

    @Test fun presetResultsKeepGarmentMeasurementsAndSeparatePerSimulationBakeFailures() {
        val rgba = ByteArray(80 * 100 * 4)
        for (y in 0 until 100) for (x in 0 until 80) if (x in (25 - y / 5)..(55 + y / 5)) rgba[(y * 80 + x) * 4 + 3] = -1
        val raster = org.umamo.format.art.LayerRaster(80, 100, rgba)
        val profile = ModelPresets.garmentProfile(raster, 20f, "skirt")
        val field = ClothFit.analyze(raster, 0f, 20f, ClothFit.Wear.SKIRT, profile.waist)
        val garment = JsonObject(profile.toJson() + field.toJson() + ("simulated" to JsonPrimitive(true)))
        val summary = SimBakeResult("static", emptyMap(), emptyList(), emptyList()).summary()
        val report = buildJsonObject {
            put("simulations", JsonArray(listOf(JsonPrimitive("skirt"))))
            putJsonObject("garments") { put("mesh", garment) }
            putJsonObject("bakes") { put("skirt", summary); put("other", buildJsonObject {
                put("error", "No motion"); put("quality", SimulationQualityCheck.failedBake("other", "No motion").toJson())
            }) }
        }
        val result = JsonObject(identity + report)
        validateOperationSchema(result, schema("model_apply_preset"))
        validateOperationSchema(identity, schema("model_apply_preset"))
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(result - "bakes"), schema("model_apply_preset")) }
        val bad = JsonObject(result + ("bakes" to buildJsonObject { put("skirt", JsonObject(summary + ("error" to JsonPrimitive("Both outcomes")))) }))
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(bad, schema("model_apply_preset")) }
    }
}
