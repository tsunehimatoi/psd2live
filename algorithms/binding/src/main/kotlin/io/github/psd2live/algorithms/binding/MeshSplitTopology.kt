package io.github.psd2live.algorithms.binding

/** The connected triangle islands shared by mesh modifiers and source-layer splitting. */
object MeshSplitTopology {
    class Island(val vertices: IntArray, val triangleOffsets: IntArray)

    /** Returns null for invalid indices. Isolated vertices without triangles are left to the caller. */
    fun connectedIslands(vertexCount: Int, indices: IntArray, checkpoint: () -> Unit = {}): List<Island>? {
        if (vertexCount < 0 || indices.size % 3 != 0) return null
        val parents = IntArray(vertexCount) { it }
        val used = BooleanArray(vertexCount)

        fun root(vertex: Int): Int {
            var current = vertex
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }

        for (offset in indices.indices step 3) {
            if (offset % 768 == 0) checkpoint()
            val a = indices[offset]
            val b = indices[offset + 1]
            val c = indices[offset + 2]
            if (a !in parents.indices || b !in parents.indices || c !in parents.indices) return null
            parents[root(b)] = root(a)
            parents[root(c)] = root(a)
            used[a] = true
            used[b] = true
            used[c] = true
        }

        val vertices = linkedMapOf<Int, MutableList<Int>>()
        for (vertex in used.indices) {
            if (vertex % 256 == 0) checkpoint()
            if (used[vertex]) vertices.getOrPut(root(vertex)) { mutableListOf() }.add(vertex)
        }
        if (vertices.size < 2) return emptyList()

        // Preserve the modifier's triangle order. The lists above keep original vertex indices sorted.
        val triangles = linkedMapOf<Int, MutableList<Int>>()
        for (offset in indices.indices step 3) {
            if (offset % 768 == 0) checkpoint()
            triangles.getOrPut(root(indices[offset])) { mutableListOf() }.add(offset)
        }
        return triangles.map { (root, offsets) ->
            checkpoint()
            Island(vertices.getValue(root).toIntArray(), offsets.toIntArray())
        }
    }
}
