package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class RigModifierOrganizationTest {
    private val targets = listOf(ModifierTarget("mesh", "mesh"))

    @Test fun defaultFoldersFollowPurposeIncludingSimulationMaterial() {
        val stack = RigModifierStack().add("face/fitted", 1, targets, id = "face")
            .add("simulation", 1, targets, buildJsonObject { put("kind", "hair") }, id = "hair")
            .add("simulation", 1, targets, buildJsonObject { put("kind", "cloth") }, id = "cloth")
        val nodes = RigModifierOrganization.nodes(stack)
        RigModifierOrganization.validate(stack, nodes)
        assertEquals("folder/face", nodes.single { it.id == "face" }.parent)
        assertEquals("folder/hair", nodes.single { it.id == "hair" }.parent)
        assertEquals("folder/clothing", nodes.single { it.id == "cloth" }.parent)
    }

    @Test fun foldersReorderAndRoundTripWithoutChangingEvaluationOrder() {
        val stack = RigModifierStack().add("motion", 1, targets, id = "first").add("motion", 1, targets, id = "second")
        val nodes = RigModifierOrganization.nodes(stack) + ModifierPanelNode("folder/custom", name = "Custom")
        val moved = RigModifierOrganization.move(nodes, "second", "folder/custom", null)
        RigModifierOrganization.validate(stack, moved)
        val restored = RigModifierStack.fromJson(stack.copy(organization = moved).toJson())
        assertEquals(listOf("first", "second"), restored.instances.map { it.id })
        assertEquals(moved, restored.organization)
        assertEquals("folder/custom", RigModifierOrganization.nodes(restored).single { it.id == "second" }.parent)
    }

    @Test fun deletedAndNewModifiersDoNotLeaveStaleRows() {
        val stack = RigModifierStack().add("motion", 1, targets, id = "first")
        val organized = stack.copy(organization = RigModifierOrganization.nodes(stack))
        val changed = organized.remove("first").add("body/base", 1, targets, id = "new")
        val nodes = RigModifierOrganization.nodes(changed)
        RigModifierOrganization.validate(changed, nodes)
        assertFalse(nodes.any { it.id == "first" })
        assertEquals("folder/body", nodes.single { it.id == "new" }.parent)
    }

    @Test fun cyclesAndMissingInstancesAreRejected() {
        val stack = RigModifierStack().add("motion", 1, targets, id = "item")
        assertFailsWith<IllegalArgumentException> { RigModifierOrganization.validate(stack, emptyList()) }
        val cycle = listOf(ModifierPanelNode("folder/a", "folder/b", "A"), ModifierPanelNode("folder/b", "folder/a", "B"), ModifierPanelNode("item"))
        assertFailsWith<IllegalArgumentException> { RigModifierOrganization.validate(stack, cycle) }
    }
}
