package org.umamo.format.binary

import okio.ByteString.Companion.toByteString

/*
 * The content hash the document records for what it read - a file's bytes, a layer's pixels - and
 * that the re-import compares against: the watcher to skip a save that changed nothing, the matcher to
 * recognize a renamed layer whose pixels did not change.  One algorithm, named here and nowhere else:
 * SHA-256 as lowercase hex, which is what the native format persists (docs/plan/uma-format.md D6).
 * :storage's contentHashOfFile is the streaming form of the same digest for a file on disk.
 */

/**
 * The content hash of [bytes].
 *
 * @param ByteArray bytes The bytes.
 * @return String The digest as lowercase hex.
 */
fun contentHashOf(bytes: ByteArray): String = bytes.toByteString().sha256().hex()