package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [visibleDrawableIds]: the Parts-panel eyeball cascades down the org tree, so a drawable is
 * shown only when its own flag and every ancestor part are visible. Corpus-free - a hand-built tree
 * exercises own-hidden, direct-parent-hidden, ancestor-hidden, and root-level (no part) cases.
 */
class VisibilityCascadeTest {
	private fun drawable(id: String, isVisible: Boolean): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			isVisible = isVisible,
		)

	@Test
	fun cascadesEyeballThroughAncestorParts() {
		val parts =
			listOf(
				Part(PartId("root"), "Root", listOf(OrgChild.Part(PartId("hidden")), OrgChild.Part(PartId("shown"))), isVisible = true),
				Part(
					PartId("hidden"),
					"[Guide Image]",
					listOf(OrgChild.Part(PartId("grandchild")), OrgChild.Drawable(DrawableId("inHidden"))),
					isVisible = false,
				),
				Part(PartId("grandchild"), "Under Hidden", listOf(OrgChild.Drawable(DrawableId("underHidden"))), isVisible = true),
				Part(
					PartId("shown"),
					"Visible Folder",
					listOf(OrgChild.Drawable(DrawableId("inShown")), OrgChild.Drawable(DrawableId("ownHidden"))),
					isVisible = true,
				),
			)
		val drawables =
			listOf(
				drawable("inHidden", isVisible = true), // parent hidden → excluded
				drawable("underHidden", isVisible = true), // grandparent hidden → excluded
				drawable("inShown", isVisible = true), // all visible → shown
				drawable("ownHidden", isVisible = false), // own eyeball off → excluded
				drawable("rootLevel", isVisible = true), // no part → shown
			)
		// Top level: the "root" folder plus a loose root-level mesh.
		val rootChildren = listOf(OrgChild.Part(PartId("root")), OrgChild.Drawable(DrawableId("rootLevel")))
		val model = PuppetModel(emptyList(), parts, emptyList(), drawables, rootChildren, rootPartId = PartId("root"))

		val shown = model.visibleDrawableIds()

		assertEquals(setOf(DrawableId("inShown"), DrawableId("rootLevel")), shown)
	}
}