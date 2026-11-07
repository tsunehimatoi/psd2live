package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.CheekSource
import io.github.psd2live.algorithms.binding.CheekV2Algorithm
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CheekV2AlgorithmTest {
    @Test fun aCheekAxisCannotPushTheMeshPastTheLoweringBudget() {
        val parameters = listOf(
            Parameter("ParamAngleX", "X", 0f, 8f, 0f),
            Parameter("ParamAngleY", "Y", 0f, 7f, 0f),
            Parameter("ParamAngleZ", "Z", 0f, 1f, 0f),
        )
        val axes = parameters.map { parameter ->
            KeyAxis(parameter.id, Floats.of(FloatArray(parameter.max.toInt() + 1) { it.toFloat() }))
        }
        val positions = Floats.of(floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f))
        val mesh = Mesh("face", "Face", null,
            geometry = MeshGeometry(positions, positions, Ints.of(intArrayOf(0, 1, 2))),
            offsets = KeyGrid(axes, emptyList()))
        val input = RigIR(Canvas(100f, 100f), parameters, meshes = listOf(mesh))
        val source = CheekSource(true, "Cheek", "Face", mapOf(mesh.id to CheekSource.Layer(CheekSource.LayerKind.FACE)))
        val instance = RigModifierInstance("cheek", CheekV2Algorithm.TYPE, CheekV2Algorithm.VERSION, listOf(ModifierTarget("mesh", mesh.id)))

        assertFailsWith<IllegalArgumentException> {
            CheekV2Algorithm().changes(input, instance, source.context()) {}
        }
    }
}
