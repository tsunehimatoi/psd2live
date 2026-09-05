package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import java.nio.file.Files
import kotlin.test.*

class RigIntegrityValidatorTest {
	@Test
	fun `reported front hair deviations produce warnings and retain evaluated bounds`() {
		val cases = listOf(
			Bounds(209f, 61f, 820f, 411f) to Bounds(550.1062f, 95.37065f, 808.41187f, 411.3775f),
			Bounds(513f, 0f, 948f, 338f) to Bounds(514.0559f, 0.6690035f, 711.59863f, 332.46454f),
		)
		for ((expected, actual) in cases) {
			val result = RigIntegrityValidator.validateNeutralPose("test", puppet(actual), mapOf(ID to expected))
			assertEquals(actual, result.boundsByDrawableId.getValue(ID))
			assertEquals(listOf(tr("validation.neutralMismatch", "test", ID, expected, actual)), result.warnings)
		}
	}

	@Test
	fun `center deviations warn while small drift stays quiet`() {
		val expected = Bounds(0f, 0f, 100f, 100f)
		for ((offset, key) in listOf(1f to null, 5f to "validation.neutralWarning", 60f to "validation.neutralMismatch")) {
			val actual = Bounds(offset, 0f, 100f + offset, 100f)
			val result = RigIntegrityValidator.validateNeutralPose("test", puppet(actual), mapOf(ID to expected))
			val warnings = key?.let { listOf(tr(it, "test", ID, expected, actual)) } ?: emptyList()
			assertEquals(warnings, result.warnings)
		}
	}

	@Test
	fun `unusable geometry still fails validation`() {
		for (actual in listOf(Bounds(0f, 0f, 0f, 100f), Bounds(0f, 0f, Float.NaN, 100f))) {
			assertFailsWith<IllegalArgumentException> {
				RigIntegrityValidator.validateNeutralPose("test", puppet(actual), emptyMap())
			}
		}
	}

	@Test
	fun `neutral deviation does not block either export and is saved in the report`() {
		val layer = object : SourceLayer {
			override val id = LayerId("hair")
			override val name = "front hair"
			override val groupPath = ""
			override val kind = SourceLayerKind.Raster
			override val visible = true
			override val order = 0
			override val bounds = LayerBounds(24, 12, 64, 96)
			override val opacity = 1f
			override val clipped = false
			override val blend = LayerBlend.Normal
			override val raster = LayerRaster(64, 96, ByteArray(64 * 96 * 4) { if (it % 4 == 3) 255.toByte() else 80 })
		}
		val source = object : SourceArt {
			override val widthPx = 256
			override val heightPx = 128
			override val layers = listOf(layer)
		}
		val pipeline = PSD2LivePipeline()
		val config = PipelineConfig(atlasSize = 256, meshSpacing = 16, meshOnly = true,
			exportMoc3 = true, exportCmo3 = true, exportJson = true)
		val mesh = pipeline.buildPreview(source, config).rig.puppet.drawables.single()
		val overlay = RigEditOverlay(
			parameterEdits = listOf(RigParameterEdit("Offset", "Offset", -1f, 1f, 0f, created = true)),
			keyformSetEdits = listOf(-1f, 0f, 1f).map { value ->
				RigKeyformSetEdit(
					target = RigTargetRef(RigTargetKind.ART_MESH, mesh.id.raw),
					coordinate = mapOf("Offset" to value),
					geometry = RigKeyformGeometryEdit(positionDeltas = List(mesh.mesh!!.positions.size) { if (it % 2 == 0) 100f else 0f }),
				)
			},
		)
		val output = Files.createTempDirectory("neutral-deviation-export-")
		try {
			val result = pipeline.run(source, "hair.psd", output, config.copy(rigEdits = overlay))
			for (extension in listOf("moc3", "cmo3")) {
				assertTrue(Files.size(result.exportedFiles.single { it.path.toString().endsWith(".$extension") }.path) > 0)
			}
			for (labelKey in listOf("validation.generated", "validation.moc3Readback", "validation.cmo3Readback")) {
				assertTrue(result.warnings.any { tr(labelKey) in it && mesh.id.raw in it && "Bounds(" in it }, "Missing warning for $labelKey")
			}
			val report = result.exportedFiles.single { it.path.toString().endsWith(".psd2live.json") }
			val savedWarnings = Json.parseToJsonElement(Files.readString(report.path)).jsonObject.getValue("warnings").jsonArray
			assertEquals(result.warnings, savedWarnings.map { it.jsonPrimitive.content })
		} finally {
			output.toFile().deleteRecursively()
		}
	}

	private fun puppet(bounds: Bounds): PuppetModel {
		val drawable = Drawable(
			id = DrawableId(ID), name = "front hair", parentDeformerId = null,
			blendMode = BlendMode.Normal, maskedBy = emptyList(), geometryGrid = null,
			mesh = DrawableMesh(
				floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.top, bounds.right, bounds.bottom, bounds.left, bounds.bottom),
				floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3),
			),
		)
		return PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(),
			drawables = listOf(drawable), rootChildren = listOf(OrgChild.Drawable(drawable.id)), rootPartId = null)
	}

	private companion object {
		const val ID = "ArtMeshFrontHair"
	}
}
