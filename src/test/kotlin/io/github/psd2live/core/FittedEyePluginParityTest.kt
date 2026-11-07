package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.EyeFittedAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.VertexGroup
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittedEyePluginParityTest {
    @Test fun fittedBlinkHandoverIrisAndGazeMatchTheNativeBase() {
        compare(Characters.eyed())
    }

    @Test fun missingEyeWhitesRetainTheLegacyBlink() {
        val document = Characters.eyed()
        val config = document.rawConfig()
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        compare(document, analysis.copy(layers = analysis.layers.filter { it.semantic.tag != SemanticTag.EYEWHITE }))
    }

    private fun compare(document: io.github.psd2live.project.WorkspaceDocument, source: PipelineAnalysis? = null) {
        val neutralConfig = document.rawConfig().copy(generateDeformers = true, mouthOutlineEnabled = false, recipe = RigRecipe.Legacy)
        val oldConfig = neutralConfig.copy(recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted", 2)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) }))
        val analysis = source ?: CharacterAnalyzer.analyze(document.source, neutralConfig)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, neutralConfig)
        val scaffold = RigBuilder.buildNeutralScaffold(analysis, atlas, neutralConfig, cache)
        val native = RigBuilder.build(analysis, atlas, oldConfig, cache).puppet
        val expected = PuppetIr.toIr(native)
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val eyeIds = scaffold.layerIdByDrawableId.filterValues { layers[it]?.semantic?.tag in
            setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.IRIDES, SemanticTag.EYE_CLOSE) }.keys
        assertTrue(eyeIds.isNotEmpty())
        val targets = scaffold.layerIdByDrawableId.keys.map { ModifierTarget("mesh", it) }
        val provider = RigModifierInstance("structure", "rig.scaffold", 2, targets,
            outputAliases = scaffold.puppet.deformers.associate { "shared/deformer:${it.id.raw}" to it.id.raw })
        val instance = RigModifierInstance("fitted", "eye/fitted", EyeFittedAlgorithm.VERSION, targets,
            config = buildJsonObject { put("scaffold", provider.id) })
        val primary = scaffold.layerIdByDrawableId.entries.associate { it.value to it.key }
        val sourceMeasurements = RigBuilder.scaffoldSourceInputs(analysis, oldConfig, cache)
        val organization = ScaffoldPartsContext.capture(analysis.layers.mapNotNull { layer ->
            val id = primary[layer.source.id.raw] ?: return@mapNotNull null
            ScaffoldParts.Material(org.umamo.runtime.model.DrawableId(id), RigBuilder.scaffoldSlot(layer, analysis.anchors), layer.source.order)
        }, label = { it })
        val captured = ScaffoldAlgorithm.captureSource(scaffold.puppet, targets.associate { it.id to it.id },
            organization, sourceMeasurements.frames, sourceMeasurements.branches, RigBuilder.scaffoldMeshInputs(analysis, atlas, oldConfig, cache, primary))
        val context = RigFittedEyeSource.capture(instance, provider, analysis, neutralConfig, captured, requireNotNull(sourceMeasurements.eyes),
            scaffold.layerIdByDrawableId, primary)
        val sourceScaffold = ScaffoldAlgorithm().buildSource(captured)
        val staticNative = BindingProjection.retain(native, emptySet())
        val sourceDeformers = sourceScaffold.deformers.associateBy { it.id }
        val nativeDeformers = staticNative.deformers.associateBy { it.id }
        val eyeParents = staticNative.drawables.filter { it.id.raw in eyeIds }.flatMap { drawable ->
            generateSequence(drawable.parentDeformerId) { nativeDeformers.getValue(it).parent }.toList()
        }.toSet()
        assertTrue(eyeParents.isNotEmpty())
        fun deformerFrames(model: Map<DeformerId, Deformer>) =
            eyeParents.map { PuppetIr.deformerToIr(model.getValue(it)) }
        assertEquals(deformerFrames(nativeDeformers), deformerFrames(sourceDeformers))
        if (source == null) {
            val staticLegacy = BindingProjection.retain(scaffold.puppet, emptySet())
            assertNotEquals(deformerFrames(nativeDeformers), deformerFrames(staticLegacy.deformers.associateBy { it.id }))
        }
        val sourceMeshes = sourceScaffold.drawables.associateBy { it.id }
        val scaffoldIr = PuppetIr.toIr(BindingProjection.retain(scaffold.puppet.copy(
            deformers = sourceScaffold.deformers,
            drawables = scaffold.puppet.drawables.map { drawable ->
                val sourceMesh = sourceMeshes.getValue(drawable.id)
                drawable.copy(parentDeformerId = sourceMesh.parentDeformerId, mesh = sourceMesh.mesh, geometryGrid = sourceMesh.geometryGrid)
            }), emptySet()))
        val before = scaffoldIr.copy(parameters = emptyList(), parameterLinks = emptyList(), parameterTree = emptyList(),
            meshes = scaffoldIr.meshes.map { if (it.id in eyeIds) it.copy(channels = emptyMap(), maskedBy = emptyList(),
                opacity = layers.getValue(scaffold.layerIdByDrawableId.getValue(it.id)).source.opacity) else it })
        val changes = EyeFittedAlgorithm().changes(before, instance, context) {}
        val actual = changes.apply(before)
        val eyeNames = RigFittedEyeSource.parameterNames().keys
        assertEquals(expected.parameters.filter { it.id in eyeNames }, actual.parameters)
        assertEquals(expected.parameterLinks.filter { it.horizontal in eyeNames }, actual.parameterLinks)
        val expectedEyes = expected.meshes.filter { it.id in eyeIds }
        val actualEyes = actual.meshes.filter { it.id in eyeIds }
        assertEquals(expectedEyes.map { it.id }, actualEyes.map { it.id })
        for ((mesh, actualMesh) in expectedEyes.zip(actualEyes)) assertEquals(mesh, actualMesh, mesh.id)
        assertEquals(expected.deformers.filter { it.id.startsWith("DeformEyeGaze") },
            actual.deformers.filter { it.id.startsWith("DeformEyeGaze") })
        assertTrue(changes.meshes.updated.all { it.before.id in eyeIds && it.before.geometry == it.after.geometry })
        if (source == null) {
            val (lash, indexedLash) = before.meshes.filter { layers[scaffold.layerIdByDrawableId[it.id]]?.semantic?.tag == SemanticTag.EYELASH }
            val geometry = assertNotNull(lash.geometry)
            val editedUvs = geometry.uvs.shared().copyOf().also { it[0] += 0.0001f }
            val authored = geometry.copy(uvs = Floats.wrap(editedUvs))
            val indexed = assertNotNull(indexedLash.geometry)
            val group = VertexGroup("Pins", indexedLash.id, "pin", Floats.wrap(FloatArray(indexed.positions.size / 2) { 1f }))
            val authoredInput = before.copy(
                meshes = before.meshes.map { if (it.id == lash.id) it.copy(geometry = authored) else it },
                authoring = before.authoring.copy(vertexGroups = listOf(group)),
            )
            val rebound = EyeFittedAlgorithm().changes(authoredInput, instance, context) {}.apply(authoredInput)
            val reboundLash = rebound.meshes.single { it.id == lash.id }
            assertEquals(authored, reboundLash.geometry)
            assertTrue(reboundLash.offsets?.axes.orEmpty().isNotEmpty())
            assertEquals(indexed, rebound.meshes.single { it.id == indexedLash.id }.geometry)
            assertEquals(listOf(group), rebound.authoring.vertexGroups)
        }
    }

}
