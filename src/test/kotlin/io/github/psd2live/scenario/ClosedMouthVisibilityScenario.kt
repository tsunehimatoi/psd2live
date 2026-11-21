package io.github.psd2live.scenario

import io.github.psd2live.core.modifier.ClosedMouthVisibility
import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ClosedMouthVisibilityScenario {
    companion object {
        private val baseSource by lazy {
            Characters.closedMouthHead().let { document ->
                document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
                    document.source.layers.map { layer -> when (layer.id.raw) {
                        "mouth" -> (layer as WorkspaceSourceLayer).copy(opacity = .65f)
                        "closed-mouth" -> (layer as WorkspaceSourceLayer).copy(opacity = .4f)
                        else -> layer
                    } }, document.source.groups))
            }
        }
        private val initializedCache = java.util.concurrent.ConcurrentHashMap<Boolean, io.github.psd2live.project.WorkspaceDocument>()
        private fun initializedDocument(separate: Boolean) = initializedCache.computeIfAbsent(separate) {
            val (prepared, choices) = WorkspacePresetInitialize.prepare(baseSource, WorkspaceInitialModifiers.RECOMMENDED)
            WorkspacePresetInitialization.prepare(prepared, WorkspaceRasterWork.Direct, requireNotNull(choices),
                requestedVersions = if (separate) mapOf("mouth/fitted" to 1) else emptyMap()).first
        }
    }

    @TempDir lateinit var temp: Path

    @Test fun foldedMouthVisibilityIsIndependentAndPersistent() = exercise(separate = false, fullLifecycle = true)
    @Test fun separateMouthModifiersKeepTheSameVisibilityContract() = exercise(separate = true, fullLifecycle = true)

    @Test fun archivedV1MouthCanCustomizeVisibilityWithoutUpdatingCode() = exercise(separate = true, archived = true)
    @Test fun archivedFoldedMouthSkipsRedundantOutlinesAndCanCustomizeVisibility() = exercise(separate = false, archived = true, outline = true)
    @Test fun separateMouthSkipsRedundantOutlinesAndKeepsVisibility() = exercise(separate = true, outline = true)

    private fun exercise(separate: Boolean, archived: Boolean = false, outline: Boolean = false, fullLifecycle: Boolean = false): Unit = runBlocking {
        val initialized = initializedDocument(separate)
        val initialStack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
        val stack = if (!outline) initialStack else initialStack.copy(instances = initialStack.instances.map {
            when (it.type) {
                "mouth" -> it.copy(config = JsonObject(it.config + ("outline" to JsonPrimitive(true))))
                "mouth.outline" -> it.copy(enabled = true)
                else -> it
            }
        })
        val owner = stack.instances.single { it.type == if (separate) "mouth/fitted" else "mouth" }
        val reference = if (!archived) owner.plugin else {
            val artifact = requireNotNull(BundledAlgorithmArtifacts.find(owner.type, owner.version))
            val manifest = artifact.manifest.copy(name = "Archived visibility fixture")
            val directory = Files.createDirectory(temp.resolve("archived-plugin"))
            Files.writeString(directory.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
            for (file in manifest.files.keys) requireNotNull(artifact.open(file)).use { Files.copy(it, directory.resolve(file)) }
            ModifierPluginStore.shared.install(directory.toString()).reference.also { assertNotEquals(owner.plugin, it) }
        }
        val saved = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = stack.copy(instances =
            stack.instances.map { if (it.id == owner.id) it.copy(plugin = reference) else it }).toJson()))
        try {
            Studio.run(temp.resolve("workspace"), saved) {
                assertEquals(ModifierStatus.ACTIVE, now.model.modifierEvaluation!!.entries.single { it.instance.id == owner.id }.status,
                    now.model.rig.modifierReport.toString())
                val openId = now.model.rig.ownLayerIdByDrawableId.entries.first { it.value == "mouth" }.key
                val closedId = now.model.rig.ownLayerIdByDrawableId.entries.first { it.value == "closed-mouth" }.key
                val ribbons = stack.instances.flatMap { MouthVisibilityCustomization.outlineOwners(it, setOf(openId)).keys }
                    .filter { id -> puppet.drawables.any { it.id.raw == id } }
                if (outline) assertTrue(ribbons.isEmpty(), "3.8.2 skips redundant outlines when custom closed artwork exists")
                val parameter = ClosedMouthVisibility.PARAMETER
                fun alpha(id: String, value: Float): Float {
                    val drawable = puppet.drawables.single { it.id.raw == id }
                    return drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity,
                        { if (it == parameter) value else puppet.parameters.single { p -> p.id == it }.default })
                }
                for (value in listOf(0f, .025f, .05f, .25f, .5f, 1f)) {
                    val c = ClosedMouthVisibility.Default.sample(value)
                    // New 3.8.2 generators respect source alpha even before customizing visibility.
                    assertEquals(.4f * c, alpha(closedId, value), .0001f)
                    assertEquals(.65f * (1f - c), alpha(openId, value), .0001f)
                }
                if (fullLifecycle) {
                    val original = RigIrCompiler.compile(now.model)
                    reopen()
                    assertEquals(original, RigIrCompiler.compile(now.model), "Opening an uncustomized project must not migrate its output")
                }

                fun keyEdit(action: String, value: Float, from: Float? = null) {
                    val target = if (separate) mapOf("target" to "mesh:$openId", "track" to "geometry") else emptyMap()
                    edit("$action deformation stop $value", "keyform_apply", req("changes" to listOf(target + mapOf(
                        "op" to "parameter_keys", "parameter" to parameter.raw, "action" to action, "values" to listOf(value)) +
                        (from?.let { mapOf("from" to it) } ?: emptyMap()))))
                }
                if (fullLifecycle) {
                    // Historical global edits also must not retime a visibility curve customized afterwards.
                    keyEdit("move", .075f, .05f)
                }
                val before = now
                val beforePuppet = puppet
                val curve = ClosedMouthVisibility.Default.move(.05f, .25f).insert(.2f).setOpacity(.2f, 1f)
                fun mouth() = RigModifierStack.fromJson(requireNotNull(now.document.rigEdits.modifierStack)).instances
                    .single { it.type == if (separate) "mouth/fitted" else "mouth" }
                fun configure(curve: ClosedMouthVisibility) {
                    val owner = mouth()
                    edit("set closed-mouth visibility", "modifier_update", req("id" to owner.id, "type" to owner.type,
                        "config" to JsonObject(owner.config + (ClosedMouthVisibility.CONFIG to curve.toJson()))))
                }
                configure(curve)
                if (fullLifecycle) {
                    val evaluator = CpuDeformationEvaluator()
                    val poses = listOf(0f, .05f, .075f, .2f, .225f, .25f, .5f, 1f).map { mapOf(parameter to it) } +
                        beforePuppet.parameters.filter { it.id != parameter }.flatMap { p ->
                            listOf(p.min, p.max).map { mapOf(parameter to .225f, p.id to it) }
                        }
                    for (pose in poses) {
                        val beforeGeometry = evaluator.evaluate(beforePuppet, pose).worldPositions
                        val afterGeometry = evaluator.evaluate(puppet, pose).worldPositions
                        assertEquals(beforeGeometry.keys, afterGeometry.keys)
                        for (id in beforeGeometry.keys) assertContentEquals(beforeGeometry.getValue(id), afterGeometry.getValue(id), "Visibility moved $id at $pose")
                    }
                }
                fun verifyVisibility() {
                    assertEquals(reference, mouth().plugin, "Visibility must never rebind the saved algorithm")
                    for (value in listOf(0f, .05f, .075f, .12f, .2f, .21f, .225f, .24f, .25f, .5f, 1f)) {
                        val c = curve.sample(value)
                        assertEquals(.4f * c, alpha(closedId, value), .0001f, "Closed at $value")
                        assertEquals(.65f * (1f - c), alpha(openId, value), .0001f, "Open at $value")
                        ribbons.forEach { id -> assertEquals(.65f * (1f - c), alpha(id, value), .0001f, "Outline at $value") }
                    }
                }
                verifyVisibility()
                assertEquals(.2f, alpha(closedId, .225f), .0001f)
                assertEquals(.325f, alpha(openId, .225f), .0001f)
                if (fullLifecycle) {
                    val customized = now
                    checkout("undo visibility", before.historyHead)
                    assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(now.model))
                    checkout("redo visibility", customized.historyHead)
                    verifyVisibility()
                    keyEdit("delete", .25f)
                    keyEdit("move", .12f, .075f)
                    verifyVisibility()
                    val owner = mouth()
                    edit("rebuild fitted mouth with new opening timing", "modifier_update", req("id" to owner.id,
                        "config" to JsonObject(owner.config + ("values" to JsonObject(owner.config["values"]?.jsonObject.orEmpty() + ("early" to JsonPrimitive(45f)))))))
                    verifyVisibility()
                }
                val expected = RigIrCompiler.compile(now.model)
                reopen()
                verifyVisibility()
                assertEquals(curve, ClosedMouthVisibility.fromConfig(mouth().config))
                assertEquals(expected, RigIrCompiler.compile(now.model))
                if (fullLifecycle || archived) {
                    // 3.8.2 can bake a single node in place. Its replay already includes our opacity
                    // customization and must not apply it twice or require upgrading the saved worker.
                    edit("apply customized mouth in place", "modifier_apply", req("id" to mouth().id, "scope" to "selected"))
                    verifyVisibility()
                    assertEquals(expected, RigIrCompiler.compile(now.model))
                    if (fullLifecycle) {
                        reopen()
                        verifyVisibility()
                        assertEquals(expected, RigIrCompiler.compile(now.model))
                    }
                }
            }
        } finally {
            if (archived && reference != null) {
                ModifierPluginWorkers.stop(reference)
                runCatching { ModifierPluginStore.shared.uninstall(reference) }
            }
        }
    }
}
