package org.umamo.format.moc3.encode

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.moc.Section

/**
 * Synthesizes the keyform-binding grid sections from [context] (byte-exact). Reproduces the
 * editor's parameter-binding dedup: a parameter-binding is a unique `(parameter, key positions)`
 * pair; they are grouped by owning parameter and, within a parameter, ordered by first occurrence
 * scanning keyform-bindings in index order.
 *
 * @param MocLoweringContext context The shared lowering derivations.
 * @return Map Section index → element-region bytes.
 */
internal fun keyformGridSections(context: MocLoweringContext): Map<Int, ByteArray> {
	val doc = context.doc
	val sink = SectionSink(doc.version)
	val version = doc.version

	val parameterCount = doc.parameters.size
	val bindingCount = context.bindingCount
	val bindingAxes = context.bindingAxes
	val keySetsByParameter = context.keySetsByParameter
	// assign parameter-binding indices grouped by parameter
	val parameterBindingIndex = HashMap<Pair<Int, List<Float>>, Int>()
	val parameterBindingOrder = ArrayList<List<Float>>()
	val parameterBindingCount = IntArray(parameterCount)
	for (parameterIndex in 0 until parameterCount) {
		parameterBindingCount[parameterIndex] = keySetsByParameter[parameterIndex].size
		for (keys in keySetsByParameter[parameterIndex]) {
			parameterBindingIndex[parameterIndex to keys] = parameterBindingOrder.size
			parameterBindingOrder.add(keys)
		}
	}

	val keyOffset = ArrayList<Int>()
	val keyCount = ArrayList<Int>()
	val keyPositions = ArrayList<Float>()
	for (keys in parameterBindingOrder) {
		keyOffset.add(keyPositions.size)
		keyCount.add(keys.size)
		keyPositions.addAll(keys)
	}
	val keyformBindingStart = ArrayList<Int>()
	val keyformBindingCount = ArrayList<Int>()
	val keyformBindingSlot = ArrayList<Int>()
	for (bindingIndex in 0 until bindingCount) {
		keyformBindingStart.add(keyformBindingSlot.size)
		keyformBindingCount.add(bindingAxes[bindingIndex].size)
		for ((parameterIndex, keys) in bindingAxes[bindingIndex]) {
			keyformBindingSlot.add(parameterBindingIndex.getValue(parameterIndex to keys))
		}
	}

	sink.putInts(Section.PARAMETER_BINDING_COUNT, parameterBindingCount.toList())
	// The matching START column, which the runtime uses to find each parameter's binding run.  Written
	// here rather than beside the other prefix sums because it needs the pool this function builds.
	//
	// The empty-slot filler is NOT arbitrary: a parameter with no bindings stores -1 when it is NORMAL
	// and 0 when it is BLEND_SHAPE.  Both values occur within one file (LimeBirb, modelF), so the
	// parameter type is the only thing that tells the two fillers apart.
	val parameterBindingStart = IntArray(parameterCount)
	run {
		var cursor = 0
		for (parameterIndex in 0 until parameterCount) {
			parameterBindingStart[parameterIndex] =
				if (parameterBindingCount[parameterIndex] > 0) {
					val start = cursor
					cursor += parameterBindingCount[parameterIndex]
					start
				} else if (doc.parameters.getOrNull(parameterIndex)?.type == ParameterType.BLEND_SHAPE) {
					0
				} else {
					-1
				}
		}
	}
	sink.putInts(Section.PARAM_BINDING_START, parameterBindingStart.toList())
	sink.putInts(Section.BINDING_KEY_OFFSET, keyOffset)
	sink.putInts(Section.BINDING_KEY_COUNT, keyCount)
	// KEY_POSITIONS is up to THREE regions (MOC3 §5.6): the main-grid dedup keys; then, on a blend
	// model, the blend bindings' key runs (section 117 offsets point here); then the per-parameter
	// sorted union of main-grid axis keys and blend-binding keys, whose counts are section 104.
	// A blend-free document contributes nothing to the middle region and nothing but its main-grid
	// keys to the union, so the same walk covers both kinds of document.
	//
	// Omitting 104 on a v4+ file is not a smaller file, it is an unloadable one: the runtime reads
	// it unconditionally to SIZE its parameter key store, and an absent section still resolves to a
	// pointer - so it sizes the arena from whatever bytes follow.
	val allKeyPositions = ArrayList(keyPositions)
	for (bindingKeys in context.blendLayout.bindingKeys) {
		bindingKeys.forEach { allKeyPositions.add(it) }
	}
	val unionBase = allKeyPositions.size
	val unionKeyCounts = ArrayList<Int>()
	if (context.writesUnionRegion) {
		for (unionKeys in context.unionKeysByParameter) {
			unionKeyCounts.add(unionKeys.size)
			allKeyPositions.addAll(unionKeys)
		}
	}
	sink.putFloats(Section.KEY_POSITIONS, allKeyPositions)
	putParameterKeyRuns(sink, version, unionKeyCounts, unionBase)
	sink.putInts(Section.KEYFORM_BINDING_SLOT, keyformBindingSlot)
	sink.putInts(Section.KEYFORM_BINDING_START, keyformBindingStart)
	sink.putInts(Section.KEYFORM_BINDING_COUNT, keyformBindingCount)
	return sink.toMap()
}

/**
 * Writes the per-parameter key run columns (103 start, 104 count) over the key-position union region.
 *
 * @param SectionSink sink      The calling producer's section sink.
 * @param MocVersion version     The target version (the columns are MOC3 v4+).
 * @param List      counts      Each parameter's union key count, in parameter order.
 * @param Int       regionStart Where the union region begins inside KEY_POSITIONS.
 */
private fun putParameterKeyRuns(
	sink: SectionSink,
	version: MocVersion,
	counts: List<Int>,
	regionStart: Int,
) {
	if (version.byteValue < 4 || counts.isEmpty()) {
		return
	}
	// The start column is an OFFSET INTO KEY_POSITIONS, not a running count from zero: the union
	// region sits after the main-grid keys (and, on a blend model, the blend binding keys), so a
	// prefix sum based at 0 would point every parameter at the wrong run.
	var cursor = regionStart
	val starts =
		counts.map { count ->
			val start = cursor
			cursor += count
			start
		}
	sink.putInts(Section.PARAM_KEY_START, starts)
	sink.putInts(Section.PARAM_KEY_COUNT, counts)
}