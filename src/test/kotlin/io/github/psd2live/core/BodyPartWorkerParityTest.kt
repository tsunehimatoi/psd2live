package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class BodyPartWorkerParityTest {
    @TempDir lateinit var temp: Path

    @Test fun bodyAndLegacyWorkerMatchInProcessAcrossEditedInputsAndReinstall() {
        val document = Characters.mouthed()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig())
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val ordinary = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
        val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val expanded = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add("body/legacy", 1, targets, buildJsonObject {
                put("scaffold", "structure"); put("strength", .75f)
                putJsonObject("tuning") { put("faceLeanUp", 11f); put("breathLift", 4.25f) }
            }, id = "body")
        val folded = expanded.copy(instances = RigPartFolding.foldAll(expanded.instances, freshInstanceIds = setOf("body")))
        assertEquals("body", folded.instances.last().type)
        fun source(stack: RigModifierStack) = RigModifierSource(analysis, atlas,
            config.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson())), ordinary.layerIdByDrawableId, materialBase = ordinary.puppet)
        val expandedSource = source(expanded)
        val foldedSource = source(folded)
        val scaffold = expanded.instances.first()
        val input = ScaffoldAlgorithm().evaluate(PuppetIr.toIr(ordinary.puppet), scaffold, expandedSource.scaffoldContext(scaffold)) {}
        val painted = input.meshes.first { it.geometry != null }
        val geometry = requireNotNull(painted.geometry)
        val positions = geometry.positions.shared().copyOf().apply { this[0] += .000001f }
        val edited = input.copy(meshes = input.meshes.map {
            if (it.id == painted.id) it.copy(geometry = geometry.copy(positions = Floats.wrap(positions)), opacity = .6f) else it
        })
        val upstream = listOf(ModifierUpstreamCapability("rig.scaffold", scaffold.type, scaffold.version, targets, scaffold.id))
        val legacy = expanded.instances.last()
        val part = folded.instances.last()
        val legacyContext = expandedSource.bodyContext(legacy).withUpstream(upstream)
        val partContext = foldedSource.bodyPartContext(part).withUpstream(upstream)
        val preferences = Preferences.userRoot().node("body-part-worker/${UUID.randomUUID()}")
        val store = ModifierPluginStore(temp.resolve("installed"), preferences)
        try {
            for ((instance, context) in listOf(legacy to legacyContext, part to partContext)) {
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 1))
                val staging = Files.createDirectory(temp.resolve(instance.type.replace('/', '-')))
                val manifest = Json.parseToJsonElement(artifact.bytes.decodeToString()).jsonObject
                Files.writeString(staging.resolve("plugin.json"), JsonObject(manifest + ("version" to JsonPrimitive("0.9.0"))).toString())
                artifact.manifest.files.keys.forEach { name -> assertNotNull(artifact.open(name)).use { Files.copy(it, staging.resolve(name)) } }
                repeat(2) {
                    val installed = store.install(staging.toString())
                    try {
                        for (candidate in listOf(input, edited)) {
                            val expected = LegacyBodyAlgorithm().changes(candidate, legacy, legacyContext) {}.apply(candidate)
                            val puppet = PuppetIr.toPuppet(expected)
                            val carried = BindingFrames.carriedToParent(puppet, puppet,
                                org.umamo.runtime.model.DrawableId(painted.id))
                            assertEquals(expected, PuppetIr.toIr(carried), "Identical frame transport preserves every float bit")
                            val direct = BodyPartAlgorithm().changes(candidate, part, partContext) {}.apply(candidate)
                            assertEquals(expected, direct, "expanded versus part")
                            val actual = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), candidate,
                                instance.copy(type = installed.manifest.algorithms.single().type, plugin = installed.reference), {}, store, context = context,
                                declaredParameters = if (instance.type == "body") foldedSource.partParameters(part)
                                    else RigLegacyBindings.definition(RigLegacyBindings.spec("body/legacy"), expandedSource).parameters(legacy))
                            assertEquals(expected, actual, "${instance.type} worker, install $it")
                            if (candidate === edited) {
                                val output = actual.meshes.single { it.id == painted.id }
                                assertEquals(.6f, output.opacity)
                                assertEquals(candidate.meshes.single { it.id == painted.id }.geometry, output.geometry,
                                    "Body's neutral frame preserves a sub-millipixel authored edit")
                            }
                        }
                    } finally { ModifierPluginWorkers.stop(installed.reference); store.uninstall(installed.reference) }
                }
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
        }
    }
}
