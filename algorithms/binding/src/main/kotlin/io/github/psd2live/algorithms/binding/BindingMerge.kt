package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.targets.cubism.PuppetIr
import io.github.psd2live.algorithms.binding.BindingFrames.reparent
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.*
import org.umamo.edit.withParameterRange

/** Applies a source algorithm transition onto the actual preceding rig, preserving authored residuals. */
object BindingMerge {
    /** Candidate-scoped read-only inputs; implementations must not access live project/UI state or publish edits. */
    interface Source {
        fun provider(instance: RigModifierInstance): RigModifierInstance
        fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Pair<PuppetModel, PuppetModel>
        fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                             declared: io.github.psd2live.format.model.Parameter): io.github.psd2live.format.model.Parameter?
        fun static(model: PuppetModel): PuppetModel
        fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>): Boolean
        fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Map<DeformerId, DeformerId>
        fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Map<PartId, PartId>
        fun requiredParts(model: PuppetModel, target: ModifierTarget): Set<PartId>
        fun partId(instance: RigModifierInstance, mesh: String, canonical: String,
                   shared: Boolean = instance.type == "rig.scaffold" && instance.version >= 2): String
        fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                          checkpoint: () -> Unit): List<org.umamo.edit.VertexSource>
    }

    fun apply(input: PuppetModel, instance: RigModifierInstance, source: Source, checkpoint: () -> Unit): PuppetModel {
        val provider = source.provider(instance)
        var current = input
        val updatedDeformers = LinkedHashMap<DeformerId, Deformer>()
        val addedDeformers = HashSet<DeformerId>()
        val obsoleteCarriers = LinkedHashSet<DeformerId>()
        val addedParts = HashSet<PartId>()
        val preserveSourceOrder = instance.outputAliases.keys.any { it.startsWith("parameter-folder:") }
        for (target in instance.targets) {
            checkpoint()
            val (previous, desired) = source.transition(instance, target, checkpoint)
            val existingDefinitions = PuppetIr.parametersToIr(current).associateBy { it.id }
            for (definition in PuppetIr.parametersToIr(desired)) {
                val before = existingDefinitions[definition.id] ?: continue
                val resolved = source.resolveParameter(instance, before, definition) ?: continue
                current = current.withParameterRange(ParameterId(resolved.id), resolved.min, resolved.default, resolved.max)
            }
            val additions = desired.parameters.filter { parameter -> current.parameters.none { it.id == parameter.id } }
            val folders = LinkedHashSet<ParameterGroupId>()
            fun collect(nodes: List<ParameterNode>) { nodes.filterIsInstance<ParameterNode.Group>().forEach { folders += it.id; collect(it.children) } }
            collect(desired.parameterTree)
            val treeIds = ModelIdRemap(groups = folders.associateWith { ParameterGroupId(instance.outputId("parameter-folder:${it.raw}")) })
            current = current.copy(parameters = current.parameters + additions,
                parameterTree = appendTree(current, desired.parameterTree.map(treeIds::node), additions.mapTo(HashSet()) { it.id }, preserveSourceOrder))
            val links = desired.parameterLinks.filter { it !in previous.parameterLinks && it !in current.parameterLinks }
            for (link in links) require(current.parameterLinks.none { existing ->
                existing.horizontal == link.horizontal || existing.vertical == link.horizontal ||
                    existing.horizontal == link.vertical || existing.vertical == link.vertical
            }) { "${instance.id}: incompatible parameter link for ${link.horizontal.raw} and ${link.vertical.raw}" }
            val combinedLinks = current.parameterLinks + links
            current = current.copy(parameterLinks = if (preserveSourceOrder) sourceOrder(combinedLinks, desired.parameterLinks) { it } else combinedLinks)
            val defaults = current.parameters.associate { it.id to it.default }
            val oldProjection by lazy(LazyThreadSafetyMode.NONE) { previous.copy(parameters = desired.parameters, deformers = source.static(previous).deformers) }
            val nextProjection by lazy(LazyThreadSafetyMode.NONE) { desired.copy(deformers = source.static(desired).deformers) }
            val previousById = previous.deformers.associateBy { it.id }
            val desiredById = desired.deformers.associateBy { it.id }
            val id = DrawableId(target.id)
            val sample = current.drawables.single { it.id == id }
            val branch = LinkedHashSet<DeformerId>()
            var parent = previous.drawables.single { it.id == id }.parentDeformerId
            while (parent != null) {
                require(branch.add(parent)) { "Cyclic binding hierarchy" }
                parent = previousById.getValue(parent).parent
            }
            val desiredBranch = ArrayList<DeformerId>()
            var nextParent = desired.drawables.single { it.id == id }.parentDeformerId
            while (nextParent != null) {
                require(nextParent !in desiredBranch) { "Cyclic generated binding hierarchy" }
                desiredBranch += nextParent
                nextParent = desiredById.getValue(nextParent).parent
            }
            if (desiredBranch == branch.toList() && source.onlyMasksChange(previous, desired, target, branch)) {
                val masks = masks(sample, previous.drawables.single { it.id == id }, desired.drawables.single { it.id == id }, instance)
                if (sample.maskedBy != masks.first || sample.invertMask != masks.second)
                    current = current.copy(drawables = current.drawables.map { if (it.id == id) it.copy(maskedBy = masks.first, invertMask = masks.second) else it })
                continue
            }
            val mapped = source.deformerIds(instance, target, checkpoint)
            val nativeGeometry = sameMeshGeometry(sample, previous.drawables.single { it.id == id }) &&
                sample.parentDeformerId == previous.drawables.single { it.id == id }.parentDeformerId?.let(mapped::getValue) &&
                branch.all { canonical ->
                    val actual = updatedDeformers[mapped.getValue(canonical)] ?: current.deformers.singleOrNull { it.id == mapped.getValue(canonical) }
                    actual != null && PuppetIr.deformerToIr(actual) == PuppetIr.deformerToIr(ModelIdRemap(deformers = mapped).deformer(previousById.getValue(canonical))).let {
                        // Part ownership does not change the coordinate frame.
                        when (it) {
                            is io.github.psd2live.format.model.Deformer.Warp -> it.copy(part = actual.partId?.raw)
                            is io.github.psd2live.format.model.Deformer.Rotation -> it.copy(part = actual.partId?.raw)
                        }
                    }
                }
            val common = branch.toList().asReversed().zip(desiredBranch.asReversed()).takeWhile { it.first == it.second }.map { it.first }.asReversed()
            val added = desiredBranch.dropLast(common.size)
            val removed = branch - common.toSet()
            require(added.none { it in branch }) { "${instance.id}: incompatible binding branch order" }
            val anchor = common.firstOrNull()?.let(mapped::getValue)
            val partIds = if (removed.isNotEmpty() || added.isNotEmpty()) source.partIds(instance, target, checkpoint) else emptyMap()
            val remap = ModelIdRemap(deformers = mapped, parts = partIds)
            // Bypass an unchanged obsolete native carrier. Keeping it in the evaluation path can distort the
            // new lattice at non-neutral poses. An authored residual, including a wrapper, stays in the path.
            val bypass = removed.isNotEmpty() && sample.parentDeformerId == branch.firstOrNull()?.let(mapped::getValue) && removed.all { canonical ->
                val expected = remap.deformer(previousById.getValue(canonical))
                val actual = current.deformers.singleOrNull { it.id == expected.id }
                actual != null && PuppetIr.deformerToIr(actual) == PuppetIr.deformerToIr(expected)
            }
            if (bypass) obsoleteCarriers += removed.map(mapped::getValue)
            if (added.isNotEmpty() || bypass) {
                val beforeInsertion = current
                val actualParent = if (bypass) anchor else sample.parentDeformerId
                val requiredParts = source.requiredParts(desired, target)
                for (part in requiredParts) {
                    val generated = PartId(source.partId(instance, target.id, part.raw))
                    require(remap.part(part) != generated || current.parts.none { it.id == generated } || generated in addedParts ||
                        provider.outputAliases["shared/part:${part.raw}"] == generated.raw) {
                        "${instance.id}: generated part ID collision ${generated.raw}"
                    }
                }
                val missingParts = desired.parts.filter { it.id in requiredParts && current.parts.none { actual -> actual.id == remap.part(it.id) } }
                addedParts += missingParts.map { remap.part(it.id) }
                val partParents = desired.parts.flatMap { part -> part.children.filterIsInstance<OrgChild.Part>().map { it.id to part.id } }.toMap()
                current = current.copy(parts = current.parts + missingParts.map { remap.part(it.copy(children = emptyList())) })
                for (part in missingParts) {
                    val child = OrgChild.Part(remap.part(part.id))
                    val parent = partParents[part.id]?.let(remap::part)
                    current = if (parent == null) current.copy(rootChildren = current.rootChildren + child)
                    else current.copy(parts = current.parts.map { if (it.id == parent && child !in it.children) it.copy(children = it.children + child) else it })
                }
                for (canonical in added.asReversed()) {
                    checkpoint()
                    val original = desiredById.getValue(canonical)
                    var cloned = remap.deformer(original)
                    if (cloned.id in addedDeformers) {
                        require(current.deformers.single { it.id == cloned.id } == cloned) { "${instance.id}: incompatible shared carrier ${cloned.id.raw}" }
                        continue
                    }
                    require(current.deformers.none { it.id == cloned.id }) { "${instance.id}: generated carrier ID collision ${cloned.id.raw}" }
                    require(cloned.partId == null || current.parts.any { it.id == cloned.partId }) { "${instance.id}: missing generated carrier part" }
                    if (original.parent?.let(mapped::getValue) == anchor && actualParent != anchor) {
                        require(cloned is Deformer.Warp) { "A generated carrier under an authored frame must be a Warp" }
                        cloned = reparent(current, sample, cloned, anchor, actualParent, instance.outputId("${target.id}/carrier-frame"), checkpoint)
                    }
                    current = current.copy(deformers = current.deformers + cloned)
                    addedDeformers += cloned.id
                }
                val destination = desiredBranch.firstOrNull()?.let(mapped::getValue)
                current = current.copy(drawables = current.drawables.map { if (it.id == id) it.copy(parentDeformerId = destination) else it })
                if (!nativeGeometry) current = carry(beforeInsertion, current, id, instance, checkpoint)
            }
            val previousFrame = current.copy(deformers = current.deformers.map { updatedDeformers[it.id] ?: it })
            for (canonical in branch) {
                checkpoint()
                if (bypass && canonical in removed) continue
                val liveId = mapped.getValue(canonical)
                if (liveId in updatedDeformers) continue
                val actual = requireNotNull(current.deformers.singleOrNull { it.id == liveId }) { "${instance.id}: scaffold object ${liveId.raw} is missing" }
                updatedDeformers[liveId] = actual
                var before = previousById.getValue(canonical)
                var after = desiredById[canonical] ?: source.static(previous).deformers.single { it.id == canonical }
                val originalParent = before.parent
                val anchor = originalParent?.let(mapped::getValue)
                val carriedParent = actual.parent != anchor
                if (carriedParent) {
                    require(before is Deformer.Warp && after is Deformer.Warp) { "A binding in an authored frame must be a Warp" }
                    before = reparent(current, sample, before, anchor, actual.parent, instance.outputId("${target.id}/frame"), checkpoint)
                    after = reparent(current, sample, after, anchor, actual.parent, instance.outputId("${target.id}/frame"), checkpoint)
                }
                fun channels(value: Deformer) = sample.copy(channelGrids = value.channelGrids,
                    opacity = value.opacity, multiplyColor = value.multiplyColor, screenColor = value.screenColor)
                val tracks = channelDelta(current, channels(actual), channels(before), channels(after), checkpoint)
                var replacement = when {
                    actual is Deformer.Warp && before is Deformer.Warp && after is Deformer.Warp -> actual.copy(
                        geometryGrid = warpDelta(actual.geometryGrid, before.geometryGrid, after.geometryGrid, defaults, checkpoint), channelGrids = tracks)
                    actual is Deformer.Rotation && before is Deformer.Rotation && after is Deformer.Rotation -> actual.copy(
                        geometryGrid = rotationDelta(actual.geometryGrid, before.geometryGrid, after.geometryGrid, defaults, checkpoint), channelGrids = tracks)
                    else -> error("${instance.id}: scaffold kind changed on ${actual.id.raw}")
                }
                if (actual is Deformer.Warp && before is Deformer.Warp && after is Deformer.Warp && replacement is Deformer.Warp) {
                    val expected = remap.deformer(before) as Deformer.Warp
                    val untouched = !carriedParent && PuppetIr.deformerToIr(actual.copy(partId = expected.partId)) == PuppetIr.deformerToIr(expected)
                    val merged = if (untouched) after.blendShapes else WarpBlendResidual.warp(current,
                        if (carriedParent) current else oldProjection,
                        if (carriedParent) current else if (canonical in removed) oldProjection else nextProjection,
                        actual, before, after, replacement, DrawableId(instance.outputId("${target.id}/warp-morph:${canonical.raw}")), checkpoint)
                    val dimensionsChanged = before.rows != after.rows || before.columns != after.columns || before.isQuadTransform != after.isQuadTransform
                    require(!dimensionsChanged || actual.rows == before.rows && actual.columns == before.columns && actual.isQuadTransform == before.isQuadTransform) {
                        "${instance.id}: incompatible authored lattice dimensions on ${actual.id.raw}"
                    }
                    replacement = replacement.copy(blendShapes = merged,
                        rows = if (dimensionsChanged) after.rows else actual.rows,
                        columns = if (dimensionsChanged) after.columns else actual.columns,
                        isQuadTransform = if (dimensionsChanged) after.isQuadTransform else actual.isQuadTransform)
                }
                current = current.copy(deformers = current.deformers.map { if (it.id == liveId) replacement else it })
            }
            // Fitting may change a neutral parent frame even when its ID stays. Re-express the authored geometry
            // in that new frame before adding the local algorithm correction, as ordinary canvas-space rebinding does.
            if (!nativeGeometry) current = carry(previousFrame, current, id, instance, checkpoint)
            // Parent motion remains in deformers. Only local mesh corrections are transferred through the native
            // residual mapper, against static projections, so no ancestor's motion is counted a second time.
            val oldMesh = previous.drawables.single { it.id == id }
            val nextMesh = desired.drawables.single { it.id == id }
            val actual = current.drawables.single { it.id == id }
            if (nativeGeometry) {
                val channels = channelDelta(current, actual, oldMesh, nextMesh, checkpoint)
                val masks = masks(actual, oldMesh, nextMesh, instance)
                current = current.copy(drawables = current.drawables.map {
                    if (it.id == id) it.copy(mesh = nextMesh.mesh, geometryGrid = nextMesh.geometryGrid, blendShapes = nextMesh.blendShapes, channelGrids = channels,
                        maskedBy = masks.first, invertMask = masks.second) else it
                })
                continue
            }
            val oldSources = source.vertexSources(oldProjection, oldMesh, current, actual, checkpoint)
            val nextSources = source.vertexSources(nextProjection, nextMesh, current, actual, checkpoint)
            val geometry = GeometryResidual.geometry(current, oldProjection, nextProjection, id, oldSources, nextSources, checkpoint)
            val channels = channelDelta(current, actual, oldMesh, nextMesh, checkpoint)
            val masks = masks(actual, oldMesh, nextMesh, instance)
            val updated = current.copy(drawables = current.drawables.map { if (it.id == id) it.copy(geometryGrid = geometry, channelGrids = channels,
                maskedBy = masks.first, invertMask = masks.second) else it })
            val blends = MeshBlendResidual.merge(current, updated, oldProjection, nextProjection, id, oldSources, nextSources, checkpoint)
            current = updated.copy(drawables = updated.drawables.map { if (it.id == id) it.copy(blendShapes = blends) else it })
        }
        // A replaced, unchanged carrier has no contribution once its last child has moved.
        // Keep it when another drawable or deformer still uses it.
        do {
            val unused = obsoleteCarriers.filterTo(HashSet()) { candidate ->
                current.drawables.none { it.parentDeformerId == candidate } && current.deformers.none { it.parent == candidate }
            }
            if (unused.isEmpty()) break
            current = current.copy(deformers = current.deformers.filterNot { it.id in unused })
            obsoleteCarriers.removeAll(unused)
        } while (obsoleteCarriers.isNotEmpty())
        if (preserveSourceOrder) {
            val target = instance.targets.first()
            val native = source.transition(instance, target, checkpoint).second
            val ids = source.deformerIds(instance, target, checkpoint)
            val previous = source.transition(instance, target, checkpoint).first
            val remap = ModelIdRemap(deformers = ids, parts = native.parts.associate {
                it.id to PartId(source.partId(provider, target.id, it.id.raw))
            })
            val used = HashSet<DeformerId>()
            val parents = native.deformers.associate { it.id to it.parent }
            for (mesh in native.drawables) {
                var parent = mesh.parentDeformerId
                while (parent != null && used.add(parent)) parent = parents[parent]
            }
            // Native templates also contain editable empty carriers. Keep their transitions without inventing children.
            for (deformer in native.deformers.filter { it.id !in used }) {
                val desired = remap.deformer(deformer)
                val existing = current.deformers.singleOrNull { it.id == desired.id }
                val before = previous.deformers.singleOrNull { it.id == deformer.id }?.let(remap::deformer)
                if (existing == null) current = current.copy(deformers = current.deformers + desired)
                else if (before != null && PuppetIr.deformerToIr(existing) == PuppetIr.deformerToIr(before))
                    current = current.copy(deformers = current.deformers.map { if (it.id == desired.id) desired else it })
            }
            val actual = current.deformers.associateBy { it.id }
            val order = native.deformers.mapNotNull { actual[ids[it.id]] }
            current = current.copy(deformers = sourceOrder(current.deformers, order) { it.id })
        }
        checkpoint()
        return current.withDerivedRenderRoot()
    }

    private fun carry(before: PuppetModel, after: PuppetModel, id: DrawableId, instance: RigModifierInstance, checkpoint: () -> Unit): PuppetModel {
        fun frame(model: PuppetModel): List<Deformer> {
            val nodes = model.deformers.associateBy { it.id }
            val path = ArrayList<Deformer>()
            var parent = model.drawables.single { it.id == id }.parentDeformerId
            while (parent != null) {
                val node = nodes.getValue(parent)
                require(path.none { it.id == node.id }) { "Cyclic binding hierarchy" }
                path += node
                parent = node.parent
            }
            return path
        }
        // An unchanged frame needs no inverse. Fitted lattices can have ambiguous inverses outside their bounds.
        if (frame(before).map(PuppetIr::deformerToIr) == frame(after).map(PuppetIr::deformerToIr) &&
            before.parameters == after.parameters) return after
        return try { io.github.psd2live.algorithms.binding.BindingFrames.carriedToParent(before, after, id, checkpoint) }
        catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("${instance.type} (${instance.id}), ${id.raw}: ${failure.message}", failure)
        }
    }

    private fun masks(actual: Drawable, before: Drawable, after: Drawable, instance: RigModifierInstance): Pair<List<DrawableId>, Boolean> {
        val old = before.maskedBy to before.invertMask
        val next = after.maskedBy to after.invertMask
        val authored = actual.maskedBy to actual.invertMask
        if (old == next) return authored
        require(authored == old || authored == next) { "${instance.id}: incompatible authored masks on ${actual.id.raw}" }
        return next
    }

    private fun value(grid: KeyformGrid<*>, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>, id: ParameterId): Float {
        val axis = grid.axes.single { it.parameterId == id }
        return (pose[id] ?: defaults[id] ?: 0f).coerceIn(axis.keys.first(), axis.keys.last())
    }

    private fun delta(actual: Float, before: Float, after: Float) = when {
        before == after -> actual
        actual == before -> after
        else -> actual + (after - before)
    }

    private fun sameMeshGeometry(actual: Drawable, expected: Drawable): Boolean {
        val a = PuppetIr.meshToIr(actual)
        val b = PuppetIr.meshToIr(expected)
        return a.geometry != null && a.geometry == b.geometry && a.offsets == b.offsets && a.shapes == b.shapes
    }

    private fun channelDelta(model: PuppetModel, actual: Drawable, before: Drawable, after: Drawable, checkpoint: () -> Unit): ChannelGrids {
        fun same(a: Drawable, b: Drawable): Boolean = a.drawOrder == b.drawOrder && a.opacity == b.opacity &&
            a.multiplyColor == b.multiplyColor && a.screenColor == b.screenColor && PuppetIr.meshToIr(a).channels == PuppetIr.meshToIr(b).channels
        if (same(actual, before)) return after.channelGrids
        if (same(before, after)) return actual.channelGrids
        return GeometryResidual.channels(model, actual, before, after, checkpoint)
    }

    private fun <T> sameGrid(a: KeyformGrid<T>, b: KeyformGrid<T>, sameForm: (T, T) -> Boolean): Boolean {
        if (a === b) return true
        return a.axes.size == b.axes.size && a.cells.size == b.cells.size &&
            a.axes.zip(b.axes).all { (left, right) -> left.parameterId == right.parameterId && left.keys.contentEquals(right.keys) } &&
            a.cells.zip(b.cells).all { (left, right) -> left.coordinate.contentEquals(right.coordinate) && sameForm(left.form, right.form) }
    }

    private fun warpDelta(actual: KeyformGrid<WarpLatticeForm>?, before: KeyformGrid<WarpLatticeForm>?, after: KeyformGrid<WarpLatticeForm>?,
                          defaults: Map<ParameterId, Float>, checkpoint: () -> Unit): KeyformGrid<WarpLatticeForm> {
        require(actual != null && before != null && after != null) { "Binding needs complete Warp lattices" }
        fun same(a: KeyformGrid<WarpLatticeForm>, b: KeyformGrid<WarpLatticeForm>) =
            sameGrid(a, b) { left, right -> left.controlPoints.contentEquals(right.controlPoints) }
        if (same(actual, before)) return after
        if (same(before, after)) return actual
        val axes = BindingAxes.unionAxes(actual.axes + before.axes + after.axes)
        return KeyformGrid(axes, BindingAxes.coordinates(axes).map { coordinate ->
            checkpoint()
            val pose = BindingAxes.key(axes, coordinate)
            fun sample(grid: KeyformGrid<WarpLatticeForm>) = requireNotNull(warpControlPointsAt(grid) { value(grid, pose, defaults, it) })
            val a = sample(actual); val b = sample(before); val c = sample(after)
            require(a.size == b.size && b.size == c.size) { "Binding cannot combine different lattice dimensions" }
            KeyformCell(coordinate, WarpLatticeForm(FloatArray(a.size) { delta(a[it], b[it], c[it]) }))
        })
    }

    private fun rotationDelta(actual: KeyformGrid<RotationPivotForm>?, before: KeyformGrid<RotationPivotForm>?, after: KeyformGrid<RotationPivotForm>?,
                              defaults: Map<ParameterId, Float>, checkpoint: () -> Unit): KeyformGrid<RotationPivotForm> {
        require(actual != null && before != null && after != null) { "Binding needs complete rotation pivots" }
        fun same(a: KeyformGrid<RotationPivotForm>, b: KeyformGrid<RotationPivotForm>) = sameGrid(a, b) { left, right ->
            left.originX == right.originX && left.originY == right.originY && left.angle == right.angle && left.scale == right.scale
        }
        if (same(actual, before)) return after
        if (same(before, after)) return actual
        val axes = BindingAxes.unionAxes(actual.axes + before.axes + after.axes)
        return KeyformGrid(axes, BindingAxes.coordinates(axes).map { coordinate ->
            checkpoint()
            val pose = BindingAxes.key(axes, coordinate)
            fun sample(grid: KeyformGrid<RotationPivotForm>) = requireNotNull(rotationFormAt(grid) { value(grid, pose, defaults, it) })
            val a = sample(actual); val b = sample(before); val c = sample(after)
            KeyformCell(coordinate, RotationPivotForm(delta(a.originX, b.originX, c.originX), delta(a.originY, b.originY, c.originY),
                delta(a.angle, b.angle, c.angle), delta(a.scale, b.scale, c.scale)))
        })
    }

    /** Reorder source-owned entries in their existing slots, leaving authored entries in place. */
    private fun <T, K> sourceOrder(actual: List<T>, desired: List<T>, key: (T) -> K): List<T> {
        val ranks = desired.mapIndexed { index, entry -> key(entry) to index }.toMap()
        val ordered = actual.filter { key(it) in ranks }.sortedBy { ranks.getValue(key(it)) }.iterator()
        return actual.map { if (key(it) in ranks) ordered.next() else it }
    }

    private fun appendTree(model: PuppetModel, desired: List<ParameterNode>, added: Set<ParameterId>, preserveSourceOrder: Boolean): List<ParameterNode> {
        val existing = model.parameterTree.ifEmpty { model.parameters.filter { it.id !in added }.map { ParameterNode.Param(it.id) } }
        fun groups(nodes: List<ParameterNode>): Set<ParameterGroupId> = nodes.filterIsInstance<ParameterNode.Group>().flatMapTo(HashSet()) { listOf(it.id) + groups(it.children) }
        val present = groups(existing)
        val templates = HashMap<ParameterGroupId, ParameterNode.Group>()
        fun collect(nodes: List<ParameterNode>) { nodes.filterIsInstance<ParameterNode.Group>().forEach { templates[it.id] = it; collect(it.children) } }
        collect(desired)
        fun extra(nodes: List<ParameterNode>): List<ParameterNode> = nodes.mapNotNull { node -> when (node) {
            is ParameterNode.Param -> node.takeIf { it.id in added }
            is ParameterNode.Group -> if (node.id in present) null else node.copy(children = extra(node.children)).takeIf { preserveSourceOrder || it.children.isNotEmpty() }
        } }
        fun ordered(nodes: List<ParameterNode>, template: List<ParameterNode>) =
            if (!preserveSourceOrder) nodes else sourceOrder(nodes, template) { node -> when (node) {
                is ParameterNode.Param -> "parameter:${node.id.raw}"
                is ParameterNode.Group -> "group:${node.id.raw}"
            } }
        fun update(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map { node -> when (node) {
            is ParameterNode.Param -> node
            is ParameterNode.Group -> node.copy(children = ordered(update(node.children) + extra(templates[node.id]?.children.orEmpty()), templates[node.id]?.children.orEmpty()))
        } }
        return ordered(update(existing) + extra(desired), desired)
    }
}
