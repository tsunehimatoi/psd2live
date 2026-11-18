package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BindingAxes
import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/**
 * Authored visibility belongs to the host, like generated-content overrides, not to a particular
 * build of the fitting algorithm. Saved V1 workers keep their exact geometry and input contract.
 * An absent setting is deliberately a no-op: merely opening a project never migrates its output.
 */
internal object MouthVisibilityCustomization {
    fun supports(instance: RigModifierInstance): Boolean = instance.type == "mouth/fitted" ||
        instance.type == "mouth" && (instance.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted"

    fun algorithmInstance(instance: RigModifierInstance): RigModifierInstance =
        if (instance.type in setOf("mouth", "mouth/fitted") && ClosedMouthVisibility.CONFIG in instance.config)
            instance.copy(config = JsonObject(instance.config - ClosedMouthVisibility.CONFIG)) else instance

    /** Ribbons inherit their open-art owner's opacity, including when generated inside a folded part. */
    fun outlineOwners(instance: RigModifierInstance, targets: Set<String>): Map<String, String> {
        val outline = when (instance.type) {
            "mouth" -> ModifierPartConfig.child(instance, "mouth.outline")
            "mouth.outline" -> instance
            else -> return emptyMap()
        }
        return outline.targets.filter { it.id in targets }.flatMap { target ->
            (0..1).map { side -> outline.outputId("mouth-lip:${target.id}:$side") to target.id }
        }.toMap()
    }

    fun protectedTracks(source: RigModifierSource?, stack: RigModifierStack?): Set<String> {
        if (source == null || stack == null) return emptySet()
        val targets = stack.instances.filter { it.enabled && supports(it) && ClosedMouthVisibility.CONFIG in it.config }
            .flatMapTo(HashSet()) { instance ->
                val inputs = MouthFittedInputs.decode(source.mouthFittedContext(instance))
                ClosedMouthVisibility.targets(inputs, instance.targets.mapTo(HashSet()) { it.id })
            }
        if (targets.isEmpty()) return targets
        return targets + stack.instances.filter { it.enabled }.flatMap { outlineOwners(it, targets).keys }
    }

    /** Moving or deleting a global deformation stop must preserve explicitly authored visibility. */
    fun keepOpacity(before: PuppetModel, after: PuppetModel, targets: Set<String>): PuppetModel {
        if (targets.isEmpty() || before === after) return after
        val opacity = FormChannel.OPACITY
        val tracks = before.drawables.filter { it.id.raw in targets }.associate { it.id to it.channelGrids[opacity] }
        return after.copy(drawables = after.drawables.map { drawable ->
            val track = tracks[drawable.id]
            if (track == null || track === drawable.channelGrids[opacity]) drawable
            else drawable.copy(channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel + (opacity to track)))
        })
    }

    fun apply(source: RigModifierSource?, before: RigIR, generated: RigIR, instance: RigModifierInstance): RigIR {
        if (source == null || !supports(instance)) return generated
        val curve = ClosedMouthVisibility.fromConfig(instance.config) ?: return generated
        val inputs = MouthFittedInputs.decode(source.mouthFittedContext(instance))
        val targets = ClosedMouthVisibility.targets(inputs, instance.targets.mapTo(HashSet()) { it.id })
        if (targets.isEmpty()) return generated
        val originalOpacity = before.meshes.associate { it.id to it.opacity }
        val owners = targets.associateWith { it } + outlineOwners(instance,
            targets.filterTo(HashSet()) { inputs.layers.getValue(it).tag != "MOUTH_CLOSE" })
        val model = PuppetIr.toPuppet(generated)
        val defaults = model.parameters.associate { it.id to it.default }
        return generated.withPuppet(model.copy(drawables = model.drawables.map { drawable ->
            val owner = owners[drawable.id.raw]
            if (owner == null) drawable else customize(drawable, curve,
                inputs.layers.getValue(owner).tag == "MOUTH_CLOSE",
                originalOpacity[owner] ?: drawable.opacity, defaults)
        }))
    }

    internal fun customize(drawable: Drawable, curve: ClosedMouthVisibility, closed: Boolean,
                           sourceOpacity: Float, defaults: Map<ParameterId, Float>): Drawable {
        val channel = FormChannel.OPACITY
        val open = ClosedMouthVisibility.PARAMETER
        val rest = { id: ParameterId -> defaults[id] ?: 0f }
        fun sample(values: (ParameterId) -> Float) = drawable.channelGrids.scalarAt(channel, drawable.opacity, values)
        val visibleAt = if (closed) 0f else 1f
        val hiddenAt = 1f - visibleAt
        val visibleAlpha = sample { if (it == open) visibleAt else rest(it) }
        fun ratio(value: Float, scale: Float) = if (scale > 0f) (value / scale).coerceIn(0f, 1f) else 0f
        // A folded mouth may also have lip-press/capture opacity axes. Retain each slice's
        // forced-visible/hidden endpoints instead of replacing the whole multidimensional track.
        // Moving/deleting an endpoint holds its nearest authored value to the edge of Mouth Open.
        val keys = (listOf(0f, 1f) + curve.points.map { it.open }).distinct().sorted()
        val values = keys.map(curve::sample)
        val axes = listOf(KeyformAxis(open, keys.toFloatArray())) +
            drawable.channelGrids[channel]?.axes.orEmpty().filter { it.parameterId != open }
        val grid = KeyformGrid(axes, BindingAxes.coordinates(axes).map { coordinate ->
            val pose = BindingAxes.key(axes, coordinate)
            val on = sample { if (it == open) visibleAt else pose[it] ?: rest(it) }
            val off = sample { if (it == open) hiddenAt else pose[it] ?: rest(it) }
            val opacity = values[coordinate[0]].let { if (closed) it else 1f - it }
            // Capture effects already use source alpha. Scale only the remaining swap contribution,
            // not a forced-visible term shared by both endpoints (e.g. closed art during lip press).
            KeyformCell<ChannelValue>(coordinate, ChannelValue.Scalar(
                (off + sourceOpacity * ratio(on - off, visibleAlpha) * opacity).coerceIn(0f, sourceOpacity)))
        })
        val channels = ChannelGrids(drawable.channelGrids.gridsByChannel + (channel to grid))
        val oldRest = sample(rest)
        val newRest = channels.scalarAt(channel, drawable.opacity, rest)
        // Blend shapes store absolute channel values relative to the neutral pose. Rebase only
        // their opacity: neutral geometry shapes remain neutral, and opening shapes still swap art.
        fun rebase(alpha: Float): Float = when {
            alpha.isNaN() -> alpha
            alpha <= oldRest -> newRest * ratio(alpha, oldRest)
            else -> newRest + (sourceOpacity - newRest) * ratio(alpha - oldRest, sourceOpacity - oldRest)
        }
        return drawable.copy(channelGrids = channels, blendShapes = drawable.blendShapes.map { binding ->
            binding.copy(forms = binding.forms.map { form -> form?.let {
                MeshForm(it.positionDeltas, it.drawOrder, if (it.opacity == oldRest) newRest else rebase(it.opacity),
                    it.multiplyColor, it.screenColor)
            } })
        })
    }
}
