package io.github.psd2live.core

import org.umamo.format.art.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.runtime.model.Deformer
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class IndependentHairRigTest {
    @Test fun `independent Warp and physics export and reimport without built in physics`() {
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
            override val widthPx = 128
            override val heightPx = 128
            override val layers = listOf(layer)
        }
        val config = PipelineConfig(atlasSize = 256, meshSpacing = 16,
            physicsFrontHair = false, physicsBackHair = false, physicsEyeJelly = false)
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(source, config)
        val mesh = preview.rig.puppet.drawables.first()
        val warp = RigWarpEdit("LockWarp", "Lock", mesh.parentDeformerId!!.raw, listOf(mesh.id.raw))
        val physics = RigPhysicsEdit("LockPhysics", "Lock \"physics\"", "ParamAngleX", "LockSway")
        val overlay = RigEditOverlay(warpEdits = listOf(warp), physicsEdits = listOf(physics),
            parameterEdits = listOf(RigParameterEdit("LockSway", "Lock sway", -1f, 1f, 0f, created = true)))
        val output = Files.createTempDirectory("independent-hair-export")
        try {
            val result = pipeline.run(source, "independent.psd", output, config.copy(rigEdits = overlay))
            val physicsFile = result.exportedFiles.single { it.path.toString().endsWith(".physics3.json") }
            val json = Files.readString(physicsFile.path)
            assertNotNull(Moc3.readPhysics3(json))
            val data = Json.parseToJsonElement(json).jsonObject
            assertEquals("LockPhysics", data.getValue("PhysicsSettings").jsonArray.single().jsonObject.getValue("Id").jsonPrimitive.content)
            val cmo = result.exportedFiles.single { it.path.toString().endsWith(".cmo3") }
            val root = Cmo3.read(Files.readAllBytes(cmo.path)).root as CModelSource
            val imported = Cmo3Import.fromModelSource(root)
            assertTrue(imported.deformers.any { it.id.raw == warp.id && it is Deformer.Warp })
            assertEquals(warp.id, imported.drawables.single { it.id == mesh.id }.parentDeformerId?.raw)
            val groups = (root.physicsSettingsSourceSet as CPhysicsSettingsSourceSet)._sourceCubismPhysics as Iterable<*>
            val group = groups.filterIsInstance<CPhysicsSettingsSource>().single()
            assertEquals(physics.id, (group.id as Id).idstr)
            assertEquals(physics.name, group.name)
        } finally { output.toFile().deleteRecursively() }
    }

    @Test fun `custom physics remains enabled without presets and respects master switch`() {
        val edit = RigPhysicsEdit("p", "p", "input", "output")
        val state = io.github.psd2live.ui.state.PSD2LiveState(physicsFrontHair = false, physicsBackHair = false, physicsEyeJelly = false,
            generatePhysics = true, rigEdits = RigEditOverlay(physicsEdits = listOf(edit)))
        assertTrue(state.buildConfig().generatePhysics)
        assertFalse(state.copy(generatePhysics = false).buildConfig().generatePhysics)
        assertFalse(state.copy(meshOnly = true).buildConfig().generatePhysics)
    }

    @Test fun `invalid physics wiring cannot export silently`() {
        val edit = RigPhysicsEdit("p", "p", "input", "output")
        assertFailsWith<IllegalArgumentException> { PhysicsGenerator.generate(false, false, false, setOf("input"), listOf(edit)) }
        assertFailsWith<IllegalArgumentException> { PhysicsGenerator.generate(false, false, false, setOf("input", "output"), listOf(edit, edit.copy(id = "p2"))) }
        assertFailsWith<IllegalArgumentException> { edit.copy(mobility = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { edit.copy(inputParameter = "output") }
    }
}
