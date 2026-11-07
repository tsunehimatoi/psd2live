package io.github.psd2live.application

import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceRevisions
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceSourceImporterTest {
    @TempDir lateinit var directory: Path

    @Test fun aPsdInstallsTheSelectedStackInItsInitialHistoryNode() = runBlocking<Unit> {
        val original = Characters.head(physics = false)
        val path = Files.createFile(directory.resolve("modifier-input.psd")).toAbsolutePath()
        val builder = WorkspacePreviewBuilder()
        for (mode in listOf(WorkspaceInitialModifiers.RECOMMENDED, WorkspaceInitialModifiers.EMPTY)) {
            val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
            val importer = WorkspaceSourceImporter(runtime, readPsd = { original.source })
            val result = importer.importPsd(path, null, runtime.state.value.state, initialConfig = original.rawConfig(), modifiers = mode)
            val installed = result.capture
            assertNotNull(installed.model.modifierEvaluation, "Freezing the imported atlas must retain the evaluated prefix")
            assertNotNull(installed.model.modifierMeshCache, "The first edit must retain the import's geometry cache")
            val stack = io.github.psd2live.core.modifier.RigModifierStack.fromJson(installed.document.rigEdits.modifierStack!!)
            assertEquals(1, runtime.history().selections.size)
            if (mode == WorkspaceInitialModifiers.EMPTY) {
                assertTrue(stack.instances.isEmpty())
                assertTrue(installed.model.rig.puppet.deformers.isEmpty())
            } else {
                assertTrue(stack.instances.all { it.presetGroup == "modern" })
                assertTrue(stack.instances.any { it.type == "face/fitted" })
                assertTrue(stack.instances.none { it.type == "face/legacy" || it.type == "face.depth-turn" })
                assertTrue(installed.model.rig.puppet.deformers.isNotEmpty())
                val independent = mapOf("body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5)
                for ((type, version) in independent) {
                    val instance = stack.instances.single { it.type == type }
                    assertEquals(version, instance.version, type)
                    assertEquals(assertNotNull(BuiltinModifierPlugins.reference(type, version)), instance.plugin)
                }
                val previous = mapOf("body/legacy" to 1, "face/fitted" to 1, "eye/fitted" to 2)
                val locked = stack.copy(instances = stack.instances.map { instance ->
                    val version = previous[instance.type] ?: return@map instance
                    instance.copy(version = version, plugin = assertNotNull(BuiltinModifierPlugins.reference(instance.type, version)))
                })
                val reference = installed.document.copy(rigEdits = installed.document.rigEdits.copy(modifierStack = locked.toJson()))
                assertEquals(RigIrCompiler.compile(builder.build(reference)), RigIrCompiler.compile(installed.model))
            }
            val rebuilt = builder.build(installed.document)
            assertEquals(io.github.psd2live.core.RigIrCompiler.compile(installed.model),
                io.github.psd2live.core.RigIrCompiler.compile(rebuilt))
        }
    }

    @Test fun newLegacyPresetUsesIndependentFaceEyesAndBrowsWithoutChangingTheModel() = runBlocking<Unit> {
        val original = Characters.head(physics = false)
        val path = Files.createFile(directory.resolve("legacy-face.psd")).toAbsolutePath()
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val importer = WorkspaceSourceImporter(runtime, readPsd = { original.source })
        val installed = importer.importPsd(path, null, runtime.state.value.state,
            initialConfig = original.rawConfig().copy(recipe = RigRecipe.Legacy),
            modifiers = WorkspaceInitialModifiers.CUSTOM,
            preset = WorkspaceInitialPreset(headStrength = .7f, bodyStrength = 1.3f, clothing = false)).capture
        val stack = RigModifierStack.fromJson(assertNotNull(installed.document.rigEdits.modifierStack))
        val replaced = setOf("face/legacy", "eye/legacy", "brow/legacy")
        assertEquals(replaced, stack.instances.filter { it.type in replaced }.mapTo(HashSet()) { entry ->
            assertEquals(2, entry.version, entry.type)
            assertEquals(assertNotNull(BuiltinModifierPlugins.reference(entry.type, 2)), entry.plugin)
            entry.type
        })
        assertEquals(1, runtime.history().selections.size)
        val locked = stack.copy(instances = stack.instances.map { entry ->
            if (entry.type in replaced) entry.copy(version = 1, plugin = assertNotNull(BuiltinModifierPlugins.reference(entry.type, 1)))
            else entry
        })
        val reference = installed.document.copy(rigEdits = installed.document.rigEdits.copy(modifierStack = locked.toJson()))
        assertEquals(RigIrCompiler.compile(builder.build(reference), tileArt = true),
            RigIrCompiler.compile(installed.model, tileArt = true))
    }

    @Test fun shortcutChoicesInstallIndependentModifiersAndReplayInOneHistoryNode() = runBlocking<Unit> {
        val original = Characters.head(physics = true)
        val path = Files.createFile(directory.resolve("shortcut.psd")).toAbsolutePath()
        val builder = WorkspacePreviewBuilder()
        for (mode in listOf(WorkspaceInitialModifiers.MINIMAL, WorkspaceInitialModifiers.DEFAULT, WorkspaceInitialModifiers.FULL, WorkspaceInitialModifiers.CUSTOM)) {
            val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
            val importer = WorkspaceSourceImporter(runtime, readPsd = { original.source })
            val choices = WorkspaceInitialPreset(headStrength = 0.7f, bodyStrength = 3.2f, clothing = false)
            val installed = importer.importPsd(path, null, runtime.state.value.state, initialConfig = original.rawConfig(),
                modifiers = mode, preset = choices.takeIf { mode == WorkspaceInitialModifiers.CUSTOM }).capture
            val stack = io.github.psd2live.core.modifier.RigModifierStack.fromJson(installed.document.rigEdits.modifierStack!!)
            assertEquals(1, runtime.history().selections.size)
            assertTrue(stack.instances.isNotEmpty())
            assertTrue(stack.instances.all { it.presetGroup == if (installed.model.config.recipe.modern) "modern" else "legacy" })
            assertTrue(installed.document.rigEdits.simEdits.isEmpty())
            if (mode == WorkspaceInitialModifiers.FULL) assertTrue(stack.instances.any { it.type == "simulation" })
            if (mode == WorkspaceInitialModifiers.MINIMAL) assertTrue(stack.instances.none { it.type == "motion" || it.type == "simulation" })
            val rebuilt = builder.build(installed.document)
            assertEquals(io.github.psd2live.core.RigIrCompiler.compile(installed.model, simulations = true),
                io.github.psd2live.core.RigIrCompiler.compile(rebuilt, simulations = true))
            if (mode == WorkspaceInitialModifiers.FULL) {
                val simulation = stack.instances.first { it.type == "simulation" }
                val provider = simulation.parameterProviders.values.first()
                WorkspaceDocumentCommands(runtime).execute(installed.projectId, installed.state, "Disable simulation input", listOf(
                    WorkspaceDocumentOperation("modifier_enable", buildJsonObject { put("id", provider); put("enabled", false) })
                ), io.github.psd2live.project.MutationAuthor.USER)
                val report = runtime.capture().model.rig.modifierReport!!.getValue("entries").jsonArray
                    .single { it.jsonObject.getValue("id").jsonPrimitive.content == simulation.id }.jsonObject
                assertEquals("missing_dependency", report.getValue("status").jsonPrimitive.content)
            }
        }
    }

    @Test fun materialAndRevisionPreparationLeaveTheCallerThreadButProjectionReturnsToIt() = runBlocking {
        val original = Characters.head(physics = false)
        val model = WorkspacePreviewBuilder().build(original)
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val reads = Collections.synchronizedList(mutableListOf<Thread>())
        val source = object : SourceArt by original.source {
            override val layers: List<SourceLayer> get() {
                reads += Thread.currentThread()
                return original.source.layers
            }
        }
        val runtime = WorkspaceRuntime<RigPreviewModel>({ error("Importer uses its injected builder") })
        val importer = WorkspaceSourceImporter(runtime, build = { model }, readPsd = { source })
        val path = Files.createFile(directory.resolve("input.psd")).toAbsolutePath()
        try {
            withContext(caller) {
                val thread = Thread.currentThread()
                var projected = false
                val result = importer.importPsd(path, null, runtime.state.value.state, initialConfig = original.rawConfig()) { document, _, _ ->
                    assertSame(thread, Thread.currentThread())
                    assertNull(runtime.state.value.capture, "Projection precedes the single runtime publication")
                    projected = true
                    assertEquals(original.source.layers.size, document.layout.materials.items.size)
                }
                assertTrue(projected)
                assertTrue(reads.isNotEmpty())
                assertTrue(reads.all { it !== thread }, "Source traversal and hashing must not run on the caller")
                val document = result.capture.document
                val items = document.layout.materials.items.associateBy { it.id }
                original.source.layers.forEach { layer ->
                    val material = items.getValue(document.layout.layerMaterials.getValue(layer.id.raw))
                    assertSame(layer.raster, material.raster, "Materials share the original pixels")
                }
                assertEquals(WorkspaceRevisions.reference(document), result.capture.revision)
                assertEquals(1, runtime.history().selections.size)
            }
        } finally { caller.close() }
    }

    @Test fun cancellationDuringRevisionPreparationDoesNotProjectOrPublish() = runBlocking {
        val original = Characters.head(physics = false)
        val model = WorkspacePreviewBuilder().build(original)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var preparingRevision = false
        val source = object : SourceArt by original.source {
            override val layers: List<SourceLayer> get() {
                if (preparingRevision) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return original.source.layers
            }
        }
        val runtime = WorkspaceRuntime<RigPreviewModel>({ error("Unused") })
        val before = runtime.read()
        var projected = false
        val importer = WorkspaceSourceImporter(runtime, build = { preparingRevision = true; model }, readPsd = { source })
        val path = Files.createFile(directory.resolve("cancel.psd")).toAbsolutePath()
        val job = launch(Dispatchers.Default) {
            importer.importPsd(path, null, before.runtime.state, initialConfig = original.rawConfig()) { _, _, _ -> projected = true }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            job.cancel()
        } finally { release.countDown(); job.cancelAndJoin() }
        assertFalse(projected)
        assertSame(before, runtime.read())
    }
}
