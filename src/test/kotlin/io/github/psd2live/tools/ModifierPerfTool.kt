package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** Opt-in end-to-end PSD import, including presets, simulation baking and initial history. */
class ModifierPerfTool {
    @Test fun profile() = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val report = StringBuilder()
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
            }
        } finally { RigBuildProfile.recording = false }
    }
}
