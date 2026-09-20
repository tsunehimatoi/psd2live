package org.umamo.interop.cmo3

import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.format.atlas.AtlasPackSkipReason
import org.umamo.format.atlas.packAtlas
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/**
 * Un-deduplicates baked atlas twins before fresh-graph synthesis.
 *
 * A .moc3 bake may pack mirrored duplicates as ONE atlas slot sampled by drawables at different
 * canvas placements.  The CMO3 model-image web cannot express that shape: a CModelImage carries a
 * single canvas placement, so a shared material loses every twin but the first in the editor's
 * source-image mode, while duplicated materials over one slot make the editor's atlas recomposite
 * stack the shared art's alpha.  Official documents never contain the shape either - every corpus
 * multi-drawable material is co-located (Erica: 45 of 45 groups share mesh AND placement), and
 * real mirror pairs are separate source layers in separate slots.  This prepass restores that
 * structure: each ADDITIONAL placement of a shared slot gets the patch pixels copied onto a
 * synthesized extra page, packed by the shared atlas packer, and its drawables' uvs remapped
 * there, so every slot downstream carries a single canvas placement and the image chain builds one
 * correctly-placed material per twin.
 */
internal object Cmo3AtlasUndedup {
	/** Transparent spacing between packed patches (and the page border): the packer's gutter. */
	private const val PACK_GUTTER = 2

	/** The un-deduplicated inputs for the fresh-graph pipeline. */
	internal class Result(
		val puppet: PuppetModel,
		val pages: List<Cmo3Conversion.AtlasPage>,
		val pageIndexByDrawableId: Map<String, Int>,
		/** The drawables whose uvs were remapped onto a synthesized page, for tests and reports. */
		val duplicatedDrawableIds: List<String>,
		/**
		 * The drawables of a further placement whose patch is too large for a page even on its own,
		 * left sharing the source slot with their uvs untouched, for the export notice.  A further
		 * placement with no opaque pixel (a hit area's twin) shares silently and is listed nowhere:
		 * there is nothing to show at either placement, so the web's single placement costs nothing.
		 */
		val sharedDrawableIds: List<String> = emptyList(),
	)

	/** One atlas slot shared by several drawables: page + uv bbox + the mesh arrays. */
	private class SlotKey(
		val pageIndex: Int,
		private val uvs: FloatArray,
		private val indices: IntArray,
	) {
		override fun equals(other: Any?): Boolean =
			other is SlotKey &&
				pageIndex == other.pageIndex &&
				uvs.contentEquals(other.uvs) &&
				indices.contentEquals(other.indices)

		override fun hashCode(): Int {
			var result = pageIndex
			result = 31 * result + uvs.contentHashCode()
			result = 31 * result + indices.contentHashCode()
			return result
		}
	}

	/** One placement sub-group needing its own copy of a slot's patch. */
	private class DuplicationJob(
		val sourcePageIndex: Int,
		val sourceRect: IntArray,
		val drawableIds: List<String>,
	)

	/**
	 * Detects shared slots sampled from multiple canvas placements and gives every additional
	 * placement its own patch copy on a synthesized page.
	 *
	 * @param PuppetModel puppet The model to convert; canvas-frame rest meshes.
	 * @param List        pages  The atlas pages, in model3 texture order.
	 * @param Map         pageIndexByDrawableId Each drawable id's atlas page index.
	 * @return Result The (possibly) remapped puppet, extended page list, and updated page map.
	 */
	fun undeduplicate(
		puppet: PuppetModel,
		pages: List<Cmo3Conversion.AtlasPage>,
		pageIndexByDrawableId: Map<String, Int>,
	): Result {
		// Group drawables by slot, then by quantized placement within the slot.
		val placementsBySlot = LinkedHashMap<SlotKey, LinkedHashMap<List<Long>, MutableList<String>>>()
		val rectBySlot = HashMap<SlotKey, IntArray>()
		for (drawable in puppet.drawables) {
			val pageIndex = pageIndexByDrawableId[drawable.id.raw] ?: continue
			val page = pages.getOrNull(pageIndex) ?: continue
			val mesh = drawable.mesh ?: continue
			val rect = Cmo3ImageChainBuilder.patchRectOf(mesh.uvs, page.width, page.height) ?: continue
			val slotKey = SlotKey(pageIndex, mesh.uvs, mesh.indices)
			rectBySlot[slotKey] = rect
			val fit = fitAtlasPageToCanvasTransform(mesh.uvs, mesh.positions, page.width, page.height)
			val placementKey =
				listOf(
					Math.round(fit.m00.toDouble() * 1024),
					Math.round(fit.m01.toDouble() * 1024),
					Math.round(fit.m10.toDouble() * 1024),
					Math.round(fit.m11.toDouble() * 1024),
					Math.round(fit.m02.toDouble()),
					Math.round(fit.m12.toDouble()),
				)
			placementsBySlot
				.getOrPut(slotKey) { LinkedHashMap() }
				.getOrPut(placementKey) { ArrayList() }
				.add(drawable.id.raw)
		}

		// The first placement of a slot keeps the original pixels; every further placement is a job.
		val jobs = ArrayList<DuplicationJob>()
		for ((slotKey, placements) in placementsBySlot) {
			if (placements.size < 2) {
				continue
			}
			for ((placementIndex, drawableIds) in placements.values.withIndex()) {
				if (placementIndex == 0) {
					continue
				}
				jobs.add(DuplicationJob(slotKey.pageIndex, rectBySlot.getValue(slotKey), drawableIds))
			}
		}
		if (jobs.isEmpty()) {
			return Result(puppet, pages, pageIndexByDrawableId, emptyList())
		}

		// Each job's patch is cut out of its source page and handed to the shared packer as a tile
		// whose reserve is the whole patch: the mesh's uv box reaches into the transparent margin
		// around the opaque art, and the reserve keeps that margin on the tile's own transparent space
		// rather than on a neighbor's pixels.  The key is the job's first drawable, stable and unique,
		// so the pack is deterministic run to run.
		val decodedPages = HashMap<Int, RasterImage>()
		val jobByKey = LinkedHashMap<String, DuplicationJob>()
		val items =
			jobs.map { job ->
				val sourcePage = decodedPages.getOrPut(job.sourcePageIndex) { PngCodec.read(pages[job.sourcePageIndex].pngBytes) }
				val key = job.drawableIds.first()
				jobByKey[key] = job
				val width = job.sourceRect[2] - job.sourceRect[0]
				val height = job.sourceRect[3] - job.sourceRect[1]
				AtlasPackItem(key, width, height, cutPatch(sourcePage, job.sourceRect), reserve = AtlasPackReserve(0, 0, width, height))
			}
		// Extrusion stays off and the trim threshold at one: the image chain crops each layer PNG by
		// its uv box, so an extruded band inside the reserved margin would put edge color where the
		// bake had transparent pixels, and a lossless trim blitted into a transparent reserve
		// reproduces the patch verbatim.
		val pageCap = maxOf(1024, pages.maxOf { page -> maxOf(page.width, page.height) })
		val packed =
			packAtlas(
				items,
				AtlasPackOptions(
					maxPageSize = pageCap,
					gutter = PACK_GUTTER,
					extrude = 0,
					allowRotation = false,
					powerOfTwoPages = true,
					squarePages = true,
					shrinkPages = true,
					alphaThreshold = 1,
				),
			)

		// Remap the duplicated drawables' uvs onto the synthesized pages.
		val newPageIndexByDrawableId = HashMap(pageIndexByDrawableId)
		val remapByDrawableId = HashMap<String, FloatArray>()
		val duplicatedIds = ArrayList<String>()
		for (placement in packed.placements) {
			check(placement.quarterTurns == 0) { "the un-dedup packs without rotation" }
			val job = jobByKey.getValue(placement.key)
			val sourcePage = pages[job.sourcePageIndex]
			val page = packed.pages[placement.pageIndex]
			val pageIndex = pages.size + placement.pageIndex
			// The packer placed the patch's opaque trim at (pageX, pageY), the trim sitting
			// (trimLeft, trimTop) inside the patch: newPagePx = pageX + (oldPagePx - (sourceX + trimLeft)).
			val deltaX = (placement.pageX - job.sourceRect[0] - placement.trimLeft).toFloat()
			val deltaY = (placement.pageY - job.sourceRect[1] - placement.trimTop).toFloat()
			for (drawableId in job.drawableIds) {
				newPageIndexByDrawableId[drawableId] = pageIndex
				remapByDrawableId[drawableId] =
					floatArrayOf(deltaX, deltaY, sourcePage.width.toFloat(), sourcePage.height.toFloat(), page.width.toFloat(), page.height.toFloat())
				duplicatedIds.add(drawableId)
			}
		}
		// A patch the packer could not place keeps its drawables on the source slot.  One with no
		// opaque pixel shares silently, since nothing shows at either placement; one larger than a
		// page even alone is the unrepresentable case the conversion reports.
		val sharedIds =
			packed.skipped
				.filter { skip -> skip.reason == AtlasPackSkipReason.LargerThanPage }
				.flatMap { skip -> jobByKey.getValue(skip.key).drawableIds }
		val builtPages = pages + packed.pages.map { page -> Cmo3Conversion.AtlasPage(PngCodec.write(page), page.width, page.height) }

		val remappedDrawables =
			puppet.drawables.map { drawable ->
				val remap = remapByDrawableId[drawable.id.raw] ?: return@map drawable
				val mesh = drawable.mesh ?: return@map drawable
				val newUvs = FloatArray(mesh.uvs.size)
				var componentIndex = 0
				while (componentIndex + 1 < mesh.uvs.size) {
					newUvs[componentIndex] = (mesh.uvs[componentIndex] * remap[2] + remap[0]) / remap[4]
					newUvs[componentIndex + 1] = (mesh.uvs[componentIndex + 1] * remap[3] + remap[1]) / remap[5]
					componentIndex += 2
				}
				drawable.copy(mesh = DrawableMesh(mesh.positions, newUvs, mesh.indices))
			}
		return Result(
			puppet.copy(drawables = remappedDrawables),
			builtPages,
			newPageIndexByDrawableId,
			duplicatedIds,
			sharedIds,
		)
	}

	/**
	 * The pixels of one patch rect cut out of a decoded page, as their own straight-alpha raster.
	 *
	 * @param RasterImage page The decoded source page.
	 * @param IntArray    rect The patch rect on the page, [x0, y0, x1, y1] with exclusive maxima.
	 * @return ByteArray The patch's RGBA pixels, row-major from its top row.
	 */
	private fun cutPatch(page: RasterImage, rect: IntArray): ByteArray {
		val width = rect[2] - rect[0]
		val height = rect[3] - rect[1]
		val rgba = ByteArray(width * height * 4)
		for (rowIndex in 0 until height) {
			val sourceOffset = ((rect[1] + rowIndex) * page.width + rect[0]) * 4
			page.rgba.copyInto(rgba, rowIndex * width * 4, sourceOffset, sourceOffset + width * 4)
		}
		return rgba
	}
}