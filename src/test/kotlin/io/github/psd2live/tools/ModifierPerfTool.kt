package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.psd2live.project.config

/** Opt-in end-to-end PSD import, including presets, simulation baking and initial history. */
class ModifierPerfTool {
    @Test fun profile() = runBlocking {
        requireTools()
        System.setProperty("psd2live.validatePreviewBundles", "false")
        val sample = Sample.fromEnvironment()
        val report = StringBuilder()
        val recording = if (setting("PSD2LIVE_JFR", "0") == "1")
            jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply { start() } else null
        RigBuildProfile.recording = true
        try {
            repeat(2) { round ->
                RigBuildProfile.reset()
                val builder = WorkspacePreviewBuilder()
                val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
                val start = System.nanoTime()
                WorkspaceSourceImporter(runtime, build = { builder.build(it) }).importPsd(
                    sample.path.toAbsolutePath(), null, runtime.state.value.state,
                    modifiers = WorkspaceInitialModifiers.RECOMMENDED)
                val elapsed = (System.nanoTime() - start) / 1e6
                report.appendLine("import $round: %.1f ms".format(elapsed))
                RigBuildProfile.snapshot().forEach { (name, value) ->
                    report.appendLine("%-65s %9.1f ms x%d".format(name, value.first, value.second))
                }
                output("modifier-perf").resolve("report.txt").writeText(report.toString())
                println("import $round: $elapsed ms")
                if (round == 1 && setting("PSD2LIVE_VERIFY", "0") == "1") {
                    val capture = runtime.capture()
                    val enabled = RigStageCache.enabled
                    try {
                        RigStageCache.enabled = false
                        val cold = PSD2LivePipeline().buildPreview(capture.document.source, capture.document.config())
                        assertEquals(RigIrCompiler.compile(capture.model, tileArt = true), RigIrCompiler.compile(cold, tileArt = true))
                        report.appendLine("uncached replay: identical complete IR")
                        output("modifier-perf").resolve("report.txt").writeText(report.toString())
                    } finally { RigStageCache.enabled = enabled }
                }
            }
        } finally {
            RigBuildProfile.recording = false
            recording?.use { it.stop(); it.dump(output("modifier-perf").resolve("profile.jfr").toPath()) }
        }
    }
}
