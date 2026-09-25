package io.github.psd2live.core

import java.nio.file.Path
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkeletonPipelineIntegrationTest {
	@Test fun presetImportDoesNotAutoInitializeAndManualEnablementWorks() {
		val initialPreview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		// Not automatically initialized on import
		kotlin.test.assertNull(initialPreview.config.rigEdits.skeleton)

		// Manually enabled via tool / SkeletonAutoBuilder
		val generatedSkeleton = SkeletonAutoBuilder.build(initialPreview.analysis, initialPreview.rig)
		val preview = PSD2LivePipeline().buildPreview(
			initialPreview.analysis,
			initialPreview.config.copy(rigEdits = initialPreview.config.rigEdits.copy(skeleton = generatedSkeleton)),
		)
		val spec = assertNotNull(preview.config.rigEdits.skeleton)
		assertTrue(spec.enabled && spec.bones.any { it.role == BoneRole.FOREARM })
		val puppet = preview.rig.puppet
		val deformers = puppet.deformers.associateBy { it.id }
		val layers = preview.analysis.layers.associateBy { it.source.id.raw }
		val limbDrawables = puppet.drawables.filter { drawable ->
			val layerId = preview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
			layers[layerId]?.semantic?.tag in SkeletonAutoBuilder.limbTags
		}
		assertTrue(limbDrawables.isNotEmpty())
		for (drawable in limbDrawables) {
			var parent: DeformerId? = drawable.parentDeformerId
			while (parent != null) {
				assertFalse(parent.raw == "DeformBodyZBreath", "${drawable.id.raw} still inherits whole-body breathing")
				parent = deformers[parent]?.parent
			}
		}
		assertTrue(puppet.deformers.any { it is Deformer.Warp && it.id.raw.startsWith("DeformWarpSkel_") })
		assertTrue(puppet.parameters.any { it.id.raw == "ParamSquat" })
		val idle = assertNotNull(preview.runtimeBundle.assets.firstOrNull { it.path.endsWith(".idle.motion3.json") })
		assertTrue(idle.bytes.decodeToString().contains("ParamArmLA"))
	}
}
