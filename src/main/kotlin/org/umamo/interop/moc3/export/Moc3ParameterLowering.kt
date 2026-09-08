package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.ParameterType
import org.umamo.runtime.model.ParameterKind
import org.umamo.format.moc3.moc.MocParameter as MocParameter

/**
 * Lowers every parameter in the plan into its MOC3 record.
 *
 * Takes no keyform pool, which is the point of the signature: a parameter is an axis objects bind TO,
 * so this pass cannot intern a binding and the type says so.
 *
 * @param Moc3ExportContext context The export's derived state.
 * @param Moc3ExportIds     ids     Claimed from: each parameter's written id (over-long ones reported).
 * @return List<MocParameter> The records, in plan order.
 */
internal fun lowerParameters(
	context: Moc3ExportContext,
	ids: Moc3ExportIds,
): List<MocParameter> {
	val version = context.version
	val plan = context.plan
	return plan.parameters.map { parameter ->
		MocParameter(
			id = ids.parameterId(parameter.id),
			minimumValue = parameter.min,
			maximumValue = parameter.max,
			defaultValue = parameter.default,
			// Parameter.Types is moc 4+; below that every parameter is normal and the section is absent.
			type =
				when {
					version.byteValue < 4 -> null
					parameter.kind == ParameterKind.BLEND_SHAPE -> ParameterType.BLEND_SHAPE
					else -> ParameterType.NORMAL
				},
			repeats = parameter.repeat,
		)
	}
}