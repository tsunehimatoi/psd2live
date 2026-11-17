package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * A [RigIR] as separately stored objects, for a store that shares the unchanged objects of successive versions of a
 * rig: one frame (everything but the deformers and the meshes) and one object per deformer and per mesh, in rig order.
 *
 * Each object is self-describing - a magic number, the [RigIrBinary] version it was written with and its [Kind] -
 * and exact like [RigIrBinary]: floats keep their raw bits. Unlike that cache encoding, stored objects stay readable:
 * objects of every version from [MIN_VERSION] to [RigIrBinary.VERSION] decode, and anything else - a foreign,
 * truncated or newer object - is rejected with [IOException].
 */
public object RigIrObjects {
	/** The oldest [RigIrBinary] version a stored object may have. */
	public const val MIN_VERSION: Int = 4
	private const val MAGIC = 0x5052494F // "PRIO"

	public enum class Kind { FRAME, DEFORMER, MESH }

	/** A rig's objects: [frame] holds everything but [deformers] and [meshes], each in the rig's order. */
	public class Objects(public val frame: ByteArray, public val deformers: List<ByteArray>, public val meshes: List<ByteArray>)

	// Keep representable inputs usable by existing workers; new motion channels must never be dropped to downgrade.
	public fun split(ir: RigIR): Objects = Objects(
		encode(Kind.FRAME, RigIrBinary.encodingVersion(ir)) { it.rig(ir.copy(deformers = emptyList(), meshes = emptyList())) },
		ir.deformers.map { deformer -> encode(Kind.DEFORMER) { it.deformer(deformer) } },
		ir.meshes.map { mesh -> encode(Kind.MESH) { it.mesh(mesh) } },
	)

	public fun join(objects: Objects): RigIR = decode(objects.frame, Kind.FRAME) { it.rig() }.copy(
		deformers = objects.deformers.map { bytes -> decode(bytes, Kind.DEFORMER) { it.deformer() } },
		meshes = objects.meshes.map { bytes -> decode(bytes, Kind.MESH) { it.mesh() } },
	)

	private fun encode(kind: Kind, version: Int = 6, write: (RigIrBinary.Writer) -> Unit): ByteArray {
		val bytes = ByteArrayOutputStream(1 shl 12)
		DataOutputStream(bytes).use { out ->
			out.writeInt(MAGIC); out.writeInt(version); out.writeInt(kind.ordinal)
			write(RigIrBinary.Writer(out, version))
		}
		return bytes.toByteArray()
	}

	private fun <T> decode(bytes: ByteArray, kind: Kind, read: (RigIrBinary.Reader) -> T): T {
		val input = DataInputStream(bytes.inputStream())
		try {
			if (input.readInt() != MAGIC) throw IOException("Not a stored rig object")
			val version = input.readInt()
			if (version !in MIN_VERSION..RigIrBinary.VERSION) throw IOException("Unsupported stored rig object version $version")
			if (input.readInt() != kind.ordinal) throw IOException("Stored rig object is not a ${kind.name.lowercase()}")
			val value = read(RigIrBinary.Reader(input, version))
			if (input.read() != -1) throw IOException("Trailing bytes after stored rig object")
			return value
		} catch (failure: java.io.EOFException) {
			throw IOException("Truncated stored rig object", failure)
		} catch (failure: IllegalArgumentException) {
			throw IOException("Invalid stored rig object", failure)
		} catch (failure: IndexOutOfBoundsException) {
			throw IOException("Invalid stored rig object", failure)
		}
	}
}
