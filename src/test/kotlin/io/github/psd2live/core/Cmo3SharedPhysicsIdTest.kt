package io.github.psd2live.core

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cubism Editor saves physics settings that share an ID; an import keeps each of them under an ID of its own. */
class Cmo3SharedPhysicsIdTest {
	@Test fun repeatsTakeTheNextFreeSuffix() {
		assertEquals(listOf("A", "A_2", "B", "A_3"), Cmo3ModelImport.uniqueIds(listOf("A", "A_2", "B", "A")))
		assertEquals(listOf("A", "A_3", "A_2"), Cmo3ModelImport.uniqueIds(listOf("A", "A", "A_2")))
	}

	@Test fun settingsThatShareAnIdAllImport() {
		val bytes = cmo3(RigPhysicsEdit("PhysicsSetting1", "Hair front", listOf(PhysicsInput("ParamAngleX")), listOf(PhysicsOutput("ParamHairFront"))),
			RigPhysicsEdit("PhysicsSetting1", "Hair back", listOf(PhysicsInput("ParamAngleX")), listOf(PhysicsOutput("ParamHairBack"))))
		val (source, config) = Cmo3ModelImport.prepare(bytes, Cmo3ImportMode.NEW, null, PipelineConfig())
		val physics = config.rigEdits.physicsEdits
		assertEquals(listOf("PhysicsSetting1" to "Hair front", "PhysicsSetting1_2" to "Hair back"), physics.map { it.id to it.name })
		assertEquals(listOf(listOf("ParamHairFront"), listOf("ParamHairBack")), physics.map { it.outputParameters })
		assertEquals(physics, Cmo3ModelImport.prepare(bytes, Cmo3ImportMode.NEW, null, PipelineConfig()).second.rigEdits.physicsEdits)
		assertEquals(physics, Cmo3ModelImport.decode(config.rigEdits.importedCmo3!!).physics, "a reopened project reads the same IDs")
		val ir = RigIrCompiler.compile(PSD2LivePipeline().buildPreview(source, config))
		assertEquals(listOf("PhysicsSetting1", "PhysicsSetting1_2"), ir.physics.groups.map { it.id }, "exports keep the IDs apart")
	}

	/** A one-mesh cmo3 whose physics holds [settings] as given, IDs and all. */
	private fun cmo3(vararg settings: RigPhysicsEdit): ByteArray {
		val parameters = listOf("ParamAngleX", "ParamHairFront", "ParamHairBack").map { Parameter(ParameterId(it), it, -30f, 30f, 0f) }
		val mesh = DrawableMesh(floatArrayOf(8f, 8f, 56f, 8f, 56f, 56f, 8f, 56f), floatArrayOf(0.125f, 0.125f, 0.875f, 0.125f, 0.875f, 0.875f, 0.125f, 0.875f),
			intArrayOf(0, 1, 2, 0, 2, 3))
		val drawable = Drawable(DrawableId("ArtMesh0"), "Square", null, BlendMode.Normal, emptyList(), mesh, null)
		val puppet = PuppetModel(parameters, emptyList(), emptyList(), listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null,
			canvasWidth = 64f, canvasHeight = 64f)
		val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
			for (y in 0 until 64) for (x in 0 until 64) setRGB(x, y, 0xFF80A0C0.toInt())
		}, "png", it) }.toByteArray()
		val model = Cmo3Conversion.freshCmo3(puppet, listOf(Cmo3Conversion.AtlasPage(png, 64, 64)), mapOf("ArtMesh0" to 0), "Shared IDs", 0L, 0).model
		Cmo3PhysicsInjector.inject(model.root as CModelSource, settings.toList())
		return Cmo3.write(model)
	}
}
