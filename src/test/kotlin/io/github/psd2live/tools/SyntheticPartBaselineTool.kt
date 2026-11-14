package io.github.psd2live.tools

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Same authored child identity and inputs across expanded, folded and real-worker folded stacks. */
class SyntheticPartBaselineTool {
    private data class Case(val name: String, val document: WorkspaceDocument, val base: String, val child: String)

    @Test fun profile() {
        requireTools()
        val smoke = setting("PSD2LIVE_BASELINE_SMOKE", "0") == "1"
        val samples = if (smoke) 1 else setting("PSD2LIVE_BASELINE_SAMPLES", "30").toInt().also { require(it >= 30) }
        val report = SyntheticBaselineReport(if (smoke) "synthetic-parts-smoke" else "synthetic-parts",
            "source-context capture and modifier evaluation; expanded/folded/real worker; no workspace CAS, export or GUI")
        report.metadata("acceptance_run", (!smoke).toString())
        report.metadata("identity_policy", "fresh synthetic instances have explicit stable ids and no pre-existing plugin locks; compare complete RigIR without id normalization")
        report.metadata("timing_scope", "in-process folded base/sub changes calls; excludes applying changes and final composition; real worker remains host end-to-end without internal stage wire fields")
        val wasRecording = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try {
            val cases = listOf(Case("eye-wide", Characters.eyed(), "eye/fitted", "eye.wide"),
                Case("brow-shape", Characters.browed(), "brow/fitted", "brow.shape"),
                Case("mouth-outline", Characters.mouthed(), "mouth/fitted", "mouth.outline"))
            for (case in cases) for ((size, spacing) in listOf("small" to 40, "medium" to 20, "large" to 10)) {
                if (smoke && (case.name != "eye-wide" || size != "small")) continue
                val label = "${case.name}/$size"
                report.context("$label/setup")
                val config = RigModifierPipeline.ordinaryConfig(case.document.rawConfig()).copy(meshSpacing = spacing)
                val analysis = CharacterAnalyzer.analyze(case.document.source, config)
                val atlas = AtlasLayout.pack(analysis.layers, config)
                val ordinary = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
                val input = PuppetIr.toIr(ordinary.puppet)
                val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
                val knob = RigLegacyBindings.spec(case.child).knobs.first()
                val vertices = input.meshes.sumOf { (it.geometry?.positions?.size?.toLong() ?: 0) / 2 }
                report.metadata("$label input", "meshSpacing=$spacing, meshes=${input.meshes.size}, vertices=$vertices, knob=${knob.id}")
                fun expanded(fraction: Float): RigModifierStack {
                    val sub = buildJsonObject {
                        put("scaffold", "structure")
                        if (RigLegacyBindings.spec(case.child).module?.base != null) put("base", "base")
                        putJsonObject("values") { put(knob.id, knob.range.start + (knob.range.endInclusive - knob.range.start) * fraction) }
                        if (case.child == "mouth.outline") put("color", 0x563421)
                    }
                    // Explicit unpinned identities describe freshly generated fixture instances, not migrated history.
                    return RigModifierStack(instances = listOf(
                        RigModifierInstance("structure", "rig.scaffold", 2, targets),
                        RigModifierInstance("base", case.base, 1, targets, config = buildJsonObject { put("scaffold", "structure") }),
                        RigModifierInstance("authored-child", case.child, 1, targets, config = sub)))
                }
                fun folded(stack: RigModifierStack) = stack.copy(instances = RigPartFolding.foldAll(stack.instances)).also {
                    assertEquals(2, it.instances.size, "Fixture must actually fold")
                }
                val partType = folded(expanded(.63f)).instances.last().type
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(partType, 1))
                val temporary = Files.createTempDirectory("part-baseline-")
                val preferences = Preferences.userRoot().node("psd2live-tools/parts/${UUID.randomUUID()}")
                val store = ModifierPluginStore(temporary.resolve("installed"), preferences)
                val session = ModifierPluginWorkers.openSession(store)
                val meshes = PreviewMeshCache()
                val knownWorkers = linkedSetOf<Long>()
                fun memory(phase: String, released: Boolean = false) {
                    knownWorkers += ModifierPluginWorkers.cacheSnapshots(store).map { it.pid }
                    report.memory("$label/$phase", knownWorkers, released)
                }
                try {
                    memory("before-worker")
                    val installed = installWorker(artifact, temporary, store)
                    report.metadata("$label worker", "${installed.reference.id}@${installed.reference.version}:${installed.reference.digest}")
                    val caches = listOf("expanded", "folded", "worker-folded").associateWith { RigModifierEvaluationCache() }
                    fun evaluate(stack: RigModifierStack, variant: String, cache: RigModifierEvaluationCache?): RigIR {
                        val source = RigModifierSource(analysis, atlas,
                            config.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson())), ordinary.layerIdByDrawableId,
                            materialBase = ordinary.puppet, meshCache = meshes)
                        if (variant != "worker-folded") return RigModifierAlgorithms.evaluator(source, stack)
                            .evaluate(input, stack, cache).rig
                        val definitions = BuiltinModifierPlugins.definitions(source)
                        val worker = ModifierPluginCatalog.definitions(store, captured = definitions,
                            resolveRasters = source::resolveRasters, workerSession = session)
                            .single { it.pluginReference == installed.reference && it.type != partType }
                        val registry = RigModifierRegistry(definitions.map {
                            if (it.type == partType) worker.copy(type = partType, pluginReference = null) else it
                        })
                        return RigModifierEvaluator(registry, resolveRasters = source::resolveRasters).evaluate(input, stack, cache).rig
                    }
                    fun compare(scenario: String, ordinal: Int, fraction: Float, cached: Boolean) {
                        report.context("$label/$scenario/$ordinal")
                        val expanded = expanded(fraction)
                        val folded = folded(expanded)
                        val results = caches.keys.associateWith { variant ->
                            val stack = if (variant == "expanded") expanded else folded
                            val sample = RigBuildProfile.measure(RigBuildProfile.Operation("$label-$scenario-$ordinal-$variant",
                                ordinal.toString(), "${knob.id}=$fraction", "$label/$scenario/$variant", input.meshes.size, vertices)) {
                                evaluate(stack, variant, caches.getValue(variant).takeIf { cached }).also {
                                    val workerCaches = ModifierPluginWorkers.cacheSnapshots(store)
                                    RigBuildProfile.gauge("worker geometry bytes", workerCaches.sumOf { it.geometry.retainedBytes })
                                    RigBuildProfile.gauge("worker geometry entries", workerCaches.sumOf { it.geometry.entries.toLong() })
                                    RigBuildProfile.gauge("canonical raster bytes", it.textures.tileArt.sumOf { art -> art.rgba.size.toLong() })
                                }
                            }
                            report.append(sample)
                            sample.value
                        }
                        assertEquals(results.getValue("expanded"), results.getValue("folded"), "All fields, order, ids and resources: $label/$scenario/$ordinal")
                        assertEquals(results.getValue("expanded"), results.getValue("worker-folded"), "Real worker parity: $label/$scenario/$ordinal")
                        val canonical = results.getValue("expanded").textures
                        if (case.child == "mouth.outline") assertTrue(canonical.tileArt.size > input.textures.tileArt.size,
                            "Outline fixture must produce real raster content")
                        for (result in results.values) {
                            assertEquals(canonical.tileArt.map { it.tile }, result.textures.tileArt.map { it.tile })
                            canonical.tileArt.zip(result.textures.tileArt).forEach { (expected, actual) ->
                                assertEquals(actual.width.toLong() * actual.height * 4, actual.rgba.size.toLong())
                                assertContentEquals(expected.rgba.toArray(), actual.rgba.toArray(), "Canonical RGBA must match for ${actual.tile}")
                            }
                            canonical.pages.zip(result.textures.pages).forEach { (expected, actual) ->
                                assertContentEquals(expected.png.toArray(), actual.png.toArray(), "Packed raster payload must match")
                            }
                        }
                        if (cached) assertEquals(evaluate(expanded, "expanded", null), results.getValue("expanded"), "Cached result must equal cold replay")
                    }
                    compare("cold", 0, .63f, false)
                    memory("after-cold")
                    repeat(5) { compare("warmup", it, .63f, true) }
                    memory("after-warmup")
                    repeat(samples) { compare("warm-identical", it, .63f, true) }
                    memory("after-warm-identical")
                    repeat(samples) { compare("local-subconfig", it, .25f + .5f * (it + 1) / (samples + 1), true) }
                    memory("after-local-subconfig")
                } finally {
                    session.close(); session.awaitReleased(); ModifierPluginWorkers.awaitRetirements(store)
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(temporary)
                }
                memory("after-final-release", released = true)
            }
            report.complete()
        } catch (failure: Throwable) {
            report.failed(failure)
            throw failure
        } finally { RigBuildProfile.recording = wasRecording; report.close() }
    }

    private fun installWorker(artifact: BundledAlgorithmArtifacts.Artifact, root: Path, store: ModifierPluginStore): InstalledModifierPlugin {
        val staging = Files.createDirectory(root.resolve("package"))
        val manifest = Json.parseToJsonElement(artifact.bytes.decodeToString()).jsonObject
        Files.writeString(staging.resolve("plugin.json"), JsonObject(manifest + ("version" to JsonPrimitive("0.9.0"))).toString())
        artifact.manifest.files.keys.forEach { name ->
            val file = staging.resolve(name)
            Files.createDirectories(file.parent)
            assertNotNull(artifact.open(name)).use { Files.copy(it, file) }
        }
        return store.install(staging.toString())
    }
}
