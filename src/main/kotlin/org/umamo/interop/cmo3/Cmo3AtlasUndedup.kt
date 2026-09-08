package org.umamo.interop.cmo3

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
 * synthesized extra page and its drawables' uvs remapped there, so every slot downstream carries
 * a single canvas placement and the image chain builds one correctly-placed material per twin.
 */
internal object Cmo3AtlasUndedup {
	/** Transparent spacing between packed patches (and the page border), like a packer's gutter. */
	private const val PACK_GUTTER = 2

	/** The un-deduplicated inputs for the fresh-graph pipeline. */
	internal class Result(
		val puppet: PuppetModel,
		val pages: List<Cmo3Conversion.AtlasPage>,
		val pageIndexByDrawableId: Map<String, Int>,
		/** The drawables whose uvs were remapped onto a synthesized page, for tests and reports. */
		val duplicatedDrawableIds: List<String>,
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

		// Shelf-pack the jobs onto extra pages.  A page's working side comes from the remaining
		// jobs' padded area (with slack for shelf waste), so a handful of small twins packs onto a
		// small page rather than spreading across the source pages' full width.
		val pageCap = maxOf(1024, pages.maxOf { page -> maxOf(page.width, page.height) })
		val sortedJobs = jobs.sortedByDescending { job -> job.sourceRect[3] - job.sourceRect[1] }
		val extraPages = ArrayList<PackedPage>()
		for ((jobIndex, job) in sortedJobs.withIndex()) {
			val width = job.sourceRect[2] - job.sourceRect[0] + 2 * PACK_GUTTER
			val height = job.sourceRect[3] - job.sourceRect[1] + 2 * PACK_GUTTER
			var placed = false
			for (packedPage in extraPages) {
				if (packedPage.tryPlace(job, width, height)) {
					placed = true
					break
				}
			}
			if (!placed) {
				var remainingArea = 0L
				for (remainingIndex in jobIndex until sortedJobs.size) {
					val remaining = sortedJobs[remainingIndex]
					remainingArea += (remaining.sourceRect[2] - remaining.sourceRect[0] + 2L * PACK_GUTTER) *
						(remaining.sourceRect[3] - remaining.sourceRect[1] + 2L * PACK_GUTTER)
				}
				var side = 64
				val targetSide = kotlin.math.sqrt(remainingArea.toDouble() * 1.3).toInt()
				while (side < targetSide || side < width || side < height) {
					side *= 2
				}
				val fresh = PackedPage(minOf(side, pageCap))
				check(fresh.tryPlace(job, width, height)) { "patch ${width}x$height exceeds the page cap $pageCap" }
				extraPages.add(fresh)
			}
		}

		// Compose the extra pages' pixels and remap the duplicated drawables' uvs.
		val decodedPages = HashMap<Int, RasterImage>()
		val newPageIndexByDrawableId = HashMap(pageIndexByDrawableId)
		val newUvOriginByDrawableId = HashMap<String, FloatArray>()
		val duplicatedIds = ArrayList<String>()
		val builtPages = ArrayList<Cmo3Conversion.AtlasPage>(pages)
		for (packedPage in extraPages) {
			val pageIndex = builtPages.size
			val pageSize = packedPage.usedSize()
			val rgba = ByteArray(pageSize * pageSize * 4)
			for (placement in packedPage.placements) {
				val job = placement.job
				val sourcePage = pages[job.sourcePageIndex]
				val decoded =
					decodedPages.getOrPut(job.sourcePageIndex) { PngCodec.read(sourcePage.pngBytes) }
				val sourceX = job.sourceRect[0]
				val sourceY = job.sourceRect[1]
				val rectWidth = job.sourceRect[2] - sourceX
				val rectHeight = job.sourceRect[3] - sourceY
				val destinationX = placement.x + PACK_GUTTER
				val destinationY = placement.y + PACK_GUTTER
				for (rowIndex in 0 until rectHeight) {
					val sourceOffset = ((sourceY + rowIndex) * decoded.width + sourceX) * 4
					val destinationOffset = ((destinationY + rowIndex) * pageSize + destinationX) * 4
					decoded.rgba.copyInto(rgba, destinationOffset, sourceOffset, sourceOffset + rectWidth * 4)
				}
				for (drawableId in job.drawableIds) {
					newPageIndexByDrawableId[drawableId] = pageIndex
					// Remap parameters: newPagePx = destination + (oldPagePx - sourceOrigin).
					newUvOriginByDrawableId[drawableId] =
						floatArrayOf(
							(destinationX - sourceX).toFloat(),
							(destinationY - sourceY).toFloat(),
							sourcePage.width.toFloat(),
							sourcePage.height.toFloat(),
							pageSize.toFloat(),
						)
					duplicatedIds.add(drawableId)
				}
			}
			builtPages.add(Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, rgba)), pageSize, pageSize))
		}

		val remappedDrawables =
			puppet.drawables.map { drawable ->
				val remap = newUvOriginByDrawableId[drawable.id.raw] ?: return@map drawable
				val mesh = drawable.mesh ?: return@map drawable
				val newUvs = FloatArray(mesh.uvs.size)
				var componentIndex = 0
				while (componentIndex + 1 < mesh.uvs.size) {
					newUvs[componentIndex] = (mesh.uvs[componentIndex] * remap[2] + remap[0]) / remap[4]
					newUvs[componentIndex + 1] = (mesh.uvs[componentIndex + 1] * remap[3] + remap[1]) / remap[4]
					componentIndex += 2
				}
				drawable.copy(mesh = DrawableMesh(mesh.positions, newUvs, mesh.indices))
			}
		return Result(
			puppet.copy(drawables = remappedDrawables),
			builtPages,
			newPageIndexByDrawableId,
			duplicatedIds,
		)
	}

	/** One extra page being shelf-packed: fixed width, growing rows of placements. */
	private class PackedPage(private val cap: Int) {
		class Placement(val job: DuplicationJob, val x: Int, val y: Int)

		val placements = ArrayList<Placement>()
		private var shelfX = PACK_GUTTER
		private var shelfY = PACK_GUTTER
		private var shelfHeight = 0
		private var maxX = 0
		private var maxY = 0

		/**
		 * Places one job if it fits, growing shelves downward.
		 *
		 * @param DuplicationJob job    The job to place.
		 * @param Int            width  The padded patch width.
		 * @param Int            height The padded patch height.
		 * @return Boolean True when placed.
		 */
		fun tryPlace(job: DuplicationJob, width: Int, height: Int): Boolean {
			if (width > cap || height > cap) {
				return false
			}
			if (shelfX + width > cap) {
				val nextY = shelfY + shelfHeight
				if (nextY + height > cap) {
					return false
				}
				shelfX = PACK_GUTTER
				shelfY = nextY
				shelfHeight = 0
			}
			if (shelfY + height > cap) {
				return false
			}
			placements.add(Placement(job, shelfX, shelfY))
			maxX = maxOf(maxX, shelfX + width)
			maxY = maxOf(maxY, shelfY + height)
			shelfX += width
			shelfHeight = maxOf(shelfHeight, height)
			return true
		}

		/** The page dimension actually needed, rounded up to a power of two. */
		fun usedSize(): Int {
			var size = 64
			while (size < maxOf(maxX, maxY) + PACK_GUTTER && size < cap) {
				size *= 2
			}
			return size
		}
	}
}