package io.github.psd2live.core.interior

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.umamo.runtime.model.DrawableMesh

/** Stable rest-topology identity; independent of the removed dense-grid experiment. */
internal object InteriorMeshStamp {
	fun of(mesh: DrawableMesh): String {
		val hash = MessageDigest.getInstance("SHA-256")
		val bytes = ByteBuffer.allocate(8 + mesh.positions.size * 4 + mesh.indices.size * 4)
		bytes.putInt(mesh.positions.size).putInt(mesh.indices.size)
		mesh.positions.forEach { bytes.putFloat(it) }
		mesh.indices.forEach { bytes.putInt(it) }
		return hash.digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
	}
}
