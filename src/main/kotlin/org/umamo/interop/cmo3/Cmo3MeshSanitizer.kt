package org.umamo.interop.cmo3

import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/** Cubism's editable mesh rejects self-edges produced by zero-area triangles. */
internal object Cmo3MeshSanitizer {
	fun cleanIndices(mesh: DrawableMesh): IntArray {
		val clean = ArrayList<Int>(mesh.indices.size)
		for (start in 0 until mesh.indices.size - 2 step 3) {
			val a = mesh.indices[start]
			val b = mesh.indices[start + 1]
			val c = mesh.indices[start + 2]
			if (a == b || b == c || c == a || a !in 0 until mesh.vertexCount || b !in 0 until mesh.vertexCount || c !in 0 until mesh.vertexCount) continue
			val ax = mesh.positions[a * 2].toDouble()
			val ay = mesh.positions[a * 2 + 1].toDouble()
			val bx = mesh.positions[b * 2].toDouble()
			val by = mesh.positions[b * 2 + 1].toDouble()
			val cx = mesh.positions[c * 2].toDouble()
			val cy = mesh.positions[c * 2 + 1].toDouble()
			val twiceArea = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
			if (!twiceArea.isFinite() || twiceArea == 0.0) continue
			clean.add(a)
			clean.add(b)
			clean.add(c)
		}
		return if (clean.size == mesh.indices.size) mesh.indices else clean.toIntArray()
	}

	/** Works on an export copy, preserving the editing model and all vertex/keyform indices. */
	fun sanitize(puppet: PuppetModel): PuppetModel {
		var changed = false
		val drawables = puppet.drawables.map { drawable ->
			val mesh = drawable.mesh ?: return@map drawable
			val indices = cleanIndices(mesh)
			if (indices === mesh.indices) drawable else {
				changed = true
				drawable.copy(mesh = DrawableMesh(mesh.positions, mesh.uvs, indices))
			}
		}
		return if (changed) puppet.copy(drawables = drawables) else puppet
	}
}
