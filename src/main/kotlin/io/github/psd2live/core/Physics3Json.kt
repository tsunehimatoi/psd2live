package io.github.psd2live.core

import io.github.psd2live.format.model.PhysicsSource
import io.github.psd2live.targets.cubism.Cubism3Json
import io.github.psd2live.format.model.PhysicsGroup as IrPhysicsGroup


/** physics3.json, written the way Cubism Editor exports it and read the way the Cubism runtime does. */
object Physics3Json {
	/** The settings of a file, in file order, and its `Fps` if it declares one. */
	data class Physics3(val settings: List<RigPhysicsEdit>, val fps: Float?)

	/** physics3.json for [settings] stepping at [fps] (no `Fps` when unlimited), or null when there are none. */
	fun write(settings: List<RigPhysicsEdit>, fps: Int): String? = Cubism3Json.physics3(settings.map(::group), fps)

	/** A pendulum setting as its neutral IR group. */
	fun group(setting: RigPhysicsEdit): IrPhysicsGroup = io.github.psd2live.algorithms.physics.PhysicsGroups.fromSetting(setting)

    /** Exact inverse for materializing generated groups as ordinary editable physics. */
    fun setting(group: IrPhysicsGroup): RigPhysicsEdit {
        fun type(source: PhysicsSource): PhysicsSourceType {
            require(source != PhysicsSource.Y) { "Vertical physics cannot be represented as an authored pendulum" }
            return if (source == PhysicsSource.ANGLE) PhysicsSourceType.ANGLE else PhysicsSourceType.X
        }
        return RigPhysicsEdit(group.id, group.name,
            group.inputs.map { PhysicsInput(it.parameter, it.weight, type(it.source), it.reflect) },
            group.outputs.map { PhysicsOutput(it.parameter, it.vertex, it.scale, it.weight, type(it.source), it.reflect) },
            group.segments.map { PhysicsSegment(it.length, it.mobility, it.delay, it.acceleration) },
            group.normalization.let { PhysicsNormalization(it.positionMin, it.positionDefault, it.positionMax, it.angleMin, it.angleDefault, it.angleMax) })
    }

	/** Reads vertex radii as segment lengths, as Cubism does; positions are ignored. */
	fun read(text: String): Physics3 {
		val file = org.umamo.format.moc3.Moc3.readPhysics3(text)
		val names = file.meta.physicsDictionary.associate { it.id to it.name }
		fun type(t: String) = if (t == "Angle") PhysicsSourceType.ANGLE else PhysicsSourceType.X
		return Physics3(file.physicsSettings.map { s ->
			val n = s.normalization
			RigPhysicsEdit(
				id = s.id,
				name = names[s.id]?.takeIf { it.isNotBlank() } ?: s.id,
				inputs = s.input.map { PhysicsInput(it.source.id, it.weight.content.toFloat(), type(it.type), it.reflect) },
				outputs = s.output.map { PhysicsOutput(it.destination.id, it.vertexIndex, it.scale.content.toFloat(), it.weight.content.toFloat(), type(it.type), it.reflect) },
				segments = s.vertices.drop(1).map { v ->
					PhysicsSegment(v.radius.content.toFloat(), v.mobility.content.toFloat(), v.delay.content.toFloat(), v.acceleration.content.toFloat())
				},
				normalization = PhysicsNormalization(n.position.minimum.content.toFloat(), n.position.default.content.toFloat(), n.position.maximum.content.toFloat(),
					n.angle.minimum.content.toFloat(), n.angle.default.content.toFloat(), n.angle.maximum.content.toFloat()),
			)
		}, file.meta.fps?.content?.toFloatOrNull())
	}

	/** Particle positions down the strand, root first; Cubism reads only the radii, but writes both. */
	internal fun vertexY(setting: RigPhysicsEdit): List<Float> = Cubism3Json.vertexY(group(setting))
}
