package org.umamo.format.moc3.decode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.KeyformAxis
import org.umamo.format.moc3.model.KeyformBinding

/**
 * Resolves keyform-binding indices into [KeyformBinding]s, and accumulates every one it resolves.
 *
 * This is deliberately an accumulator, not merely a cache: the set it has resolved BECOMES
 * [MocDocument.keyformBindings], so registering a binding is how it reaches the document.  Callers
 * therefore resolve through [binding] even when they only want the grid size, and the glue pass
 * resolves bindings purely to register them.  Call [collected] and [hasParameterUnionRegion] only
 * after every object kind has been decoded, or the tail of the binding table goes missing.
 */
internal class MocBindingResolver(sections: MocSections) {
	/**
	 * Owning parameter per binding slot, expanded from the per-parameter slot counts (MOC3 §5.6
	 * section 57): the slot table is a flat concatenation, so the parameter is positional.
	 */
	private val owningParameter: IntArray

	private val keyOffset: IntArray = sections.intArray(Section.BINDING_KEY_OFFSET)
	private val keyCount: IntArray = sections.intArray(Section.BINDING_KEY_COUNT)
	private val keyformBindingSlot: IntArray = sections.intArray(Section.KEYFORM_BINDING_SLOT)
	private val keyformBindingStart: IntArray = sections.intArray(Section.KEYFORM_BINDING_START)
	private val keyformBindingCount: IntArray = sections.intArray(Section.KEYFORM_BINDING_COUNT)
	private val resolved = HashMap<Int, KeyformBinding>()

	/** The shared key-position table (MOC3 §5.6 section 77); the blend path reads it too. */
	val keyPositions: FloatArray = sections.floatArray(Section.KEY_POSITIONS)

	init {
		val bindingCountPerParameter = sections.intArray(Section.PARAMETER_BINDING_COUNT)
		owningParameter = IntArray(bindingCountPerParameter.sum())
		var bindingSlot = 0
		for (parameterIndex in bindingCountPerParameter.indices) {
			repeat(bindingCountPerParameter[parameterIndex]) { owningParameter[bindingSlot++] = parameterIndex }
		}
	}

	/** Every binding resolved so far, which is what the document carries. */
	val collected: Map<Int, KeyformBinding> get() = resolved.toMap()

	/**
	 * Resolves the keyform binding at [keyformBindingIndex] into its parameter axes, registering it.
	 *
	 * @param Int keyformBindingIndex A keyform-binding index referenced by an object.
	 * @return KeyformBinding The resolved binding (its controlling parameter axes + key positions).
	 */
	fun binding(keyformBindingIndex: Int): KeyformBinding =
		resolved.getOrPut(keyformBindingIndex) {
			val start = keyformBindingStart[keyformBindingIndex]
			val axes =
				(0 until keyformBindingCount[keyformBindingIndex]).map { axisIndex ->
					val bindingSlot = keyformBindingSlot[start + axisIndex]
					KeyformAxis(
						owningParameter[bindingSlot],
						keyPositions.copyOfRange(
							keyOffset[bindingSlot],
							keyOffset[bindingSlot] + keyCount[bindingSlot],
						),
					)
				}
			KeyformBinding(keyformBindingIndex, axes)
		}

	/**
	 * The grid size of a binding, treating a part's 0 as "static" rather than as binding 0.
	 *
	 * @param Int keyformBindingIndex A part's keyform-binding index, where 0 means no binding.
	 * @return Int The keyform grid size, 1 when static.
	 */
	fun staticAwareGridSize(keyformBindingIndex: Int): Int =
		if (keyformBindingIndex <= 0) {
			1
		} else {
			binding(keyformBindingIndex).gridSize
		}

	/**
	 * Registers every binding record the file stores, not only those objects reference.
	 *
	 * The file allocates CountInfo[12] records, and a mesh-less model carries a single EMPTY
	 * binding (0 axes) that only static parts point at - lazy by-reference registration would drop
	 * it and shrink the re-synthesized binding sections + CountInfo (probed on the
	 * ModelWithOffscreen family).  MOC3 §5.1 CountInfo field 12.
	 *
	 * @param Int storedBindingCount The stored record count, CountInfo field 12.
	 */
	fun registerStoredBindings(storedBindingCount: Int) {
		repeat(storedBindingCount) { bindingIndex ->
			binding(bindingIndex)
		}
	}

	/**
	 * Total keys the deduplicated main-grid region of KEY_POSITIONS holds, over the bindings
	 * registered so far.  This is where the optional trailing union region begins.
	 *
	 * @return Int The main-grid key total.
	 */
	private fun mainGridKeyTotal(): Int {
		val keySetsByParameter = HashMap<Int, LinkedHashSet<List<Float>>>()
		for (resolvedBinding in resolved.values) {
			for (axis in resolvedBinding.axes) {
				keySetsByParameter.getOrPut(axis.parameterIndex) { LinkedHashSet() }.add(axis.keyPositions.toList())
			}
		}
		return keySetsByParameter.values.sumOf { keySets -> keySets.sumOf { it.size } }
	}

	/**
	 * Whether KEY_POSITIONS (77) trails its dedup region with the per-parameter sorted-union of the
	 * main-grid axis keys, an editor-version artifact on some blend-free v1/v3 files (MOC3 §5.6).
	 *
	 * Detected as any nonzero key beyond the dedup (main-grid) region; zero padding beyond the
	 * region reads as absent.  Only meaningful on a blend-free file - a blend model carries the
	 * region unconditionally, which the blend lowering path handles.
	 *
	 * @param Boolean isBlendFree Whether the model decoded no blend shapes.
	 * @return Boolean Whether the trailing union region is present.
	 */
	fun hasParameterUnionRegion(isBlendFree: Boolean): Boolean =
		isBlendFree && (mainGridKeyTotal() until keyPositions.size).any { keyIndex -> keyPositions[keyIndex] != 0f }
}