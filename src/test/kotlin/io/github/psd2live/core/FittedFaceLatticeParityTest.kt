package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.FittedFaceLattices
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.ModelIdRemap
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.ModifierPluginWorkers
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.legacyFaceTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.Part
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittedFaceLatticeParityTest {
    @Test fun transportedLatticesMatchTheExistingStyledBindingBeforeLowering() {
        val document = Characters.eyed()
        val depth = RigModules.FACE_DEPTH_TURN
        val config = document.rawConfig().copy(generateDeformers = true, mouthOutlineEnabled = false,
            recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted")),
                modules = RigModules.modules.associate {
                    it.id to ModuleChoice(it == depth, values = if (it == depth) mapOf("style" to 4f) else emptyMap())
                }))
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(analysis, config, cache)
        val parents = context.deformers.mapNotNullTo(linkedSetOf()) { it.parent }
        val source = assertNotNull(RigFittedFaceSource.capture(context, config, parents))
        assertNotNull(source.source.styledTurn())
        // The legacy pre-style skeleton is used here to isolate the fitted replacement from nine-pose generation.
        var captured: (() -> PuppetModel)? = null
        RigBuilder.build(analysis, AtlasLayout.pack(analysis.layers, config), config, cache) { captured = it }
        val native = assertNotNull(captured).invoke()
        val deformers = context.deformers.map(PuppetIr::deformerToIr)
        val parts = deformers.mapNotNull { it.part }.distinct().map { Part(it, it, emptyList()) }
        val face = Mesh("face", "Face", "DeformFaceContour", geometry = null, offsets = null)
        val nativeParameters = PuppetIr.parametersToIr(native)
        val nativeIds = nativeParameters.mapTo(hashSetOf()) { it.id }
        // The pre-lowering fixture retains structural axes omitted from the finished character.
        val structuralAxes = deformers.flatMap { deformer ->
            when (deformer) {
                is Deformer.Warp -> deformer.lattice?.axes.orEmpty()
                is Deformer.Rotation -> deformer.pivot?.axes.orEmpty()
            } + deformer.channels.values.flatMap { it.axes }
        }.map { it.parameter }.distinct().filterNot { it in nativeIds }
        val parameters = nativeParameters + structuralAxes.map { Parameter(it, it, -45f, 45f, 0f) }
        val before = RigIR(Canvas(420f, 420f), parameters,
            parts = parts, deformers = deformers, meshes = listOf(face))
        val changes = FittedFaceLattices.changes(before, FittedFaceLattices.Inputs.decode(source.encode()))
        assertTrue(changes.deformers.updated.any { it.before.id == "DeformFaceContour" })
        val result = changes.apply(before)
        System.getProperty("psd2live.fittedFaceLatticesPackage")?.let {
            assertEquals(result, packagedResult(it, before, source.encode()))
        }
        val candidate = PuppetIr.toPuppet(result)
        val afterLean = RigBuilder.withFaceLean(candidate.deformers, config.rigTuning).map(PuppetIr::deformerToIr)
        val expected = native.deformers.map(PuppetIr::deformerToIr).associateBy { it.id }
        val changedIds = changes.deformers.updated.mapTo(hashSetOf()) { it.after.id }
        for (deformer in afterLean.filter { it.id in changedIds }) assertEquals(expected[deformer.id], deformer, deformer.id)
    }

    @Test fun packagedBindingTurnsHairWhileRetainingAuthoredMeshKeysAndScaffoldAliases() {
        val directory = System.getProperty("psd2live.fittedFaceLatticesPackage") ?: return
        val document = Characters.eyed()
        val module = RigModules.FACE_DEPTH_TURN
        val config = document.rawConfig().copy(generateDeformers = true, generatePhysics = false, mouthOutlineEnabled = false,
            recipe = RigRecipe.Legacy)
        val turnedConfig = config.copy(recipe = RigRecipe(true,
            bases = mapOf("eye" to BaseChoice("eye/fitted")),
            modules = RigModules.modules.associate { it.id to ModuleChoice(it == module) }))
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val scaffoldSource = RigBuilder.scaffoldSourceInputs(analysis, config, cache)
        val context = RigBuilder.rigContext(analysis, config, cache)
        val static = RigBuilder.buildNeutralScaffold(analysis, atlas, config, cache)
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val targetsByLayer = static.puppet.drawables.associate { drawable ->
            drawable.id.raw to layers.getValue(static.layerIdByDrawableId.getValue(drawable.id.raw))
        }.filterValues { it.semantic.tag in setOf(SemanticTag.FACE, SemanticTag.BACK_HAIR) }
        val material = static.puppet.drawables.filter { it.id.raw in targetsByLayer }
        val hair = material.first { targetsByLayer.getValue(it.id.raw).semantic.tag == SemanticTag.BACK_HAIR }
        val custom = ParameterId("ParamAuthoredHair")
        val keys = listOf(0f, 1f)
        val mesh = assertNotNull(hair.mesh)
        val authored = hair.copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(custom, keys.toFloatArray())),
            keys.mapIndexed { index, key ->
                KeyformCell(intArrayOf(index), MeshDeltaForm(FloatArray(mesh.positions.size) { if (it % 2 == 0) key * 0.02f else 0f }))
            }))
        val neutral = static.puppet.copy(
            drawables = material.map { if (it.id == hair.id) authored else it },
            parameters = listOf(org.umamo.runtime.model.Parameter(custom, "Authored hair", 0f, 1f, 0f)),
            parameterTree = emptyList(), parameterLinks = emptyList(),
            parts = static.puppet.parts.map { it.copy(children = emptyList()) },
            rootChildren = emptyList(), renderRoot = org.umamo.runtime.model.RenderGroup(null, 0, emptyList()),
        )
        val scaffoldFrames = ScaffoldFramesContext.decode(scaffoldSource.frames).head
        val face = context.faceRig
        val angles = StandardParameters.all.filter {
            it.id in setOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z)
        }
        val base = LegacyFaceInputs.capture(ModifierAlgorithmContext(buildJsonObject {
            put("schema", JsonPrimitive("scaffold/5"))
            put("frames", scaffoldSource.frames)
        }), face.centerY, face.radiusX, face.radiusY, face.eyeLineY, config.rigTuning.legacyFaceTuning(),
            config.headTurnStrength, angles.map { it.name }, tr("model.group.face"))
        assertEquals(scaffoldFrames.headFrame, context.head)
        val parents = material.associate { it.id.raw to it.parentDeformerId }
        val frames = (static.puppet.deformers.mapNotNull { it.parent } + parents.values.filterNotNull())
            .distinct().mapNotNull { id -> context.resolveFrame(id, turnedConfig)?.let { id to it } }.toMap()
        val turn = RigDepthTurnSource.capture(context, turnedConfig, targetsByLayer, parents, frames).encode()
        val targets = targetsByLayer.keys.map { ModifierTarget("mesh", it) }
        val scaffold = RigModifierInstance("fixture-scaffold", "rig.scaffold", 2, targets,
            outputAliases = mapOf("shared/deformer:DeformFaceContour" to "existing-contour"))
        val remap = ModelIdRemap(deformers = neutral.deformers.associate {
            it.id to DeformerId(ScaffoldBinding.deformerId(scaffold, targets.first().id, it.id.raw))
        })
        val input = PuppetIr.toIr(remap.model(neutral))
        val source = ModifierAlgorithmContext(buildJsonObject {
            put("base", base.values)
            put("turn", turn.values)
        }, base.resources + turn.resources)
        val output = packagedResult(directory, input, source, entrypoint = "binding", scaffold = scaffold,
            parameters = PuppetIr.parametersToIr(neutral.copy(parameters = angles)))
        assertEquals(input.deformers.map { it.id }, output.deformers.take(input.deformers.size).map { it.id })
        val contour = output.deformers.single { it.id == "existing-contour" } as Deformer.Warp
        assertEquals(16, contour.columns)
        assertEquals(angles.map { it.id.raw }, output.parameters.drop(input.parameters.size).map { it.id })
        val actualHair = output.meshes.single { it.id == hair.id.raw }
        val inputHair = input.meshes.single { it.id == hair.id.raw }
        assertEquals(inputHair.geometry, actualHair.geometry)
        val offsets = assertNotNull(actualHair.offsets)
        assertTrue(offsets.axes.any { it.parameter == StandardParameters.ANGLE_X.raw })
        val ownAxis = offsets.axes.indexOfFirst { it.parameter == custom.raw }
        assertTrue(ownAxis >= 0)
        val rest = offsets.cells.first { it.coordinate.shared()[ownAxis] == 0 }
        val authoredKey = offsets.cells.single { cell ->
            cell.coordinate.shared().indices.all { i -> cell.coordinate.shared()[i] == if (i == ownAxis) 1 else rest.coordinate.shared()[i] }
        }
        rest.form.deltas.shared().forEachIndexed { i, value ->
            assertEquals(if (i % 2 == 0) 0.02f else 0f, authoredKey.form.deltas.shared()[i] - value, absoluteTolerance = 1e-6f)
        }
    }

    private fun packagedResult(
        directory: String, input: RigIR, context: ModifierAlgorithmContext, entrypoint: String = "styled",
        scaffold: RigModifierInstance = RigModifierInstance("scaffold", "rig.scaffold", 2, listOf(ModifierTarget("mesh", "face"))),
        parameters: List<Parameter> = emptyList(),
    ): RigIR {
        val root = Files.createTempDirectory("fitted-face-lattices-")
        val preferences = Preferences.userRoot().node("psd2live-test/fitted-face-lattices/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            val installed = store.install(directory)
            try {
                val algorithm = installed.manifest.algorithms.single { it.type.endsWith("/$entrypoint") }
                val instance = RigModifierInstance("styled-face", algorithm.type, algorithm.version, scaffold.targets, plugin = installed.reference)
                val provided = context.withUpstream(listOf(
                    ModifierUpstreamCapability("rig.scaffold", scaffold.type, scaffold.version, scaffold.targets, scaffold.id,
                        scaffold.config, scaffold.outputAliases),
                ))
                return ModifierPluginWorkers.evaluate(installed, algorithm, input, instance, {}, store, context = provided,
                    declaredParameters = parameters)
            } finally {
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
