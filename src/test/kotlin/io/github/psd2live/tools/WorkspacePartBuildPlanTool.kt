package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/**
 * Procedural artwork only; no external sample or worker is required.
 * PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*WorkspacePartBuildPlanTool'
 * Default: one correctness pair, zero warmups, never performance acceptance.
 * Formal (reserved window only): PSD2LIVE_BUILD_PLAN_FORMAL=1 and PSD2LIVE_BUILD_PLAN_BASELINE=<full SHA>.
 * Requires a clean fixed checkout, >=5 warmup pairs and >=30 measured pairs; defaults are 5 and 30.
 * PSD2LIVE_BUILD_PLAN_WARMUPS / PSD2LIVE_BUILD_PLAN_SAMPLES override those counts.
 * Both phases validate parity; warmup observations are excluded from latency summaries.
 */
class WorkspacePartBuildPlanTool {
    @Test fun actualPartSubConfigMatchesUncachedBuild() = runBlocking<Unit> {
        requireTools()
        val directory = output("workspace-part-build-plan").toPath()
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
            val artwork = Characters.mouthed(withNose = true)
            val config = artwork.config().copy(meshSpacing = 24,
                rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
            val plain = artwork.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
            val ordinary = retain(enabled.build(plain))
            val all = ordinary.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
            val ids = ordinary.rig.layerIdByDrawableId
            val mouthTargets = all.filter { ids[it.id] in setOf("mouth", "face", "nose") }
            assertEquals(3, mouthTargets.size)
            val face = mouthTargets.single { ids[it.id] == "face" }.id
            val nose = mouthTargets.single { ids[it.id] == "nose" }
            fun partConfig(share: Int) = buildJsonObject {
                put("scaffold", "structure"); put("mode", "fitted")
                put("outline", true); put("jaw", true)
                putJsonObject("subConfigs") {
                    putJsonObject("mouth.jaw") { putJsonObject("values") { put("share", share) } }
                }
            }
            val scaffold = RigModifierInstance("structure", "rig.scaffold", 2, all)
            val part = RigModifierInstance("mouth-part", "mouth", 1, all, config = partConfig(15))
            val suffix = RigModifierInstance("successor", "mesh.split", 1, listOf(nose))
            val stack = RigModifierStack(listOf(scaffold, part, suffix))
            val beforeDocument = plain.copy(rigEdits = plain.rigEdits.copy(modifierStack = stack.toJson()))
            val before = retain(enabled.build(beforeDocument, ordinary))
            val original = RigIrCompiler.compile(before, tileArt = true)
            val edited = beforeDocument.copy(rigEdits = beforeDocument.rigEdits.copy(modifierStack =
                stack.update(part.id) { it.copy(config = partConfig(25)) }.toJson()))
            val saved = before.modifierEvaluation
            val identity = RigBuildProfile.Operation("part-sub-config", WorkspaceRevisions.of(beforeDocument),
                WorkspaceRevisions.of(edited), "mouth.jaw.share:15->25", original.meshes.size,
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
                assertEquals(WorkspaceBuildPilot.PART_SUB_CONFIG, plan.pilot)
                assertEquals(listOf(WorkspaceBuildReason.UNCHANGED_PREFIX, WorkspaceBuildReason.PART_SUB_CONFIG_CHANGED,
                    WorkspaceBuildReason.ORDERED_SUCCESSOR), plan.modifiers.map { it.reason })
                assertEquals(1L, hot.stages["build plan pilot: part_sub_config"]?.second)
                assertEquals(2L, hot.gauges["build plan invalidated modifiers"])
                assertEquals(1L, hot.stages["modifier author input cache hits"]?.second)
                assertEquals(1L, hot.stages["modifier cache hits"]?.second)
                assertEquals(2L, hot.stages["modifier cache misses"]?.second)
                assertEquals(1L, hot.stages["modifier evaluate: mouth"]?.second)
                assertEquals(1L, hot.stages["modifier evaluate: mesh.split"]?.second)
                assertEquals(3L, cold.gauges["build plan invalidated modifiers"])
                assertNull(cold.stages["modifier author input cache hits"])
                assertNull(cold.stages["modifier cache hits"])
                val actual = RigIrCompiler.compile(hot.value, tileArt = true)
                val expected = RigIrCompiler.compile(cold.value, tileArt = true)
                assertNotEquals(original.meshes.single { it.id == face }.offsets, actual.meshes.single { it.id == face }.offsets,
                    "The local jaw setting must change real face keyforms")
                assertEquals(expected, actual, "Complete IR, including parameter order, resources and texture bindings")
                assertContentEquals(RigIrBinary.encode(expected), RigIrBinary.encode(actual))
                assertTrue(actual.textures.pages.isNotEmpty() && actual.textures.pages.all { it.png.size > 0 })
                assertTrue(actual.textures.tileArt.isNotEmpty() && actual.textures.tileArt.all { it.rgba.size > 0 })
                actual.textures.pages.zip(expected.textures.pages).forEach { (a, b) -> assertContentEquals(b.png.shared(), a.png.shared()) }
                actual.textures.tileArt.zip(expected.textures.tileArt).forEach { (a, b) -> assertContentEquals(b.rgba.shared(), a.rgba.shared()) }
                assertEquals(cold.value.rig.modifierReport, hot.value.rig.modifierReport)
                assertEquals(cold.value.rig.layerIdByDrawableId, hot.value.rig.layerIdByDrawableId)
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
                put("validation", "complete IR + PNG + RGBA + actual PART_SUB_CONFIG route + changed face keyforms")
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
