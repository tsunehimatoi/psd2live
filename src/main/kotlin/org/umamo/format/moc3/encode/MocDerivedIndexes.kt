package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer

/**
 * Synthesizes the sections that are a pure function of the object lists: per-object form counts, the
 * prefix-sum start columns into the shared static tables, the glue ids, and the constant snap type.
 *
 * Every value here is redundant with data the document already holds, which is why the decoder
 * ignores most of them - but a file that omits or mis-sizes one is not loadable, so a writer has to
 * produce them all.  Each identity below is corpus-verified by `UnmodeledSectionIdentityProbeTest`.
 *
 * Only the sections needing NO intermediate from another producer live here.  The rest - the
 * per-form color refs (137-142), the offscreen-by-part alias (160), the parameter binding/key starts
 * (56, 103) - are emitted beside the producer that already computes their inputs, so the two cannot
 * drift apart.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
public object MocDerivedIndexes {
	/**
	 * Builds every self-contained derived section for [doc], keyed by section-table index.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes.
	 */
	public fun derivedIndexSections(doc: MocDocument): Map<Int, ByteArray> {
		val sink = SectionSink(doc.version)

		/**
		 * The running prefix sum of [counts]: entry i is the total of everything before it.
		 *
		 * @param List counts The per-object run lengths.
		 * @param Int  base   The value of entry 0.
		 * @return List<Int> The start column.
		 */
		fun prefixSum(counts: List<Int>, base: Int = 0): List<Int> {
			var running = base
			return counts.map { count ->
				val start = running
				running += count
				start
			}
		}

		// Per-object keyform counts - the run length each object's keyform base points at.
		sink.putInts(Section.PART_KEYFORM_COUNT, doc.parts.map { it.drawOrderKeyforms.size })
		sink.putInts(Section.WARP_KEYFORM_COUNT, doc.deformers.filterIsInstance<WarpDeformer>().map { it.keyforms.size })
		sink.putInts(
			Section.ROTATION_KEYFORM_COUNT,
			doc.deformers.filterIsInstance<RotationDeformer>().map { it.keyforms.size },
		)

		// Start columns into the shared static tables.  The UV column advances by TWO per vertex
		// because section 78 is a flat float array, not a vertex array.
		sink.putInts(Section.ARTMESH_UV_START, prefixSum(doc.artMeshes.map { it.vertexCount * 2 }))
		sink.putInts(Section.ARTMESH_INDEX_START, prefixSum(doc.artMeshes.map { it.triangleIndices.size }))
		// The offscreen masks occupy the PREFIX of section 80 and the drawable masks follow, so the
		// drawable column starts past them rather than at zero.
		sink.putInts(
			Section.ARTMESH_MASK_START,
			prefixSum(
				doc.artMeshes.map { it.maskDrawableIndices.size },
				base = doc.offscreens.sumOf { it.maskIndices.size },
			),
		)

		sink.putInts(Section.RENDER_ORDER_CHILD_START, prefixSum(doc.renderOrderGroups.map { it.children.size }))

		// The constant 3 on every parameter of every corpus sample, v1 through v6 - whatever it selects,
		// no shipped model ever selects anything else, so there is no model field behind it.
		sink.putInts(Section.PARAM_SNAP_TYPE, List(doc.parameters.size) { SNAP_TYPE })

		sink.putIds(Section.GLUE_ID, doc.glues.map { it.id })
		return sink.toMap()
	}

	/** The only snap type any corpus model uses; see [Section.PARAM_SNAP_TYPE]. */
	private const val SNAP_TYPE: Int = 3
}