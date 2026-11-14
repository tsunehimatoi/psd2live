package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthOutlineAlgorithm
import io.github.psd2live.algorithms.binding.MouthOutlineInputs
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.DeformerId
import kotlin.test.*

class CliFaceSourcePresentationTest {
    @Test fun sourceOutlinePreservesEvaluatedBrowsAndTheirSeeThroughCopies() {
        val f = fixture
        val context = f.owner.workerBindingContext(f.outline)
        assertEquals(JsonPrimitive(MouthOutlineInputs.SCHEMA), context.values["schema"])
        assertFalse("before" in context.resources)
        assertFalse("after" in context.resources)
        assertTrue(MouthOutlineInputs.decode(context).targets.isEmpty(), "This fixture must have no mouth")
        for (type in listOf("eye/fitted", "brow/fitted", "brow.shape")) {
            val captured = f.owner.workerBindingContext(f.stack.instances.single { it.type == type })
            assertFalse("before" in captured.resources, type)
            assertFalse("after" in captured.resources, type)
        }
        val ir = PuppetIr.toIr(f.preview.rig.puppet)
        assertEquals(ir, MouthOutlineAlgorithm().changes(ir, f.outline, context) {}.apply(ir))
        val brows = f.preview.rig.puppet.drawables.filter { it.id.raw in f.brows }
        assertEquals(2, brows.size)
        assertTrue(brows.all { it.geometryGrid != null && it.blendShapes.isNotEmpty() })
        val copies = f.preview.rig.puppet.drawables.filter { it.textureSourceId?.raw in f.brows }
        assertEquals(2, copies.size)
        assertTrue(copies.all { it.geometryGrid != null && it.blendShapes.isNotEmpty() && it.opacity < 1f })
        assertSame(f.preview, CliFaceSourcePresentation.restoreBrowForms(f.preview, f.preview, f.preview.config))
        // Preserve the presentation's current UVs, authored geometry, opacity and draw order too.
        val shown = f.preview.copy(rig = f.preview.rig.copy(puppet = f.preview.rig.puppet.copy(drawables =
            f.preview.rig.puppet.drawables.map { drawable ->
                if (drawable.id.raw !in f.brows && drawable.textureSourceId?.raw !in f.brows) drawable
                else changed(drawable).copy(geometryGrid = drawable.geometryGrid, blendShapes = drawable.blendShapes,
                    opacity = .37f, drawOrder = 381f)
            })))
        assertSame(shown, CliFaceSourcePresentation.restoreBrowForms(f.preview, shown, f.preview.config))
    }

    @Test fun historicalContextRestoresFormsAndCopiesWithoutReplacingPackedUvsOrOrder() {
        val f = fixture
        val desired = PuppetIr.toIr(f.preview.rig.puppet)
        val context = ModifierAlgorithmContext(resources = mapOf(
            "before" to ModifierAlgorithmResource(RigIrBinary.encode(desired)),
            "after" to ModifierAlgorithmResource(RigIrBinary.encode(desired))))
        val shown = f.preview.copy(rig = f.preview.rig.copy(puppet = f.preview.rig.puppet.copy(drawables =
            f.preview.rig.puppet.drawables.map {
                if (it.id.raw in f.brows || it.textureSourceId?.raw in f.brows) changed(it) else it
            })))
        val result = CliFaceSourcePresentation.restoreBrowForms(f.preview, shown, f.preview.config, context)
        for (native in f.preview.rig.puppet.drawables.filter { it.id.raw in f.brows }) {
            val actual = result.rig.puppet.drawables.single { it.id == native.id }
            val packed = shown.rig.puppet.drawables.single { it.id == native.id }
            assertContentEquals(native.mesh!!.positions, actual.mesh!!.positions)
            assertContentEquals(packed.mesh!!.uvs, actual.mesh!!.uvs)
            assertContentEquals(packed.mesh!!.indices, actual.mesh!!.indices)
            assertEquals(PuppetIr.meshToIr(native).offsets, PuppetIr.meshToIr(actual).offsets)
            assertEquals(PuppetIr.meshToIr(native).shapes, PuppetIr.meshToIr(actual).shapes)
            val copy = result.rig.puppet.drawables.single { it.id == SeeThroughLayers.copyId(native.id) }
            val oldCopy = shown.rig.puppet.drawables.single { it.id == copy.id }
            assertContentEquals(actual.mesh!!.positions, copy.mesh!!.positions)
            assertContentEquals(actual.mesh!!.uvs, copy.mesh!!.uvs)
            assertEquals(actual.geometryGrid, copy.geometryGrid)
            assertEquals(oldCopy.drawOrder, copy.drawOrder)
            assertEquals(oldCopy.opacity, copy.opacity)
            assertEquals(actual.blendShapes.size, copy.blendShapes.size)
            for ((sourceShape, copyShape) in actual.blendShapes.zip(copy.blendShapes)) {
                assertEquals(sourceShape.forms.size, copyShape.forms.size)
                for ((sourceForm, copyForm) in sourceShape.forms.zip(copyShape.forms)) {
                    if (sourceForm == null) assertNull(copyForm) else {
                        assertNotNull(copyForm)
                        assertContentEquals(sourceForm.positionDeltas, copyForm.positionDeltas)
                        assertEquals(oldCopy.drawOrder, copyForm.drawOrder)
                        assertEquals(sourceForm.opacity * f.preview.config.seeThroughOpacity, copyForm.opacity)
                    }
                }
            }
        }
        assertEquals(shown.rig.puppet.deformers, result.rig.puppet.deformers)
    }

    @Test fun historicalChangedBrowsAndMismatchedTopologyAreNotRestored() {
        val f = fixture
        val before = PuppetIr.toIr(f.preview.rig.puppet)
        val edited = f.preview.rig.puppet.copy(drawables = f.preview.rig.puppet.drawables.map {
            if (it.id.raw in f.brows) changed(it) else it
        })
        fun context(after: ByteArray) = ModifierAlgorithmContext(resources = mapOf(
            "before" to ModifierAlgorithmResource(RigIrBinary.encode(before)), "after" to ModifierAlgorithmResource(after)))
        assertSame(f.preview, CliFaceSourcePresentation.restoreBrowForms(f.preview, f.preview, f.preview.config,
            context(RigIrBinary.encode(PuppetIr.toIr(edited)))))
        val mismatched = f.preview.copy(rig = f.preview.rig.copy(puppet = edited.copy(drawables = edited.drawables.map {
            if (it.id.raw !in f.brows) it else {
                val mesh = requireNotNull(it.mesh)
                it.copy(mesh = DrawableMesh(mesh.positions, mesh.uvs, mesh.indices.reversedArray()))
            }
        })))
        assertSame(mismatched, CliFaceSourcePresentation.restoreBrowForms(f.preview, mismatched, f.preview.config,
            context(RigIrBinary.encode(before))))
        val reparented = f.preview.copy(rig = f.preview.rig.copy(puppet = edited.copy(drawables = edited.drawables.map {
            if (it.id.raw in f.brows) it.copy(parentDeformerId = DeformerId("authored-parent")) else it
        })))
        assertSame(reparented, CliFaceSourcePresentation.restoreBrowForms(f.preview, reparented, f.preview.config,
            context(RigIrBinary.encode(before))))
    }

    @Test fun unknownAndIncompleteHistoricalContextsStillFail() {
        val f = fixture
        val bytes = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(f.preview.rig.puppet)))
        for (context in listOf(ModifierAlgorithmContext.Empty,
            ModifierAlgorithmContext(buildJsonObject { put("schema", "mouth-outline/unknown") }),
            ModifierAlgorithmContext(resources = mapOf("before" to bytes)),
            ModifierAlgorithmContext(resources = mapOf("after" to bytes)))) {
            assertFailsWith<NoSuchElementException> {
                CliFaceSourcePresentation.restoreBrowForms(f.preview, f.preview, f.preview.config, context)
            }
        }
    }

    private fun changed(drawable: Drawable): Drawable {
        val mesh = requireNotNull(drawable.mesh)
        return drawable.copy(mesh = DrawableMesh(mesh.positions.copyOf().apply { this[0] += .01f },
            mesh.uvs.copyOf().apply { this[0] += .02f }, mesh.indices), geometryGrid = null, blendShapes = emptyList())
    }

    private data class Fixture(val preview: RigPreviewModel, val owner: RigModifierSource,
                               val stack: RigModifierStack, val outline: RigModifierInstance, val brows: Set<String>)

    companion object {
        private val fixture by lazy {
            val eyes = Characters.eyed()
            val brows = Characters.browed()
            val browLayers = brows.source.layers.filter { brows.layerOverrides[it.id.raw]?.tag == SemanticTag.EYEBROW }
            val artwork = object : org.umamo.format.art.SourceArt by eyes.source {
                override val layers = eyes.source.layers + browLayers
            }
            val doc = eyes.copy(source = artwork,
                layerOverrides = eyes.layerOverrides + brows.layerOverrides.filterKeys { id -> browLayers.any { it.id.raw == id } })
            val config = RigModifierPipeline.ordinaryConfig(doc.rawConfig())
            val analysis = CharacterAnalyzer.analyze(doc.source, config)
            val atlas = AtlasLayout.pack(analysis.layers, config)
            val ordinary = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
            val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
            val browIds = ordinary.layerIdByDrawableId.filterValues { layer -> browLayers.any { it.id.raw == layer } }.keys
            val scaffold = buildJsonObject { put("scaffold", "structure") }
            var stack = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
                .add("eye/fitted", 1, targets, scaffold, id = "eyes")
                .add("brow/fitted", 1, targets, scaffold, id = "brows")
                .add("brow.shape", 1, targets, buildJsonObject { put("scaffold", "structure"); put("base", "brows") }, id = "shape")
                .add("brow.see-through", 1, targets, scaffold, id = "copies")
            stack = stack.copy(instances = stack.instances.map { instance ->
                if (instance.id != "copies") instance else instance.copy(outputAliases = browIds.associate {
                    "see-through:$it" to SeeThroughLayers.copyId(org.umamo.runtime.model.DrawableId(it)).raw
                })
            })
            val complete = stack.add("mouth.outline", 1, targets, scaffold, id = "outline")
            val cfg = config.copy(rigEdits = config.rigEdits.copy(modifierStack = complete.toJson()))
            val owner = RigModifierSource(analysis, atlas, cfg, ordinary.layerIdByDrawableId, materialBase = ordinary.puppet)
            val cache = RigModifierEvaluationCache()
            val evaluated = RigModifierAlgorithms.evaluator(owner, stack).evaluate(PuppetIr.toIr(ordinary.puppet), stack, cache)
            assertTrue(evaluated.entries.all { it.status == ModifierStatus.ACTIVE })
            val model = PuppetIr.toPuppet(evaluated.rig)
            val preview = RigPreviewModel(analysis, atlas, ordinary.copy(puppet = model), cfg,
                CubismRuntimeBundle("test.model3.json", listOf(CubismRuntimeAsset("test.model3.json", "{}".encodeToByteArray()))))
            val outline = complete.instances.last()
            preview.modifierEvaluation = requireNotNull(cache.snapshot).copy(stack = complete,
                entries = evaluated.entries + ModifierEvaluation(outline, ModifierStatus.ACTIVE))
            Fixture(preview, owner, complete, outline, browIds)
        }
    }
}
