package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.LegacyBodyAlgorithm
import io.github.psd2live.algorithms.binding.LegacyBodyInputs
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import org.umamo.runtime.model.Deformer
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyBodyPluginParityTest {
    @Test fun sourceCandidateMatchesLegacyBindingWithHeadPitchAtMixedStrength() {
        val document = Characters.mouthed()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(recipe = RigRecipe.Legacy)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val ordinary = RigBuilder.build(analysis, atlas, config)
        val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val stack = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add("body/legacy", 1, targets, buildJsonObject {
                put("scaffold", "structure")
                put("strength", .75f)
                putJsonObject("tuning") { put("faceLeanUp", 11f); put("breathLift", 4.25f) }
            }, id = "body")
        val (scaffold, body) = stack.instances
        val capture = RigModifierSource(analysis, atlas, config.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson())),
            ordinary.layerIdByDrawableId, materialBase = ordinary.puppet)
        val input = ScaffoldAlgorithm().evaluate(PuppetIr.toIr(ordinary.puppet), scaffold, capture.scaffoldContext(scaffold)) {}
        // Test oracle only: capture the legacy head curves before applying body lean.
        val native = RigBuilder.rigContext(analysis, config.copy(meshOnly = false, generateDeformers = true))
        val poses = native.deformers.filterIsInstance<Deformer.Warp>().filter { warp ->
            warp.geometryGrid?.axes?.any { it.parameterId == StandardParameters.ANGLE_Y } == true
        }.map(PuppetIr::deformerToIr)
        val pitch = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(0f, 0f), emptyList(), deformers = poses)))
        val source = capture.bodyContext(body)
        val author = ModifierAlgorithmContext(source.values, source.resources + (LegacyBodyInputs.HEAD_POSES to pitch)).withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", scaffold.type, scaffold.version, scaffold.targets, scaffold.id)))
        val algorithmInProcess = LegacyBodyAlgorithm()
        fun compare(candidate: RigIR): RigIR {
            val expected = RigSourceBinding.apply(candidate, body, capture) {}
            val actual = algorithmInProcess.evaluate(candidate, body, author) {}
            // Object changes preserve the input parameter array order. The panel tree carries the authored order.
            assertEquals(expected.parameters.associateBy { it.id }, actual.parameters.associateBy { it.id })
            assertEquals(expected.parameterTree, actual.parameterTree)
            assertEquals(expected.parameterLinks, actual.parameterLinks)
            assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id })
            expected.deformers.zip(actual.deformers).forEach { (a, b) -> assertEquals(a, b, a.id) }
            assertEquals(expected.meshes.map { it.id }, actual.meshes.map { it.id })
            expected.meshes.zip(actual.meshes).forEach { (a, b) -> assertEquals(a, b, a.id) }
            return actual
        }
        val actual = compare(input)
        val painted = input.meshes.single { ordinary.layerIdByDrawableId[it.id] == "top" }
        val mesh = requireNotNull(painted.geometry)
        val moved = mesh.positions.shared().copyOf().apply { this[0] += 0.01f }
        val edited = input.copy(meshes = input.meshes.map {
            if (it.id == painted.id) it.copy(geometry = mesh.copy(positions = Floats.wrap(moved)), opacity = .6f) else it
        })
        assertEquals(.6f, compare(edited).meshes.single { it.id == painted.id }.opacity)
        System.getenv("PSD2LIVE_BODY_PACKAGE")?.let { directory ->
            val root = Files.createTempDirectory("legacy-body-package-")
            val preferences = Preferences.userRoot().node("psd2live/body-test/${UUID.randomUUID()}")
            val store = ModifierPluginStore(root, preferences)
            try {
                val pkg = store.install(Path.of(directory).toString())
                val algorithm = pkg.manifest.algorithms.single()
                val declared = RigLegacyBindings.definition(RigLegacyBindings.spec("body/legacy"), capture).parameters(body)
                repeat(2) {
                    val installed = store.snapshot().single()
                    val transported = ModifierPluginWorkers.evaluate(installed, algorithm, input,
                        body.copy(type = algorithm.type, plugin = installed.reference), {}, store,
                        context = author, declaredParameters = declared)
                    assertEquals(actual, transported)
                    store.uninstall(installed.reference)
                    if (it == 0) assertEquals(pkg.reference, store.install(directory).reference)
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                ModifierPluginStore.deleteTree(root)
                preferences.removeNode()
            }
        }
    }
}
