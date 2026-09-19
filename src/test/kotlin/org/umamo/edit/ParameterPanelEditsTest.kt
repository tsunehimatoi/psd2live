package org.umamo.edit

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParameterPanelEditsTest {
	private fun sampleModel(): PuppetModel {
		val a = Parameter(ParameterId("ParamA"), "A", -1f, 1f, 0f)
		val b = Parameter(ParameterId("ParamB"), "B", -1f, 1f, 0f)
		val c = Parameter(ParameterId("ParamC"), "C", -1f, 1f, 0f)
		return PuppetModel(
			parameters = listOf(a, b, c),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = listOf(
				ParameterNode.Group(
					ParameterGroupId("ParamGroupFace"),
					"Face",
					true,
					listOf(ParameterNode.Param(a.id), ParameterNode.Param(b.id), ParameterNode.Param(c.id)),
				),
			),
		)
	}

	@Test
	fun linkMakesPairAdjacentAndRecordsCombinedLink() {
		val linked = sampleModel().withParameterLink(ParameterId("ParamA"), ParameterId("ParamC"), true)
		assertEquals(listOf(ParameterLink(ParameterId("ParamA"), ParameterId("ParamC"))), linked.parameterLinks)
		assertEquals(
			listOf(ParameterId("ParamA"), ParameterId("ParamC"), ParameterId("ParamB")),
			linked.parameters.map { it.id },
		)
		val children = assertIs<ParameterNode.Group>(linked.parameterTree.single()).children
		assertEquals(ParameterId("ParamA"), assertIs<ParameterNode.Param>(children[0]).id)
		assertEquals(ParameterId("ParamC"), assertIs<ParameterNode.Param>(children[1]).id)
	}

	@Test
	fun createRenameAndDeleteFolder() {
		val created = sampleModel().withParameterGroupCreated(ParameterGroupId("ParamGroupExtra"), "Extra")
		assertTrue(created.parameterTree.any { it is ParameterNode.Group && it.id.raw == "ParamGroupExtra" })
		val renamed = created.withParameterGroupRenamed(ParameterGroupId("ParamGroupExtra"), "Custom")
		assertEquals("Custom", assertIs<ParameterNode.Group>(renamed.parameterTree.last()).name)
		val deleted = renamed.withParameterGroupDeleted(ParameterGroupId("ParamGroupFace"))
		assertEquals(4, deleted.parameterTree.size) // Extra folder + A,B,C promoted
		assertTrue(deleted.parameterTree.any { it is ParameterNode.Param && it.id.raw == "ParamA" })
	}

	@Test
	fun moveParameterIntoNewFolder() {
		val withFolder = sampleModel().withParameterGroupCreated(ParameterGroupId("ParamGroupExtra"), "Extra")
		val moved = withFolder.withParameterPanelNodeMoved(
			ParameterPanelRef.Param(ParameterId("ParamB")),
			ParameterGroupId("ParamGroupExtra"),
			before = null,
		)
		val extra = assertIs<ParameterNode.Group>(moved.parameterTree.last())
		assertEquals(listOf(ParameterId("ParamB")), extra.children.map { assertIs<ParameterNode.Param>(it).id })
	}
}
