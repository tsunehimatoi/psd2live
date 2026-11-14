package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.scenario.Characters
import io.github.psd2live.project.rawConfig
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspacePartInitializationTest {
    @Test fun actualMouthPresetSupportsStrictQueriesAndConfigurationRoundTrips() {
        val artwork = Characters.mouthed()
        val empty = artwork.copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val (document, model) = WorkspacePresetInitialization.prepare(empty, WorkspaceRasterWork.Direct)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ error("The installed preset is already built") })
        runtime.install(runtime.state.value.state, "mouth-preset", document, model)
        val observed = WorkspaceModifierQueries.describe(runtime.capture())
        validateOperationSchema(observed, WorkspaceModifierSchemas.stack)
        val stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        assertTrue(stack.instances.any { it.type in setOf("mouth", "mouth/fitted") })
        assertEquals(stack, RigModifierStack.fromJson(Json.parseToJsonElement(stack.toJson().toString()).jsonObject))
        for (entry in stack.instances) validateOperationSchema(buildJsonObject {
            put("id", entry.id); put("type", entry.type); put("config", entry.config); put("state", runtime.capture().state)
        }, WorkspaceModifierSchemas.request("modifier_update"))
    }

    @Test fun cliSourceExportBuildProducesDeclaredMouthParametersWithoutPartFolding() {
        val artwork = Characters.mouthed()
        val model = PSD2LivePipeline().buildSourceExport(artwork.source, artwork.rawConfig())
        val stack = RigModifierStack.fromJson(requireNotNull(model.config.rigEdits.modifierStack))
        assertTrue(stack.instances.any { it.type == "mouth/fitted" })
        assertFalse(stack.instances.any { it.type == "mouth" })
        val ir = RigIrCompiler.compile(model)
        assertTrue(ir.parameters.map { it.id }.containsAll(listOf("ParamMouthForm", "ParamMouthOpenY")))
        assertEquals(ir.parameters.size, ir.parameters.map { it.id }.distinct().size)
        assertTrue(stack.instances.first { it.type == "rig.scaffold" }.outputAliases.isNotEmpty())
    }

    @Test fun freshDefaultFoldsButRequestedVersionsAndOverridesRemainExplicit() {
        val artwork = Characters.head(physics = false)
        val empty = artwork.copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val choices = WorkspaceInitialPreset(clothing = false)
        val normal = WorkspacePresetInitialization.prepare(empty, WorkspaceRasterWork.Direct, choices)
        val normalStack = RigModifierStack.fromJson(normal.first.rigEdits.modifierStack!!)
        val face = normalStack.instances.single { it.type == "face" }
        assertEquals(BuiltinModifierPlugins.reference("face", 1), face.plugin)
        val faceBase = if (face.config["mode"]?.jsonPrimitive?.content == "legacy") "face/legacy" else "face/fitted"
        val locked = WorkspacePresetInitialization.prepare(empty, WorkspaceRasterWork.Direct, choices,
            requestedVersions = mapOf(faceBase to 1))
        val lockedFace = RigModifierStack.fromJson(locked.first.rigEdits.modifierStack!!).instances.single { it.type == faceBase }
        assertEquals(BuiltinModifierPlugins.reference(faceBase, 1), lockedFace.plugin)
        assertEquals(RigIrCompiler.compile(normal.second), RigIrCompiler.compile(locked.second))
        val override = WorkspacePresetInitialization.prepare(empty, WorkspaceRasterWork.Direct, choices,
            pluginOverrides = mapOf(faceBase to (faceBase to requireNotNull(BuiltinModifierPlugins.reference(faceBase, 1)))))
        val overrideFace = RigModifierStack.fromJson(override.first.rigEdits.modifierStack!!).instances.single { it.type == faceBase }
        assertEquals(lockedFace, overrideFace)
        assertEquals(RigIrCompiler.compile(locked.second), RigIrCompiler.compile(override.second))
    }
}
