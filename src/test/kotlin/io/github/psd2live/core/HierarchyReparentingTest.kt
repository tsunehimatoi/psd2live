package io.github.psd2live.core

import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyReparentingTest {

	@Test
	fun `deletedLayerIds excludes layers from preview pipeline`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return

		val pipeline = PSD2LivePipeline()
		val basePreview = pipeline.buildPreview(sample, PipelineConfig(atlasSize = 2048, meshSpacing = 128))
		val allLayerIds = basePreview.rig.layerIdByDrawableId.values.toSet()
		assertTrue(allLayerIds.size > 2, "Expected multiple layers in sample PSD")

		val layerToDelete = allLayerIds.first()
		val filteredPreview = pipeline.buildPreview(
			sample,
			PipelineConfig(
				atlasSize = 2048,
				meshSpacing = 128,
				deletedLayerIds = setOf(layerToDelete),
			),
		)

		val remainingLayerIds = filteredPreview.rig.layerIdByDrawableId.values.toSet()
		assertFalse(remainingLayerIds.contains(layerToDelete), "Deleted layer should not produce drawables")
		assertEquals(allLayerIds.size - 1, remainingLayerIds.size)
	}

	@Test
	fun `parentOverrides reparents deformers and drawables in preview`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return

		val pipeline = PSD2LivePipeline()
		val basePreview = pipeline.buildPreview(sample, PipelineConfig(atlasSize = 2048, meshSpacing = 128))
		val deformers = basePreview.rig.puppet.deformers
		assertTrue(deformers.size >= 2, "Expected at least 2 deformers in rig")

		val targetParent = deformers.first()
		val childDeformer = deformers.last()

		// Reparent childDeformer under targetParent
		val reparentedPreview = pipeline.buildPreview(
			sample,
			PipelineConfig(
				atlasSize = 2048,
				meshSpacing = 128,
				parentOverrides = mapOf(childDeformer.id.raw to targetParent.id.raw),
			),
		)

		val updatedDeformers = reparentedPreview.rig.puppet.deformers
		val updatedChild = updatedDeformers.first { it.id == childDeformer.id }
		assertEquals(targetParent.id, updatedChild.parent)

		// Reparent a drawable to root (null)
		val aDrawable = basePreview.rig.puppet.drawables.first()
		val aLayerId = basePreview.rig.layerIdByDrawableId[aDrawable.id.raw]
		if (aLayerId != null) {
			val rootReparentedPreview = pipeline.buildPreview(
				sample,
				PipelineConfig(
					atlasSize = 2048,
					meshSpacing = 128,
					parentOverrides = mapOf(aLayerId to null),
				),
			)
			val updatedDrawable = rootReparentedPreview.rig.puppet.drawables.first { it.id == aDrawable.id }
			assertNull(updatedDrawable.parentDeformerId, "Drawable reparented to null should have null parentDeformerId")
		}
	}

	@Test
	fun `cycle detection prevents invalid deformer reparenting`() {
		val d1 = Deformer.Warp(
			id = DeformerId("A"),
			name = "A",
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = false,
			geometryGrid = null,
		)
		val d2 = Deformer.Warp(
			id = DeformerId("B"),
			name = "B",
			parent = DeformerId("A"),
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = false,
			geometryGrid = null,
		)
		val map: Map<String, Deformer> = mapOf("A" to d1, "B" to d2)

		// Setting A's parent to B would create cycle: A -> B -> A
		assertTrue(RigBuilder.wouldCreateCycle("A", "B", map, emptyMap()))
		// Setting B's parent to C is not a cycle
		assertFalse(RigBuilder.wouldCreateCycle("B", "C", map, emptyMap()))
	}

	@Test
	fun `viewModel hierarchy and delete layer actions manage state properly`() {
		val vm = PSD2LiveViewModel()
		try {
			// Delete and restore layers
			vm.deleteLayer("layer_alpha")
			vm.deleteLayer("layer_beta")
			assertEquals(setOf("layer_alpha", "layer_beta"), vm.state.value.deletedLayerIds)
			assertEquals(setOf("layer_alpha", "layer_beta"), vm.state.value.buildConfig().deletedLayerIds)

			vm.restoreLayer("layer_alpha")
			assertEquals(setOf("layer_beta"), vm.state.value.deletedLayerIds)

			vm.restoreAllDeletedLayers()
			assertTrue(vm.state.value.deletedLayerIds.isEmpty())

			// Reparent items
			vm.reparentItem("layer_1", "Deformer_Head")
			assertEquals("Deformer_Head", vm.state.value.parentOverrides["layer_1"])
			assertEquals("Deformer_Head", vm.state.value.buildConfig().parentOverrides["layer_1"])

			// Reparent to root
			vm.reparentItem("layer_1", null)
			assertTrue(vm.state.value.parentOverrides.containsKey("layer_1"))
			assertNull(vm.state.value.parentOverrides["layer_1"])

			// Reset individual item hierarchy
			vm.reparentItem("layer_1", "Deformer_Head")
			vm.reparentItem("layer_2", "Deformer_Body")
			assertEquals("Deformer_Head", vm.state.value.parentOverrides["layer_1"])
			assertEquals("Deformer_Body", vm.state.value.parentOverrides["layer_2"])
			vm.resetItemHierarchy("layer_1")
			assertFalse(vm.state.value.parentOverrides.containsKey("layer_1"))
			assertEquals("Deformer_Body", vm.state.value.parentOverrides["layer_2"])

			// Reset all hierarchy overrides
			vm.resetHierarchyOverrides()
			assertTrue(vm.state.value.parentOverrides.isEmpty())

			// Selection mutual exclusivity (never select warp and mesh at the same time)
			vm.selectLayer("layer_alpha")
			assertEquals("layer_alpha", vm.state.value.selectedLayerId)
			assertNull(vm.state.value.selectedDeformerId)

			vm.selectDeformer("deformer_head")
			assertEquals("deformer_head", vm.state.value.selectedDeformerId)
			assertNull(vm.state.value.selectedLayerId, "Selecting deformer must clear selectedLayerId")

			vm.selectLayer("layer_beta")
			assertEquals("layer_beta", vm.state.value.selectedLayerId)
			assertNull(vm.state.value.selectedDeformerId, "Selecting layer must clear selectedDeformerId")
		} finally {
			vm.close()
		}
	}
}
