package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR

/** A conservative component dependency key. Writes are reads too, so replay cannot erase another writer. */
internal object ModifierInputCache {
    fun project(rig: RigIR, kinds: Set<String>): RigIR {
        if ("*" in kinds || kinds.any { it !in known }) return rig
        return rig.copy(
            parameters = if ("parameter" in kinds) rig.parameters else emptyList(),
            meshes = if ("mesh" in kinds || "relations" in kinds) rig.meshes else emptyList(),
            deformers = if ("deformer" in kinds) rig.deformers else emptyList(),
            parts = if ("part" in kinds) rig.parts else emptyList(),
            glues = if ("glue" in kinds) rig.glues else emptyList(),
            clips = if ("motion" in kinds) rig.clips else emptyList(),
            physics = if ("physics" in kinds) rig.physics else io.github.psd2live.format.model.Physics(),
            textures = if ("textures" in kinds) rig.textures else io.github.psd2live.format.model.Textures(),
            parameterLinks = if ("relations" in kinds) rig.parameterLinks else emptyList(),
            parameterRoles = if ("relations" in kinds) rig.parameterRoles else emptyList(),
            parameterTree = if ("hierarchy" in kinds) rig.parameterTree else emptyList(),
            rootPart = if ("hierarchy" in kinds) rig.rootPart else null,
            rootChildren = if ("hierarchy" in kinds) rig.rootChildren else emptyList(),
            renderRoot = if ("hierarchy" in kinds) rig.renderRoot else io.github.psd2live.format.model.RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, emptyList()),
        )
    }
    fun input(rig: RigIR, kinds: Set<String>): Any {
        if ("*" in kinds || kinds.any { it !in known }) return rig
        return kinds.sorted().map { kind -> when (kind) {
            "mesh" -> rig.meshes
            "deformer" -> rig.deformers
            "part" -> rig.parts
            "parameter" -> rig.parameters
            "physics" -> rig.physics
            "motion" -> rig.clips
            "glue" -> rig.glues
            "textures" -> rig.textures
            "hierarchy" -> listOf(rig.rootPart, rig.rootChildren, rig.parameterTree, rig.renderRoot)
            "relations" -> listOf(rig.parameterLinks, rig.parameterRoles, rig.meshes)
            else -> rig
        } } + listOf(rig.canvas, rig.restPose, rig.authoring, rig.advanced, rig.hitAreas)
    }
    private val known = setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue", "textures", "hierarchy", "relations")

    /** Replace only changed components; untouched components always come from the actual upstream input. */
    class Patch(before: RigIR, after: RigIR) {
        // Captured IR snapshots are immutable. Compare once, not on every cache replay.
        private class Field<T>(before: T, private val after: T) {
            private val changed = before != after
            fun apply(current: T): T = if (changed) after else current
        }
        private val canvas = Field(before.canvas, after.canvas)
        private val parameters = Field(before.parameters, after.parameters)
        private val parameterLinks = Field(before.parameterLinks, after.parameterLinks)
        private val parameterTree = Field(before.parameterTree, after.parameterTree)
        private val parameterRoles = Field(before.parameterRoles, after.parameterRoles)
        private val parts = Field(before.parts, after.parts)
        private val rootChildren = Field(before.rootChildren, after.rootChildren)
        private val rootPart = Field(before.rootPart, after.rootPart)
        private val deformers = Field(before.deformers, after.deformers)
        private val meshes = Field(before.meshes, after.meshes)
        private val glues = Field(before.glues, after.glues)
        private val renderRoot = Field(before.renderRoot, after.renderRoot)
        private val textures = Field(before.textures, after.textures)
        private val physics = Field(before.physics, after.physics)
        private val clips = Field(before.clips, after.clips)
        private val restPose = Field(before.restPose, after.restPose)
        private val authoring = Field(before.authoring, after.authoring)
        private val advanced = Field(before.advanced, after.advanced)
        private val hitAreas = Field(before.hitAreas, after.hitAreas)

        fun apply(current: RigIR): RigIR = current.copy(
            canvas = canvas.apply(current.canvas),
            parameters = parameters.apply(current.parameters),
            parameterLinks = parameterLinks.apply(current.parameterLinks),
            parameterTree = parameterTree.apply(current.parameterTree),
            parameterRoles = parameterRoles.apply(current.parameterRoles),
            parts = parts.apply(current.parts),
            rootChildren = rootChildren.apply(current.rootChildren),
            rootPart = rootPart.apply(current.rootPart),
            deformers = deformers.apply(current.deformers),
            meshes = meshes.apply(current.meshes),
            glues = glues.apply(current.glues),
            renderRoot = renderRoot.apply(current.renderRoot),
            textures = textures.apply(current.textures),
            physics = physics.apply(current.physics),
            clips = clips.apply(current.clips),
            restPose = restPose.apply(current.restPose),
            authoring = authoring.apply(current.authoring),
            advanced = advanced.apply(current.advanced),
            hitAreas = hitAreas.apply(current.hitAreas),
        )
    }
    data class ReadEntry(val instance: RigModifierInstance, val plugin: ModifierAlgorithmPlugin,
                         val context: ModifierAlgorithmContext, val reads: ModifierReadSet, val changes: ModifierModelChanges)
    data class Entry(val instance: RigModifierInstance, val input: Any, val patch: Patch)
}
