package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import kotlin.test.*

class MouthSourceTopologyTest {
    @Test fun fittedSourceUpgradesOrdinaryTopologyAndPreservesAuthoredVertices() {
        val document = Characters.mouthed()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig())
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val ordinary = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
        val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val expanded = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add("mouth/fitted", 1, targets, buildJsonObject { put("scaffold", "structure") }, id = "mouth")
        val folded = expanded.copy(instances = RigPartFolding.foldAll(expanded.instances, freshInstanceIds = setOf("mouth")))
        fun source(stack: RigModifierStack) = RigModifierSource(analysis, atlas,
            config.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson())), ordinary.layerIdByDrawableId, materialBase = ordinary.puppet)
        val source = source(expanded)
        val scaffold = expanded.instances.first()
        val input = ScaffoldAlgorithm().evaluate(PuppetIr.toIr(ordinary.puppet), scaffold, source.scaffoldContext(scaffold)) {}
        val upstream = listOf(ModifierUpstreamCapability("rig.scaffold", scaffold.type, scaffold.version, targets, scaffold.id,
            scaffold.config, outputAliases = scaffold.outputAliases))
        val base = expanded.instances.last()
        val part = folded.instances.last()
        val context = source.mouthFittedContext(base).withUpstream(upstream)
        val historical = source.mouthFittedContext(base.copy(plugin = requireNotNull(base.plugin).copy(digest = "0".repeat(64))))
        assertFalse(MouthFittedSourceAlgorithm.COARSE_CONTEXT in historical.values)
        assertFalse(ModifierParameterI18n.CONTEXT_KEY in historical.values)
        val partContext = source(folded).mouthPartContext(part).withUpstream(upstream)
        val mouthLayer = analysis.layers.first { it.semantic.tag == SemanticTag.MOUTH }.source.id.raw
        val mouthId = ordinary.layerIdByDrawableId.entries.first { it.value == mouthLayer }.key
        val mesh = input.meshes.single { it.id == mouthId }
        val geometry = requireNotNull(mesh.geometry)
        val editedGeometry = geometry.copy(positions = Floats.wrap(geometry.positions.shared().copyOf().apply { this[0] += .01f }))
        val edited = input.copy(meshes = input.meshes.map { if (it.id == mouthId) it.copy(geometry = editedGeometry, opacity = .6f) else it })
        fun geometryCase(next: MeshGeometry) = input.copy(meshes = input.meshes.map { if (it.id == mouthId) it.copy(geometry = next) else it })
        val changedUv = geometryCase(geometry.copy(uvs = Floats.wrap(geometry.uvs.shared().copyOf().apply { this[0] += .01f })))
        val changedIndices = geometryCase(geometry.copy(indices = Ints.wrap(geometry.indices.shared().copyOf().apply {
            val first = this[0]; this[0] = this[1]; this[1] = first
        })))
        val micro = geometryCase(geometry.copy(positions = Floats.wrap(geometry.positions.shared().copyOf().apply { this[0] += .000001f })))
        val withBody = context.withUpstream(upstream + ModifierUpstreamCapability("body/legacy", "body/legacy", 1, targets, "body",
            buildJsonObject { put("scaffold", "structure") }))
        val microResult = MouthFittedSourceAlgorithm().changes(micro, base, withBody) {}.apply(micro)
        assertEquals(micro.meshes.single { it.id == mouthId }.geometry, microResult.meshes.single { it.id == mouthId }.geometry,
            "A body provider cannot turn a sub-millipixel authored edit into a presumed rounding error")
        val count = geometry.positions.size / 2
        val emptyFloats = Floats.wrap(floatArrayOf())
        val emptyInts = Ints.wrap(intArrayOf())
        val zeros = Floats.wrap(FloatArray(count))
        val simulation = SimulationIR("mouth-sim", 60f, 1, 0f, 0f, 0f, 0f, 0f,
            listOf(SimTarget(mouthId, count)), SimParticles(Floats.wrap(FloatArray(count) { 1f }), zeros, zeros, zeros, zeros,
                zeros, zeros, List(count) { "" }, Ints.wrap(IntArray(count) { -1 })),
            SimStretch(emptyInts, emptyInts, emptyFloats, emptyFloats, emptyFloats),
            SimTriangles(emptyInts, emptyInts, emptyInts, emptyFloats), SimBends(emptyInts, emptyInts, emptyFloats),
            SimWelds(emptyInts, emptyInts, emptyFloats, emptyFloats, emptyFloats), SimLongRange(emptyInts, emptyInts, emptyFloats),
            emptyList(), emptyList(), emptyList())
        val references = listOf(
            input.copy(glues = listOf(Glue("mouth-glue", mouthId, input.meshes.first { it.id != mouthId }.id, listOf(GluePair(0, 0, .5f, .5f))))),
            input.copy(authoring = input.authoring.copy(paths = listOf(DeformPath("mouth-path", mouthId,
                listOf(PathPoint(geometry.indices[0], geometry.indices[1], geometry.indices[2], 1f, 0f, 0f),
                    PathPoint(geometry.indices[0], geometry.indices[1], geometry.indices[2], 0f, 1f, 0f)), 1f, 1f, false, 2)))),
            input.copy(authoring = input.authoring.copy(vertexGroups = listOf(VertexGroup("mouth-pin", mouthId, "pin", zeros)))),
            input.copy(advanced = input.advanced.copy(colliders = listOf(ColliderIR("mouth-collider", false, mesh = mouthId, radiusA = 1f)))),
            input.copy(advanced = input.advanced.copy(simulations = listOf(simulation))),
        )
        for (candidate in listOf(input, edited, changedUv, changedIndices) + references) {
            val expected = MouthFittedSourceAlgorithm().changes(candidate, base, context) {}.apply(candidate)
            val actual = MouthPartAlgorithm().changes(candidate, part, partContext) {}.apply(candidate)
            assertEquals(expected, actual)
            val result = actual.meshes.single { it.id == mouthId }
            if (candidate === input) assertNotEquals(geometry.positions.size, result.geometry!!.positions.size,
                "Untouched ordinary topology must migrate to the fitted source topology")
            else {
                assertEquals(candidate.meshes.single { it.id == mouthId }.geometry, result.geometry)
                assertEquals(candidate.meshes.single { it.id == mouthId }.opacity, result.opacity)
            }
            assertEquals(candidate.glues, actual.glues)
            assertEquals(candidate.authoring, actual.authoring)
            assertEquals(candidate.advanced, actual.advanced)
        }
        // Existing authored order is never silently repaired or folded across a frame writer.
        val interleaved = expanded.add("body/legacy", 1, targets, buildJsonObject { put("scaffold", "structure") }, id = "body")
            .add("mouth.vowel", 1, targets, buildJsonObject { put("scaffold", "structure"); put("base", "mouth") }, id = "vowel")
        val saved = interleaved.toJson()
        assertTrue(RigPartFolding.foldMouth(interleaved.instances, interleaved.instances.mapTo(hashSetOf()) { it.id }).isEmpty())
        val evaluator = RigModifierAlgorithms.evaluator(source(interleaved), interleaved)
        val cache = RigModifierEvaluationCache()
        val material = PuppetIr.toIr(ordinary.puppet)
        evaluator.evaluate(material, interleaved.copy(instances = interleaved.instances.dropLast(1)), cache)
        val prior = cache.snapshot
        val failure = assertFailsWith<IllegalArgumentException> { evaluator.evaluate(material, interleaved, cache) }
        assertContains(requireNotNull(failure.message), "fitted source mesh")
        assertSame(prior, cache.snapshot)
        assertEquals(saved, interleaved.toJson())
    }
}
