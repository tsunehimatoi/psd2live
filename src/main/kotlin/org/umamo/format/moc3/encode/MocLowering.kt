package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.Section

/**
 * Lowers a [MocDocument] back to section byte-arrays (the semantic half of the bake).
 *
 * The work is split across per-concern producers, each in its own file and each mirroring the decoder
 * collaborator it has to round-trip against: structure ([structuralSections]), keyform value tables
 * ([valueTableSections]), color tables ([colorSections]), the glue/render-order/offscreen tables
 * ([auxiliarySections]), the parameter-binding grid ([keyformGridSections]), blend records
 * ([blendShapeSections]), the self-contained derived columns ([MocDerivedIndexes]), the zero-filled
 * runtime slots ([MocRuntimeSlots]), and section 0 ([countInfoSection]).  Everything they share is
 * derived once in [MocLoweringContext]; everything they write goes through a [SectionSink].
 *
 * Every packing here is derived rather than carried, and deterministic, so one document always lowers
 * to one set of bytes.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5, §7</a>
 */
public object MocLowering {
	/**
	 * Lowers [doc] to every section it can produce, keyed by section-table index.
	 *
	 * The producers are merged STRICTLY: two of them claiming the same index fails here rather than
	 * resolving by merge order.  That is not defensive - a `+` fold would resolve a double claim by
	 * silently keeping the later producer, leaving the shadowed copy editable with no effect on any
	 * output and no test to notice.  Every index has exactly one owner, and this is what keeps it
	 * that way.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes (no trailing padding).
	 */
	public fun lower(doc: MocDocument): Map<Int, ByteArray> {
		val context = MocLoweringContext(doc)
		val merged = LinkedHashMap<Int, ByteArray>()
		val claimedBy = HashMap<Int, String>()

		/**
		 * Folds one producer's output in, failing when it claims an index another producer already owns.
		 *
		 * @param String producerName The producer's name, for the failure message.
		 * @param Map    produced     Its section index → element-region bytes.
		 */
		fun merge(producerName: String, produced: Map<Int, ByteArray>) {
			for ((index, bytes) in produced) {
				val previousProducer = claimedBy.put(index, producerName)
				require(previousProducer == null) {
					val sectionName = Section.entries.firstOrNull { it.indexIn(doc.version) == index }?.name ?: "unmodeled"
					"section $index ($sectionName) claimed by both $previousProducer and $producerName"
				}
				merged[index] = bytes
			}
		}

		merge("structural", structuralSections(context))
		merge("valueTables", valueTableSections(context))
		merge("color", colorSections(context))
		merge("auxiliary", auxiliarySections(context))
		merge("keyformGrid", keyformGridSections(context))
		merge("blendShapes", blendShapeSections(context))
		merge("runtimeSlots", MocRuntimeSlots.runtimeSlotSections(doc))
		merge("derivedIndexes", MocDerivedIndexes.derivedIndexSections(doc))
		merge("countInfo", mapOf(Section.COUNT_INFO.indexIn(doc.version) to countInfoSection(context)))
		return merged
	}
}