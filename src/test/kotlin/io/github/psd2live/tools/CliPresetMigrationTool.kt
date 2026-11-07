package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspaceModifierConversion
import io.github.psd2live.application.WorkspaceModifierConversionMismatch
import io.github.psd2live.application.WorkspaceModifierEdits
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Mesh as RigIRMesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceRevisions
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Compare a raw PSD export with a stack that uses names derived from the same source. */
class CliPresetMigrationTool {
    @Test fun compareRawPreset() = compareRawPreset(meshOnly = false, generateDeformers = true)

    @Test fun compareMeshOnly() = compareRawPreset(meshOnly = true, generateDeformers = true)

    @Test fun compareWithoutDeformers() = compareRawPreset(meshOnly = false, generateDeformers = false)

    @Test fun buildSharedFrameWorkspace(): Unit = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig()
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (saved, model) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct)
        val stack = RigModifierStack.fromJson(assertNotNull(saved.rigEdits.modifierStack))
        val evaluated = assertNotNull(model.modifierEvaluation)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4,
            "eye.aftershock" to 5, "eye.squash" to 5, "mouth/fitted" to 6, "mouth.outline" to 8,
            "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 8, "mouth.cheek-lid" to 8,
            "expression.cheek" to 3, "expression.gloom" to 3)
        for ((type, version) in versions) {
            val instance = stack.instances.single { it.type == type }
            assertEquals(version, instance.version, type)
            assertEquals(BuiltinModifierPlugins.reference(type, version), assertNotNull(instance.plugin), type)
            assertEquals(ModifierStatus.ACTIVE, evaluated.entries[stack.index(instance.id)].status, type)
        }
        val ir = RigIrCompiler.compile(model, tileArt = true, simulations = true)
        val reopened = WorkspacePreviewBuilder().build(saved)
        assertEquals(ir, RigIrCompiler.compile(reopened, tileArt = true, simulations = true))
    }

    @Test fun compareProjectConversion() = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val source = PSD2LivePipeline().readPsd(sample.path)
        val config = PipelineConfig()
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val before = builder.build(document)
        val report = output("cli-preset-migration").resolve("project-conversion-${sample.name}.txt")
        RigBuildProfile.reset()
        RigBuildProfile.recording = true
        try {
            val converted = WorkspaceModifierConversion.convert(document, before, WorkspaceRasterWork.Direct)
            val after = builder.build(converted)
            val oldIr = RigIrCompiler.compile(before, tileArt = true, simulations = true)
            val newIr = RigIrCompiler.compile(after, tileArt = true, simulations = true)
            report.writeText(buildString { compare("project conversion", oldIr, newIr) })
            assertEquals(oldIr.copy(parameters = oldIr.parameters.sortedBy { it.id }),
                newIr.copy(parameters = newIr.parameters.sortedBy { it.id }))
            val stack = RigModifierStack.fromJson(assertNotNull(converted.rigEdits.modifierStack))
            assertTrue(stack.overrides.isEmpty(), "Generated presentation must not become authored overrides")
            val outline = stack.instances.firstOrNull { instance ->
                instance.type == "mouth.outline" && instance.outputAliases.keys.any { it.startsWith("mouth-art:") }
            }
            if (outline != null) {
                val applied = WorkspaceModifierEdits.apply(
                    WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", outline.id) }), converted, after)
                val remaining = RigModifierStack.fromJson(assertNotNull(applied.rigEdits.modifierStack))
                assertTrue(remaining.instances.none { it.id == outline.id })
                verifyConvertedArchive(sample, document, converted, applied, newIr)
                report.appendText("\nApplied the raster-producing prefix and reopened both revisions from the archive.\n")
            }
        } catch (failure: WorkspaceModifierConversionMismatch) {
            report.writeText(failure.message.orEmpty() + "\n" + failure.objects + "\n")
            runCatching {
                val candidate = builder.build(WorkspaceModifierConversion.capture(document, before))
                val original = RigIrCompiler.compile(before, tileArt = true, simulations = true)
                val observed = RigIrCompiler.compile(candidate, tileArt = true, simulations = true)
                report.appendText(buildString { compare("rejected conversion candidate", original, observed) })
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            RigBuildProfile.recording = false
            val templates = RigBuildProfile.snapshot().filterKeys { it.startsWith("modifier legacy") || it.startsWith("modifier template consumer") }
            report.appendText(templates.entries.joinToString("\n", prefix = "\n") { (stage, value) -> "$stage ${value.second}" })
            RigBuildProfile.reset()
        }
    }

    private suspend fun verifyConvertedArchive(sample: Sample, original: WorkspaceDocument, converted: WorkspaceDocument,
                                               applied: WorkspaceDocument, expected: RigIR) {
        val root = Files.createTempDirectory("psd2live-project-")
        try {
            val store = WorkspaceStore(root.resolve("live"))
            val revision = WorkspaceRevisions.of(original)
            val history = WorkspaceHistoryTree(original, revision, revision)
            val convertedRevision = WorkspaceRevisions.of(converted)
            val convertedNode = history.commit(history.head().node.id, converted, convertedRevision, convertedRevision,
                "Convert presets", "user").node.id
            val appliedRevision = WorkspaceRevisions.of(applied)
            history.commit(convertedNode, applied, appliedRevision, appliedRevision, "Apply mouth outline", "user")
            val projectId = "converted-rig"
            val state = history.state()
            store.persistHistory(projectId, state)
            val repository = ProjectRepository(writeHeadCache = false)
            val file = output("cli-preset-migration").resolve("converted-${sample.name}.psd2live").toPath()
            repository.save(ProjectSaveCapture(projectId, state, JsonObject(emptyMap()), sample.path, store), file)
            repository.open(file).use { reopened ->
                val before = reopened.history.checkout(convertedNode).snapshot
                assertEquals(convertedRevision, WorkspaceRevisions.of(before))
                fun compile(model: RigPreviewModel) = RigIrCompiler.compile(model, tileArt = true, simulations = true)
                assertEquals(expected, compile(WorkspacePreviewBuilder().build(before)))
                val head = reopened.history.checkout(state.headNodeId).snapshot
                assertEquals(appliedRevision, WorkspaceRevisions.of(head))
                assertEquals(expected, compile(WorkspacePreviewBuilder().build(head)))
            }
        } finally {
            ProjectArchive.deleteTemporaryDirectory(root)
        }
    }

    private fun compareRawPreset(meshOnly: Boolean, generateDeformers: Boolean) {
        requireTools()
        val sample = Sample.fromEnvironment()
        val config = PipelineConfig(meshSpacing = 64,
            meshOnly = meshOnly, generateDeformers = generateDeformers,
            generatePhysics = setting("PSD2LIVE_CLI_PHYSICS", "1") == "1",
            exportMotions = setting("PSD2LIVE_CLI_MOTIONS", "1") == "1")
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val source = pipeline.readPsd(sample.path)
        val native = pipeline.buildPreview(source, config)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val sourceAnalyses = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val bindings = CliPresetBindings(sourceAnalyses, config)
        val candidate = if (config.meshOnly) pipeline.buildRawMeshExport(source, config) else
            WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false), cliBindings = bindings).second
        val expected = RigIrCompiler.compile(native, tileArt = true)
        val after = RigIrCompiler.compile(candidate, tileArt = true)
        val presented = if (config.meshOnly) candidate else
            CliPresetPresentation.apply(candidate, sourceAnalyses, bindings.moduleParameters, config)
        val result = RigIrCompiler.compile(presented, tileArt = true)
        val directory = output("cli-preset-migration")
        val name = when {
            config.meshOnly -> "report-mesh-only.txt"
            !config.generateDeformers -> "report-no-deformers.txt"
            else -> "report.txt"
        }
        val comparison = directory.resolve(name)
        comparison.writeText(buildString {
            appendLine("Names follow the source measurement. The native build is used only as the comparison baseline.")
            compare("historical names", expected, after)
            compare("shared export layout", expected, result)
        })
        if (setting("PSD2LIVE_CLI_VERIFY", "0") == "1") {
            assertEquals(expected, result, "See the field comparison in $comparison")
            val readback = directory.resolve(if (config.meshOnly) "mesh-only-readback" else "readback").toPath()
            val exported = if (config.meshOnly) {
                pipeline.run(sample.path, readback, config).also { output ->
                    val stack = RigModifierStack.fromJson(assertNotNull(output.previewModel.config.rigEdits.modifierStack))
                    assertTrue(stack.instances.isEmpty())
                    assertEquals(expected, RigIrCompiler.compile(output.previewModel, tileArt = true))
                }
            } else pipeline.export(presented, sample.path.fileName.toString(), readback)
            val report = exported.exportedFiles.single { it.path.fileName.toString().endsWith(".psd2live.json") }
            val physics = Json.parseToJsonElement(report.path.readText()).jsonObject.getValue("physics").jsonObject
            assertEquals(result.physics.groups.map { it.id }, physics.getValue("groups").jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun compareFittedBrowSharedFrames() = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(sample.path)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val bindings = CliPresetBindings(analysis, config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 1)
        for (cliBindings in listOf(null, bindings)) {
            val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = cliBindings)
            val stack = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
            val brow = stack.instances.single { it.type == "brow/fitted" }
            val index = stack.index(brow.id)
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(brow.type, 4))
            val selected = brow.copy(version = 4, plugin = artifact.reference)
            val modified = stack.copy(instances = stack.instances.map { if (it.id == brow.id) selected else it })
            val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
            val expected = assertNotNull(historical.modifierEvaluation).outputs
            val actual = assertNotNull(candidate.modifierEvaluation).outputs
            val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
            assertTrue(expected[index].meshes.any { !it.id.startsWith("ArtMeshEyebrow") && it != oldMeshes[it.id] },
                "Choose the DS PSD or another fixture with non-brow descendants of the refitted frame")
            assertTrue(expected[index - 1] == actual[index - 1], "The real preceding fitted head and eye stages must match")
            assertTrue(expected[index] == actual[index], buildString { compare("brow stage", expected[index], actual[index]) })
            val reference = RigIrCompiler.compile(historical, tileArt = true)
            val result = RigIrCompiler.compile(candidate, tileArt = true)
            assertTrue(reference == result, buildString { compare("full IR", reference, result) })

            if (cliBindings != null) {
                val root = Files.createTempDirectory("fitted-brow-v4-")
                val preferences = Preferences.userRoot().node("fitted-brow-v4/" + UUID.randomUUID())
                val store = ModifierPluginStore(root.resolve("installed"), preferences)
                try {
                    BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                    val worker = store.snapshot().single()
                    val definition = RigBrowFittedModifier.sharedFrameDefinition(RigModifierSource.of(candidate))
                    val providers = stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }
                    for ((type, version) in versions.filterKeys { it != brow.type })
                        assertEquals(version, providers.single { it.type == type }.version)
                    val context = definition.authorContext(selected).withUpstream(providers.map {
                        ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                    })
                    val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), expected[index - 1],
                        selected, {}, store, context = context, declaredParameters = definition.parameters(selected))
                    assertTrue(expected[index] == replay, buildString { compare("installed brow worker", expected[index], replay) })
                } finally {
                    ModifierPluginWorkers.stop(artifact.reference)
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(root)
                }
            }
        }
    }

    private fun StringBuilder.compare(label: String, expected: RigIR, actual: RigIR) {
        val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { field -> field.isAccessible = true; field.get(expected) != field.get(actual) }.map { it.name }
        appendLine("$label IR differences = $fields")
        fun <T> records(kind: String, reference: List<T>, observed: List<T>, id: (T) -> String) {
            val before = reference.associateBy(id)
            val after = observed.associateBy(id)
            val missing = before.keys - after.keys
            val extra = after.keys - before.keys
            val common = before.keys.intersect(after.keys)
            val changed = common.filter { before.getValue(it) != after.getValue(it) }
            val reordered = before.keys.filter { it in common } != after.keys.filter { it in common }
            appendLine("  $kind missing=${missing.size} ${missing.take(8)} extra=${extra.size} ${extra.take(8)} " +
                "changed=${changed.size} ${changed.take(12)} reordered=$reordered")
        }
        records("parameters", expected.parameters, actual.parameters) { it.id }
        records("meshes", expected.meshes, actual.meshes) { it.id }
        records("deformers", expected.deformers, actual.deformers) { it.id }
        val referenceDeformers = expected.deformers.map { it.id }
        val observedDeformers = actual.deformers.map { it.id }
        if (referenceDeformers.toSet() == observedDeformers.toSet() && referenceDeformers != observedDeformers) {
            appendLine("  deformer order differences = " + referenceDeformers.zip(observedDeformers)
                .mapIndexedNotNull { index, (before, after) -> if (before == after) null else "$index: $before -> $after" })
        }
        records("parts", expected.parts, actual.parts) { it.id }
        records("physics", expected.physics.groups, actual.physics.groups) { it.id }
        records("motions", expected.clips, actual.clips) { it.id }
        records("tiles", expected.textures.tiles, actual.textures.tiles) { it.id }
        records("paths", expected.authoring.paths, actual.authoring.paths) { it.id }
        appendLine("  texture pages match = ${expected.textures.pages == actual.textures.pages}")
        appendLine("  parameter links reordered only = ${expected.parameterLinks != actual.parameterLinks &&
            expected.parameterLinks.toSet() == actual.parameterLinks.toSet()}")
        val rotations = expected.deformers.filterIsInstance<io.github.psd2live.format.model.Deformer.Rotation>()
            .associateBy { it.id }
        for (rotation in actual.deformers.filterIsInstance<io.github.psd2live.format.model.Deformer.Rotation>()) {
            val before = rotations[rotation.id] ?: continue
            if (before == rotation) continue
            val cellDiff = before.pivot?.cells.orEmpty().zip(rotation.pivot?.cells.orEmpty())
                .firstOrNull { (left, right) -> left != right }
            appendLine("  rotation ${rotation.id}: angle=${before.baseAngle}->${rotation.baseAngle}" +
                " handle=${before.handleLength}->${rotation.handleLength}" +
                " axes changed=${before.pivot?.axes != rotation.pivot?.axes}" +
                " first changed pivot=$cellDiff")
        }
        val meshes = expected.meshes.associateBy { it.id }
        fun <T> meshDifferences(kind: String, value: (RigIRMesh) -> T) {
            val changed = actual.meshes.mapNotNull { mesh ->
                val previous = meshes[mesh.id] ?: return@mapNotNull null
                mesh.id.takeIf { value(previous) != value(mesh) }
            }
            appendLine("  mesh $kind differences = $changed")
        }
        for (mesh in actual.meshes) {
            val before = meshes[mesh.id]?.geometry ?: continue
            val after = mesh.geometry ?: continue
            if (before.indices == after.indices) continue
            appendLine("  topology ${mesh.id}: vertices=${before.positions.size / 2}->${after.positions.size / 2}" +
                " triangles=${before.indices.size / 3}->${after.indices.size / 3}")
        }
        meshDifferences("indices") { it.geometry?.indices }
        meshDifferences("UVs") { it.geometry?.uvs }
        meshDifferences("positions") { it.geometry?.positions }
        meshDifferences("deformer assignment") { it.parent }
        meshDifferences("texture assignment") { Triple(it.textureSource, it.page, it.tile) }
        meshDifferences("draw order") { it.drawOrder }
        meshDifferences("mask") { it.maskedBy to it.invertMask }
        meshDifferences("keyforms") { it.offsets }
        meshDifferences("channels") { it.channels }
        meshDifferences("blend shapes") { it.shapes }
        if (label == "shared export layout" || label == "rejected conversion candidate") {
            for ((before, after) in expected.parameterTree.zip(actual.parameterTree)) {
                if (before != after) appendLine("  parameter folder: expected=$before actual=$after")
            }
            for (part in expected.parts) {
                val observed = actual.parts.singleOrNull { it.id == part.id } ?: continue
                if (part.children != observed.children)
                    appendLine("  part ${part.id}: expected=${part.children} actual=${observed.children}")
            }
            val paths = actual.authoring.paths.mapTo(HashSet()) { it.id }
            appendLine("  missing paths by mesh = ${expected.authoring.paths.filterNot { it.id in paths }.groupingBy { it.mesh }.eachCount()}")
        }
        val tiles = expected.textures.tileArt.associateBy { it.tile }
        val pixels = actual.textures.tileArt.filter { it.tile in tiles && tiles[it.tile] != it }.map { it.tile }
        appendLine("  tile pixel differences = $pixels")
    }
}
