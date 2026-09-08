package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit-tests the per-drawable service queries in PuppetModelQueries: the pickable sets exclude
 * hidden and mesh-less drawables, the part-name lookup follows the org tree, and the atlas key
 * resolves a duplicate to its texture source.
 */
class PuppetModelQueriesTest {
	private val meshedId = DrawableId("meshed")
	private val hiddenId = DrawableId("hidden")
	private val meshlessId = DrawableId("meshless")
	private val duplicateId = DrawableId("duplicate")

	/**
	 * A quad mesh so pickable queries have geometry to report.
	 *
	 * @return DrawableMesh The mesh.
	 */
	private fun quadMesh(): DrawableMesh =
		DrawableMesh(
			positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f),
			uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
			indices = intArrayOf(0, 1, 2, 1, 3, 2),
		)

	/**
	 * A minimal drawable for query tests.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param DrawableMesh? mesh Its mesh, or null for a geometry-less drawable.
	 * @param Boolean isVisible Its own eyeball flag.
	 * @param DrawableId? textureSource Its texture source when it is a duplicate.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, mesh: DrawableMesh?, isVisible: Boolean = true, textureSource: DrawableId? = null): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = mesh,
			geometryGrid = null,
			isVisible = isVisible,
			textureSourceId = textureSource,
		)

	/**
	 * A model with one part ("Face") owning the meshed drawable, plus hidden / mesh-less / duplicate
	 * drawables at the root.
	 *
	 * @return PuppetModel The fixture model.
	 */
	private fun model(): PuppetModel {
		val facePart = Part(PartId("face"), "Face", children = listOf(OrgChild.Drawable(meshedId)))
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(facePart),
			deformers = emptyList(),
			drawables =
				listOf(
					drawable(meshedId, quadMesh()),
					drawable(hiddenId, quadMesh(), isVisible = false),
					drawable(meshlessId, mesh = null),
					drawable(duplicateId, quadMesh(), textureSource = meshedId),
				),
			rootChildren =
				listOf(
					OrgChild.Part(PartId("face")),
					OrgChild.Drawable(hiddenId),
					OrgChild.Drawable(meshlessId),
					OrgChild.Drawable(duplicateId),
				),
			rootPartId = null,
		)
	}

	@Test
	fun pickableSetsExcludeHiddenAndMeshless() {
		val model = model()
		assertEquals(setOf(meshedId, duplicateId), model.pickableIndicesByDrawable().keys)
		assertEquals(setOf(meshedId, duplicateId), model.pickableUvsByDrawable().keys)
	}

	@Test
	fun pickableGeometryMatchesTheMesh() {
		val model = model()
		val mesh = quadMesh()
		assertEquals(mesh.indices.toList(), model.pickableIndicesByDrawable().getValue(meshedId).toList())
		assertEquals(mesh.uvs.toList(), model.pickableUvsByDrawable().getValue(meshedId).toList())
	}

	@Test
	fun partNamesFollowTheOrgTree() {
		val names = model().partNameByDrawable()
		assertEquals("Face", names[meshedId])
		// Root-level drawables have no owning part, so no label entry.
		assertNull(names[hiddenId])
	}

	@Test
	fun atlasKeyResolvesDuplicateToItsSource() {
		val keys = model().atlasKeyByDrawable()
		assertEquals(meshedId.raw, keys[duplicateId])
		assertEquals(meshedId.raw, keys[meshedId])
	}
}