package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * What a runtime bundle's assets hash to, with the texture pages its manifest lists in page order.
 * Two bundles whose non-texture assets hash alike share a moc, physics and motions, so the live model
 * can keep them and only swap the pages that differ.
 */
internal class CubismBundleFingerprint private constructor(
	val manifestPath: String,
	/** Content hash of every asset by path. */
	val assetHashes: Map<String, String>,
	/** Asset paths of the manifest's texture pages, by page index. */
	val texturePaths: List<String>,
	/** The asset byte arrays that were hashed, so an unchanged array is not hashed again. */
	private val hashedArrays: Map<String, Pair<ByteArray, String>>,
) {
	fun textureHash(page: Int): String? = texturePaths.getOrNull(page)?.let(assetHashes::get)

	companion object {
		fun of(bundle: CubismRuntimeBundle, previous: CubismBundleFingerprint? = null): CubismBundleFingerprint {
			val hashed = LinkedHashMap<String, Pair<ByteArray, String>>(bundle.assets.size)
			for (asset in bundle.assets) {
				val known = previous?.hashedArrays?.get(asset.path)
				hashed[asset.path] = if (known != null && known.first === asset.bytes) known else asset.bytes to sha256(asset.bytes)
			}
			val manifest = bundle.assets.first { it.path == bundle.manifestPath }.bytes.decodeToString()
			return CubismBundleFingerprint(
				manifestPath = bundle.manifestPath,
				assetHashes = hashed.mapValues { it.value.second },
				texturePaths = cubismManifestTexturePaths(bundle.manifestPath, manifest),
				hashedArrays = hashed,
			)
		}

		private fun sha256(bytes: ByteArray): String {
			val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
			return digest.joinToString("") { "%02x".format(it) }
		}
	}
}

/** How the live native model follows a new runtime bundle. */
internal sealed interface CubismPreviewReload {
	/** Same bytes: keep the model as it is. */
	data object Unchanged : CubismPreviewReload

	/** Only these texture pages changed: upload them into the live models. */
	data class Textures(val pages: List<Int>) : CubismPreviewReload

	/** The moc, physics, motions or page layout changed: rebuild each live model from memory in place. */
	data object Recreate : CubismPreviewReload

	/** Nothing live to update, or a native library without the in-memory entry points: load from scratch. */
	data object Full : CubismPreviewReload
}

internal fun planCubismPreviewReload(
	previous: CubismBundleFingerprint?,
	next: CubismBundleFingerprint,
	memoryModels: Boolean,
	textureReplace: Boolean,
): CubismPreviewReload {
	// Without in-memory models the previous file-based reload stays exactly as it was.
	if (previous == null || !memoryModels) return CubismPreviewReload.Full
	if (previous.assetHashes == next.assetHashes && previous.manifestPath == next.manifestPath) {
		return CubismPreviewReload.Unchanged
	}
	val sameLayout = previous.manifestPath == next.manifestPath &&
		previous.texturePaths == next.texturePaths &&
		previous.assetHashes.keys == next.assetHashes.keys
	val textures = next.texturePaths.toSet()
	val otherAssetsSame = sameLayout && next.assetHashes.all { (path, hash) -> path in textures || previous.assetHashes[path] == hash }
	if (!otherAssetsSame || !textureReplace) return CubismPreviewReload.Recreate
	val pages = next.texturePaths.indices.filter { previous.textureHash(it) != next.textureHash(it) }
	return if (pages.isEmpty()) CubismPreviewReload.Unchanged else CubismPreviewReload.Textures(pages)
}

/** Texture asset paths of a model3 manifest, resolved against the manifest's directory, by page index. */
internal fun cubismManifestTexturePaths(manifestPath: String, manifest: String): List<String> {
	val textures = runCatching {
		kotlinx.serialization.json.Json.parseToJsonElement(manifest).jsonObject["FileReferences"]?.jsonObject?.get("Textures") as? JsonArray
	}.getOrNull() ?: return emptyList()
	val home = manifestPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
	return textures.map { home + normalizeBundlePath(it.jsonPrimitive.content) }
}

private fun normalizeBundlePath(path: String): String {
	var result = path.replace('\\', '/')
	while (result.startsWith("./")) result = result.removePrefix("./")
	return result
}

/**
 * The native renderer's in-memory transport (`Live2D_CreateModelFromMemory`): every byte of [bundle]
 * behind a small little-endian envelope, so no preview reload writes a temporary directory.
 */
internal fun encodeCubismPreviewBundle(bundle: CubismRuntimeBundle): ByteArray {
	val magic = "QDPREVIEW".encodeToByteArray()
	val manifest = bundle.manifestPath.encodeToByteArray()
	val paths = bundle.assets.map { it.path.encodeToByteArray() }
	var size = magic.size.toLong() + 12 + manifest.size
	for ((index, asset) in bundle.assets.withIndex()) size += 12L + paths[index].size + asset.bytes.size
	require(size <= Int.MAX_VALUE - 8) { "Cubism preview bundle is too large to pass in memory" }
	val buffer = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
	buffer.put(magic)
	buffer.putInt(1)
	buffer.putInt(manifest.size)
	buffer.putInt(bundle.assets.size)
	buffer.put(manifest)
	for ((index, asset) in bundle.assets.withIndex()) {
		buffer.putInt(paths[index].size)
		buffer.putLong(asset.bytes.size.toLong())
		buffer.put(paths[index])
		buffer.put(asset.bytes)
	}
	return buffer.array()
}

/** MOC3 preserves parameter order but may shorten and disambiguate their IDs to fit its 64-byte records. */
internal fun cubismWrittenParameterIds(bundle: CubismRuntimeBundle,
                                      parameters: List<org.umamo.runtime.model.ParameterId>): Map<org.umamo.runtime.model.ParameterId, String> {
    if (parameters.isEmpty()) return emptyMap()
    val manifest = org.umamo.format.moc3.Moc3.readModel3(bundle.assets.single { it.path == bundle.manifestPath }.bytes.decodeToString())
    val directory = bundle.manifestPath.substringBeforeLast('/', "")
    val path = listOf(directory, manifest.fileReferences.moc).filter { it.isNotEmpty() }.joinToString("/")
    val bytes = bundle.assets.single { it.path == path }.bytes
    val written = org.umamo.format.moc3.moc.MocCodec.read(bytes).parameters()
    require(written.size == parameters.size) { "Cubism parameter layout differs from the preview model" }
    return parameters.zip(written).associate { (id, parameter) -> id to parameter.id }
}
