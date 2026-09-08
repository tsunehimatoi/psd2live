package org.umamo.format.moc3.encode

import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.RenderOrderChild

/**
 * Synthesizes the glue, render-order, and offscreen tables - the object kinds whose packing is
 * deterministic but which own no keyform value tables of their own.
 *
 * @param MocLoweringContext context The shared lowering derivations.
 * @return Map Section index → element-region bytes.
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal fun auxiliarySections(context: MocLoweringContext): Map<Int, ByteArray> {
	val doc = context.doc
	val sink = SectionSink(doc.version)

	// glue value tables
	if (doc.glues.isNotEmpty()) {
		val vertexStart = ArrayList<Int>()
		val glueVertexCount = ArrayList<Int>()
		val keyOffset = ArrayList<Int>()
		val keyCount = ArrayList<Int>()
		val weights = ArrayList<Float>()
		val indices = ArrayList<Short>()
		val intensities = ArrayList<Float>()
		for (glue in doc.glues) {
			vertexStart.add(weights.size)
			glueVertexCount.add(glue.pairs.size * 2)
			for (pair in glue.pairs) {
				weights.add(pair.weightA)
				weights.add(pair.weightB)
				indices.add(pair.vertexA.toShort())
				indices.add(pair.vertexB.toShort())
			}
			keyOffset.add(intensities.size)
			keyCount.add(glue.intensityKeyforms.size)
			glue.intensityKeyforms.forEach { intensities.add(it) }
		}
		sink.putInts(Section.GLUE_VERTEX_START, vertexStart)
		sink.putInts(Section.GLUE_VERTEX_COUNT, glueVertexCount)
		sink.putInts(Section.GLUE_KEY_OFFSET, keyOffset)
		sink.putInts(Section.GLUE_KEY_COUNT, keyCount)
		sink.putFloats(Section.GLUE_WEIGHTS, weights)
		sink.putFloats(Section.GLUE_INTENSITIES, intensities)
		sink.putShorts(Section.GLUE_VERTEX_INDICES, indices)
	}

	// render-order group tree
	if (doc.renderOrderGroups.isNotEmpty()) {
		val groups = doc.renderOrderGroups
		val childCount = ArrayList<Int>()
		val childKind = ArrayList<Int>()
		val childIndex = ArrayList<Int>()
		val childGroupIndex = ArrayList<Int>()
		for (group in groups) {
			childCount.add(group.children.size)
			for (child in group.children) {
				childKind.add(child.kind)
				childIndex.add(child.index)
				childGroupIndex.add(child.groupIndex)
			}
		}
		sink.putInts(Section.RENDER_ORDER_CHILD_COUNT, childCount)
		sink.putInts(Section.RENDER_ORDER_CHILD_KIND, childKind)
		sink.putInts(Section.RENDER_ORDER_CHILD_INDEX, childIndex)
		sink.putInts(Section.RENDER_ORDER_GROUP_INDEX, childGroupIndex)

		// Per-group render count (83) and child draw-order extent (84/85), which the runtime reads.  A kind-0 child's draw order is its
		// mesh's; a kind-1 child's is its sub-group part's; the render count recurses into sub-groups and
		// counts an extra slot for a sub-group part that owns an offscreen. Draw order = floor(0.001+value).
		val ownerParts = doc.offscreens.map { it.ownerPartIndex }.toHashSet()
		val renderCountMemo = HashMap<Int, Int>()

		/**
		 * Total render-index count of group [groupIndex] (recursive, with offscreen-owner slots).
		 *
		 * @param Int groupIndex The render-order group index.
		 * @return Int The recursive render count.
		 */
		fun renderCount(groupIndex: Int): Int =
			renderCountMemo.getOrPut(groupIndex) {
				groups[groupIndex].children.sumOf { child ->
					if (child.kind == 0) {
						1
					} else {
						(if (child.index in ownerParts) 1 else 0) + renderCount(child.groupIndex)
					}
				}
			}

		/**
		 * The integer draw order of one render-order [child] (its mesh's, or its sub-group part's).
		 *
		 * @param RenderOrderChild child A render-order child.
		 * @return Int The floored draw order (`floor(0.001 + value)`).
		 */
		fun childDrawOrder(child: RenderOrderChild): Int {
			val drawOrderValue =
				if (child.kind == 0) {
					doc.artMeshes[child.index].keyforms.first().drawOrder
				} else {
					doc.parts[child.index].drawOrderKeyforms.first()
				}
			return (0.001f + drawOrderValue).toInt()
		}

		val renderCounts = ArrayList<Int>()
		val maxDraw = ArrayList<Int>()
		val minDraw = ArrayList<Int>()
		for (groupIndex in groups.indices) {
			renderCounts.add(renderCount(groupIndex))
			val childOrders = groups[groupIndex].children.map(::childDrawOrder)
			// MOC3 §5.6: an EMPTY group stores the max/min fold identities (-INT_MAX / INT_MAX),
			// not a 500 default - probed on the ModelWithOffscreen family's childless groups.
			maxDraw.add(childOrders.maxOrNull() ?: -Int.MAX_VALUE)
			minDraw.add(childOrders.minOrNull() ?: Int.MAX_VALUE)
		}
		sink.putInts(Section.RENDER_ORDER_GROUP_RENDER_COUNT, renderCounts)
		sink.putInts(Section.RENDER_ORDER_GROUP_MAX_DRAW_ORDER, maxDraw)
		sink.putInts(Section.RENDER_ORDER_GROUP_MIN_DRAW_ORDER, minDraw)
	}

	// offscreens: per-object scalar sections + keyform tables
	if (doc.offscreens.isNotEmpty()) {
		sink.putInts(Section.OFFSCREEN_OWNER_PART, doc.offscreens.map { it.ownerPartIndex })
		sink.putBytes(Section.OFFSCREEN_CONSTANT_FLAGS, ByteArray(doc.offscreens.size) { doc.offscreens[it].constantFlags.toByte() })
		sink.putInts(Section.OFFSCREEN_BLEND_MODE, doc.offscreens.map { it.blendMode })
		sink.putInts(Section.OFFSCREEN_MASK_COUNT, doc.offscreens.map { it.maskCount })
		// 152: the per-part inverse of OFFSCREEN_OWNER_PART (-1 for offscreen-less parts).
		val offscreenByPart = IntArray(doc.parts.size) { -1 }
		for ((offscreenIndex, offscreen) in doc.offscreens.withIndex()) {
			if (offscreen.ownerPartIndex in offscreenByPart.indices) {
				offscreenByPart[offscreen.ownerPartIndex] = offscreenIndex
			}
		}
		sink.putInts(Section.OFFSCREEN_BY_PART, offscreenByPart.toList())
		// 160 gets the SAME inverse map, and it must be written: the runtime reads it as a per-part
		// offscreen index, so leaving it empty makes it read the neighbouring section's bytes as part
		// indices and take the core down (not a rejected file - a segfault inside it).
		//
		// The two columns are byte-identical in every corpus file but modelA, where 152 is the exact
		// inverse of the offscreen owner column (155) and 160 names a different, larger set of parts
		// that matches no owner.  Which makes 152 the reconstructible one and 160 an editor-internal
		// artifact, in the same class as the keyform-binding numbering: we write the consistent
		// inverse in both rather than reproduce a divergence we cannot derive.
		sink.putInts(Section.OFFSCREEN_BY_PART_ALIAS, offscreenByPart.toList())
		// 158: cumulative mask base (the scan of 159; MOC3 §5.6, OffscreenKeyformProbeTest).
		val maskBases = ArrayList<Int>()
		var maskBaseCursor = 0
		for (offscreen in doc.offscreens) {
			maskBases.add(maskBaseCursor)
			maskBaseCursor += offscreen.maskCount
		}
		sink.putInts(Section.OFFSCREEN_MASK_BASE, maskBases)
		// Keyform tables only when the typed extraction populated them (else carried).
		if (doc.offscreens.all { it.keyforms.isNotEmpty() }) {
			val offscreenOpacities = doc.offscreens.flatMap { offscreen -> offscreen.keyforms.map { it.opacity } }
			sink.putFloats(Section.OFFSCREEN_OPACITY, offscreenOpacities)
			// 162/163: keyform → color-prefix row maps; identity because the offscreen keyform
			// rows ARE the color tables' prefix, in offscreen order.
			val identityRows = (0 until offscreenOpacities.size).toList()
			sink.putInts(Section.OFFSCREEN_KEYFORM_MULTIPLY_ROW, identityRows)
			sink.putInts(Section.OFFSCREEN_KEYFORM_SCREEN_ROW, identityRows)
		}
	}
	return sink.toMap()
}