package example

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class ModifierModelChangesTest {
    private val a = Parameter("a", "A", -1f, 1f, 0f)
    private val b = Parameter("b", "B", -1f, 1f, 0f)
    private val c = Parameter("c", "C", -1f, 1f, 0f)
    private val input = RigIR(Canvas(64f, 64f), listOf(a, b), rootPart = "root")

    @Test fun explicitEditsPreserveOrderAndUnchangedSections() {
        val replacement = b.copy(name = "Updated")
        val changes = ModifierModelChanges(parameters = ModifierObjectChanges(
            added = listOf(c), updated = listOf(ModifierObjectUpdate(b, replacement)), reused = listOf(a)),
            rootPart = ModifierFieldChange("root", null))
        val output = changes.apply(input)
        assertEquals(listOf(a, replacement, c), output.parameters)
        assertSame(a, output.parameters.first())
        assertSame(input.meshes, output.meshes)
        assertSame(input.textures, output.textures)
        assertSame(input.physics, output.physics)
        assertNull(output.rootPart)
        assertEquals(listOf(a, b), input.parameters)
        assertEquals(setOf(ModifierChangeAction.ADD, ModifierChangeAction.UPDATE, ModifierChangeAction.REUSE),
            changes.contributions().map { it.action }.toSet())
    }

    @Test fun staleMissingDuplicateAndIdentityChangingEditsAreRejected() {
        val invalid = listOf(
            ModifierObjectChanges(added = listOf(a)),
            ModifierObjectChanges(removed = listOf(c)),
            ModifierObjectChanges(updated = listOf(ModifierObjectUpdate(a.copy(name = "Stale"), a))),
            ModifierObjectChanges(updated = listOf(ModifierObjectUpdate(a, c))),
            ModifierObjectChanges(removed = listOf(a), reused = listOf(a)),
            ModifierObjectChanges(added = listOf(c, c)),
            ModifierObjectChanges(reused = listOf(a.copy(max = 2f))),
        )
        invalid.forEach { changes -> assertFailsWith<IllegalArgumentException> { changes.apply(input.parameters, { it.id }) } }
        assertEquals(listOf(a, b), input.parameters)
    }

    @Test fun laterFieldFailureAndCancellationLeaveInputUntouched() {
        val changes = ModifierModelChanges(parameters = ModifierObjectChanges(removed = listOf(a)),
            restPose = ModifierFieldChange(mapOf("a" to 1f), emptyMap()))
        assertFailsWith<IllegalArgumentException> { changes.apply(input) }
        var visits = 0
        assertFailsWith<CancellationException> {
            changes.copy(restPose = null).apply(input) { if (++visits == 4) throw CancellationException() }
        }
        assertEquals(listOf(a, b), input.parameters)
        assertTrue(input.restPose.isEmpty())
    }

    @Test fun emptyAndReuseOnlyEditsDoNotCopyCollections() {
        assertSame(input.parameters, ModifierObjectChanges<Parameter>().apply(input.parameters, { error("Untouched") }))
        assertSame(input.parameters, ModifierObjectChanges(reused = listOf(a)).apply(input.parameters, { it.id }))
        val output = ModifierModelChanges(parameters = ModifierObjectChanges(removed = listOf(a))).apply(input)
        assertEquals(listOf(b), output.parameters)
    }
    @Test fun reuseIsReadOnlyButUndeclaredObjectAndFieldWritesFail() {
        ModifierModelChanges(parameters = ModifierObjectChanges(reused = listOf(a))).requireWrites(emptySet())
        val changes = ModifierModelChanges(parameters = ModifierObjectChanges(added = listOf(c)),
            rootPart = ModifierFieldChange("root", null))
        assertEquals(setOf("parameter", "hierarchy"), changes.writeKinds())
        assertFailsWith<IllegalArgumentException> { changes.requireWrites(setOf("parameter")) }
        changes.requireWrites(setOf("parameter", "hierarchy"))
    }
}
