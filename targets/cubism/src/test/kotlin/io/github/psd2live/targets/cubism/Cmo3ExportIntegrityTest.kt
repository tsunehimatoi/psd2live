package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.*
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3GraphIndex
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Cmo3ExportIntegrityTest {
	@TempDir lateinit var temp: Path

	private fun blankPngBytes(size: Int = 16): Bytes {
		val rgba = ByteArray(size * size * 4) { 255.toByte() }
		return Bytes.wrap(PngCodec.write(RasterImage(size, size, rgba)))
	}

	private fun exportAndReadRoot(ir: RigIR): CModelSource {
		Compiler.export(Cmo3Target(), ir, ExportOptions("integrity")) { name, bytes ->
			Files.write(temp.resolve(name), bytes)
		}
		val bytes = Files.readAllBytes(temp.resolve("integrity.cmo3"))
		return Cmo3.read(bytes).root as CModelSource
	}

	@Test
	fun unkeyedDeformersAndOutOfSpanChannelsStillEmitKeyforms() {
		val png = blankPngBytes()
		val ir = RigIR(
			canvas = Canvas(width = 64f, height = 64f),
			authoring = Authoring(runtime = "Cubism53"),
			parameters = listOf(
				Parameter("ParamBlend", "Blend", 0f, 1f, 0f, blend = true),
				Parameter("ParamX", "X", -30f, 30f, 0f, blend = false),
			),
			parts = listOf(Part(id = "Part0", name = "Part0", children = listOf(ChildRef.MeshRef("MeshOutOfSpan")), drawOrder = 500)),
			deformers = listOf(
				Deformer.Warp(
					id = "WarpUnkeyed",
					name = "Warp Unkeyed",
					parent = null,
					part = "Part0",
					rows = 2,
					columns = 2,
					bilinear = true,
					lattice = null,
				),
				Deformer.Rotation(
					id = "RotUnkeyed",
					name = "Rot Unkeyed",
					parent = null,
					part = "Part0",
					baseAngle = 0f,
					pivot = null,
				),
			),
			meshes = listOf(
				Mesh(
					id = "MeshOutOfSpan",
					name = "Mesh Out Of Span",
					parent = null,
					geometry = MeshGeometry(
						positions = Floats.wrap(floatArrayOf(0f, 0f, 16f, 0f, 0f, 16f)),
						uvs = Floats.wrap(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)),
						indices = Ints.wrap(intArrayOf(0, 1, 2)),
					),
					offsets = KeyGrid(
						axes = listOf(KeyAxis("ParamX", Floats.wrap(floatArrayOf(-30f, 0f, 30f)))),
						cells = listOf(
							KeyCell(Ints.wrap(intArrayOf(0)), MeshOffsets(Floats.wrap(floatArrayOf(-1f, 0f, -1f, 0f, -1f, 0f)))),
							KeyCell(Ints.wrap(intArrayOf(1)), MeshOffsets(Floats.wrap(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)))),
							KeyCell(Ints.wrap(intArrayOf(2)), MeshOffsets(Floats.wrap(floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f)))),
						),
					),
					channels = mapOf(
						Channel.OPACITY to KeyGrid(
							axes = listOf(KeyAxis("ParamX", Floats.wrap(floatArrayOf(-10f, 10f)))),
							cells = listOf(
								KeyCell(Ints.wrap(intArrayOf(0)), ChannelValue.Scalar(0.5f)),
								KeyCell(Ints.wrap(intArrayOf(1)), ChannelValue.Scalar(1f)),
							),
						),
					),
					drawOrder = 1005f,
					page = 0,
				),
			),
			textures = Textures(
				pages = listOf(TexturePage(16, 16, png)),
				bindings = mapOf("MeshOutOfSpan" to 0),
			),
			physics = Physics(
				groups = listOf(
					PhysicsGroup(
						id = "Phys0",
						name = "Phys0",
						inputs = listOf(PhysicsInput("ParamX", 1f, PhysicsSource.X, false)),
						outputs = listOf(PhysicsOutput("ParamX", 1, 1f, 1f, PhysicsSource.ANGLE, false)),
						segments = listOf(PhysicsSegment(10f, 1f, 0.9f, 0.5f)),
						normalization = PhysicsNormalization(-1f, 0f, 1f, -45f, 0f, 45f),
					),
				),
				fps = 60f,
			),
		)

		val root = exportAndReadRoot(ir)
		val index = Cmo3GraphIndex(root)

		// 1. Parameter types: blend shape first must not taint following normal parameters.
		val paramX = index.parameterByIdStr["ParamX"]
		assertNotNull(paramX)
		assertEquals(Type.NORMAL, paramX.paramType)
		val paramBlend = index.parameterByIdStr["ParamBlend"]
		assertNotNull(paramBlend)
		assertEquals(Type.MORPH_TARGET, paramBlend.paramType)

		// 2. Unkeyed deformers still have default keyform + grid source.
		val warp = index.deformerByIdStr["WarpUnkeyed"] as? CWarpDeformerSource
		assertNotNull(warp)
		assertTrue(Cmo3Import.elementsOf(warp.keyforms).isNotEmpty(), "Unkeyed warp should have a default keyform")
		assertNotNull(warp.keyformGridSource, "Unkeyed warp should have a keyformGridSource")

		val rot = index.deformerByIdStr["RotUnkeyed"] as? CRotationDeformerSource
		assertNotNull(rot)
		assertTrue(Cmo3Import.elementsOf(rot.keyforms).isNotEmpty(), "Unkeyed rotation should have a default keyform")
		assertNotNull(rot.keyformGridSource, "Unkeyed rotation should have a keyformGridSource")

		// 3. Out-of-span drawable falls back instead of leaving empty keyforms, and drawOrder <= 1000.
		val mesh = index.drawableByIdStr["MeshOutOfSpan"]
		assertNotNull(mesh)
		val meshForms = Cmo3Import.elementsOf(mesh.keyforms).filterIsInstance<CArtMeshForm>()
		assertTrue(meshForms.isNotEmpty(), "Out-of-span drawable should still emit keyforms")
		assertNotNull(mesh.keyformGridSource)
		assertTrue(meshForms.all { it.drawOrder in 0..1000 })

		// 4. Physics selection GUID points to an existing physics setting when present.
		val physSet = root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet
		assertNotNull(physSet)
		val physGuids = Cmo3Import.elementsOf(physSet._sourceCubismPhysics)
			.filterIsInstance<CPhysicsSettingsSource>()
			.mapNotNull { Cmo3Import.uuidOf(it.guid) }
			.toSet()
		val selectedUuid = Cmo3Import.uuidOf(physSet.selectedCubismPhysics)
		assertTrue(selectedUuid == null || selectedUuid in physGuids)
	}
}
