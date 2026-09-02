package io.github.autolive2d.core

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Import as EditableCmo3Import
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineIntegrationTest {
	@Test
	fun `reference PSD exports a self-contained validated family`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return
		val output = createTempDirectory("autolive2d-e2e-")
		val result = AutoLive2DPipeline().run(
			sample,
			output,
			PipelineConfig(atlasSize = 2048, meshSpacing = 128),
		)
		val byExtension = result.exportedFiles.associateBy { it.path.fileName.toString().substringAfter('.') }
		assertTrue(result.exportedFiles.any { it.path.fileName.toString().endsWith(".idle.motion3.json") })
		val mocDocument = Moc3.read(Files.readAllBytes(checkNotNull(byExtension["moc3"]).path))
		val cmoDocument = Cmo3.read(Files.readAllBytes(checkNotNull(byExtension["cmo3"]).path))
		val cmoRoot = cmoDocument.root as CModelSource
		val mocPuppet = Moc3Import.fromMocDocument(mocDocument, null)
		val tagByLayerId = result.analysis.layers.associate { it.source.id.raw to it.semantic.tag }
		val mouthMaskIds = result.previewModel.rig.layerIdByDrawableId
			.filterValues { tagByLayerId[it] in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
			.keys
		val mouthInternalIds = result.previewModel.rig.layerIdByDrawableId
			.filterValues { tagByLayerId[it] in CharacterAnalyzer.MOUTH_COMPONENT_TAGS }
			.keys
		fun assertMouthMasks(puppet: PuppetModel, label: String) {
			if (mouthInternalIds.isEmpty()) return
			assertTrue(mouthMaskIds.isNotEmpty(), "$label lost the mouth clipping source")
			for (drawable in puppet.drawables.filter { it.id.raw in mouthInternalIds }) {
				assertTrue(drawable.maskedBy.isNotEmpty(), "$label ${drawable.id.raw} lost its clipping id")
				assertTrue(drawable.maskedBy.all { it.raw in mouthMaskIds }, "$label ${drawable.id.raw} uses a non-mouth mask")
			}
		}
		assertMouthMasks(mocPuppet, "MOC3")
		val angleX = mocPuppet.parameters.single { it.id == StandardParameters.ANGLE_X }
		assertEquals(-45f, angleX.min)
		assertEquals(45f, angleX.max)
		val faceNinePose = mocPuppet.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw == "DeformFaceNinePose" }
		val faceGrid = checkNotNull(faceNinePose.geometryGrid)
		val axes = faceGrid.axes
		assertEquals(StandardParameters.ANGLE_X, axes[0].parameterId, "first nine-pose coordinate must bind AngleX")
		assertEquals(StandardParameters.ANGLE_Y, axes[1].parameterId, "second nine-pose coordinate must bind AngleY")
		assertContentEquals(floatArrayOf(-45f, 0f, 45f), axes.single { it.parameterId == StandardParameters.ANGLE_X }.keys)
		assertContentEquals(floatArrayOf(-30f, 0f, 30f), axes.single { it.parameterId == StandardParameters.ANGLE_Y }.keys)
		assertEquals(9, faceGrid.cells.size)

		val warpById = mocPuppet.deformers.filterIsInstance<Deformer.Warp>().associateBy { it.id.raw }
		val headContainer = checkNotNull(warpById["DeformHeadContainer"])
		assertEquals(DeformerId("DeformHeadRotation"), headContainer.parent)
		assertEquals(listOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y), checkNotNull(headContainer.geometryGrid).axes.map { it.parameterId })
		assertEquals(headContainer.id, faceNinePose.parent, "face must be a child surface of the head container")

		val headRotation = mocPuppet.deformers.filterIsInstance<Deformer.Rotation>().single { it.id.raw == "DeformHeadRotation" }
		val rotationPivot = checkNotNull(headRotation.geometryGrid).cells.first().form
		val character = result.analysis.anchors.character
		val faceRig = NinePoseFaceRig.from(result.analysis)
		val pivotCanvas = faceRig.coordinateSpace.toCanvas(faceRig.centerX, faceRig.mouthLineY)
		val expectedPivotX = (pivotCanvas.first - character.left) / character.width
		val expectedPivotY = (pivotCanvas.second - character.top) / character.height
		assertEquals(expectedPivotX, rotationPivot.originX, 1e-3f, "Head Z rotation pivot X must match the oriented face rig")
		assertEquals(expectedPivotY, rotationPivot.originY, 1e-3f, "Head Z rotation pivot Y must match the oriented face rig")

		fun assertHairChain(tag: SemanticTag, followId: String, physicsId: String, parameter: org.umamo.runtime.model.ParameterId) {
			if (result.analysis.layers.none { it.semantic.tag == tag && it.opaquePixels > 0 }) return
			val follow = checkNotNull(warpById[followId])
			val physics = checkNotNull(warpById[physicsId])
			assertEquals(headContainer.id, follow.parent, "$tag follow warp must bypass the face warp")
			assertEquals(follow.id, physics.parent)
			assertEquals(listOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y), checkNotNull(follow.geometryGrid).axes.map { it.parameterId })
			assertEquals(listOf(parameter), checkNotNull(physics.geometryGrid).axes.map { it.parameterId })
			val minus = checkNotNull(physics.geometryGrid).cells.single { it.coordinate.contentEquals(intArrayOf(0)) }.form.controlPoints
			val plus = checkNotNull(physics.geometryGrid).cells.single { it.coordinate.contentEquals(intArrayOf(2)) }.form.controlPoints
			val topComponents = (physics.columns + 1) * 2
			assertContentEquals(minus.copyOfRange(0, topComponents), plus.copyOfRange(0, topComponents), "$tag roots must remain pinned")
			assertTrue(kotlin.math.abs(minus[minus.size - 2] - plus[plus.size - 2]) > 0.01f, "$tag tips must respond to physics")
			val hairNames = result.analysis.layers.filter { it.semantic.tag == tag }.map { it.source.name }.toSet()
			assertTrue(mocPuppet.drawables.filter { it.name in hairNames }.all { it.parentDeformerId == physics.id })
		}
		assertHairChain(SemanticTag.FRONT_HAIR, "DeformHairFrontFollow", "DeformHairFrontPhysics", StandardParameters.HAIR_FRONT)
		assertHairChain(SemanticTag.BACK_HAIR, "DeformHairBackFollow", "DeformHairBackPhysics", StandardParameters.HAIR_BACK)

		// Re-import the editable CMO3 and inspect an actual eye child warp.  This catches an X/Y key
		// coordinate swap in the lowering layer: +X/Y=0 must have horizontal rows, while +X/Y=-30
		// must contain the signed diagonal correction rather than accidentally becoming the neutral row.
		val cmoPuppet = EditableCmo3Import.fromModelSource(cmoRoot)
		assertMouthMasks(cmoPuppet, "CMO3")
		val eyeWarp = cmoPuppet.deformers.filterIsInstance<Deformer.Warp>().first { it.id.raw.startsWith("DeformEyeShape") }
		val eyeGrid = checkNotNull(eyeWarp.geometryGrid)
		assertEquals(StandardParameters.ANGLE_X, eyeGrid.axes[0].parameterId)
		assertEquals(StandardParameters.ANGLE_Y, eyeGrid.axes[1].parameterId)
		fun rowChord(warp: Deformer.Warp, coordinate: IntArray, row: Int): Float {
			val points = checkNotNull(warp.geometryGrid).cells.single { it.coordinate.contentEquals(coordinate) }.form.controlPoints
			val stride = warp.columns + 1
			val leftY = points[(row * stride) * 2 + 1]
			val rightY = points[(row * stride + warp.columns) * 2 + 1]
			return rightY - leftY
		}
		assertEquals(0f, rowChord(eyeWarp, intArrayOf(2, 1), 1), 1e-6f, "AngleY=0 eye row must be horizontal")
		assertTrue(kotlin.math.abs(rowChord(eyeWarp, intArrayOf(2, 0), 1)) > 1e-5f, "AngleY=-30 eye row lost its XY correction")

		fun elements(value: Any?): List<Any?> = when (value) {
			is Iterable<*> -> value.toList()
			is Array<*> -> value.toList()
			else -> emptyList()
		}
		val expectedPhysics = listOf(
			SemanticTag.FRONT_HAIR to "PhysicsHairFront",
			SemanticTag.BACK_HAIR to "PhysicsHairBack",
			SemanticTag.IRIDES to "PhysicsEyeJelly",
		).filter { (tag, _) -> result.analysis.layers.any { it.semantic.tag == tag && it.opaquePixels > 0 } }
		val physicsSet = cmoRoot.physicsSettingsSourceSet as CPhysicsSettingsSourceSet
		val editableSettings = elements(physicsSet._sourceCubismPhysics).filterIsInstance<CPhysicsSettingsSource>()
		assertEquals(expectedPhysics.map { it.second }.toSet(), editableSettings.map { (it.id as Id).idstr }.toSet())
		for (setting in editableSettings) {
			val id = (setting.id as Id).idstr
			val expectedInputs = if (id == "PhysicsEyeJelly") 2 else 4
			assertEquals(expectedInputs, elements(setting.inputs).filterIsInstance<CPhysicsInput>().size)
			assertTrue(elements(setting.outputs).filterIsInstance<CPhysicsOutput>().single().angleScale > 0f)
		}
		assertTrue(result.warnings.none { "FractionalDrawOrder" in it })
	}

	@Test
	fun `mesh-only configuration exports minimal geometry and no deformers or physics`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return
		val output = createTempDirectory("autolive2d-meshonly-")
		val result = AutoLive2DPipeline().run(
			sample,
			output,
			PipelineConfig(
				atlasSize = 2048,
				meshSpacing = 128,
				meshOnly = true,
				exportCmo3 = true,
				exportMoc3 = true,
				exportJson = true,
			),
		)
		val byExtension = result.exportedFiles.associateBy { it.path.fileName.toString().substringAfter('.') }
		val mocDocument = Moc3.read(Files.readAllBytes(checkNotNull(byExtension["moc3"]).path))
		val mocPuppet = Moc3Import.fromMocDocument(mocDocument, null)
		assertTrue(mocPuppet.deformers.isEmpty(), "Mesh-only mode must not produce deformers")
		assertTrue(mocPuppet.drawables.all { it.parentDeformerId == null }, "Mesh-only drawables must not reference parent deformers")
		assertTrue(result.exportedFiles.none { it.path.fileName.toString().endsWith(".physics3.json") }, "Mesh-only must not export physics")
		assertTrue(result.exportedFiles.none { it.path.fileName.toString().endsWith(".motion3.json") }, "Mesh-only must not export motions")
		assertTrue(result.exportedFiles.any { it.path.fileName.toString().endsWith(".autolive2d.json") }, "Must export autolive2d metadata json")
	}

	@Test
	fun `fine-grained sub-motions and physics selections are exported correctly`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return
		val output = createTempDirectory("autolive2d-granular-")
		val result = AutoLive2DPipeline().run(
			sample,
			output,
			PipelineConfig(
				atlasSize = 2048,
				meshSpacing = 128,
				exportMotions = true,
				motionIdle = false,
				motionBlink = true,
				motionNod = true,
				motionShake = false,
				generatePhysics = true,
				physicsFrontHair = true,
				physicsBackHair = false,
				physicsEyeJelly = true,
			),
		)
		assertTrue(result.exportedFiles.any { it.path.fileName.toString().endsWith(".blink.motion3.json") })
		assertTrue(result.exportedFiles.any { it.path.fileName.toString().endsWith(".nod.motion3.json") })
		assertTrue(result.exportedFiles.none { it.path.fileName.toString().endsWith(".idle.motion3.json") })
		assertTrue(result.exportedFiles.none { it.path.fileName.toString().endsWith(".shake.motion3.json") })
		val physicsFile = result.exportedFiles.firstOrNull { it.path.fileName.toString().endsWith(".physics3.json") }
		if (physicsFile != null) {
			val physics = Moc3.readPhysics3(Files.readString(physicsFile.path))
			val ids = physics.physicsSettings.map { it.id }
			assertTrue("PhysicsHairFront" in ids)
			assertTrue("PhysicsHairBack" !in ids)
		}
	}
}
