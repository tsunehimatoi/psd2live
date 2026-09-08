package org.umamo.format.moc3.encode

import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.BlendShapeKeyform

/**
 * Synthesizes the keyform value tables from [context], keyed by section-table index. These are
 * the layout-dependent geometry/scalar tables; the packing is deterministic (warp keyform blocks
 * then mesh blocks in `POS_VALUES`, each padded to 16 floats; bases are cumulative), so they
 * reconstruct byte-for-byte. The keyform-binding grid (param-binding dedup) is not synthesized
 * here.
 *
 * @param MocLoweringContext context The shared lowering derivations.
 * @return Map Section index → element-region bytes.
 */
internal fun valueTableSections(context: MocLoweringContext): Map<Int, ByteArray> {
	val doc = context.doc
	val sink = SectionSink(doc.version)

	val warps = context.warps
	val rotations = context.rotations

	// POS_VALUES: warp control-point keyform blocks first, then art-mesh vertex keyform blocks,
	// each padded to a 16-float (64-byte) boundary. Index tables record each block's float offset.
	val positionValues = LittleEndianWriter(64 * 1024)
	val warpPositionIndex = ArrayList<Int>()
	val warpKeyformBase = IntArray(warps.size)
	val warpOpacity = ArrayList<Float>()
	for ((warpIndex, warp) in warps.withIndex()) {
		warpKeyformBase[warpIndex] = warpPositionIndex.size
		for (keyform in warp.keyforms) {
			warpPositionIndex.add(positionValues.position / 4)
			keyform.controlPoints.forEach(positionValues::writeFloat32)
			padTo16Floats(positionValues, keyform.controlPoints.size)
			warpOpacity.add(keyform.opacity)
		}
	}
	val meshPositionIndex = ArrayList<Int>()
	val meshKeyformBase = IntArray(doc.artMeshes.size)
	val meshOpacity = ArrayList<Float>()
	val meshDrawOrder = ArrayList<Float>()
	for ((meshIndex, mesh) in doc.artMeshes.withIndex()) {
		meshKeyformBase[meshIndex] = meshPositionIndex.size
		for (keyform in mesh.keyforms) {
			meshPositionIndex.add(positionValues.position / 4)
			keyform.vertexPositions.forEach(positionValues::writeFloat32)
			padTo16Floats(positionValues, keyform.vertexPositions.size)
			meshOpacity.add(keyform.opacity)
			meshDrawOrder.add(keyform.drawOrder)
		}
	}

	// Blend-shape delta rows append AFTER the base keyforms in the same tables (MOC3 §5.6):
	// POS_VALUES gains warp-record then mesh-record delta blocks (16-float padded like the base
	// blocks), the per-kind index/scalar tables gain one row per (record, key) in global record
	// order, so the per-object bases above stay pure base-prefix indices.
	val blendLayout = context.blendLayout.takeIf { context.hasBlendShapes }
	if (blendLayout != null) {
		for (record in blendLayout.warpRecords) {
			for (keyform in record.keyforms) {
				val warpDelta = (keyform as? BlendShapeKeyform.Warp)?.form ?: continue
				warpPositionIndex.add(positionValues.position / 4)
				warpDelta.controlPoints.forEach(positionValues::writeFloat32)
				padTo16Floats(positionValues, warpDelta.controlPoints.size)
				warpOpacity.add(warpDelta.opacity)
			}
		}
		for (record in blendLayout.meshRecords) {
			for (keyform in record.keyforms) {
				val meshDelta = (keyform as? BlendShapeKeyform.Mesh)?.form ?: continue
				meshPositionIndex.add(positionValues.position / 4)
				meshDelta.vertexPositions.forEach(positionValues::writeFloat32)
				padTo16Floats(positionValues, meshDelta.vertexPositions.size)
				meshOpacity.add(meshDelta.opacity)
				meshDrawOrder.add(meshDelta.drawOrder)
			}
		}
	}

	sink.putInts(Section.WARP_KEYFORM_BASE, warpKeyformBase.toList())
	sink.putInts(Section.ARTMESH_KEYFORM_BASE, meshKeyformBase.toList())

	// rotation deformers: per-keyform affine tables, base = cumulative rotation keyforms.
	val rotationKeyformBase = IntArray(rotations.size)
	val rotationAngle = ArrayList<Float>()
	val rotationOriginX = ArrayList<Float>()
	val rotationOriginY = ArrayList<Float>()
	val rotationScale = ArrayList<Float>()
	val rotationReflectX = ArrayList<Int>()
	val rotationReflectY = ArrayList<Int>()
	val rotationOpacity = ArrayList<Float>()
	var rotationKeyformCounter = 0
	for ((rotationIndex, rotation) in rotations.withIndex()) {
		rotationKeyformBase[rotationIndex] = rotationKeyformCounter
		for (keyform in rotation.keyforms) {
			rotationAngle.add(keyform.angle)
			rotationOriginX.add(keyform.originX)
			rotationOriginY.add(keyform.originY)
			rotationScale.add(keyform.scale)
			rotationReflectX.add(if (keyform.reflectX) 1 else 0)
			rotationReflectY.add(if (keyform.reflectY) 1 else 0)
			rotationOpacity.add(keyform.opacity)
			rotationKeyformCounter++
		}
	}
	sink.putInts(Section.ROTATION_KEYFORM_BASE, rotationKeyformBase.toList())
	if (blendLayout != null) {
		// MOC3 §5.6: rotation delta rows sit directly in the affine tables 61-67 after the base rows.
		for (record in blendLayout.rotationRecords) {
			for (keyform in record.keyforms) {
				val rotationDelta = (keyform as? BlendShapeKeyform.Rotation)?.form ?: continue
				rotationAngle.add(rotationDelta.angle)
				rotationOriginX.add(rotationDelta.originX)
				rotationOriginY.add(rotationDelta.originY)
				rotationScale.add(rotationDelta.scale)
				rotationReflectX.add(if (rotationDelta.reflectX) 1 else 0)
				rotationReflectY.add(if (rotationDelta.reflectY) 1 else 0)
				rotationOpacity.add(rotationDelta.opacity)
			}
		}
	}

	sink.putBytes(Section.KEYFORM_POSITION_VALUES, positionValues.toByteArray())
	sink.putInts(Section.WARP_KEYFORM_INDEX, warpPositionIndex)
	sink.putFloats(Section.WARP_OPACITY, warpOpacity)
	sink.putInts(Section.KEYFORM_POSITION_INDEX, meshPositionIndex)
	sink.putFloats(Section.ARTMESH_OPACITY, meshOpacity)
	sink.putFloats(Section.ARTMESH_DRAW_ORDER, meshDrawOrder)
	sink.putFloats(Section.ROTATION_ANGLE, rotationAngle)
	sink.putFloats(Section.ROTATION_ORIGIN_X, rotationOriginX)
	sink.putFloats(Section.ROTATION_ORIGIN_Y, rotationOriginY)
	sink.putFloats(Section.ROTATION_SCALE, rotationScale)
	sink.putInts(Section.ROTATION_REFLECT_X, rotationReflectX)
	sink.putInts(Section.ROTATION_REFLECT_Y, rotationReflectY)
	sink.putFloats(Section.ROTATION_OPACITY, rotationOpacity)

	// parts: per-part draw-order keyform table + cumulative base (a static part has a single value).
	// MOC3 v5+ §5.6: part-owned blend-shape records append draw-order delta rows to section 58.
	val partDrawOrder = ArrayList<Float>()
	val partKeyformBase = IntArray(doc.parts.size)
	for ((partIndex, part) in doc.parts.withIndex()) {
		partKeyformBase[partIndex] = partDrawOrder.size
		part.drawOrderKeyforms.forEach { partDrawOrder.add(it) }
	}
	if (blendLayout != null) {
		for (record in blendLayout.partRecords) {
			for (keyform in record.keyforms) {
				val partDelta = keyform as? BlendShapeKeyform.Part ?: continue
				partDrawOrder.add(partDelta.drawOrderDelta)
			}
		}
	}
	sink.putInts(Section.PART_KEYFORM_BASE, partKeyformBase.toList())
	sink.putFloats(Section.PART_DRAW_ORDER, partDrawOrder)
	return sink.toMap()
}

/**
 * Zero-pads [writer] so a block of [count] floats reaches the next 16-float (64-byte) boundary.
 *
 * @param LittleEndianWriter writer The value-table writer to pad.
 * @param Int                count  The number of floats just written in this block.
 */
private fun padTo16Floats(writer: LittleEndianWriter, count: Int) {
	val padCount = ((count + 15) / 16 * 16) - count
	repeat(padCount) { writer.writeFloat32(0f) }
}