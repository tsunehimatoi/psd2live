package org.umamo.format.moc3.encode

import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer

/**
 * Synthesizes the color tables and the per-form row references into them (MOC3 v4+).
 *
 * The encode-side counterpart of the decoder's ColorTables: one shared row pool that every kind of
 * object indexes into, rather than a per-object table.  Rows are laid out as a moc-6 offscreen keyform
 * PREFIX, then the base rows (deformers in unified order, then art meshes), then the blend records'
 * color delta rows - and sections 137-142 are what the runtime dereferences to reach a given form's
 * row, so a kind whose reference table is short sends it reading past the pool.
 *
 * @param MocLoweringContext context The shared lowering derivations.
 * @return Map Section index → element-region bytes (empty when the document carries no color data).
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal fun colorSections(context: MocLoweringContext): Map<Int, ByteArray> {
	val doc = context.doc
	val sink = SectionSink(doc.version)

	// Layout (MOC3 §5.6): a moc-6 offscreen keyform PREFIX, then the base rows (deformers in
	// unified order, then meshes), then the blend records' color delta rows (global record
	// order, part records excluded - parts own no color rows).  Synthesized whenever any typed
	// color data exists; a doc whose offscreens/blends predate the typed extraction (empty
	// keyform lists) falls back to carrying via the size guard below.
	val offscreenKeyformsTyped = doc.offscreens.all { it.keyforms.isNotEmpty() }
	val hasColor =
		(offscreenKeyformsTyped || doc.offscreens.isEmpty()) &&
			(
				doc.deformers.any { deformer ->
					(deformer is WarpDeformer && deformer.keyforms.any { it.multiplyColor != null }) ||
						(deformer is RotationDeformer && deformer.keyforms.any { it.multiplyColor != null })
				} ||
					doc.artMeshes.any { mesh -> mesh.keyforms.any { it.multiplyColor != null } } ||
					doc.offscreens.any { offscreen -> offscreen.keyforms.any { it.multiplyColor != null } }
			)
	if (hasColor) {
		val multiplyRed = ArrayList<Float>()
		val multiplyGreen = ArrayList<Float>()
		val multiplyBlue = ArrayList<Float>()
		val screenRed = ArrayList<Float>()
		val screenGreen = ArrayList<Float>()
		val screenBlue = ArrayList<Float>()

		/**
		 * Appends one keyform's multiply/screen colors, defaulting to white/black when absent.
		 *
		 * @param Rgb? multiplyColor The keyform's multiply color (null → white identity).
		 * @param Rgb? screenColor   The keyform's screen color (null → black identity).
		 */
		fun appendColors(multiplyColor: Rgb?, screenColor: Rgb?) {
			val multiply = multiplyColor ?: Rgb(1f, 1f, 1f)
			val screen = screenColor ?: Rgb(0f, 0f, 0f)
			multiplyRed.add(multiply.r)
			multiplyGreen.add(multiply.g)
			multiplyBlue.add(multiply.b)
			screenRed.add(screen.r)
			screenGreen.add(screen.g)
			screenBlue.add(screen.b)
		}

		/**
		 * Appends one blend delta row's colors; an unauthored delta channel is ZERO, not the
		 * channel's identity (MOC3 §5.6: delta rows are neutral-relative).
		 *
		 * @param Rgb? multiplyColor The delta row's multiply color (null → zero).
		 * @param Rgb? screenColor   The delta row's screen color (null → zero).
		 */
		fun appendDeltaColors(multiplyColor: Rgb?, screenColor: Rgb?) {
			appendColors(multiplyColor ?: Rgb(0f, 0f, 0f), screenColor ?: Rgb(0f, 0f, 0f))
		}

		// MOC3 v6: the offscreen keyform rows prefix the tables, shifting every base below.
		for (offscreen in doc.offscreens) {
			offscreen.keyforms.forEach { appendColors(it.multiplyColor, it.screenColor) }
		}
		// Per-FORM color-row references (137-142), one entry per form SLOT of each object kind, in
		// that kind's own form order.  They are what the runtime dereferences to reach a form's color
		// row, so a kind whose table is short or absent sends it reading past the end - which is a
		// segfault inside the official core, not a rejected file.
		//
		// The tail of each table covers that kind's BLEND-SHAPE record rows: the runtime reaches a
		// record's color delta through the same indirection, at the record base rather than the
		// object base, so the delta rows have to be addressable here too.
		val warpColorRefs = ArrayList<Int>()
		val rotationColorRefs = ArrayList<Int>()
		val meshColorRefs = ArrayList<Int>()
		val warpColorBase = ArrayList<Int>()
		val rotationColorBase = ArrayList<Int>()
		for (deformer in doc.deformers) {
			when (deformer) {
				is WarpDeformer -> {
					warpColorBase.add(multiplyRed.size)
					deformer.keyforms.forEach {
						warpColorRefs.add(multiplyRed.size)
						appendColors(it.multiplyColor, it.screenColor)
					}
				}

				is RotationDeformer -> {
					rotationColorBase.add(multiplyRed.size)
					deformer.keyforms.forEach {
						rotationColorRefs.add(multiplyRed.size)
						appendColors(it.multiplyColor, it.screenColor)
					}
				}
			}
		}
		val meshColorBase = ArrayList<Int>()
		for (mesh in doc.artMeshes) {
			meshColorBase.add(multiplyRed.size)
			mesh.keyforms.forEach {
				meshColorRefs.add(multiplyRed.size)
				appendColors(it.multiplyColor, it.screenColor)
			}
		}
		// A 4.2-era bake carries no delta region (see hasColorDeltaRows) - mirror its absence so
		// the re-synthesized tables end at the base rows and stay byte-exact both ways.
		if (context.hasBlendShapes && context.hasColorDeltaRows) {
			val blendLayout = context.blendLayout
			for (record in blendLayout.recordsInFileOrder) {
				if (record.target == BlendShapeTarget.PART) {
					continue
				}
				for (keyform in record.keyforms) {
					when (keyform) {
						is BlendShapeKeyform.Warp -> {
							warpColorRefs.add(multiplyRed.size)
							appendDeltaColors(keyform.form.multiplyColor, keyform.form.screenColor)
						}

						is BlendShapeKeyform.Mesh -> {
							meshColorRefs.add(multiplyRed.size)
							appendDeltaColors(keyform.form.multiplyColor, keyform.form.screenColor)
						}

						is BlendShapeKeyform.Rotation -> {
							rotationColorRefs.add(multiplyRed.size)
							appendDeltaColors(keyform.form.multiplyColor, keyform.form.screenColor)
						}

						// A part owns no color rows at all, so it contributes no reference either.
						is BlendShapeKeyform.Part -> Unit
					}
				}
			}
		}
		// The multiply and screen tables of each pair are bit-identical corpus-wide (the reference is to
		// a ROW, and a row carries both colors), so one list feeds both.
		sink.putInts(Section.WARP_FORM_MULTIPLY_ROW, warpColorRefs)
		sink.putInts(Section.WARP_FORM_SCREEN_ROW, warpColorRefs)
		sink.putInts(Section.ROTATION_FORM_MULTIPLY_ROW, rotationColorRefs)
		sink.putInts(Section.ROTATION_FORM_SCREEN_ROW, rotationColorRefs)
		sink.putInts(Section.ARTMESH_FORM_MULTIPLY_ROW, meshColorRefs)
		sink.putInts(Section.ARTMESH_FORM_SCREEN_ROW, meshColorRefs)
		sink.putInts(Section.WARP_COLOR_BASE, warpColorBase)
		sink.putInts(Section.ROTATION_COLOR_BASE, rotationColorBase)
		sink.putInts(Section.ARTMESH_COLOR_BASE, meshColorBase)
		sink.putFloats(Section.COLOR_MULTIPLY_R, multiplyRed)
		sink.putFloats(Section.COLOR_MULTIPLY_G, multiplyGreen)
		sink.putFloats(Section.COLOR_MULTIPLY_B, multiplyBlue)
		sink.putFloats(Section.COLOR_SCREEN_R, screenRed)
		sink.putFloats(Section.COLOR_SCREEN_G, screenGreen)
		sink.putFloats(Section.COLOR_SCREEN_B, screenBlue)
	}
	return sink.toMap()
}