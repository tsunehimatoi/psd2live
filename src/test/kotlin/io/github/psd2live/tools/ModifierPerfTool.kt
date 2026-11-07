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
        val importVersions = versionSelection(setting("PSD2LIVE_IMPORT_MODIFIER_VERSIONS", ""))
        if (importVersions.isNotEmpty()) report.appendLine("import locked versions: " +
            importVersions.entries.joinToString(",") { (type, version) -> "$type@$version" })
        val recording = if (setting("PSD2LIVE_JFR", "0") == "1")
            jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply { start() } else null
        RigBuildProfile.recording = true
        try {
            repeat(2) { round ->
                RigBuildProfile.reset()
                val builder = WorkspacePreviewBuilder()
                val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
                val start = System.nanoTime()
                val installed = WorkspaceSourceImporter(runtime, build = { builder.build(it) }, preparePreset = { document, work, choices ->
                    WorkspacePresetInitialization.prepare(document, work, choices, preferSourceBindings = true, requestedVersions = importVersions)
                }).importPsd(
                    sample.path.toAbsolutePath(), null, runtime.state.value.state,
                    modifiers = WorkspaceInitialModifiers.RECOMMENDED)
                val elapsed = (System.nanoTime() - start) / 1e6
                if (importVersions.isNotEmpty()) {
                    val stack = RigModifierStack.fromJson(requireNotNull(installed.capture.document.rigEdits.modifierStack))
                    for ((type, version) in importVersions) {
                        val instances = stack.instances.filter { it.type == type }
                        check(instances.isNotEmpty() && instances.all { it.version == version }) { "Import did not lock $type@$version" }
                    }
                }
                val stages = RigBuildProfile.snapshot()
                // Baking is synchronous here: subtract its outer wall-time stage once, not nested solver timings.
                val baking = stages["modifier simulation: bake"]?.first ?: 0.0
                val counted = elapsed - baking
                report.appendLine("import $round: %.1f ms".format(elapsed))
                report.appendLine("import $round baking excluded: %.1f ms".format(baking))
                report.appendLine("import $round excluding baking: %.1f ms".format(counted))
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
                    val captured = runtime.capture()
                    val overlay = captured.document.rigEdits
                    val stack = RigModifierStack.fromJson(requireNotNull(overlay.modifierStack))
                    val versions = versionSelection(baseline).mapValues { (type, version) ->
                        require(stack.instances.any { it.type == type && it.version != version }) {
                            "No alternate version to compare for $type@$version"
                        }
                        version to requireNotNull(BuiltinModifierPlugins.reference(type, version)) {
                            "Unknown builtin algorithm $type@$version"
                        }
                    }
                    val locked = stack.copy(instances = stack.instances.map { instance ->
                        versions[instance.type]?.let { (version, plugin) -> instance.copy(version = version, plugin = plugin) }
                            ?: instance
                    })
                    val reference = captured.document.copy(rigEdits = overlay.copy(modifierStack = locked.toJson()))
                    fun measured(label: String, document: io.github.psd2live.project.WorkspaceDocument): RigPreviewModel {
                        RigBuildProfile.reset()
                        val started = System.nanoTime()
                        val preview = PSD2LivePipeline().buildPreview(document.source, document.config())
                        report.appendLine("$label rebuild: %.1f ms".format((System.nanoTime() - started) / 1e6))
                        val stages = RigBuildProfile.snapshot()
                        report.appendLine("$label legacy templates: " +
                            stages.filterKeys { it.startsWith("modifier legacy template:") }.values.sumOf { it.second })
                        val selectedStages = versions.keys.flatMapTo(HashSet()) {
                            listOf("modifier parameters: $it", "modifier input: $it", "modifier evaluate: $it")
                        }
                        for ((stage, result) in stages) {
                            if (stage in selectedStages || stage.startsWith("source fitting: mouth") ||
                                stage.startsWith("modifier legacy template:") ||
                                stage.startsWith("modifier template consumer:")) {
                                report.appendLine("$label $stage: %.1f ms x%d".format(result.first, result.second))
                            }
                        }
                        return preview
                    }
                    measured("current", captured.document)
                    val replay = measured("locked $baseline", reference)
                    val expected = RigIrCompiler.compile(captured.model, tileArt = true)
                    val actual = RigIrCompiler.compile(replay, tileArt = true)
                    val detail = if (expected == actual) "identical complete IR" else
                        "differs in ${differingFields(expected, actual)}" + differingStep(captured.model, replay)
                    report.appendLine("locked baseline $baseline: $detail")
                    output("modifier-perf").resolve("report.txt").writeText(report.toString())
                    if (expected != actual) fail("Locked baseline $baseline $detail")
                    if (setting("PSD2LIVE_VERIFY", "0") == "1") {
                        val enabled = RigStageCache.enabled
                        try {
                            RigStageCache.enabled = false
                            val cold = PSD2LivePipeline().buildPreview(reference.source, reference.config())
                            val coldIr = RigIrCompiler.compile(cold, tileArt = true)
                            if (expected != coldIr) fail("Uncached locked baseline $baseline differs in ${differingFields(expected, coldIr)}")
                            report.appendLine("uncached locked baseline $baseline: identical complete IR")
                            output("modifier-perf").resolve("report.txt").writeText(report.toString())
                        } finally { RigStageCache.enabled = enabled }
                    }
                }
            }
        } finally {
            RigBuildProfile.recording = false
            recording?.use { it.stop(); it.dump(output("modifier-perf").resolve("profile.jfr").toPath()) }
        }
    }

    private fun versionSelection(value: String): Map<String, Int> {
        if (value.isBlank()) return emptyMap()
        val entries = value.split(',').map { selection ->
            val pair = selection.trim().split('@', limit = 2)
            val version = pair.getOrNull(1)?.toIntOrNull()
            require(pair[0].isNotBlank() && version != null && version > 0) { "Expected modifier type@version: $selection" }
            pair[0] to version
        }
        return entries.toMap().also { require(it.size == entries.size) { "Choose only one version per modifier type" } }
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
