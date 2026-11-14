package io.github.psd2live.core

import io.github.psd2live.format.compile.GeometryEvaluator
import io.github.psd2live.format.compile.GeometrySession
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId

/** The engine's CPU evaluator over an IR rig, for targets that bake deformation. */
internal object IrGeometryEvaluator : GeometryEvaluator {
	override fun open(ir: RigIR): GeometrySession {
		val puppet = PuppetIr.toPuppet(ir)
		val evaluator = CpuDeformationEvaluator()
		return object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val geometry = evaluator.evaluate(puppet, parameters.mapKeys { ParameterId(it.key) })
				// The evaluator's world space negates canvas y.
				return PoseGeometry(
					geometry.worldPositions.entries.associate { (id, points) -> id.raw to FloatArray(points.size) { if (it % 2 == 0) points[it] else -points[it] } },
					geometry.opacity.mapKeys { it.key.raw }, geometry.drawOrder.mapKeys { it.key.raw })
			}
			override fun close() {}
		}
	}
}
