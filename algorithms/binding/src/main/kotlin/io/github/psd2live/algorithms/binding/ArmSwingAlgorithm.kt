package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*

/** Adds arm swing to the existing hanging-arm lattices. Body pose grids remain upstream. */
class ArmSwingAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context.values
        require(context["schema"] == JsonPrimitive(SCHEMA) && input.context.resources.isEmpty())
        val dependencies = instance.config.getValue("dependencies").jsonObject
        require(dependencies.keys == setOf("body/legacy", "face/legacy"))
        fun upstream(capability: String, id: String): ModifierUpstreamCapability {
            return requireNotNull(input.providers(capability).singleOrNull {
                it.instanceId == id && it.targets.containsAll(instance.targets)
            }) { "Missing arm swing provider: $capability" }
        }
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val scaffold = upstream("rig.scaffold", scaffoldId)
        require(scaffold.type == "rig.scaffold" && scaffold.version == 2 && scaffold.targets.toSet() == instance.targets.toSet()) {
            "Arm swing requires the complete shared source scaffold v2"
        }
        val body = upstream("body/legacy", dependencies.getValue("body/legacy").jsonPrimitive.content)
        val face = upstream("face/legacy", dependencies.getValue("face/legacy").jsonPrimitive.content)
        require(body.type == "body/legacy" && body.version in 1..2 && body.config["scaffold"] == JsonPrimitive(scaffoldId) &&
            face.type in setOf("face/legacy", "face/fitted", "face") && face.config["scaffold"] == JsonPrimitive(scaffoldId)) {
            "Arm swing requires body and face binding on the same scaffold"
        }
        val source = source(context, checkpoint)
        val targets = context.getValue("targets").jsonObject
        require(targets.keys == instance.targets.mapTo(hashSetOf()) { it.id })
        val provider = RigModifierInstance(scaffoldId, scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val updates = mutableListOf<ModifierObjectUpdate<Deformer>>()
        for ((id, frame) in source.branches.armFrames) {
            checkpoint()
            val members = targets.filterValues { source.branches.parents[it.jsonPrimitive.content]?.id == id }.keys
            if (members.isEmpty()) continue
            val mesh = members.first()
            val liveId = ScaffoldBinding.deformerId(provider, mesh, id.raw)
            val live = input.deformer(liveId) as? Deformer.Warp ?: error("Missing hanging-arm warp: $liveId")
            val native = source.branches.deformers.single { it.id == id }
            val parent = requireNotNull(native.parent)
            require(live.parent == ScaffoldBinding.deformerId(provider, mesh, parent.raw) &&
                members.all { input.mesh(it)?.parent == liveId }) { "Arm swing requires the source arm hierarchy" }
            val rest = (PuppetIr.deformerToIr(native) as Deformer.Warp).lattice!!.cells.single().form
            val lattice = requireNotNull(live.lattice)
            val defaults = lattice.axes.map { axis ->
                val default = requireNotNull(input.parameter(axis.parameter)).default
                axis.keys.shared().indexOfFirst { it == default }
            }
            val liveRest = lattice.cells.singleOrNull { it.coordinate.shared().contentEquals(defaults.toIntArray()) }?.form
            require(live.rows == native.rows && live.columns == native.columns && live.bilinear == native.isQuadTransform &&
                liveRest == rest && live.opacity == 1f && live.multiply == Rgb.White && live.screen == Rgb.Black) {
                "Arm swing requires the source neutral arm lattice and channels"
            }
            val parentFrame = members.map { source.parentFrames.getValue(targets.getValue(it).jsonPrimitive.content) }.distinct().single()
            require(parentFrame.id == parent)
            val torso = source.torso
            val shoulderX = torso.centerX + (if (frame.centerX >= torso.centerX) 1f else -1f) * torso.halfWidth
            val binding = ArmSwingMotion.shape(id.raw.substringAfterLast('_'), native.rows, native.columns, frame, parentFrame.frame, shoulderX)
            val shape = (PuppetIr.deformerToIr(native.copy(blendShapes = listOf(binding))) as Deformer.Warp).shapes.single()
            val existing = live.shapes.singleOrNull { it.parameter == shape.parameter }
            require(existing == null || existing == shape) { "Arm swing shape was already edited: ${shape.parameter}" }
            if (existing == null) updates += ModifierObjectUpdate(live, live.copy(shapes = live.shapes + shape))
        }
        val definitions = definitions(context, source)
        val added = definitions.filter { input.parameter(it.id) == null }
        val parameters = if (added.isEmpty()) emptyList() else input.parameters
        val tree = input.parameterTree
        val nextTree = appendFolder(tree, parameters, added, instance.outputId("parameter-folder:ParamGroupPhysics"),
            context.getValue("folder").jsonPrimitive.content)
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added),
            deformers = ModifierObjectChanges(updated = updates), physicsGroups = physics(input, instance, added, context),
            parameterTree = nextTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun physics(input: ModifierEvaluationInput, instance: RigModifierInstance, added: List<Parameter>, context: JsonObject): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val available = (input.parameters + added).mapTo(hashSetOf()) { it.id }
        val names = context.getValue("physics_names").jsonObject
        val rules = ArmSwingMotion.physics(available) { names.getValue(it).jsonPrimitive.content }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { slot -> rules.any { it.id == slot } }) { "Unknown or unavailable arm physics slot" }
        if (rules.isEmpty()) return ModifierObjectChanges()
        val existing = input.physics.groups
        val additions = mutableListOf<PhysicsGroup>()
        val reused = mutableListOf<PhysicsGroup>()
        for (rule in rules) {
            val edit = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { it in available } &&
                edit.inputs.none { it.parameter in edit.outputParameters }) { "Invalid arm physics slot: ${rule.id}" }
            val group = PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:${rule.id}")))
            val compatible = (existing + additions).firstOrNull { it.copy(id = group.id, name = group.name) == group }
            if (compatible != null) {
                if (compatible in existing && compatible !in reused) reused += compatible
            } else {
                require((existing + additions).none { it.id == group.id || it.outputs.any { output -> output.parameter in edit.outputParameters } }) {
                    "Conflicting arm physics slot: ${rule.id}"
                }
                additions += group
            }
        }
        return ModifierObjectChanges(added = additions, reused = reused)
    }

    private fun appendFolder(tree: List<ParameterNode>, existing: List<Parameter>, added: List<Parameter>, folder: String, name: String): List<ParameterNode> {
        if (added.isEmpty()) return tree
        val nodes = added.map { ParameterNode.Param(it.id) }
        var found = false
        fun insert(items: List<ParameterNode>): List<ParameterNode> = items.map { node ->
            when {
                node !is ParameterNode.Group -> node
                node.id == folder -> node.copy(children = node.children + nodes).also { found = true }
                else -> node.copy(children = insert(node.children))
            }
        }
        val updated = insert(tree.ifEmpty { existing.map { ParameterNode.Param(it.id) } })
        return if (found) updated else updated + ParameterNode.Group(folder, name, true, nodes)
    }

    companion object {
        const val TYPE = "body.arms"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.626f64792e61726d73.v2/swing"
        const val SCHEMA = "body-arms/1"
        val WRITES = setOf("deformer", "parameter", "hierarchy", "physics")

        private data class Source(val torso: TorsoFrame, val branches: ScaffoldBranchesContext.Result,
                                  val parentFrames: Map<String, ScaffoldArms.Parent>)

        private fun source(context: JsonObject, checkpoint: () -> Unit): Source {
            val torso = LegacyBodyInputs.torso(context.getValue("torso"))
            val frames = ScaffoldFramesContext.build(context.getValue("frames").jsonObject, checkpoint)
            val branches = context.getValue("branches").jsonObject
            val native = ScaffoldBranchesContext.build(branches, frames, checkpoint)
            if (native.armFrames.isEmpty()) return Source(torso, native, emptyMap())
            // The scaffold installs arm parents after pairing. Read the paired frame before that last step.
            // Pair naming title-cases the semantic fallback, so the spelling below preserves pair IDs.
            val pairedLayers = JsonArray(branches.getValue("layers").jsonArray.map { layer ->
                val fields = layer.jsonObject
                if (fields["tag"] == JsonPrimitive("HANDWEAR")) JsonObject(fields + ("tag" to JsonPrimitive("Handwear"))) else layer
            })
            val paired = ScaffoldBranchesContext.build(JsonObject(branches + ("layers" to pairedLayers)), frames, checkpoint)
            return Source(torso, native, paired.parents)
        }

        private fun definitions(context: JsonObject, source: Source): List<Parameter> {
            val hasArms = context.getValue("branches").jsonObject.getValue("layers").jsonArray.any {
                it.jsonObject["tag"] == JsonPrimitive("HANDWEAR")
            }
            if (!hasArms) return emptyList()
            val names = context.getValue("parameter_names").jsonObject
            return listOf("L", "R").map { side ->
                val id = "ParamArm${side}Swing"
                val blend = source.branches.armFrames.keys.any { it.raw == "DeformArmHang_$side" }
                Parameter(id, names.getValue(side).jsonPrimitive.content, -1f, 1f, 0f, blend = blend,
                    keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> = definitions(context.values, source(context.values) {})
    }
}
