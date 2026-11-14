package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.CliMouthOutlinePresentation
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.bridge.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliMouthOutlinePresentationTool {
    @Test fun matchesHistoricalRibbonForms() = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val native = PSD2LivePipeline().apply { deferRuntimeBundles = true }
            .buildPreview(Sample.fromEnvironment().path, config)
        val source = native.analysis.source
        val edits = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val doc = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), edits,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = edits)))
        val (initialized, preset) = WorkspacePresetInitialization.prepare(doc, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false))
        val stack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val adjusted = CliMouthOutlinePresentation.apply(preset, config)
        val previous = preset.rig.puppet.drawables.associateBy { it.id }
        val historical = native.rig.puppet.drawables.associateBy { it.id.raw }
        val ribbons = adjusted.rig.puppet.drawables.filter { outline.localOutputName(it.id.raw)?.startsWith("mouth-lip:") == true }
        assertTrue(ribbons.isNotEmpty())
        val report = buildString {
            for (drawable in ribbons) {
                val target = requireNotNull(outline.localOutputName(drawable.id.raw)).removePrefix("mouth-lip:")
                val id = "${target.substringBeforeLast(':')}_lip_${target.substringAfterLast(':')}"
                val before = PuppetIr.meshToIr(previous.getValue(drawable.id))
                val expected = PuppetIr.meshToIr(historical.getValue(id))
                val actual = PuppetIr.meshToIr(drawable)
                appendLine("$id draw order ${before.drawOrder} -> ${actual.drawOrder}, raw=${expected.drawOrder}")
                appendLine("  keyforms ${actual.offsets == expected.offsets}, blend shapes ${actual.shapes == expected.shapes}")
                assertEquals(before.copy(drawOrder = actual.drawOrder, offsets = actual.offsets, shapes = actual.shapes), actual)
            }
        }
        output("cli-mouth-outline-presentation").resolve("report.txt").writeText(report)
        for (drawable in ribbons) {
            val target = requireNotNull(outline.localOutputName(drawable.id.raw)).removePrefix("mouth-lip:")
            val id = "${target.substringBeforeLast(':')}_lip_${target.substringAfterLast(':')}"
            val expected = PuppetIr.meshToIr(historical.getValue(id))
            val actual = PuppetIr.meshToIr(drawable)
            assertEquals(expected.drawOrder, actual.drawOrder, id)
            assertTrue(expected.offsets == actual.offsets, "$id keyforms differ")
            assertTrue(expected.shapes == actual.shapes, "$id blend shapes differ")
        }
        val ids = ribbons.mapTo(hashSetOf()) { it.id }
        assertEquals(preset.rig.puppet.drawables.filter { it.id !in ids }, adjusted.rig.puppet.drawables.filter { it.id !in ids })
    }
}
