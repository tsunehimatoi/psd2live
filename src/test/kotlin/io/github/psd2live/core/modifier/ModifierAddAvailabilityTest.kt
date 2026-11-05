package io.github.psd2live.core.modifier

import kotlin.test.*

class ModifierAddAvailabilityTest {
    private val left = ModifierTarget("mesh", "left")
    private val right = ModifierTarget("mesh", "right")

    @Test fun existingSourceBindingsAreHiddenOnlyAtOverlappingTargets() {
        val stack = RigModifierStack().add("eye/fitted", 1, listOf(left))
        assertTrue(ModifierAddCatalog.availability(stack, listOf(left), "eye/fitted").hidden)
        assertTrue(ModifierAddCatalog.availability(stack, listOf(left), "eye/legacy").hidden)
        assertFalse(ModifierAddCatalog.availability(stack, listOf(right), "eye/fitted").hidden)
        assertEquals(ModifierAddAvailability(false, 1), ModifierAddCatalog.availability(stack, listOf(left, right), "eye/fitted"))
    }

    @Test fun disabledInstancesRemainDiscoverableAsExistingWithoutBlockingAnotherAlgorithm() {
        val added = RigModifierStack().add("eye/fitted", 1, listOf(left))
        val stack = added.update(added.instances.single().id) { it.copy(enabled = false) }
        assertEquals(ModifierAddAvailability(true, 1), ModifierAddCatalog.availability(stack, listOf(left), "eye/fitted"))
        assertFalse(ModifierAddCatalog.availability(stack, listOf(left), "eye/legacy").hidden)
    }

    @Test fun composableNativeEntriesStayAvailableWithAnExistingCount() {
        val stack = RigModifierStack().add("swing", 1, listOf(left)).add("swing", 1, listOf(right))
        assertEquals(ModifierAddAvailability(false, 1), ModifierAddCatalog.availability(stack, listOf(left), "swing"))
        assertEquals(ModifierAddAvailability(false, 2), ModifierAddCatalog.availability(stack, listOf(left, right), "swing"))
    }

    @Test fun incompatibleRequiredBaseIsHiddenButCompatibleProviderIsAccepted() {
        val legacy = RigModifierStack().add("eye/legacy", 1, listOf(left))
        assertTrue(ModifierAddCatalog.availability(legacy, listOf(left), "eye.smile").hidden)
        val modern = RigModifierStack().add("eye/fitted", 1, listOf(left))
        assertFalse(ModifierAddCatalog.availability(modern, listOf(left), "eye.smile").hidden)
    }

    @Test fun appliedBindingsAlsoPreventAddingTheSameSourceAgain() {
        val stack = RigModifierStack(appliedCapabilities = listOf(ModifierCapability("eye/fitted", 1, listOf(left), "applied")))
        assertTrue(ModifierAddCatalog.availability(stack, listOf(left), "eye/fitted").hidden)
        assertFalse(ModifierAddCatalog.availability(stack, listOf(right), "eye/fitted").hidden)
    }
}
