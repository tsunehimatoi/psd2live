package io.github.psd2live.algorithms.binding

import org.umamo.edit.VertexSource

/** Transfers x/y vertex values using captured index or barycentric correspondences. */
object BindingVertices {
	fun transfer(values: FloatArray, sources: List<VertexSource>): FloatArray = FloatArray(sources.size * 2) { index ->
		val component = index % 2
		when (val source = sources[index / 2]) {
			is VertexSource.FromOld -> values[source.oldIndex * 2 + component]
			is VertexSource.BarycentricOf -> values[source.oldA * 2 + component] * source.wa +
				values[source.oldB * 2 + component] * source.wb + values[source.oldC * 2 + component] * source.wc
			else -> error("Unsupported art primitive vertex source")
		}
	}

}
