package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit-tests [firstEditableDrawableInPanelOrder]: Edit mode seeds onto the topmost drawable in Parts-panel
 * order (top = front) that both carries a mesh and is shown, so a fresh document never opens Edit on
 * nothing.  Corpus-free - hand-built trees exercise panel order, the mesh-less skip, the visibility
 * cascade, the empty-tree flat fallback, and the nothing-editable null.
 */
class FirstEditableDrawableTest {
	private fun mesh(): DrawableMesh = DrawableMesh(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), intArrayOf())

	private fun drawable(id: String, hasMesh: Boolean = true, isVisible: Boolean = true): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = if (hasMesh) mesh() else null,
			geometryGrid = null,
			isVisible = isVisible,
		)

	@Test
	fun picksFrontOfPanelOrder() {
		val drawables = listOf(drawable("front"), drawable("back"))
		// Panel order top = front: "front" precedes "back" at the root.
		val rootChildren = listOf(OrgChild.Drawable(DrawableId("front")), OrgChild.Drawable(DrawableId("back")))
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), drawables, rootChildren, rootPartId = null)

		assertEquals(DrawableId("front"), model.firstEditableDrawableInPanelOrder())
	}

	@Test
	fun skipsMeshlessAndHiddenDrawables() {
		val hiddenPart = Part(PartId("hidden"), "[Guide Image]", listOf(OrgChild.Drawable(DrawableId("inHidden"))), isVisible = false)
		val drawables =
			listOf(
				drawable("noMesh", hasMesh = false), // no mesh → skipped
				drawable("inHidden", hasMesh = true), // ancestor part hidden → skipped
				drawable("ownHidden", hasMesh = true, isVisible = false), // own eyeball off → skipped
				drawable("editable", hasMesh = true), // first shown + meshed → the pick
			)
		val rootChildren =
			listOf(
				OrgChild.Drawable(DrawableId("noMesh")),
				OrgChild.Part(PartId("hidden")),
				OrgChild.Drawable(DrawableId("ownHidden")),
				OrgChild.Drawable(DrawableId("editable")),
			)
		val model = PuppetModel(emptyList(), listOf(hiddenPart), emptyList(), drawables, rootChildren, rootPartId = null)

		assertEquals(DrawableId("editable"), model.firstEditableDrawableInPanelOrder())
	}

	@Test
	fun returnsNullWhenNothingEditable() {
		val drawables = listOf(drawable("a", hasMesh = false), drawable("b", hasMesh = true, isVisible = false))
		val rootChildren = listOf(OrgChild.Drawable(DrawableId("a")), OrgChild.Drawable(DrawableId("b")))
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), drawables, rootChildren, rootPartId = null)

		assertNull(model.firstEditableDrawableInPanelOrder())
	}

	@Test
	fun fallsBackToFlatListWhenTreeIsEmpty() {
		// A degenerate model with no org tree still finds its meshed, visible drawable.
		val drawables = listOf(drawable("orphan", hasMesh = true))
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), drawables, rootChildren = emptyList(), rootPartId = null)

		assertEquals(DrawableId("orphan"), model.firstEditableDrawableInPanelOrder())
	}
}