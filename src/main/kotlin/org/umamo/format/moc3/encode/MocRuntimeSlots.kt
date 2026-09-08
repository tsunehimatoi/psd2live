package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.ElementType
import org.umamo.format.moc3.moc.Section

/**
 * Synthesizes the per-object runtime-slot sections: the 8-byte fields each object block opens with,
 * which are all-zero on disk and filled by the runtime after its memory-cast.
 *
 * These carry no authored data, so a writer only has to SIZE them - but sizing them is not optional.
 * The array must be exactly as long as its object list or the runtime reads past the end of the one
 * it just allocated, which is why a bake cannot simply carry them from a reference file once the
 * object count changes.  That is the whole reason they are modeled at all.
 *
 * Corpus-verified all-zero across every sample by `UnmodeledSectionIdentityProbeTest`.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
public object MocRuntimeSlots {
	/**
	 * Builds every runtime-slot section for [doc], keyed by section-table index.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → zero bytes, sized to the owning object list.
	 */
	public fun runtimeSlotSections(doc: MocDocument): Map<Int, ByteArray> {
		val sink = SectionSink(doc.version)

		/**
		 * Emits one zero-filled slot array, sized to its owning object list.
		 *
		 * @param Section section     The runtime-slot section.
		 * @param Int     objectCount How many objects the array covers.
		 */
		fun put(section: Section, objectCount: Int) = sink.putZeros(section, objectCount * ElementType.U64.size)

		put(Section.PART_RUNTIME_SLOT, doc.parts.size)
		put(Section.DEFORMER_RUNTIME_SLOT, doc.deformers.size)
		// The art-mesh block opens with FOUR of them (Lina's research reads them as a header plus three
		// unknowns); all four are zero, and all four must be sized.
		put(Section.ARTMESH_RUNTIME_SLOT, doc.artMeshes.size)
		put(Section.ARTMESH_RUNTIME_SLOT_A, doc.artMeshes.size)
		put(Section.ARTMESH_RUNTIME_SLOT_B, doc.artMeshes.size)
		put(Section.ARTMESH_RUNTIME_SLOT_C, doc.artMeshes.size)
		put(Section.PARAM_RUNTIME_SLOT, doc.parameters.size)
		put(Section.PARAM_RUNTIME_SLOT_A, doc.parameters.size)
		put(Section.GLUE_RUNTIME_SLOT, doc.glues.size)
		put(Section.OFFSCREEN_RUNTIME_SLOT, doc.offscreens.size)
		return sink.toMap()
	}
}