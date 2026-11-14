package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class PartStackParityTest {
    @TempDir lateinit var temp: Path
    @Test fun fittedEyeWideKeepsAuthoredValuesAndChildIdentity() = compare(Characters.eyed(), "eye/fitted", "eye.wide")
    @Test fun mouthOutlineKeepsColorRasterPathsAndOwners() = compare(Characters.mouthed(), "mouth/fitted", "mouth.outline")
    @Test fun browShapeKeepsAuthoredValuesAndChildIdentity() = compare(Characters.browed(), "brow/fitted", "brow.shape")

    private fun compare(document: WorkspaceDocument, baseType: String, childType: String) {
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig())
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val ordinary = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
        val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val subConfig = buildJsonObject {
            put("scaffold", "structure")
            if (RigLegacyBindings.spec(childType).module?.base != null) put("base", "base")
            putJsonObject("values") {
                RigLegacyBindings.spec(childType).knobs.firstOrNull()?.let { knob ->
                    put(knob.id, knob.range.start + (knob.range.endInclusive - knob.range.start) * .63f)
                }
            }
            if (childType == "mouth.outline") put("color", 0x563421)
        }
        val expanded = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add(baseType, 1, targets, buildJsonObject { put("scaffold", "structure") }, id = "base")
            .add(childType, 1, targets, subConfig, id = "authored-child")
        val folded = expanded.copy(instances = RigPartFolding.foldAll(expanded.instances,
            freshInstanceIds = expanded.instances.mapTo(hashSetOf()) { it.id }))
        assertEquals(2, folded.instances.size, "The fixture must actually fold")
        fun evaluate(stack: RigModifierStack): io.github.psd2live.format.model.RigIR {
            val source = RigModifierSource(analysis, atlas, config.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson())),
                ordinary.layerIdByDrawableId, materialBase = ordinary.puppet)
            return RigModifierAlgorithms.evaluator(source, stack).evaluate(PuppetIr.toIr(ordinary.puppet), stack).rig
        }
        val expected = evaluate(expanded)
        val actual = evaluate(folded)
        assertEquals(expected, actual)
        assertEquals(actual, evaluate(RigModifierStack.fromJson(Json.parseToJsonElement(folded.toJson().toString()).jsonObject)))
        val source = RigModifierSource(analysis, atlas, config.copy(rigEdits = RigEditOverlay(modifierStack = folded.toJson())),
            ordinary.layerIdByDrawableId, materialBase = ordinary.puppet)
        val part = folded.instances.last()
        for (declared in source.partParameters(part)) assertEquals(declared, actual.parameters.single { it.id == declared.id })
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(part.type, 1))
        val staging = Files.createDirectory(temp.resolve("package"))
        val manifest = Json.parseToJsonElement(artifact.bytes.decodeToString()).jsonObject
        Files.writeString(staging.resolve("plugin.json"), JsonObject(manifest + ("version" to JsonPrimitive("0.9.0"))).toString())
        artifact.manifest.files.keys.forEach { name -> assertNotNull(artifact.open(name)).use { Files.copy(it, staging.resolve(name)) } }
        val preferences = Preferences.userRoot().node("part-worker/${UUID.randomUUID()}")
        val store = ModifierPluginStore(temp.resolve("installed"), preferences)
        try {
            val installed = store.install(staging.toString())
            val definitions = BuiltinModifierPlugins.definitions(source)
            val worker = ModifierPluginCatalog.definitions(store, captured = definitions, resolveRasters = source::resolveRasters)
                .single { it.pluginReference == installed.reference && it.type != part.type }
            val registry = RigModifierRegistry(definitions.map { if (it.type == part.type) worker.copy(type = part.type, pluginReference = null) else it })
            val result = RigModifierEvaluator(registry, resolveRasters = source::resolveRasters)
                .evaluate(PuppetIr.toIr(ordinary.puppet), folded.copy(instances = folded.instances.map { it.copy(plugin = null) })).rig
            assertEquals(actual, result, "Real part worker must retain child ownership and resources")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
        }
    }
}
