package io.github.psd2live.core

import org.umamo.edit.VertexSource
import org.umamo.edit.withParameterCreated
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.github.psd2live.core.legacy.RigGenerationResidual

class RigGenerationResidualTest {
	private val meshId = DrawableId("mesh")
	private val parameter = ParameterId("ParamP")

	private fun model(extra: Float, opacity: Float = 1f): PuppetModel {
		val grid = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))), listOf(
			KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))),
			KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(5f + extra, 0f, 5f, 0f, 5f, 0f))),
		))
		val drawable = Drawable(meshId, "Mesh", null, BlendMode.Normal, emptyList(),
			DrawableMesh(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)), null,
		).copy(geometryGrid = grid, opacity = opacity)
		return PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = listOf(drawable),
			rootChildren = emptyList(), rootPartId = null).withParameterCreated(parameter, "P")
	}

	@Test fun geometryDeltaIsTheAuthoredChangePerCell() {
		assertNull(RigGenerationResidual.geometryDelta(model(0f), model(0f), meshId))
		val delta = requireNotNull(RigGenerationResidual.geometryDelta(model(1f), model(0f), meshId))
		assertEquals(listOf(parameter), delta.axes.map { it.parameterId })
		val cells = delta.cells.associate { it.coordinate[0] to it.form.positionDeltas }
		assertContentEquals(FloatArray(6), cells.getValue(0))
		assertContentEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f), cells.getValue(1).map { Math.round(it * 1000) / 1000f }.toFloatArray())
		// Moved onto another inventory: a vertex in the middle of the triangle takes a third of it.
		val moved = requireNotNull(RigGenerationResidual.geometryDelta(model(1f), model(0f), meshId,
			listOf(VertexSource.FromOld(0), VertexSource.BarycentricOf(0, 1, 2, 1f / 3, 1f / 3, 1f / 3))))
		val at = moved.cells.single { it.coordinate[0] == 1 }.form.positionDeltas
		assertEquals(4, at.size)
		assertEquals(1f, at[0], 1e-3f); assertEquals(1f / 3, at[2], 1e-3f)
	}

	@Test fun channelsDeltaIsTheAuthoredChange() {
		fun opacity(atOne: Float) = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(
			listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
			listOf(KeyformCell(intArrayOf(0), ChannelValue.Scalar(1f)), KeyformCell(intArrayOf(1), ChannelValue.Scalar(atOne))))))
		val authored = model(0f).let { m -> m.copy(drawables = m.drawables.map { it.copy(channelGrids = opacity(0.25f)) }) }
		val generated = model(0f).let { m -> m.copy(drawables = m.drawables.map { it.copy(channelGrids = opacity(0.5f)) }) }
		val delta = RigGenerationResidual.channelsDelta(authored, authored.drawables.single(), generated.drawables.single())
		val cells = delta.gridsByChannel.getValue(FormChannel.OPACITY).cells.associate { it.coordinate[0] to (it.form as ChannelValue.Scalar).value }
		assertEquals(0f, cells.getValue(0), 1e-6f)
		assertEquals(-0.25f, cells.getValue(1), 1e-6f)
	}
}
