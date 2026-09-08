package org.umamo.render

/** A decoded RGBA image (top row first), ready for GL upload. */
class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int)