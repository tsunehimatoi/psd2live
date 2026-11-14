package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/**
 * Procedural artwork only; no external sample or worker is required.
 * PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*WorkspaceTopologyBuildPlanTool'
 * Default: one correctness pair, zero warmups, never performance acceptance.
 * Formal (reserved window only): PSD2LIVE_BUILD_PLAN_FORMAL=1 and PSD2LIVE_BUILD_PLAN_BASELINE=<full SHA>.
 * Requires a clean fixed checkout, >=5 warmup pairs and >=30 measured pairs; defaults are 5 and 30.
 * PSD2LIVE_BUILD_PLAN_WARMUPS / PSD2LIVE_BUILD_PLAN_SAMPLES override those counts.
 * Both phases validate parity; warmup observations are excluded from latency summaries.
 */
class WorkspaceTopologyBuildPlanTool {
    @TempDir lateinit var temp: Path
    @Test fun appendedMeshSplitMatchesUncachedBuild() = runBlocking<Unit> {
        requireTools()
        val directory = output("workspace-topology-build-plan").toPath()
        val owners = mutableSetOf<ModifierBuildCacheOwner>()
        fun retain(model: RigPreviewModel) = model.also { it.modifierBuildOwner?.let(owners::add) }
        val plans = mutableListOf<WorkspaceBuildPlan>()
        val enabled = WorkspacePreviewBuilder(observeBuildPlan = plans::add)
        val disabled = WorkspacePreviewBuilder(modifierCachesEnabled = false)
        val recording = RigBuildProfile.recording
        var report: PartBuildPlanRunReport? = null
        Files.writeString(directory.resolve("report.json"), """{"status":"initializing","complete":false}""")
        try {
            val options = PartBuildPlanRunOptions.environment()
            val runReport = PartBuildPlanRunReport(options, PartBuildPlanSource.capture()).also { report = it }
            runReport.validateStart()
            Files.writeString(directory.resolve("report.json"), runReport.json().toString())
            val source = SyntheticProject(temp.resolve("source"), 2, 12, 1).tree.head().snapshot
            val config = PipelineConfig(atlasSize = 64, meshSpacing = 8,
                rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
            val plain = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
            val ordinary = retain(enabled.build(plain))
            val initial = RigIrCompiler.compile(ordinary)
            val target = initial.meshes.first()
            val consumer = initial.meshes.last().copy(maskedBy = listOf(target.id), invertMask = true, textureSource = target.id)
            val geometry = target.geometry!!.copy(
                positions = Floats.values(0f, 0f, 3f, 0f, 0f, 3f, 5f, 5f, 8f, 5f, 5f, 8f),
                uvs = Floats.values(0f, 0f, .3f, 0f, 0f, .3f, .5f, .5f, .8f, .5f, .5f, .8f),
                indices = Ints.values(0, 1, 2, 3, 4, 5))
            val islands = target.copy(geometry = geometry, offsets = null, shapes = emptyList())
            val authoredIr = initial.copy(meshes = listOf(islands, consumer),
                glues = listOf(Glue("seam", target.id, consumer.id,
                    listOf(GluePair(0, 0, .5f, .5f), GluePair(3, 1, .4f, .6f)))),
                authoring = initial.authoring.copy(paths = listOf(DeformPath("outline", target.id,
                    listOf(PathPoint(0, 1, 2, .2f, .3f, .5f, true),
                        PathPoint(0, 1, 2, .3f, .5f, .2f), PathPoint(0, 1, 2, .5f, .2f, .3f)), 2f, .7f, true, 2)),
                    vertexGroups = listOf(VertexGroup("pins", target.id, "pin", Floats.values(.1f, .2f, .3f, .4f, .5f, .6f)))))
            val authored = ordinary.authored.copy(rig = ordinary.authored.rig.copy(puppet = PuppetIr.toPuppet(authoredIr)))
            val prefix = RigModifierInstance("prefix", "mesh.split", 1, listOf(ModifierTarget("mesh", consumer.id)))
            val split = RigModifierInstance("split", "mesh.split", 1, listOf(ModifierTarget("mesh", target.id)))
            val stack = RigModifierStack(listOf(prefix))
            val beforeDocument = plain.copy(rigEdits = plain.rigEdits.copy(
                authoringJournal = listOf(RigCheckpoint.encode(authored, ordinary.sources.bindingKey!!)), modifierStack = stack.toJson()))
            val before = retain(enabled.build(beforeDocument, ordinary))
            val original = RigIrCompiler.compile(before, tileArt = true)
            val edited = beforeDocument.copy(rigEdits = beforeDocument.rigEdits.copy(modifierStack =
                stack.copy(instances = stack.instances + split).toJson()))
            val pieces = (0..1).map { split.outputId("split:${target.id}:$it") }
            val saved = before.modifierEvaluation
            val identity = RigBuildProfile.Operation("append-mesh-split", WorkspaceRevisions.of(beforeDocument),
                WorkspaceRevisions.of(edited), "append mesh.split: two islands with masks/glue/path/weights", original.meshes.size,
                original.meshes.sumOf { (it.geometry?.positions?.size ?: 0).toLong() / 2 })
            RigBuildProfile.recording = true
            suspend fun build(cache: Boolean) = RigBuildProfile.measureSuspending(identity) {
                retain((if (cache) enabled else disabled).build(edited, before))
            }
            fun record(sample: Int, cache: Boolean, measured: RigBuildProfile.Measurement<RigPreviewModel>, ir: RigIR) {
                runReport.append(buildJsonObject {
                    put("sample", sample); put("cache", cache); put("elapsed_ms", measured.elapsedMillis)
                    put("heap_before", measured.before.heapUsed); put("heap_after", measured.after.heapUsed)
                    putJsonObject("stages") { measured.stages.forEach { (name, value) ->
                        putJsonObject(name) { put("milliseconds", value.first); put("count", value.second) }
                    } }
                    putJsonObject("gauges") { measured.gauges.forEach { (name, value) -> put(name, value) } }
                    put("rig_ir_sha256", digest(RigIrBinary.encode(ir)))
                    putJsonArray("texture_png_sha256") { ir.textures.pages.forEach { add(digest(it.png.shared())) } }
                    putJsonObject("tile_rgba_sha256") { ir.textures.tileArt.forEach { put(it.tile, digest(it.rgba.shared())) } }
                })
            }
            repeat(options.warmups + options.samples) { pair ->
                val sample = pair - options.warmups
                // Alternate pair order; the captured start model/document stay identical in every sample.
                val first = build(pair % 2 == 0)
                val second = build(pair % 2 != 0)
                val hot = if (pair % 2 == 0) first else second
                val cold = if (pair % 2 == 0) second else first
                val plan = plans.last()
                assertEquals(WorkspaceBuildRoute.PILOT, plan.route)
                assertEquals(WorkspaceBuildPilot.APPEND_MESH_SPLIT, plan.pilot)
                assertEquals(listOf(WorkspaceBuildReason.UNCHANGED_PREFIX, WorkspaceBuildReason.MESH_SPLIT_APPENDED), plan.modifiers.map { it.reason })
                assertEquals(1L, hot.stages["build plan pilot: append_mesh_split"]?.second)
                assertEquals(1L, hot.gauges["build plan invalidated modifiers"])
                assertEquals(1L, hot.stages["modifier author input cache hits"]?.second)
                assertEquals(1L, hot.stages["modifier cache hits"]?.second)
                assertEquals(1L, hot.stages["modifier cache misses"]?.second)
                assertEquals(1L, hot.stages["modifier evaluate: mesh.split"]?.second)
                assertEquals(2L, cold.gauges["build plan invalidated modifiers"])
                assertNull(cold.stages["modifier author input cache hits"])
                assertNull(cold.stages["modifier cache hits"])
                val actual = RigIrCompiler.compile(hot.value, tileArt = true)
                val expected = RigIrCompiler.compile(cold.value, tileArt = true)
                assertEquals(original.meshes.size + 1, actual.meshes.size)
                assertFalse(actual.meshes.any { it.id == target.id })
                assertTrue(pieces.all { id -> actual.meshes.any { it.id == id } })
                val live = actual.meshes.mapTo(hashSetOf()) { it.id }
                val migrated = actual.meshes.single { it.id == consumer.id }
                assertEquals(pieces, migrated.maskedBy)
                assertTrue(migrated.invertMask)
                assertEquals(pieces.first(), migrated.textureSource)
                assertEquals(2, actual.glues.size)
                assertEquals(pieces.toSet(), actual.glues.mapTo(hashSetOf()) { it.meshA })
                assertTrue(actual.glues.all { it.meshA in live && it.meshB == consumer.id })
                assertEquals(pieces.first(), actual.authoring.paths.single().mesh)
                assertEquals(pieces.toSet(), actual.authoring.vertexGroups.mapTo(hashSetOf()) { it.mesh })
                pieces.forEach { id ->
                    assertEquals(before.rig.layerIdByDrawableId[target.id], hot.value.rig.layerIdByDrawableId[id])
                    assertEquals(before.rig.pageByDrawableId[target.id], hot.value.rig.pageByDrawableId[id])
                    assertEquals(before.rig.sourceBoundsByDrawableId[target.id], hot.value.rig.sourceBoundsByDrawableId[id])
                    assertEquals(original.meshes.single { it.id == target.id }.tile, actual.meshes.single { it.id == id }.tile)
                }
                assertEquals(expected, actual, "Complete IR, including parameter order, resources and texture bindings")
                assertContentEquals(RigIrBinary.encode(expected), RigIrBinary.encode(actual))
                assertTrue(actual.textures.pages.isNotEmpty() && actual.textures.pages.all { it.png.size > 0 })
                assertTrue(actual.textures.tileArt.isNotEmpty() && actual.textures.tileArt.all { it.rgba.size > 0 })
                actual.textures.pages.zip(expected.textures.pages).forEach { (a, b) -> assertContentEquals(b.png.shared(), a.png.shared()) }
                actual.textures.tileArt.zip(expected.textures.tileArt).forEach { (a, b) -> assertContentEquals(b.rgba.shared(), a.rgba.shared()) }
                assertEquals(cold.value.rig.modifierReport, hot.value.rig.modifierReport)
                assertEquals(cold.value.rig.layerIdByDrawableId, hot.value.rig.layerIdByDrawableId)
                assertEquals(cold.value.rig.pageByDrawableId, hot.value.rig.pageByDrawableId)
                assertEquals(cold.value.rig.sourceBoundsByDrawableId, hot.value.rig.sourceBoundsByDrawableId)
                assertEquals(cold.value.rig.copiedDrawableIds, hot.value.rig.copiedDrawableIds)
                assertEquals(cold.value.atlas.placementByLayerId, hot.value.atlas.placementByLayerId)
                assertSame(saved, before.modifierEvaluation)
                assertSame(before.modifierBuildOwner, hot.value.modifierBuildOwner)
                assertNotSame(before.modifierBuildOwner, cold.value.modifierBuildOwner)
                if (sample == 0) {
                    Files.write(directory.resolve("cache-on.rigir"), RigIrBinary.encode(actual))
                    Files.write(directory.resolve("cache-off.rigir"), RigIrBinary.encode(expected))
                    Files.writeString(directory.resolve("edited-stack.json"), edited.rigEdits.modifierStack.toString())
                }
                if (sample < 0) runReport.warmupCompleted() else {
                    record(sample, true, hot, actual); record(sample, false, cold, expected)
                }
                Files.writeString(directory.resolve("report.json"), runReport.json().toString())
                cold.value.modifierBuildOwner?.let { it.close(); owners.remove(it) }
            }
            // Resource release is part of completion; a failed release cannot publish successful percentiles.
            owners.forEach { it.close() }
            runReport.complete(PartBuildPlanSource.capture())
            Files.writeString(directory.resolve("report.json"), JsonObject(runReport.json() + buildJsonObject {
                put("validation", "complete IR + PNG + RGBA + actual APPEND_MESH_SPLIT route + split masks/glue/path/weights/source bindings")
                put("operation", identity.scenario)
                put("start_revision", identity.startRevision); put("candidate_revision", identity.candidate)
                put("meshes", identity.meshes); put("vertices", identity.vertices)
                putJsonObject("plan") {
                    put("route", plans.last().route.name); put("pilot", plans.last().pilot.name)
                    putJsonArray("modifiers") { plans.last().modifiers.forEach { decision -> addJsonObject {
                        put("id", decision.id); put("action", decision.action.name); put("reason", decision.reason.name)
                    } } }
                    putJsonArray("stages") { plans.last().stages.forEach { decision -> addJsonObject {
                        put("stage", decision.stage.name); put("action", decision.action.name); put("reason", decision.reason.name)
                    } } }
                }
            }).toString())
        } catch (failure: Throwable) {
            report?.failed(failure)
            val failed = report?.json() ?: buildJsonObject {
                put("status", "failed"); put("complete", false); put("performance_acceptance", false)
                put("failure", failure.toString())
            }
            Files.writeString(directory.resolve("report.json"), failed.toString())
            throw failure
        } finally { RigBuildProfile.recording = recording; owners.forEach { it.close() } }
    }

    private fun digest(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
