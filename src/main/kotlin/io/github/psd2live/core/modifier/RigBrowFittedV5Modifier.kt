package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowFittedV5Binding
import io.github.psd2live.algorithms.binding.EyeFittedInputs
import io.github.psd2live.algorithms.binding.FittedHeadTurnSource
import io.github.psd2live.algorithms.binding.LegacyBodyInputs
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import org.umamo.runtime.model.DrawableId
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.EyeArtworkFitting
import io.github.psd2live.core.fit.EyeFitting
import io.github.psd2live.core.fit.FaceLandmarking
import io.github.psd2live.core.fit.fitBrow
import io.github.psd2live.core.fit.moved
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterNode
import org.umamo.format.art.LayerBlend
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** An explicit, narrow source boundary. No generated pose or lowered model is captured here. */
internal object RigBrowFittedV5Modifier {
    private val algorithm = object : ChangingModifierAlgorithmPlugin by BrowFittedV5Binding,
        ModifierVersionSelection {
        override val canonicalOptInType = "brow/fitted"
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigBrowFittedModifier.definition(source).copy(
            version = BrowFittedV5Binding.VERSION, plugin = algorithm,
            requires = BrowFittedV5Binding.requiredVersions.keys,
            providers = { instance -> source?.providers(instance) ?: RigLegacyBindings.providers(instance) },
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.providers(instance: RigModifierInstance): Map<String, String> {
        val active = sourcePrefix(instance)
        val selected = RigLegacyBindings.providers(instance)
        val scaffold = instance.config.getValue("scaffold")
        return BrowFittedV5Binding.requiredVersions.mapValues { (type, version) ->
            active.singleOrNull { owner -> owner.type == type && owner.version == version &&
                owner.targets.toSet() == instance.targets.toSet() &&
                (if (type == "rig.scaffold") JsonPrimitive(owner.id) == scaffold else owner.config["scaffold"] == scaffold) &&
                (selected[type] == null || selected[type] == owner.id)
            }?.id ?: generateSequence("${instance.id}/missing:$type") { "$it?" }
                .first { unavailable -> active.none { it.id == unavailable } }
        }
    }

    /** Inspect raw anonymous records and every intersecting named owner before scaffold selection. */
    private fun RigModifierSource.sourcePrefix(instance: RigModifierInstance): List<RigModifierInstance> {
        require(appliedSourceCapabilities.none { owner ->
            owner.id == null && owner.targets.any(instance.targets::contains)
        }) { "Fitted brow v5 requires identifiable applied source providers" }
        val preceding = activeBefore(instance).filter { owner -> owner.targets.any(instance.targets::contains) }
        val scaffold = instance.config.getValue("scaffold")
        require(preceding.map { it.id }.distinct().size == preceding.size) {
            "Fitted brow v5 requires unique source provider identities"
        }
        require(preceding.all { owner ->
            BrowFittedV5Binding.requiredVersions[owner.type] == owner.version &&
                owner.targets.toSet() == instance.targets.toSet() &&
                (if (owner.type == "rig.scaffold") JsonPrimitive(owner.id) == scaffold else owner.config["scaffold"] == scaffold) &&
                BundledAlgorithmArtifacts.find(owner.type, owner.version)?.reference?.let { it == owner.plugin } == true
        }) { "Fitted brow v5 rejects unknown, foreign or incompatible source providers" }
        return preceding
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val preceding = sourcePrefix(instance)
        val scaffold = provider(instance)
        require(preceding.singleOrNull { it.id == scaffold.id } == scaffold) {
            "Fitted brow v5 requires the exact selected source scaffold"
        }
        val prefix = preceding.filterNot { it.id == scaffold.id }
        require(prefix.map { it.type to it.version } == listOf("body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5)) {
            "Fitted brow v5 requires the complete body2, face3, eye5 prefix in that order"
        }
        val owners = listOf(scaffold) + prefix
        val providers = owners.map { owner -> ModifierUpstreamCapability(owner.type, owner.type, owner.version,
            owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin) }
        BrowFittedV5Binding.validateProviders(providers, instance)
        val packages = owners.associate { owner ->
            val bundled = requireNotNull(BundledAlgorithmArtifacts.find(owner.type, owner.version)).reference
            require(owner.plugin == bundled) {
                "Fitted brow v5 requires the current built-in ${owner.type}@${owner.version} source contract"
            }
            owner.id to Json.encodeToJsonElement(ModifierPluginReference.serializer(), bundled)
        }
        RigBuildProfile.count("modifier brow v5 source measurements")
        val original = sourceConfig()
        require(analysis.source.groups.isEmpty() && analysis.source.layers.all { it.groupPath.isEmpty() }) {
            "Fitted brow v5 initially requires flat source artwork without folders"
        }
        require(!skeletonEnabled && analysis.layers.all { layer ->
            layer.opaquePixels > 0 && layer.source.id.raw in atlas.placementByLayerId &&
                layer.semantic.type == LayerType.PRESET && layer.semantic.parameter.isBlank() &&
                layer.source.blend == LayerBlend.Normal && !layer.source.clipped &&
                (original.layerOverrides[layer.source.id.raw]?.type ?: LayerType.PRESET) == LayerType.PRESET &&
                original.layerOverrides[layer.source.id.raw]?.parameter.orEmpty().isBlank()
        }) { "Fitted brow v5 initially requires opaque preset source drawings with ordinary materials" }
        val allowed = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE,
            SemanticTag.EYEBROW, SemanticTag.MOUTH, SemanticTag.FACE, SemanticTag.NOSE,
            SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR)
        require(analysis.layers.all { it.semantic.tag in allowed } &&
            analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR,
                SemanticTag.TOPWEAR, SemanticTag.FACE, SemanticTag.MOUTH, SemanticTag.EYEBROW) }.all { it.semantic.role == null } &&
            analysis.layers.count { it.semantic.tag == SemanticTag.MOUTH } == 1 &&
            analysis.layers.single { it.semantic.tag == SemanticTag.MOUTH }.semantic.side == Side.NONE &&
            analysis.layers.count { it.semantic.tag == SemanticTag.FACE } == 1 &&
            analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH,
                SemanticTag.EYE_CLOSE, SemanticTag.IRIDES, SemanticTag.EYEBROW) }.all { it.semantic.side == Side.LEFT }) {
            "Fitted brow v5 initially supports one left eye, a same-side brow and one whole open mouth without interiors"
        }
        val targets = instance.targets.associate { target -> target.id to layersByMesh.getValue(target.id) }
        require(targets.values.toSet() == analysis.layers.mapTo(hashSetOf()) { it.source.id.raw } &&
            targets.all { (id, layer) -> primaryByLayer[layer] == id } && targets.size == analysis.layers.size) {
            "Fitted brow v5 requires the complete unsplit source target inventory"
        }
        val canonical = targets.entries.associate { it.value to it.key }
        val organization = ScaffoldPartsContext.capture(analysis.layers.map { layer ->
            ScaffoldParts.Material(DrawableId(canonical.getValue(layer.source.id.raw)),
                RigBuilder.scaffoldSlot(layer, analysis.anchors), layer.source.order)
        }, label = { tr(it) })
        val art = RigSourceCapture.capture(analysis.layers, targets).context()
        require(art.resources.keys.none { it in setOf("before_meshes", "after_meshes") }) {
            "Fitted brow v5 source raster IDs conflict with its topology resources"
        }
        val resources = art.resources.toMutableMap()
        val eye = prefix.last()
        val old = RigFittedMouthModuleSource.nativeConfig(this, scaffold, prefix, null)
        val next = RigFittedMouthModuleSource.nativeConfig(this, scaffold, prefix + instance, null)
        fun phase(name: String, config: PipelineConfig, fittedBrow: Boolean): JsonObject {
            val structure = RigBuilder.scaffoldSourceInputs(analysis, config, meshCache)
            val meshes = RigBuilder.scaffoldMeshInputs(analysis, atlas, config, meshCache, canonical)
            resources["${name}_meshes"] = meshes.resources.getValue("source_meshes")
            // A no-deformer context measures mesh-framed art only. headSpaceFor intentionally
            // returns null in that context, so every fitted observation below gets an explicit
            // inferred HEAD alignment instead of asking a native binding for its parent.
            val measured = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), meshCache)
            val layers = measured.analysis.layers
            val face = measured.faceRig
            val space = measured.headSpace
            require(!measured.deformersEnabled && measured.deformers.isEmpty()) {
                "Fitted brow v5 $name measurements must not generate native deformers"
            }
            require(face.initialAngleZ == 0f) {
                "Fitted brow v5 initially requires upright source measurements: $name angle=${face.initialAngleZ}"
            }
            val radians = space.angleDegrees * PI.toFloat() / 180f
            val alignment = EyeFittedInputs.Alignment(space.pivotX, space.pivotY, cos(radians), sin(radians))
            val eyeLayers = layers.filter { it.semantic.tag in setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH,
                SemanticTag.EYE_CLOSE, SemanticTag.IRIDES) }
            val firstWhite = eyeLayers.firstOrNull { it.semantic.tag == SemanticTag.EYEWHITE }
            require(firstWhite != null && firstWhite.semantic.tag.group == LayerGroup.HEAD)
            val toRig = alignment::point
            val eyeFit = EyeFitting.fit(layers, face.centerX, toRig)
            require(eyeFit.fits.keys == setOf(Side.LEFT)) { "Fitted brow v5 requires one measurable left eye" }
            fun moves(feature: String) = measured.rigMoves(config, feature, toRig)
            val browLayers = layers.filter { it.semantic.tag == SemanticTag.EYEBROW && it.semantic.role == null }
            require(browLayers.size == 1 && browLayers.all { it.semantic.tag.group == LayerGroup.HEAD })
            val browFits = if (!fittedBrow) emptyMap() else browLayers.groupBy { it.semantic.side }.mapValues { (side, group) ->
                requireNotNull(fitBrow(group.maxBy { it.opaquePixels }, eyeFit.level, face.centerX, toRig))
                    .moved(moves(if (side == Side.LEFT) "brow.L" else "brow.R"))
            }
            val marks = FaceLandmarking.read(layers, eyeFit, browFits, null, face.centerX, face.coordinateSpace::toAligned)
            val turn = FittedHeadTurnSource(face.centerX, face.centerY, face.radiusX, face.radiusY, face.chinY,
                config.recipe.value(RigModules.FACE_DEPTH_TURN.id, "turn") / 100f * config.headTurnStrength,
                config.recipe.value(RigModules.FACE_DEPTH_TURN.id, "depth") / 100f, marks, RigModules.turnStyle(config))
            require(turn.styledTurn() == null) { "Fitted brow v5 initially requires the single-eye shell fallback" }
            val captured = ModifierAlgorithmContext(buildJsonObject {
                put("schema", "scaffold/5"); put("organization", organization)
                put("frames", structure.frames); put("branches", structure.branches)
                put("mesh_inputs", meshes.values.getValue("mesh_inputs"))
            })
            val angles = StandardParameters.all.take(3)
            val legacy = LegacyFaceInputs.capture(captured, face.centerY, face.radiusX, face.radiusY, face.eyeLineY,
                config.rigTuning.legacyFaceTuning(), config.headTurnStrength, angles.map { it.name }, tr("model.group.face"))
            val body = LegacyBodyInputs.capture(captured, structure.torso, config.rigTuning.bodyMotion(), emptyMap(), emptyMap()).values
            val eyeInput = EyeFittedInputs(face.centerX, alignment, alignment, emptyMap(),
                eyeLayers.filter { it.semantic.tag == SemanticTag.EYELASH }.associate { it.source.id.raw to EyeArtworkFitting.stamp(it) },
                config.recipe.eyeParts, mapOf("LEFT" to moves("eye.L"), "RIGHT" to moves("eye.R")),
                emptyMap(), tr("model.group.eyes"))
            val tuning = config.rigTuning
            val ordered = analysis.layers.sortedBy { it.source.order }
            val step = RigBuilder.generatedOrderStep(original.drawOrderStep, ordered.size)
            val order = ordered.mapIndexed { i, layer -> layer.source.id.raw to
                (original.drawOrderOverrides[layer.source.id.raw] ?: original.drawOrderOverrides[canonical.getValue(layer.source.id.raw)]
                    ?: ((ordered.size - i) * step).toFloat()).coerceIn(0f, 1000f) }.toMap()
            return buildJsonObject {
                put("base", legacy.values)
                put("source", RigSourceCapture.capture(layers, targets).context().values)
                put("body", JsonObject(body.filterKeys { it in setOf("torso", "tuning") } + ("strength" to JsonPrimitive(config.bodyStrength))))
                put("head_turn", turn.encode())
                put("head_alignment", numbers(space.angleDegrees, space.pivotX, space.pivotY))
                put("container", numbers(face.centerX, face.mouthLineY, config.headTurnStrength,
                    tuning.headShellTurn, tuning.headShellCrown, tuning.headShellTilt))
                put("eye_input", Json.encodeToJsonElement(eyeInput))
                put("eye_values", JsonObject(RigLegacyBindings.values(eye).mapValues { JsonPrimitive(it.value) }))
                put("eye_tuning", numbers(tuning.blinkCorner, tuning.blinkDepth, tuning.eyelashSquash,
                    tuning.irisJellySquash, tuning.irisJellyStretch, tuning.gazeX, tuning.gazeY))
                put("hair_tuning", numbers(tuning.frontHairSway, tuning.frontHairCurl, tuning.backHairSway, tuning.backHairCurl))
                putJsonObject("brow_fits") {
                    for ((side, fit) in browFits) put(side.name,
                        numbers(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.length, *fit.centre.toFloatArray()))
                }
                put("brow_lift", RigLegacyBindings.values(instance)["lift"] ?: 45f)
                put("teeth_fade", tuning.teethFade)
                put("shape_name", tr("model.deformer.meshShape", "{meshName}"))
                put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
                putJsonArray("layers") {
                    val bySource = layers.associateBy { it.source.id.raw }
                    // Native v4 lowers meshes in target order, which also allocates carrier IDs.
                    for (sourceId in targets.values) {
                        val layer = bySource.getValue(sourceId)
                        val id = canonical.getValue(layer.source.id.raw)
                        add(buildJsonObject {
                            put("id", id); put("source_id", layer.source.id.raw); put("name", layer.source.name)
                            put("tag", layer.semantic.tag.name); put("side", layer.semantic.side.name)
                            put("role", layer.semantic.role?.name?.let(::JsonPrimitive) ?: JsonNull)
                            put("draw_order", order.getValue(layer.source.id.raw)); put("opacity", layer.source.opacity.coerceIn(0f, 1f))
                            put("texture_page", atlas.placementByLayerId.getValue(layer.source.id.raw).page)
                            put("atlas_tile", PuppetSourceAtlas.tileIdFor(layer.source.id.raw).raw)
                            put("visible", original.layerVisibility[layer.source.id.raw] ?: layer.source.visible)
                            put("aligned", layer.semantic.tag.group == LayerGroup.HEAD)
                            put("masked_by", JsonArray(emptyList()))
                        })
                    }
                }
                putJsonArray("parameters") {
                    for (p in StandardParameters.all) add(buildJsonObject {
                        put("id", p.id.raw); put("name", p.name); put("min", p.min); put("max", p.max); put("default", p.default)
                    })
                }
                putJsonArray("groups") {
                    for (group in StandardParameters.tree().filterIsInstance<ParameterNode.Group>()) add(buildJsonObject {
                        put("id", group.id.raw); put("name", group.name)
                        put("parameters", JsonArray(group.children.filterIsInstance<ParameterNode.Param>().map { JsonPrimitive(it.id.raw) }))
                    })
                }
                put("axes", JsonArray((prefix + if (fittedBrow) listOf(instance) else emptyList())
                    .flatMap { RigLegacyBindings.spec(it.type).parameters }.distinct().map { JsonPrimitive(it.raw) }))
            }
        }
        val before = phase("before", old, false)
        val after = phase("after", next, true)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", BrowFittedV5Binding.SCHEMA)
            put("providers", JsonArray(providers.map { Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(), it) }))
            put("provider_packages", JsonObject(packages)); put("source", art.values)
            put("before", before); put("after", after)
        }, resources)
    }

    private fun numbers(vararg values: Float) = JsonArray(values.map { value ->
        require(value.isFinite()); JsonPrimitive(value)
    })
}
