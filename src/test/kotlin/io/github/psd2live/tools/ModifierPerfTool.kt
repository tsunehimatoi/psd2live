package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.fail
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.config
import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*

/** Opt-in end-to-end PSD import, including presets, simulation baking and initial history. */
class ModifierPerfTool {
    @Test fun profile() = runBlocking {
        requireTools()
        System.setProperty("psd2live.validatePreviewBundles", "false")
        val sample = Sample.fromEnvironment()
        val report = StringBuilder()
        val baseline = setting("PSD2LIVE_BASELINE_MODIFIER", "")
        val recording = if (setting("PSD2LIVE_JFR", "0") == "1")
            jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply { start() } else null
        RigBuildProfile.recording = true
        try {
            repeat(2) { round ->
                RigBuildProfile.reset()
                val builder = WorkspacePreviewBuilder()
                val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
                val start = System.nanoTime()
                WorkspaceSourceImporter(runtime, build = { builder.build(it) }).importPsd(
                    sample.path.toAbsolutePath(), null, runtime.state.value.state,
                    modifiers = WorkspaceInitialModifiers.RECOMMENDED)
                val elapsed = (System.nanoTime() - start) / 1e6
                val stages = RigBuildProfile.snapshot()
                // Baking is synchronous here: subtract its outer wall-time stage once, not nested solver timings.
                val baking = stages["modifier simulation: bake"]?.first ?: 0.0
                val counted = elapsed - baking
                report.appendLine("import $round: %.1f ms".format(elapsed))
                report.appendLine("import $round baking excluded: %.1f ms".format(baking))
                report.appendLine("import $round counted: %.1f ms; limit: 4000 ms; %s".format(counted, if (counted <= 4000.0) "PASS" else "FAIL"))
                stages.forEach { (name, value) ->
                    report.appendLine("%-65s %9.1f ms x%d".format(name, value.first, value.second))
                }
                output("modifier-perf").resolve("report.txt").writeText(report.toString())
                println("import $round: $elapsed ms")
                if (round == 1) {
                    val before = runtime.capture()
                    val stack = RigModifierStack.fromJson(requireNotNull(before.document.rigEdits.modifierStack))
                    val eye = stack.instances.firstOrNull { it.type == "eye/fitted" }
                    if (eye != null) {
                        val values = eye.config["values"]?.jsonObject.orEmpty()
                        val old = values["roundness"]?.jsonPrimitive?.float ?: 50f
                        RigBuildProfile.reset()
                        val editStart = System.nanoTime()
                        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Adjust eye preset", listOf(
                            WorkspaceDocumentOperation("modifier_update", buildJsonObject {
                                put("id", eye.id); put("type", eye.type)
                                put("config", JsonObject(eye.config + ("values" to JsonObject(values + ("roundness" to JsonPrimitive(if (old < 99f) old + 1f else old - 1f))))))
                            })), io.github.psd2live.project.MutationAuthor.USER)
                        report.appendLine("modifier adjustment: %.1f ms".format((System.nanoTime() - editStart) / 1e6))
                        report.appendLine("adjustment simulation bakes: " + (RigBuildProfile.snapshot()["modifier simulation: bake"]?.second ?: 0))
                        RigBuildProfile.snapshot().forEach { (name, value) ->
                            report.appendLine("adjustment %-54s %9.1f ms x%d".format(name, value.first, value.second))
                        }
                        output("modifier-perf").resolve("report.txt").writeText(report.toString())
                    }
                }
                if (round == 1 && setting("PSD2LIVE_VERIFY", "0") == "1") {
                    val capture = runtime.capture()
                    val enabled = RigStageCache.enabled
                    try {
                        RigStageCache.enabled = false
                        val cold = PSD2LivePipeline().buildPreview(capture.document.source, capture.document.config())
                        val expected = RigIrCompiler.compile(capture.model, tileArt = true)
                        val replayed = RigIrCompiler.compile(cold, tileArt = true)
                        if (expected != replayed) {
                            val fields = differingFields(expected, replayed)
                            val detail = "Uncached replay differs in $fields" + differingStep(capture.model, cold)
                            report.appendLine(detail)
                            output("modifier-perf").resolve("report.txt").writeText(report.toString())
                            fail(detail)
                        }
                        report.appendLine("uncached replay: identical complete IR")
                        output("modifier-perf").resolve("report.txt").writeText(report.toString())
                    } finally { RigStageCache.enabled = enabled }
                }
                if (round == 1 && baseline.isNotEmpty()) {
                    val type = baseline.substringBeforeLast('@')
                    val version = baseline.substringAfterLast('@').toInt()
                    val captured = runtime.capture()
                    val overlay = captured.document.rigEdits
                    val stack = RigModifierStack.fromJson(requireNotNull(overlay.modifierStack))
                    require(stack.instances.any { it.type == type && it.version != version }) { "No alternate version to compare for $baseline" }
                    val plugin = requireNotNull(BuiltinModifierPlugins.reference(type, version)) { "Unknown builtin algorithm $baseline" }
                    val locked = stack.copy(instances = stack.instances.map { instance ->
                        if (instance.type == type) instance.copy(version = version, plugin = plugin)
                        else instance
                    })
                    val reference = captured.document.copy(rigEdits = overlay.copy(modifierStack = locked.toJson()))
                    val replay = PSD2LivePipeline().buildPreview(reference.source, reference.config())
                    val expected = RigIrCompiler.compile(captured.model, tileArt = true)
                    val actual = RigIrCompiler.compile(replay, tileArt = true)
                    val detail = if (expected == actual) "identical complete IR" else
                        "differs in ${differingFields(expected, actual)}" + differingStep(captured.model, replay)
                    report.appendLine("locked baseline $baseline: $detail")
                    output("modifier-perf").resolve("report.txt").writeText(report.toString())
                    if (expected != actual) fail("Locked baseline $baseline $detail")
                }
            }
        } finally {
            RigBuildProfile.recording = false
            recording?.use { it.stop(); it.dump(output("modifier-perf").resolve("profile.jfr").toPath()) }
        }
    }

    private fun differingFields(expected: RigIR, actual: RigIR): String = RigIR::class.java.declaredFields
        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .filter { field -> field.isAccessible = true; field.get(expected) != field.get(actual) }
        .joinToString { it.name }

    private fun differingStep(expected: RigPreviewModel, actual: RigPreviewModel): String {
        val reference = expected.modifierEvaluation?.outputs.orEmpty()
        val replay = actual.modifierEvaluation ?: return ""
        val first = reference.zip(replay.outputs).indexOfFirst { (left, right) -> left != right }
        return replay.entries.getOrNull(first)?.instance?.type?.let { ". First differing step $it" }.orEmpty()
    }
}
