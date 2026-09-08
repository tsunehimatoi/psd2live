package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the part-as-mask extension: a part used as a clip mask expands to its descendant drawables (through
 * nested parts) in the DERIVED render tree only, while the authored composite keeps the part reference for
 * the UI.  Also pins the dedup when a drawable is masked both directly and through an ancestor part.
 */
class PartMaskResolveTest {
	private val maskLeafId = DrawableId("maskLeaf")
	private val maskNestedId = DrawableId("maskNested")
	private val subjectId = DrawableId("subject")
	private val maskPartId = PartId("maskPart")
	private val nestedPartId = PartId("nestedPart")
	private val subjectPartId = PartId("subjectPart")

	private fun drawable(id: DrawableId): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)

	/** A mask part holding one drawable plus a nested part holding another, beside an isolated subject part. */
	private fun model(composite: PartComposite): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					Part(maskPartId, "maskPart", children = listOf(OrgChild.Drawable(maskLeafId), OrgChild.Part(nestedPartId))),
					Part(nestedPartId, "nestedPart", children = listOf(OrgChild.Drawable(maskNestedId))),
					Part(
						subjectPartId,
						"subjectPart",
						children = listOf(OrgChild.Drawable(subjectId)),
						groupMode = PartGroupMode.Isolated,
						composite = composite,
					),
				),
			deformers = emptyList(),
			drawables = listOf(drawable(maskLeafId), drawable(maskNestedId), drawable(subjectId)),
			rootChildren = listOf(OrgChild.Part(maskPartId), OrgChild.Part(subjectPartId)),
			rootPartId = null,
		)

	/** The composite the render tree carries for the subject part. */
	private fun renderedComposite(model: PuppetModel): PartComposite? {
		val root = model.deriveRenderRoot()

		fun find(node: RenderNode): RenderGroup? =
			when (node) {
				is RenderDrawable -> null
				is RenderGroup ->
					if (node.partId == subjectPartId) {
						node
					} else {
						node.children.firstNotNullOfOrNull { child -> find(child) }
					}
			}
		return find(root)?.composite
	}

	@Test
	fun parentPartLookupCoversNestedPartsOnly() {
		// Part membership lives only in the tree, so the Properties panel needs this to show a nested part's
		// owner; a top-level part has no entry and reads as unbound.
		val parents = model(PartComposite()).parentPartByPart()
		assertEquals(maskPartId, parents[nestedPartId])
		assertNull(parents[maskPartId], "a top-level part has no owning part")
		assertNull(parents[subjectPartId])
	}

	@Test
	fun subtreeQueryWalksNestedParts() {
		val subtrees = model(PartComposite()).drawablesByPartSubtree()
		assertEquals(listOf(maskLeafId, maskNestedId), subtrees[maskPartId], "nested part contributes its drawables")
		assertEquals(listOf(maskNestedId), subtrees[nestedPartId])
		assertEquals(listOf(subjectId), subtrees[subjectPartId])
	}

	@Test
	fun aMaskPartExpandsToItsDescendantDrawablesInTheRenderTree() {
		val authored = PartComposite(maskedByParts = listOf(maskPartId))
		val model = model(authored)

		assertEquals(listOf(maskLeafId, maskNestedId), renderedComposite(model)?.maskedBy, "expanded for the renderer")
		// The authored value is untouched, so the panel still shows the part, not its expansion.
		val storedComposite = model.parts.first { it.id == subjectPartId }.composite
		assertEquals(listOf(maskPartId), storedComposite.maskedByParts)
		assertTrue(storedComposite.maskedBy.isEmpty())
	}

	@Test
	fun theRenderCompositeCarriesNoPartIdsAtAll() {
		// PartComposite documents that the render tree sees the EXPANSION and never the part ids.  Leaving
		// them beside the expansion would let any consumer that later honours them clip by the same drawables
		// twice, so the resolved copy must clear the field, not merely add to maskedBy.
		val authored = PartComposite(maskedByParts = listOf(maskPartId))
		val rendered = renderedComposite(model(authored))

		assertTrue(rendered?.maskedByParts?.isEmpty() == true, "the renderer must not receive part ids")
		assertEquals(listOf(maskLeafId, maskNestedId), rendered.maskedBy)
	}

	@Test
	fun directAndPartReachedMasksMergeWithoutDuplicates() {
		// maskLeaf is masked directly AND sits under the masking part: it must appear once.
		val authored = PartComposite(maskedBy = listOf(maskLeafId), maskedByParts = listOf(maskPartId))
		assertEquals(listOf(maskLeafId, maskNestedId), renderedComposite(model(authored))?.maskedBy)
	}

	@Test
	fun flatteningForExportPreservesExactlyWhatTheRendererClipsWith() {
		// The guarantee behind withPartMasksFlattened: an export to a drawable-only mask format (CMO3) keeps
		// the clipping the viewport shows.  Only the "masked by this part" grouping is lost, which those
		// formats cannot record - so the conversion is explicit, never a silent drop.
		val authored = PartComposite(maskedBy = listOf(maskLeafId), maskedByParts = listOf(maskPartId))
		val model = model(authored)
		val rendered = renderedComposite(model)?.maskedBy

		val exported = model.withPartMasksFlattened()
		val exportedComposite = exported.parts.first { it.id == subjectPartId }.composite

		assertEquals(rendered, exportedComposite.maskedBy, "export clips exactly as the viewport does")
		assertTrue(exportedComposite.maskedByParts.isEmpty(), "the part reference is expanded, not carried")
		// Flattening is idempotent, and the render tree of a flattened model is unchanged.
		assertEquals(rendered, renderedComposite(exported)?.maskedBy)
		assertSame(exported, exported.withPartMasksFlattened())
	}

	@Test
	fun flatteningIsAnIdentityForAModelWithNoPartMasks() {
		// The common case pays nothing and an exporter can call it unconditionally.
		val model = model(PartComposite(maskedBy = listOf(maskLeafId)))
		assertSame(model, model.withPartMasksFlattened())
	}

	@Test
	fun withoutPartMasksTheCompositeInstanceIsPassedThroughUntouched() {
		// The common case pays nothing: no part masks means the authored composite reaches the render tree
		// as the very same instance.
		val authored = PartComposite(maskedBy = listOf(maskLeafId))
		val model = model(authored)
		assertSame(model.parts.first { it.id == subjectPartId }.composite, renderedComposite(model))
	}
}