package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3Author
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.interop.moc3.export.Moc3VersionDowngrade
import org.umamo.interop.cmo3FileFormatVersion
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.interop.ExportNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

class Cmo3VersionCompatibilityTest {
	private fun blank(target: RuntimeTarget = RuntimeTarget.Cubism50) = PuppetModel(
		parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = emptyList(),
		rootChildren = emptyList(), rootPartId = null, runtimeTarget = target,
	)

	@Test fun cubism50FreshProjectDoesNotImportNewerEditorClasses() {
		val root = Cmo3SkeletonBuilder.buildBlank("test", 64, 64, Cmo3TargetVersion.V50.versionNo).root
		val xml = Cmo3Author.writeFreshMainXml(root, Cmo3TargetVersion.V50.fileFormatVersion).decodeToString()
		assertFalse("AlphaComposition" in xml)
		assertFalse("CModelStateSetSet" in xml)
		assertTrue("<?version CModelSource:14?>" in xml)
		assertFalse("<?version CPartSource:2?>" in xml)
		val entry = Cmo3ImageChainBuilder.packedEntry(CTextureAtlas(), null, CAffine(), GTransform2(), includeAutoLayoutLock = false)
		assertNull(entry.autoLayoutLock)
	}

	@Test fun newerClassesAppearOnlyAtTheirSchemaLevel() {
		val root53 = Cmo3SkeletonBuilder.buildBlank("test", 64, 64, Cmo3TargetVersion.V53.versionNo).root
		val xml53 = Cmo3Author.writeFreshMainXml(root53, Cmo3TargetVersion.V53.fileFormatVersion).decodeToString()
		assertTrue("AlphaComposition" in xml53)
		assertFalse("CModelStateSetSet" in xml53)
		val rootLatest = Cmo3SkeletonBuilder.buildBlank("test", 64, 64, Cmo3TargetVersion.LATEST_VERSION_NO).root
		val xmlLatest = Cmo3Author.writeFreshMainXml(rootLatest, "504000000").decodeToString()
		assertTrue("CModelStateSetSet" in xmlLatest)
	}

	@Test fun olderSdkTargetsUseEditor50ProjectSchema() {
		for ((target, versionNo) in listOf(
			RuntimeTarget.Cubism30 to 3_000,
			RuntimeTarget.Cubism33 to 3_030,
			RuntimeTarget.Cubism40 to 400_000,
			RuntimeTarget.Cubism42 to 4_020_000,
			RuntimeTarget.Cubism50 to 5_000_000,
		)) {
			assertEquals("500000005", target.cmo3FileFormatVersion())
			assertEquals(versionNo, target.cmo3TargetVersionNo())
		}
	}

	@Test fun conversionPersistsOlderSdkTargetInsideEditor50Project() {
		val converted = Cmo3Conversion.freshCmo3(blank(RuntimeTarget.Cubism30), emptyList(), emptyMap(), "test", 0L, 0x42)
		val bytes = Cmo3.write(converted.model)
		val xml = CaffCodec.readFirstEntryByTag(bytes, CaffArchive.TAG_MAIN_XML)!!.content.decodeToString()
		assertTrue("fileFormatVersion=\"500000005\"" in xml)
		assertFalse("AlphaComposition" in xml)
		assertEquals(3_000, Cmo3.read(bytes).targetVersionNo)
	}

	@Test fun freshArtMeshCarriesAutomaticMeshGeneratorSettings() {
		val mesh = DrawableMesh(
			floatArrayOf(1f, 1f, 2f, 1f, 1f, 2f),
			floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
			intArrayOf(0, 1, 2),
		)
		val drawable = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), mesh, null)
		val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(2, 2, ByteArray(16) { 0xFF.toByte() })), 2, 2)
		val converted = Cmo3Conversion.freshCmo3(
			blank().copy(drawables = listOf(drawable), canvasWidth = 64f, canvasHeight = 64f),
			listOf(page), mapOf("mesh" to 0), "test", 0L, 0x42,
		)
		val xml = CaffCodec.readFirstEntryByTag(Cmo3.write(converted.model), CaffArchive.TAG_MAIN_XML)!!.content.decodeToString()
		assertTrue("<CMeshGeneratorExtension" in xml)
		assertTrue("<MeshGenerateSetting xs.n=\"meshGenerateSetting\">" in xml)
		assertTrue("<i xs.n=\"polygonOuterDensity\">100</i>" in xml)
		assertTrue("fileFormatVersion=\"500000005\"" in xml)
	}

	@Test fun downgradeReportsOnlyFeaturesActuallyRemoved() {
		assertTrue(Moc3VersionDowngrade.stripForCmo3(blank(), RuntimeTarget.Cubism50).notices.isEmpty())
		val repeat = Parameter(ParameterId("ParamLoop"), "Loop", 0f, 1f, 0f, repeat = true)
		val downgraded = Moc3VersionDowngrade.stripForCmo3(blank().copy(parameters = listOf(repeat)), RuntimeTarget.Cubism50)
		assertEquals(false, downgraded.puppet.parameters.single().repeat)
		assertEquals(RuntimeFeature.ParameterRepeat, (downgraded.notices.single() as ExportNotice.FeatureStripped).feature)
		val mocDowngraded = Moc3VersionDowngrade.strip(blank().copy(parameters = listOf(repeat)), MocVersion.V50)
		assertEquals(false, mocDowngraded.puppet.parameters.single().repeat)
	}

	@Test fun zeroAreaFacesAreRemovedFromCmo3ExportCopy() {
		val mesh = DrawableMesh(
			floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 2f, 0f),
			floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
			intArrayOf(0, 1, 2, 0, 0, 1, 0, 1, 3),
		)
		val drawable = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), mesh, null)
		val source = blank().copy(drawables = listOf(drawable))
		val sanitized = Cmo3MeshSanitizer.sanitize(source)
		assertContentEquals(intArrayOf(0, 1, 2), sanitized.drawables.single().mesh!!.indices)
		assertSame(mesh, source.drawables.single().mesh)
		assertContentEquals(intArrayOf(0, 1, 2, 0, 0, 1, 0, 1, 3), mesh.indices)
	}
}
