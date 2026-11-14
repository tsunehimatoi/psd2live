package io.github.psd2live.core.modifier

import java.nio.file.Files
import java.nio.file.Path

/** Serialize only resource filesystem operations, never request/store/pool locks. */
internal class ModifierWorkerResourceFiles(val directory: Path,
                                         private val deleteTree: (Path) -> Unit = ModifierPluginStore::deleteTree) : AutoCloseable {
    private val hashes = HashSet<String>()
    private var closed = false

    @Synchronized fun contains(hash: String): Boolean = !closed && hash in hashes

    @Synchronized fun write(hash: String, bytes: ByteArray): Boolean {
        check(!closed) { "Plugin worker resources are closed" }
        if (hash in hashes) return false
        val path = directory.resolve(hash)
        val written = !Files.exists(path)
        if (written) Files.write(path, bytes)
        hashes.add(hash)
        return written
    }

    @Synchronized fun clear() {
        hashes.forEach { Files.deleteIfExists(directory.resolve(it)) }
        hashes.clear()
    }

    @Synchronized override fun close() {
        closed = true
        // Keep failures observable and allow a later close to retry the remaining tree.
        deleteTree(directory)
        hashes.clear()
    }
}
