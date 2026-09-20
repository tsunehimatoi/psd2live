package org.umamo.format.atlas

/*
 * The rectangle bin packer behind the atlas packer: pure integer geometry with no art types and no
 * pixels, so it is testable on its own and reusable by any other page-composition caller.
 *
 * MaxRects with best-short-side-fit (Jukka Jylanki, "A Thousand Ways to Pack the Bin"): each page
 * keeps a list of maximal free rectangles; a request goes into the free rectangle that leaves the
 * smallest short-side remainder, and every free rectangle the placement overlaps is then split and
 * the contained ones pruned.  Pages are tried in order and a new one opens only when none fits.
 */

/** One rectangle to place: the caller's index plus the page footprint it needs, gutter included. */
internal class RectPackRequest(
	val itemIndex: Int,
	val width: Int,
	val height: Int,
)

/**
 * One rectangle decided before the pack runs: the page it holds and the footprint it keeps there,
 * gutter included.  A seed is occupied before any request is placed, so every free placement packs
 * around it; it is never placed, reported, or turned.
 */
internal class RectPackSeed(
	val pageIndex: Int,
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
)

/** Where one request landed: its page, the footprint's top-left, and whether it was quarter-turned. */
internal class RectPackSlot(
	val itemIndex: Int,
	val pageIndex: Int,
	val x: Int,
	val y: Int,
	val rotated: Boolean,
)

/**
 * The packing outcome.
 *
 * [usedWidths] / [usedHeights] are the extent actually occupied on each page - the maximum right and
 * bottom edge of any footprint - which is what a shrink-to-fit caller crops to.  Because a footprint
 * already carries its gutter on every side, that extent includes the trailing gutter margin.
 */
internal class RectPackLayout(
	val slots: List<RectPackSlot>,
	val oversizedItemIndices: List<Int>,
	val usedWidths: List<Int>,
	val usedHeights: List<Int>,
)

/**
 * Packs footprints onto as many equally-sized pages as they need.
 *
 * Requests are packed in descending max-side order, which is what makes MaxRects behave: the large
 * awkward pieces claim their space while the free list is still coarse, and the small ones fill the
 * remainders.  The caller is responsible for supplying a deterministic request order - this function
 * preserves it exactly and adds no ordering of its own.
 *
 * Seeds go first: a seeded page exists whether or not a request lands on it (a seed on page 2 opens
 * pages 0 and 1 too, so page indices keep their meaning), and its rectangle is taken before the first
 * request is placed.  The caller keeps every seed inside the page - a seed is a footprint the caller
 * already decided to keep, so there is nothing sensible to do with one that does not fit.
 *
 * @param List    requests      The footprints to place, already in packing order.
 * @param Int     pageSize      The square page side every page is packed against, in pixels.
 * @param Boolean allowRotation Whether a request may be quarter-turned to fit.
 * @param List    seeds         Footprints already decided, occupied before any request is placed.
 * @return RectPackLayout The placements, the requests too large for any page, and the used extents.
 */
internal fun packRects(
	requests: List<RectPackRequest>,
	pageSize: Int,
	allowRotation: Boolean,
	seeds: List<RectPackSeed> = emptyList(),
): RectPackLayout {
	require(pageSize > 0) { "pageSize must be positive: $pageSize" }

	val slots = ArrayList<RectPackSlot>(requests.size)
	val oversizedItemIndices = ArrayList<Int>()
	val pages = ArrayList<MaxRectsPage>()

	for (seed in seeds) {
		require(seed.pageIndex >= 0) { "seed page index must not be negative: ${seed.pageIndex}" }
		while (pages.size <= seed.pageIndex) {
			pages.add(MaxRectsPage(pageSize))
		}
		pages[seed.pageIndex].seed(seed.x, seed.y, seed.width, seed.height)
	}

	for (request in requests) {
		// A square page cap means a quarter turn cannot rescue an oversized request: both sides
		// have to fit either way.
		if (request.width > pageSize || request.height > pageSize) {
			oversizedItemIndices.add(request.itemIndex)
			continue
		}
		var placed = false
		for ((pageIndex, page) in pages.withIndex()) {
			val placement = page.place(request.width, request.height, allowRotation)
			if (placement != null) {
				slots.add(RectPackSlot(request.itemIndex, pageIndex, placement.x, placement.y, placement.rotated))
				placed = true
				break
			}
		}
		if (!placed) {
			val freshPage = MaxRectsPage(pageSize)
			val placement =
				checkNotNull(freshPage.place(request.width, request.height, allowRotation)) {
					"request ${request.width}x${request.height} fits the page cap $pageSize but not an empty page"
				}
			pages.add(freshPage)
			slots.add(RectPackSlot(request.itemIndex, pages.size - 1, placement.x, placement.y, placement.rotated))
		}
	}

	return RectPackLayout(
		slots = slots,
		oversizedItemIndices = oversizedItemIndices,
		usedWidths = pages.map { page -> page.usedWidth },
		usedHeights = pages.map { page -> page.usedHeight },
	)
}

/** A chosen position for one request, with the orientation it was accepted at. */
private class MaxRectsPlacement(val x: Int, val y: Int, val rotated: Boolean)

/** One free rectangle in the maximal-rectangles free list. */
private class FreeRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** One page being packed: a square of [pageSize] plus its maximal free rectangles. */
private class MaxRectsPage(private val pageSize: Int) {
	private var freeRects = mutableListOf(FreeRect(0, 0, pageSize, pageSize))

	/** The furthest right edge, in pixels, that any placement on this page has reached. */
	var usedWidth: Int = 0
		private set

	/** The furthest down edge, in pixels, that any placement on this page has reached. */
	var usedHeight: Int = 0
		private set

	/**
	 * Places one footprint if it fits, choosing the free rectangle with the smallest short-side
	 * remainder and breaking ties on the long side.
	 *
	 * @param Int     width         The footprint width.
	 * @param Int     height        The footprint height.
	 * @param Boolean allowRotation Whether the quarter-turned orientation may be considered.
	 * @return MaxRectsPlacement The accepted position, or null when nothing fits.
	 */
	fun place(width: Int, height: Int, allowRotation: Boolean): MaxRectsPlacement? {
		var bestShortSide = Int.MAX_VALUE
		var bestLongSide = Int.MAX_VALUE
		var bestX = -1
		var bestY = -1
		var bestRotated = false

		for (freeRect in freeRects) {
			val orientationCount = if (allowRotation) 2 else 1
			for (orientationIndex in 0 until orientationCount) {
				val rotated = orientationIndex == 1
				val candidateWidth = if (rotated) height else width
				val candidateHeight = if (rotated) width else height
				if (candidateWidth > freeRect.width || candidateHeight > freeRect.height) {
					continue
				}
				val leftoverHorizontal = freeRect.width - candidateWidth
				val leftoverVertical = freeRect.height - candidateHeight
				val shortSide = minOf(leftoverHorizontal, leftoverVertical)
				val longSide = maxOf(leftoverHorizontal, leftoverVertical)
				if (shortSide < bestShortSide || (shortSide == bestShortSide && longSide < bestLongSide)) {
					bestShortSide = shortSide
					bestLongSide = longSide
					bestX = freeRect.x
					bestY = freeRect.y
					bestRotated = rotated
				}
			}
		}

		if (bestX < 0) {
			return null
		}
		val placedWidth = if (bestRotated) height else width
		val placedHeight = if (bestRotated) width else height
		occupy(bestX, bestY, placedWidth, placedHeight)
		usedWidth = maxOf(usedWidth, bestX + placedWidth)
		usedHeight = maxOf(usedHeight, bestY + placedHeight)
		return MaxRectsPlacement(bestX, bestY, bestRotated)
	}

	/**
	 * Takes a footprint decided before the pack: the free list loses it exactly as it loses a
	 * placement, and the used extent grows to cover it, so a page cropped to what it uses still
	 * holds the seed.
	 *
	 * @param Int seedX      The footprint's left edge.
	 * @param Int seedY      The footprint's top edge.
	 * @param Int seedWidth  The footprint's width.
	 * @param Int seedHeight The footprint's height.
	 */
	fun seed(seedX: Int, seedY: Int, seedWidth: Int, seedHeight: Int) {
		occupy(seedX, seedY, seedWidth, seedHeight)
		usedWidth = maxOf(usedWidth, seedX + seedWidth)
		usedHeight = maxOf(usedHeight, seedY + seedHeight)
	}

	/**
	 * Splits every free rectangle the placement overlaps, then drops the ones now contained.
	 *
	 * @param Int placedX      The footprint's left edge.
	 * @param Int placedY      The footprint's top edge.
	 * @param Int placedWidth  The footprint's width.
	 * @param Int placedHeight The footprint's height.
	 */
	private fun occupy(placedX: Int, placedY: Int, placedWidth: Int, placedHeight: Int) {
		val next = ArrayList<FreeRect>(freeRects.size + 4)
		for (freeRect in freeRects) {
			if (!splitFreeRect(freeRect, placedX, placedY, placedWidth, placedHeight, next)) {
				next.add(freeRect)
			}
		}
		freeRects = pruneContained(next)
	}

	/**
	 * Splits one free rectangle around the placed footprint, appending the surviving strips.
	 *
	 * @param FreeRect freeRect The free rectangle to split.
	 * @param Int placedX       The footprint's left edge.
	 * @param Int placedY       The footprint's top edge.
	 * @param Int placedWidth   The footprint's width.
	 * @param Int placedHeight  The footprint's height.
	 * @param MutableList out   Receives the strips left over after the split.
	 * @return Boolean True when the footprint overlapped and the rectangle was consumed.
	 */
	private fun splitFreeRect(
		freeRect: FreeRect,
		placedX: Int,
		placedY: Int,
		placedWidth: Int,
		placedHeight: Int,
		out: MutableList<FreeRect>,
	): Boolean {
		val placedRight = placedX + placedWidth
		val placedBottom = placedY + placedHeight
		val freeRight = freeRect.x + freeRect.width
		val freeBottom = freeRect.y + freeRect.height
		if (placedX >= freeRight || placedRight <= freeRect.x || placedY >= freeBottom || placedBottom <= freeRect.y) {
			return false
		}
		if (placedX < freeRight && placedRight > freeRect.x) {
			if (placedY > freeRect.y && placedY < freeBottom) {
				out.add(FreeRect(freeRect.x, freeRect.y, freeRect.width, placedY - freeRect.y))
			}
			if (placedBottom < freeBottom) {
				out.add(FreeRect(freeRect.x, placedBottom, freeRect.width, freeBottom - placedBottom))
			}
		}
		if (placedY < freeBottom && placedBottom > freeRect.y) {
			if (placedX > freeRect.x && placedX < freeRight) {
				out.add(FreeRect(freeRect.x, freeRect.y, placedX - freeRect.x, freeRect.height))
			}
			if (placedRight < freeRight) {
				out.add(FreeRect(placedRight, freeRect.y, freeRight - placedRight, freeRect.height))
			}
		}
		return true
	}

	/**
	 * Drops every free rectangle wholly contained in another, keeping the list maximal.
	 *
	 * The pairwise sweep is the canonical MaxRects prune.  Duplicates contain each other, and the
	 * first check below fires for that case, so the EARLIER index is the one dropped; the survivors
	 * keep their relative order either way, and page layout is only deterministic if this is.
	 *
	 * @param MutableList candidates The free list after splitting, possibly with contained entries.
	 * @return MutableList The maximal free list.
	 */
	private fun pruneContained(candidates: MutableList<FreeRect>): MutableList<FreeRect> {
		var candidateIndex = 0
		while (candidateIndex < candidates.size) {
			var otherIndex = candidateIndex + 1
			var removedCandidate = false
			while (otherIndex < candidates.size) {
				if (contains(candidates[otherIndex], candidates[candidateIndex])) {
					candidates.removeAt(candidateIndex)
					removedCandidate = true
					break
				}
				if (contains(candidates[candidateIndex], candidates[otherIndex])) {
					candidates.removeAt(otherIndex)
					continue
				}
				otherIndex++
			}
			if (!removedCandidate) {
				candidateIndex++
			}
		}
		return candidates
	}

	/**
	 * Whether [outer] wholly covers [inner].
	 *
	 * @param FreeRect outer The candidate containing rectangle.
	 * @param FreeRect inner The candidate contained rectangle.
	 * @return Boolean True when every corner of [inner] lies within or on the border of [outer].
	 */
	private fun contains(outer: FreeRect, inner: FreeRect): Boolean =
		inner.x >= outer.x &&
			inner.y >= outer.y &&
			inner.x + inner.width <= outer.x + outer.width &&
			inner.y + inner.height <= outer.y + outer.height
}