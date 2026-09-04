package io.github.psd2live.core

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RigEditOverlayTest {

	@Test
	fun `target kind parses aliases correctly`() {
		assertEquals(RigTargetKind.ART_MESH, RigTargetKind.fromString("mesh"))
		assertEquals(RigTargetKind.ART_MESH, RigTargetKind.fromString("drawable"))
		assertEquals(RigTargetKind.ART_MESH, RigTargetKind.fromString("artmesh"))
		assertEquals(RigTargetKind.WARP_DEFORMER, RigTargetKind.fromString("warp"))
		assertEquals(RigTargetKind.WARP_DEFORMER, RigTargetKind.fromString("warp_deformer"))
		assertEquals(RigTargetKind.ROTATION_DEFORMER, RigTargetKind.fromString("rotation"))
		assertEquals(RigTargetKind.ROTATION_DEFORMER, RigTargetKind.fromString("rotation_deformer"))
		assertEquals(RigTargetKind.PART, RigTargetKind.fromString("part"))
		assertEquals(RigTargetKind.GLUE, RigTargetKind.fromString("glue"))
	}

	@Test
	fun `applies parameter create, update and delete overlays`() {
		val base = createBasePuppet()
		assertEquals(listOf("ParamAngleX"), base.parameters.map { it.id.raw })

		// Create
		val overlayCreate = RigEditOverlay(
			parameterEdits = listOf(
				RigParameterEdit(
					id = "ParamAngleY",
					name = "Angle Y",
					min = -30f,
					max = 30f,
					default = 0f,
				),
			),
		)
		val withParam = overlayCreate.applyTo(base)
		assertEquals(setOf("ParamAngleX", "ParamAngleY"), withParam.parameters.map { it.id.raw }.toSet())

		// Update
		val overlayUpdate = RigEditOverlay(
			parameterEdits = listOf(
				RigParameterEdit(
					id = "ParamAngleX",
					name = "Angle X Renamed",
					min = -45f,
					max = 45f,
					default = 5f,
				),
			),
		)
		val withUpdate = overlayUpdate.applyTo(base)
		val updatedParam = withUpdate.parameters.single { it.id.raw == "ParamAngleX" }
		assertEquals("Angle X Renamed", updatedParam.name)
		assertEquals(-45f, updatedParam.min)
		assertEquals(45f, updatedParam.max)
		assertEquals(5f, updatedParam.default)

		// Delete
		val overlayDelete = RigEditOverlay(
			deletedParameterIds = setOf("ParamAngleX"),
		)
		val withDelete = overlayDelete.applyTo(base)
		assertTrue(withDelete.parameters.none { it.id.raw == "ParamAngleX" })
	}

	@Test
	fun `sets, copies and deletes keyforms on warp deformer`() {
		val base = createBasePuppet()
		val target = RigTargetRef(RigTargetKind.WARP_DEFORMER, "warp_test")

		// 1. Set keyform at ParamAngleX = -30
		val pointsNegative = listOf(-10f, 0f, 10f, 0f, -10f, 20f, 10f, 20f)
		val overlaySet = RigEditOverlay(
			keyformSetEdits = listOf(
				RigKeyformSetEdit(
					target = target,
					coordinate = mapOf("ParamAngleX" to -30f),
					geometry = RigKeyformGeometryEdit(controlPoints = pointsNegative),
				),
			),
		)
		val withSet = overlaySet.applyTo(base)
		val warp = withSet.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw == "warp_test" }
		assertNotNull(warp.geometryGrid)
		val grid = warp.geometryGrid!!
		assertEquals(1, grid.axes.size)
		assertEquals("ParamAngleX", grid.axes.single().parameterId.raw)
		assertTrue(grid.axes.single().keys.toList().contains(-30f))

		// 2. Copy keyform from -30 to +30
		val overlayCopy = overlaySet.copy(
			keyformCopyEdits = listOf(
				RigKeyformCopyEdit(
					sourceTarget = target,
					sourceCoordinate = mapOf("ParamAngleX" to -30f),
					destinationTarget = target,
					destinationCoordinate = mapOf("ParamAngleX" to 30f),
				),
			),
		)
		val withCopy = overlayCopy.applyTo(base)
		val warpAfterCopy = withCopy.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw == "warp_test" }
		val copyGrid = warpAfterCopy.geometryGrid!!
		assertTrue(copyGrid.axes.single().keys.toList().contains(-30f))
		assertTrue(copyGrid.axes.single().keys.toList().contains(30f))

		// 3. Delete keyform at +30
		val overlayDelete = overlayCopy.copy(
			keyformDeleteEdits = listOf(
				RigKeyformDeleteEdit(
					target = target,
					parameterId = "ParamAngleX",
					keyValue = 30f,
				),
			),
		)
		val withDelete = overlayDelete.applyTo(base)
		val warpAfterDelete = withDelete.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw == "warp_test" }
		val deleteGrid = warpAfterDelete.geometryGrid!!
		assertTrue(deleteGrid.axes.single().keys.toList().contains(-30f))
		assertTrue(!deleteGrid.axes.single().keys.toList().contains(30f))
	}

	@Test
	fun `sets keyform channels on drawable`() {
		val base = createBasePuppet()
		val target = RigTargetRef(RigTargetKind.ART_MESH, "mesh_body")

		val overlayChannel = RigEditOverlay(
			keyformSetEdits = listOf(
				RigKeyformSetEdit(
					target = target,
					coordinate = mapOf("ParamAngleX" to 30f),
					channels = RigKeyformChannelsEdit(
						opacity = 0.5f,
						drawOrder = 100f,
					),
				),
			),
		)
		val result = overlayChannel.applyTo(base)
		val drawable = result.drawables.single { it.id.raw == "mesh_body" }
		val opacityTrack = drawable.channelGrids[FormChannel.OPACITY]
		assertNotNull(opacityTrack)
		assertEquals("ParamAngleX", opacityTrack.axes.single().parameterId.raw)
		assertTrue(opacityTrack.axes.single().keys.toList().contains(30f))
	}

	private fun createBasePuppet(): PuppetModel {
		val param = Parameter(
			id = ParameterId("ParamAngleX"),
			name = "Angle X",
			min = -30f,
			max = 30f,
			default = 0f,
		)
		val warp = Deformer.Warp(
			id = DeformerId("warp_test"),
			name = "Test Warp",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid = null,
		)
		val drawable = Drawable(
			id = DrawableId("mesh_body"),
			name = "Body Mesh",
			parentDeformerId = warp.id,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			channelGrids = ChannelGrids.Empty,
			drawOrder = 0f,
			opacity = 1f,
			isVisible = true,
			texturePage = 0,
		)
		return PuppetModel(
			parameters = listOf(param),
			parts = emptyList(),
			deformers = listOf(warp),
			drawables = listOf(drawable),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterLinks = emptyList(),
			parameterTree = emptyList(),
			canvasWidth = 1000f,
			canvasHeight = 1000f,
			worldOriginX = 500f,
			worldOriginY = 500f,
		)
	}
}
