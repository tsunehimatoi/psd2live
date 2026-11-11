package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.Part
import io.github.psd2live.format.model.RigIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModifierPipelineCleanupTest {
    private fun mesh(id: String) = Mesh(id, id, null, geometry = null, offsets = null)
    private val before = RigIR(Canvas(100f, 100f), emptyList(),
        parts = listOf(Part("part", "Part", emptyList())), meshes = listOf(mesh("mesh")), rootPart = "part")

    @Test fun patchReplaysChangedFieldsAndPreservesEachCurrentUpstream() {
        val after = before.copy(parts = listOf(before.parts.single().copy(name = "Generated")), rootPart = null)
        val patch = ModifierInputCache.Patch(before, after)
        for (width in listOf(200f, 300f)) {
            val upstream = before.copy(canvas = Canvas(width, 50f), meshes = listOf(mesh("upstream-$width")),
                parts = listOf(before.parts.single().copy(name = "Upstream")), rootPart = "upstream")
            val result = patch.apply(upstream)
            assertEquals(upstream.copy(parts = after.parts, rootPart = null), result)
            assertSame(upstream.canvas, result.canvas)
            assertSame(upstream.meshes, result.meshes)
            assertSame(after.parts, result.parts)
        }
    }

    @Test fun structurallyEqualDistinctFieldsRemainUnchanged() {
        val equal = before.copy(canvas = before.canvas.copy(),
            parts = before.parts.map { it.copy() }, meshes = before.meshes.map { it.copy() })
        assertEquals(before, equal)
        assertNotSame(before.parts, equal.parts)
        val upstream = before.copy(canvas = Canvas(200f, 200f), parts = listOf(Part("other", "Other", emptyList())),
            meshes = listOf(mesh("other")), rootPart = null)
        val result = ModifierInputCache.Patch(before, equal).apply(upstream)
        assertEquals(upstream, result)
        assertSame(upstream.parts, result.parts)
        assertSame(upstream.meshes, result.meshes)
        assertSame(upstream.canvas, result.canvas)
    }

    @Test fun contributionsPreserveMeshAndMaskOrdering() {
        val old = before.copy(meshes = listOf(mesh("removed").copy(maskedBy = listOf("source")),
            mesh("changed").copy(maskedBy = listOf("old-source")), mesh("inverted")))
        val next = old.copy(meshes = listOf(mesh("added").copy(maskedBy = listOf("source")),
            mesh("inverted").copy(invertMask = true), mesh("changed").copy(maskedBy = listOf("new-source"))))
        val contributions = RigModifierEvaluator.contributions(old, next)
        assertEquals(listOf("mesh:added", "mesh:inverted", "mesh:changed", "mesh:removed",
            "relations:mask:removed", "relations:mask:changed", "relations:mask:inverted", "relations:mask:added"),
            contributions.map { "${it.kind}:${it.id}" })
        assertEquals(listOf(ModifierChange.REMOVED, ModifierChange.MODIFIED, ModifierChange.MODIFIED, ModifierChange.ADDED),
            contributions.filter { it.kind == "relations" }.map { it.change })
        assertEquals(ModifierMaskRelation("inverted", emptyList(), emptyList(), false, true),
            contributions.single { it.id == "mask:inverted" }.mask)
    }

    @Test fun changedMeshListsStillRejectDuplicateIdsOnEitherSide() {
        val duplicate = before.copy(meshes = listOf(mesh("duplicate"), mesh("duplicate")))
        for ((old, next) in listOf(before to duplicate, duplicate to before)) {
            val failure = assertFailsWith<IllegalArgumentException> { RigModifierEvaluator.contributions(old, next) }
            assertEquals("Duplicate mesh IDs in modifier output", failure.message)
        }
    }

    @Test fun unchangedAndEqualMeshListsHaveNoContributions() {
        assertTrue(RigModifierEvaluator.contributions(before, before.copy()).isEmpty())
        assertTrue(RigModifierEvaluator.contributions(before, before.copy(meshes = before.meshes.map { it.copy() })).isEmpty())
    }
}
