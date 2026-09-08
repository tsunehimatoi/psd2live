package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeLimit
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer

/**
 * Everything the lowering producers derive from a [MocDocument] before any of them writes a byte.
 *
 * Each producer could rebuild what it needs on its own - the blend-shape file layout, the
 * parameter-binding dedup grid, the per-parameter key union - and that would not be merely repeated
 * work: CountInfo has to declare the exact extent of tables the other producers write, so two
 * traversals drifting apart produces a file whose header disagrees with its own body.  Deriving once
 * and sharing is what makes them unable to disagree.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal class MocLoweringContext(val doc: MocDocument) {
	val version: MocVersion = doc.version
	val warps: List<WarpDeformer> = doc.deformers.filterIsInstance<WarpDeformer>()
	val rotations: List<RotationDeformer> = doc.deformers.filterIsInstance<RotationDeformer>()

	/**
	 * The blend-shape layout, built even when the document has no records.
	 *
	 * An empty layout is exactly what a blend-free document has to write (no bindings, no records,
	 * every per-parameter column at its empty-slot filler), so one code path covers both kinds of
	 * document instead of two that have to be kept in agreement.
	 */
	val blendLayout: BlendShapeLayout = BlendShapeLayout(doc)

	/** Whether the document carries any blend-shape record at all. */
	val hasBlendShapes: Boolean = doc.blendShapes.isNotEmpty()

	/** Whether the color tables carry the blend delta region, probed from the records' delta colors. */
	val hasColorDeltaRows: Boolean = hasColorDeltaRows(doc)

	/**
	 * The width of the keyform-binding table: one slot per index in `0 until bindingCount`.
	 *
	 * Taken from the highest binding index rather than the map's entry count.  The two agree on every
	 * document any producer builds today, but sections 73/74 are written one row per slot while
	 * CountInfo field 12 sizes the runtime's array, and the runtime indexes that array by an object's
	 * raw `keyformBindingIndex` - which reaches the highest index, not the entry count.  Deriving both
	 * from this one value is what stops a sparse map from producing a header that under-sizes its body.
	 */
	val bindingCount: Int = (doc.bindings.maxOfOrNull { it.index } ?: -1) + 1

	/** Each keyform binding's axes as `(parameter, key positions)`, in binding-index order. */
	val bindingAxes: List<List<Pair<Int, List<Float>>>> =
		(0 until bindingCount).map { bindingIndex ->
			doc.keyformBinding(bindingIndex)?.axes?.map { it.parameterIndex to it.keyPositions.toList() }
				?: emptyList()
		}

	/** Per parameter, its distinct axis key-lists in first-occurrence order (the main-grid dedup). */
	val keySetsByParameter: Array<LinkedHashSet<List<Float>>> =
		Array<LinkedHashSet<List<Float>>>(doc.parameters.size) { LinkedHashSet() }.also { keySets ->
			for (axes in bindingAxes) {
				for ((parameterIndex, keys) in axes) {
					keySets[parameterIndex].add(keys)
				}
			}
		}

	/** Total distinct parameter-bindings across every parameter (CountInfo field 13). */
	val totalParamBindings: Int = keySetsByParameter.sumOf { it.size }

	/** Float count of KEY_POSITIONS' main-grid region (the dedup keys, before any later region). */
	val mainGridKeyTotal: Int = keySetsByParameter.sumOf { keySet -> keySet.sumOf { it.size } }

	/**
	 * Per parameter, the sorted union of its main-grid axis keys and its blend bindings' keys.
	 *
	 * One computation for both kinds of document: on a blend-free one the layout contributes nothing,
	 * so the union collapses to the main-grid keys.
	 */
	val unionKeysByParameter: List<List<Float>> =
		doc.parameters.indices.map { parameterIndex ->
			val unionKeys = mutableSetOf<Float>()
			for (keys in keySetsByParameter[parameterIndex]) {
				unionKeys.addAll(keys)
			}
			for (bindingIndex in blendLayout.bindingKeys.indices) {
				if (blendLayout.bindingOwnerParameter[bindingIndex] == parameterIndex) {
					blendLayout.bindingKeys[bindingIndex].forEach { unionKeys.add(it) }
				}
			}
			unionKeys.sorted()
		}

	/**
	 * Whether KEY_POSITIONS carries the per-parameter union region.
	 *
	 * On MOC3 v4+ the region is always present and sections 103/104 address it; below v4 it appears
	 * only in the editor builds that wrote it, which the document records as
	 * `keyPositionsHasParameterUnion`.
	 *
	 * ONE predicate answers both "is the region written" and "does CountInfo field 14 count it",
	 * because those are the same question: field 14 declares section 77's extent and 103/104 address
	 * runs inside it, so a document that writes the region without counting it points the runtime past
	 * the buffer field 14 just sized.  Splitting the two would let a v4+ blend-free export declare only
	 * its main-grid keys while carrying the union region as well.
	 */
	val writesUnionRegion: Boolean =
		hasBlendShapes || doc.keyPositionsHasParameterUnion || version.byteValue >= 4
}

/**
 * Whether [doc] carries the color tables' blend delta region.  The region is a later format
 * addition, absent on 4.2-era bakes whose tables end at the base rows (corpus: Azxiana.moc3,
 * V42); the decoder marks absence by null colors on every non-part record keyform, and the
 * region is all-or-nothing per file (a present region decodes zeros as non-null Rgb), so any
 * non-null delta color means the source carried it.
 *
 * @param MocDocument doc The semantic model.
 * @return Boolean True when the doc's blend keyforms carry color delta rows.
 */
internal fun hasColorDeltaRows(doc: MocDocument): Boolean =
	doc.blendShapes.any { blend ->
		blend.keyforms.any { keyform ->
			when (keyform) {
				is BlendShapeKeyform.Warp -> keyform.form.multiplyColor != null
				is BlendShapeKeyform.Mesh -> keyform.form.multiplyColor != null
				is BlendShapeKeyform.Rotation -> keyform.form.multiplyColor != null
				is BlendShapeKeyform.Part -> false
			}
		}
	}

/**
 * The blend-shape file layout derived from a document's records: the global record order
 * (warp, mesh, part, then rotation records - objects ascending by kind-local index, verified
 * on the corpus), the deduplicated binding list (grouped by parameter ascending, first
 * occurrence within a parameter), and the deduplicated limit sub-binding pool (sorted by
 * gating-parameter index; the within-parameter tie-break is first-occurrence order across
 * recordsInFileOrder).
 */
internal class BlendShapeLayout(doc: MocDocument) {
	val warpRecords: List<BlendShape>
	val meshRecords: List<BlendShape>
	val partRecords: List<BlendShape>
	val rotationRecords: List<BlendShape>
	val recordsInFileOrder: List<BlendShape>
	val warpLocalByDeformer: Map<Int, Int>
	val rotationLocalByDeformer: Map<Int, Int>
	val bindingKeys: List<FloatArray>
	val bindingNeutral: IntArray
	val bindingOwnerParameter: IntArray
	val parameterBegin: IntArray
	val parameterBindingCount: IntArray
	val bindingIndexOfRecord: IntArray
	val pool: List<BlendShapeLimit>
	private val poolIndexByValue: Map<List<Any>, Int>

	init {
		val warpLocal = HashMap<Int, Int>()
		val rotationLocal = HashMap<Int, Int>()
		var nextWarpLocal = 0
		var nextRotationLocal = 0
		for ((deformerIndex, deformer) in doc.deformers.withIndex()) {
			if (deformer is WarpDeformer) {
				warpLocal[deformerIndex] = nextWarpLocal++
			} else {
				rotationLocal[deformerIndex] = nextRotationLocal++
			}
		}
		warpLocalByDeformer = warpLocal
		rotationLocalByDeformer = rotationLocal
		warpRecords =
			doc.blendShapes.filter { it.target == BlendShapeTarget.WARP }
				.sortedBy { warpLocal[it.targetIndex] ?: Int.MAX_VALUE }
		meshRecords = doc.blendShapes.filter { it.target == BlendShapeTarget.ART_MESH }.sortedBy { it.targetIndex }
		partRecords = doc.blendShapes.filter { it.target == BlendShapeTarget.PART }.sortedBy { it.targetIndex }
		rotationRecords =
			doc.blendShapes.filter { it.target == BlendShapeTarget.ROTATION }
				.sortedBy { rotationLocal[it.targetIndex] ?: Int.MAX_VALUE }
		recordsInFileOrder = warpRecords + meshRecords + partRecords + rotationRecords

		// Distinct bindings: (parameter, keys, neutral), first-occurrence order re-sorted by
		// parameter (stable, so within-parameter order stays first-occurrence).
		data class BindingIdentity(val parameterIndex: Int, val keys: List<Float>, val neutralKeyIndex: Int)

		val discoveredBindings = LinkedHashSet<BindingIdentity>()
		for (record in recordsInFileOrder) {
			discoveredBindings.add(
				BindingIdentity(record.parameterIndex, record.keyPositions.toList(), record.neutralKeyIndex),
			)
		}
		val orderedBindings = discoveredBindings.toList().sortedBy { it.parameterIndex }
		bindingKeys = orderedBindings.map { it.keys.toFloatArray() }
		bindingNeutral = IntArray(orderedBindings.size) { bindingIndex -> orderedBindings[bindingIndex].neutralKeyIndex }
		bindingOwnerParameter = IntArray(orderedBindings.size) { bindingIndex -> orderedBindings[bindingIndex].parameterIndex }
		val bindingIndexByIdentity = orderedBindings.withIndex().associate { (bindingIndex, identity) -> identity to bindingIndex }
		bindingIndexOfRecord =
			IntArray(recordsInFileOrder.size) { recordIndex ->
				val record = recordsInFileOrder[recordIndex]
				bindingIndexByIdentity.getValue(
					BindingIdentity(record.parameterIndex, record.keyPositions.toList(), record.neutralKeyIndex),
				)
			}
		parameterBegin = IntArray(doc.parameters.size)
		parameterBindingCount = IntArray(doc.parameters.size)
		var bindingCursor = 0
		for (parameterIndex in doc.parameters.indices) {
			parameterBindingCount[parameterIndex] =
				orderedBindings.count { it.parameterIndex == parameterIndex }
			// Binding-less parameters store 0, not the running cumulative - UNLESS the parameter is
			// itself blend-shape-typed (a morph-target axis authored with no record bound to it),
			// which stores -1 instead.
			parameterBegin[parameterIndex] =
				when {
					parameterBindingCount[parameterIndex] > 0 -> bindingCursor
					doc.parameters[parameterIndex].type == ParameterType.BLEND_SHAPE -> -1
					else -> 0
				}
			bindingCursor += parameterBindingCount[parameterIndex]
		}

		// The limit sub-binding pool, deduplicated by value.  Sorted by gating-parameter index
		// (corpus-observed); the within-parameter tie-break is first-occurrence order across
		// recordsInFileOrder (Kotlin's sortedBy is stable).
		fun identityOf(limit: BlendShapeLimit): List<Any> =
			listOf(limit.parameterIndex, limit.keyPositions.toList(), limit.weights.toList())

		val distinctLimits = LinkedHashMap<List<Any>, BlendShapeLimit>()
		for (record in recordsInFileOrder) {
			for (limit in record.limits) {
				distinctLimits.getOrPut(identityOf(limit)) { limit }
			}
		}
		pool = distinctLimits.values.sortedBy { it.parameterIndex }
		poolIndexByValue = pool.withIndex().associate { (poolIndex, poolEntry) -> identityOf(poolEntry) to poolIndex }
	}

	/**
	 * The pool index of [limit] (matched by value).
	 *
	 * @param BlendShapeLimit limit A per-record expanded limit curve.
	 * @return Int The deduplicated pool index.
	 */
	fun poolIndexOf(limit: BlendShapeLimit): Int =
		poolIndexByValue.getValue(listOf(limit.parameterIndex, limit.keyPositions.toList(), limit.weights.toList()))
}