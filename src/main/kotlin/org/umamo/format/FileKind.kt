package org.umamo.format

/**
 * The file families Umamo understands, plus the art-source formats it ingests.
 *
 * Modelled as a `sealed interface` so a `when` over kinds is exhaustive (the compiler rejects an
 * unhandled case) - the same enforcement applied to [Cmo3Version]. Each kind declares its
 * capabilities ([readable]/[writable]), so dispatch and UI can reason about support without a
 * scattered list of booleans, and its [extension], so nothing else spells the extension by hand.
 */
sealed interface FileKind {
	/** Conventional file extension without the dot, e.g. `"cmo3"`. */
	val extension: String
	val readable: Boolean
	val writable: Boolean

	// --- Umamo UMA!~ ---
	data object Uma : FileKind {
		override val extension = "uma"
		override val readable = true
		override val writable = true
	}

	// --- Live2D Cubism CMO3 Puppet Model ---
	data object Cmo3 : FileKind {
		override val extension = "cmo3"
		override val readable = true
		override val writable = true
	}

	// --- Live2D Cubism MOC3 Puppet Model ---
	data object Moc3 : FileKind {
		override val extension = "moc3"
		override val readable = true
		override val writable = true
	}

	// --- Live2D Cubism MOC3 Puppet Model Sidecar Files - model3/physics3/cdi3/pose3/exp3/motion3/userdata3. ---
	data object Json : FileKind {
		override val extension = "json"
		override val readable = true
		override val writable = true
	}

	// --- Adobe Photoshop PSD Layered Artwork ---
	data object Psd : FileKind {
		override val extension = "psd"
		override val readable = true
		override val writable = false
	}

	// --- Clip Studio Paint CLIP Layered Artwork ---
	data object Clip : FileKind {
		override val extension = "clip"
		override val readable = true
		override val writable = false
	}

	// --- Krita KRA Layered Artwork ---
	data object Kra : FileKind {
		override val extension = "kra"
		override val readable = true
		override val writable = false
	}

	// --- PNG Raster Artwork ---
	data object Png : FileKind {
		override val extension = "png"
		override val readable = true
		override val writable = true
	}

	// --- BMP Raster Artwork ---
	data object Bmp : FileKind {
		override val extension = "bmp"
		override val readable = true
		override val writable = true
	}

	// --- JPEG Raster Artwork ---
	data object Jpeg : FileKind {
		override val extension = "jpg"
		override val readable = true
		override val writable = false
	}

	// --- WEBP Raster Artwork ---
	data object WebP : FileKind {
		override val extension = "webp"
		override val readable = true
		override val writable = false
	}

	// --- TIFF Raster Artwork ---
	data object Tiff : FileKind {
		override val extension = "tiff"
		override val readable = true
		override val writable = false
	}
}