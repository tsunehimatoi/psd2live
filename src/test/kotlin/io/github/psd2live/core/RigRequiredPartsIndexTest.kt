package io.github.psd2live.core

import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierSource
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import org.umamo.runtime.model.*
import kotlin.test.*

class RigRequiredPartsIndexTest {
    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size get() = values.size
        override fun get(index: Int): T = values[index].also { reads++ }
    }

    private fun source(): RigModifierSource {
        val document = Characters.head(physics = false)
        val config = document.rawConfig()
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        return RigModifierSource(analysis, atlas, config, emptyMap(),
            materialBase = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null))
    }

    private fun part(id: String, vararg children: OrgChild) = Part(PartId(id), id, children.toList())
    private fun child(id: String) = OrgChild.Part(PartId(id))
    private fun meshChild(id: String) = OrgChild.Drawable(DrawableId(id))
    private fun deformer(id: String, parent: String? = null, part: String? = null) =
        Deformer.Rotation(DeformerId(id), id, parent?.let(::DeformerId), part?.let(::PartId), 0f, null)
    private fun mesh(id: String, parent: String? = null) =
        Drawable(DrawableId(id), id, parent?.let(::DeformerId), BlendMode.Normal, emptyList(), null, null)
    private fun model(parts: List<Part>, deformers: List<Deformer>, meshes: List<Drawable>) =
        PuppetModel(emptyList(), parts, deformers, meshes, emptyList(), null)
    private fun required(source: RigModifierSource, model: PuppetModel, target: String = "left") =
        source.requiredParts(model, ModifierTarget("mesh", target)).map { it.raw }

    private fun parts() = listOf(
        part("root", child("left-part"), child("right-part"), child("outer-part")),
        part("left-part", meshChild("left")),
        part("right-part", meshChild("right")),
        part("outer-part", child("inner-part")),
        part("inner-part"),
    )
    private fun deformers(): List<Deformer> = listOf(
        deformer("inner", "outer", "inner-part"), deformer("outer", part = "outer-part"))
    private fun meshes() = listOf(mesh("left", "inner"), mesh("right", "outer"))

    @Test fun repeatedTargetsReuseIndexesAndPreserveAncestorEncounterOrder() {
        val source = source()
        val parts = CountedList(parts())
        val deformers = CountedList(deformers())
        val model = model(parts, deformers, meshes())
        val left = listOf("left-part", "root", "inner-part", "outer-part")
        val right = listOf("right-part", "root", "outer-part")
        assertEquals(left, required(source, model))
        assertEquals(deformers.size, deformers.reads)
        assertEquals(parts.size * 2, parts.reads, "Build the parent index, then find the target's own part")
        val initialPartReads = parts.reads
        repeat(3) {
            assertEquals(right, required(source, model, "right"))
            assertEquals(left, required(source, model))
        }
        assertEquals(deformers.size, deformers.reads, "Do not rebuild the deformer index per target")
        assertEquals(initialPartReads + 6 * parts.size, parts.reads,
            "Only the original target-part lookup should scan parts on subsequent calls")
    }

    @Test fun equalModelCopiesAndChangedTopologyGetTheirOwnIndexes() {
        val source = source()
        val deformers = CountedList(deformers())
        val original = model(parts(), deformers, meshes())
        val equalCopy = original.copy()
        assertNotSame(original, equalCopy)
        assertEquals(original, equalCopy)
        val expected = required(source, original)
        val initialReads = deformers.reads
        assertEquals(expected, required(source, equalCopy))
        assertEquals(initialReads + deformers.size, deformers.reads,
            "A distinct model must not reuse another model's index through structural equality")
        val changed = original.copy(parts = original.parts.map {
            if (it.id == PartId("root")) it.copy(id = PartId("new-root")) else it
        })
        assertEquals(listOf("left-part", "new-root", "inner-part", "outer-part"), required(source, changed))
        assertEquals(expected, required(source, original))
    }

    @Test fun separateSourceCapturesDoNotShareIndexes() {
        val first = source()
        val second = source()
        val deformers = CountedList(deformers())
        val model = model(parts(), deformers, meshes())
        val expected = required(first, model)
        val initialReads = deformers.reads
        assertEquals(expected, required(second, model))
        assertEquals(initialReads + deformers.size, deformers.reads)
        val capturedReads = deformers.reads
        repeat(2) {
            assertEquals(expected, required(first, model))
            assertEquals(expected, required(second, model))
        }
        assertEquals(capturedReads, deformers.reads)
    }

    @Test fun ancestorCyclesAreStillRejectedAfterTheIndexesAreCached() {
        val source = source()
        val deformerCycle = model(emptyList(), listOf(deformer("a", "b"), deformer("b", "a")),
            listOf(mesh("left", "a")))
        val partCycle = model(listOf(part("a", child("b"), meshChild("left")), part("b", child("a"))),
            emptyList(), listOf(mesh("left")))
        repeat(2) {
            assertEquals("Cyclic binding branch", assertFailsWith<IllegalArgumentException> {
                required(source, deformerCycle)
            }.message)
            assertEquals("Cyclic binding parts", assertFailsWith<IllegalArgumentException> {
                required(source, partCycle)
            }.message)
        }
    }

    @Test fun missingDeformerParentsStillFailAndUnlistedPartSeedsKeepTheirAncestors() {
        val source = source()
        val missingDeformer = model(emptyList(), listOf(deformer("inner", "missing")),
            listOf(mesh("left", "inner")))
        val unlistedPart = model(listOf(part("root", child("unlisted"))),
            listOf(deformer("inner", part = "unlisted")), listOf(mesh("left", "inner")))
        repeat(2) {
            assertContains(assertFailsWith<NoSuchElementException> { required(source, missingDeformer) }.message.orEmpty(), "missing")
            assertEquals(listOf("unlisted", "root"), required(source, unlistedPart))
        }
    }

    @Test fun duplicateIndexKeysAndAmbiguousDrawableOwnersKeepExistingSemantics() {
        val source = source()
        val repeatedParents = model(listOf(part("first", child("leaf")), part("last", child("leaf")),
            part("leaf", meshChild("left"))),
            listOf(deformer("inner", part = "first-carrier"), deformer("inner", part = "last-carrier")),
            listOf(mesh("left", "inner")))
        val ambiguousOwner = model(listOf(part("first", meshChild("left")), part("last", meshChild("left"))),
            emptyList(), listOf(mesh("left")))
        repeat(2) {
            assertEquals(listOf("leaf", "last", "last-carrier"), required(source, repeatedParents),
                "Both parent and deformer indexes retain the last value for a duplicate key")
            assertEquals(emptyList(), required(source, ambiguousOwner),
                "Multiple drawable owners do not become an arbitrary first or last owner")
        }
    }
}
