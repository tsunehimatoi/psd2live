package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.format.model.Mesh as RigIRMesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compare a raw PSD export with a stack that uses names derived from the same source. */
class CliPresetMigrationTool {
    @Test fun compareRawPreset() = compareRawPreset(meshOnly = false, generateDeformers = true)

    @Test fun compareMeshOnly() = compareRawPreset(meshOnly = true, generateDeformers = true)

    @Test fun compareWithoutDeformers() = compareRawPreset(meshOnly = false, generateDeformers = false)

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
        val candidate = if (config.meshOnly) pipeline.buildPreview(source, config.copy(rigEdits = overlay)) else
            WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false), cliBindings = bindings).second
        val expected = RigIrCompiler.compile(native, tileArt = true)
        val after = RigIrCompiler.compile(candidate, tileArt = true)
        val presented = CliPresetPresentation.apply(candidate, sourceAnalyses, bindings.moduleParameters, config)
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
            val exported = pipeline.export(presented, sample.path.fileName.toString(), directory.resolve("readback").toPath())
            val report = exported.exportedFiles.single { it.path.fileName.toString().endsWith(".psd2live.json") }
            val physics = Json.parseToJsonElement(report.path.readText()).jsonObject.getValue("physics").jsonObject
            assertEquals(result.physics.groups.map { it.id }, physics.getValue("groups").jsonArray.map { it.jsonPrimitive.content })
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
        val meshes = expected.meshes.associateBy { it.id }
        fun <T> meshDifferences(kind: String, value: (RigIRMesh) -> T) {
            val changed = actual.meshes.mapNotNull { mesh ->
                val previous = meshes[mesh.id] ?: return@mapNotNull null
                mesh.id.takeIf { value(previous) != value(mesh) }
            }
            appendLine("  mesh $kind differences = $changed")
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
        if (label == "shared export layout") {
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
