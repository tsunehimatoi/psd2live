package example

import io.github.psd2live.algorithm.worker.AlgorithmResultValidation
import io.github.psd2live.format.model.*
import kotlin.test.*

class AlgorithmResultValidationTest {
    private val input = RigIR(Canvas(32f, 32f), emptyList(), meshes = listOf(
        Mesh("mesh", "Mesh", null, geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
            Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)))

    @Test fun rejectsNonFiniteGeometryAndOutOfRangeIndices() {
        AlgorithmResultValidation.validate(input)
        val mesh = input.meshes.single()
        val geometry = requireNotNull(mesh.geometry)
        assertFailsWith<IllegalArgumentException> {
            val invalid = geometry.copy(positions = Floats.values(Float.NaN, 0f, 1f, 0f, 0f, 1f))
            AlgorithmResultValidation.validate(input.copy(meshes = listOf(mesh.copy(geometry = invalid))))
        }
        assertFailsWith<IllegalArgumentException> {
            AlgorithmResultValidation.validate(input.copy(meshes = listOf(mesh.copy(geometry = geometry.copy(indices = Ints.values(0, 1, 3))))))
        }
    }

    @Test fun rejectsCyclesDuplicateIdsAndInvalidChannels() {
        val warp = Deformer.Warp("warp", "Warp", "warp", null, 1, 1, true, null)
        assertFailsWith<IllegalArgumentException> { AlgorithmResultValidation.validate(input.copy(deformers = listOf(warp))) }
        assertFailsWith<IllegalArgumentException> { AlgorithmResultValidation.validate(input.copy(meshes = input.meshes + input.meshes)) }
        assertFailsWith<IllegalArgumentException> {
            AlgorithmResultValidation.validate(input.copy(meshes = listOf(input.meshes.single().copy(opacity = 2f))))
        }
    }
    @Test fun rejectsDanglingAndDuplicateParameterTreeReferencesAndLinks() {
        val rig = input.copy(parameters = listOf(Parameter("a", "A", -1f, 1f, 0f), Parameter("b", "B", -1f, 1f, 0f)),
            parameterTree = listOf(ParameterNode.Group("group", "Group", true, listOf(ParameterNode.Param("b")))),
            parameterLinks = listOf(ParameterLink("a", "b")))
        AlgorithmResultValidation.validate(rig) // A partial tree is valid; unlisted parameters retain their order.
        for (invalid in listOf(
            rig.copy(parameterTree = listOf(ParameterNode.Param("missing"))),
            rig.copy(parameterTree = rig.parameterTree + ParameterNode.Param("b")),
            rig.copy(parameterTree = rig.parameterTree + rig.parameterTree),
            rig.copy(parameterLinks = listOf(ParameterLink("a", "missing"))),
            rig.copy(parameters = rig.parameters + rig.parameters.first()),
        )) assertFailsWith<IllegalArgumentException> { AlgorithmResultValidation.validate(invalid) }
    }

}
