package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import kotlinx.serialization.json.*
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.render.eval.warpApply
import org.umamo.runtime.eval.*
import org.umamo.runtime.model.*
import kotlin.math.atan2
import kotlin.math.sqrt

/** Automatic motion remains a hierarchy of small grids, alongside the author's original frames. */
internal object RigGenerationFrames {
    const val OP = "rig_generation_frames"
    data class Prepared(val command: JsonObject, val model: PuppetModel, val desired: PuppetModel,
                        val previousProjection: PuppetModel, val desiredProjection: PuppetModel,
                        val meshTransforms: Map<String, (FloatArray) -> FloatArray>)

    fun previousIds(overlay: RigEditOverlay): Map<DeformerId, DeformerId> = ids(overlay.authoringJournal)

    /** The names the last frames entry of [journal] gave the generated deformers, by generated id; empty without one. */
    fun ids(journal: List<JsonObject>): Map<DeformerId, DeformerId> = journal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == OP
    }?.getValue("frames")?.jsonObject?.map { (id, value) -> DeformerId(id) to DeformerId(value.jsonPrimitive.content) }?.toMap().orEmpty()

    /**
     * [generated] - a rig as the generators make it - with its deformers under the names [journal]'s last frames entry gave
     * them, as the authored rig holds them: the generated frames' copies the meshes hang from. Deformers the entry did
     * not name (one a later generator adds) keep their ids and hang from the renamed ones.
     */
    fun named(generated: PuppetModel, journal: List<JsonObject>): PuppetModel {
        val ids = ids(journal)
        if (ids.isEmpty()) return generated
        fun id(deformer: DeformerId?) = deformer?.let { ids[it] ?: it }
        return generated.copy(deformers = generated.deformers.map { parent(it, id(it.parent), id(it.id)!!) },
            drawables = generated.drawables.map { it.copy(parentDeformerId = id(it.parentDeformerId)) })
    }

    fun prepare(input: PuppetModel, previous: PuppetModel, desired: PuppetModel, savedIds: Map<DeformerId, DeformerId>,
                epoch: Int, checkpoint: () -> Unit): Prepared {
        val oldIds = if (savedIds.isEmpty()) previous.deformers.filter { generated -> input.deformers.any { it.id == generated.id } }
            .associate { it.id to it.id } else savedIds.filterValues { id -> input.deformers.any { it.id == id } }
        val reserved = input.deformers.mapTo(HashSet()) { it.id.raw }
        val newIds = desired.deformers.associate { generated ->
            var id = "GenerationFrame${epoch}_${generated.id.raw}"; var ordinal = 2
            while (!reserved.add(id)) id = "GenerationFrame${epoch}_${generated.id.raw}_${ordinal++}"
            generated.id to DeformerId(id)
        }
        val additions = desired.parameters.filter { p -> input.parameters.none { it.id == p.id } }
        val context = input.copy(parameters = input.parameters + additions)
        val automatic = oldIds.mapNotNull { (id, actualId) ->
            checkpoint()
            val actual = input.deformers.single { it.id == actualId }
            val generated = previous.deformers.singleOrNull { it.id == id } ?: return@mapNotNull null
            val expected = parent(generated, generated.parent?.let(oldIds::get), actualId)
            if (encode(actual) == encode(expected)) actualId else null
        }.toSet()
        val replacements = oldIds.mapNotNull { (id, actualId) ->
            checkpoint()
            val actual = context.deformers.single { it.id == actualId }
            val generated = previous.deformers.singleOrNull { it.id == id } ?: return@mapNotNull null
            actualId to removeGenerated(context, actual, generated, checkpoint)
        }.toMap()
        var updated = context.copy(deformers = context.deformers.map { replacements[it.id] ?: it })
        val named = desired.copy(deformers = desired.deformers.map { d ->
            val placed = parent(d, d.parent?.let(newIds::getValue), newIds.getValue(d.id))
            val part = placed.partId?.takeIf { id -> input.parts.any { it.id == id } }
            when (placed) { is Deformer.Warp -> placed.copy(partId = part); is Deformer.Rotation -> placed.copy(partId = part) }
        },
            drawables = desired.drawables.map { it.copy(parentDeformerId = it.parentDeformerId?.let(newIds::getValue)) })
        updated = updated.copy(deformers = updated.deformers + named.deformers)
        val beforeProjection = mutableMapOf<DrawableId, DeformerId?>()
        val afterProjection = mutableMapOf<DrawableId, DeformerId?>()
        val drawableParents = mutableMapOf<DrawableId, DeformerId?>()
        val meshChanges = mutableListOf<JsonObject>()
        val meshTransforms = mutableMapOf<String, (FloatArray) -> FloatArray>()
        val roots = input.drawables.filter { d -> d.mesh != null && desired.drawables.any { it.id == d.id } }.groupBy { d ->
            var root = d.parentDeformerId
            val old = previous.drawables.singleOrNull { it.id == d.id }
            val oldParent = old?.parentDeformerId?.let(oldIds::get)
            // A direct binding chosen by the author remains a real frame even when its ID is generated. A mesh the
            // previous generation never had (a part of a version 1 split, placed by its record) has no binding to
            // compare: under an automatic frame it follows the generation like the meshes around it.
            if (root in automatic && (root == oldParent || old == null)) root = null
            val seen = HashSet<DeformerId>()
            while (root != null) {
                require(seen.add(root)) { "Generation migration parent hierarchy contains a cycle" }
                val p = input.deformers.single { it.id == root }.parent ?: break
                if (p in automatic) break
                root = p
            }
            root?.raw ?: "mesh:${d.id.raw}"
        }
        for ((rootKey, members) in roots) {
            checkpoint()
            val common = commonParent(desired, members.map { it.id })
            val newParent = common?.let(newIds::getValue)
            val oldCommon = commonParent(previous, members.map { it.id })
            members.forEach { d -> beforeProjection[d.id] = oldCommon; afterProjection[d.id] = common }
            if (rootKey.startsWith("mesh:")) {
                val drawable = members.single()
                if (drawable.parentDeformerId != newParent) {
                    val conversion = convertMeshParent(context, updated, drawable, newParent, checkpoint)
                    updated = updated.copy(drawables = updated.drawables.map { if (it.id == drawable.id) conversion.first else it })
                    meshTransforms[drawable.id.raw] = conversion.second
                    meshChanges += encodeMesh(input.drawables.single { it.id == drawable.id }, conversion.first)
                    drawableParents[drawable.id] = newParent
                }
            } else {
                val root = updated.deformers.single { it.id.raw == rootKey }
                val converted = convertParent(updated, updated, root, newParent, checkpoint)
                updated = updated.copy(deformers = updated.deformers.map { if (it.id == root.id) converted else it })
            }
        }
        updated = updated.copy(drawables = updated.drawables.map { d ->
            if (d.id in drawableParents) d.copy(parentDeformerId = drawableParents[d.id]) else d
        })
        val changed = updated.deformers.filter { d -> input.deformers.singleOrNull { it.id == d.id } !== d }
        val command = buildJsonObject {
            put("op", OP); put("parameters", GeneratedRigJournalCodec.parameters(context.copy(parameters = additions)))
            put("deformers", JsonArray(changed.map { d -> buildJsonObject {
                checkpoint()
                val before = input.deformers.singleOrNull { it.id == d.id }
                put("expected", before?.let(::encode) ?: JsonNull); put("replacement", encode(d))
            } }))
            put("parents", buildJsonObject { drawableParents.forEach { (id, p) -> put(id.raw, p?.raw?.let(::JsonPrimitive) ?: JsonNull) } })
            put("meshes", JsonArray(meshChanges))
            put("frames", buildJsonObject { newIds.forEach { (id, actual) -> put(id.raw, actual.raw) } })
        }
        return Prepared(command, updated, named, project(previous, beforeProjection, checkpoint), project(desired, afterProjection, checkpoint), meshTransforms)
    }

    fun replay(input: PuppetModel, command: JsonObject): PuppetModel {
        require(command.keys == setOf("op", "parameters", "deformers", "parents", "meshes", "frames")) { "Invalid generation frames" }
        val additions = GeneratedRigJournalCodec.parameters(command.getValue("parameters").jsonArray)
        require(additions.map { it.id }.distinct().size == additions.size && additions.none { p -> input.parameters.any { it.id == p.id } }) { "Generation frame parameter already exists" }
        var result = input.copy(parameters = input.parameters + additions)
        val replacements = command.getValue("deformers").jsonArray.map { element ->
            val record = element.jsonObject
            val replacement = decode(record.getValue("replacement").jsonObject, result)
            val before = input.deformers.singleOrNull { it.id == replacement.id }
            require(record.getValue("expected") == (before?.let(::encode) ?: JsonNull)) { "Generation frame has changed" }
            replacement
        }
        require(replacements.map { it.id }.distinct().size == replacements.size) { "Duplicate generation frame" }
        val byId = replacements.associateBy { it.id }
        result = result.copy(deformers = result.deformers.map { byId[it.id] ?: it } + replacements.filter { d -> input.deformers.none { it.id == d.id } })
        val parents = command.getValue("parents").jsonObject
        require(parents.keys.all { id -> result.drawables.any { it.id.raw == id } }) { "Generation frame drawable is missing" }
        result = result.copy(drawables = result.drawables.map { d ->
            parents[d.id.raw]?.let { d.copy(parentDeformerId = it.jsonPrimitive.contentOrNull?.let(::DeformerId)) } ?: d
        })
        val meshes = command.getValue("meshes").jsonArray.map { it.jsonObject }
        require(meshes.map { it.getValue("id").jsonPrimitive.content }.distinct().size == meshes.size) { "Duplicate generation parent mesh" }
        val meshById = meshes.associateBy { it.getValue("id").jsonPrimitive.content }
        result = result.copy(drawables = result.drawables.map { d ->
            val change = meshById[d.id.raw] ?: return@map d
            val original = input.drawables.single { it.id == d.id }
            val mesh = requireNotNull(original.mesh)
            require(RasterMeshJournal.fingerprint(mesh) == change.getValue("expected").jsonPrimitive.content &&
                original.parentDeformerId?.raw == change.getValue("expected_parent").jsonPrimitive.contentOrNull) { "Generation parent mesh has changed" }
            fun points(e: JsonElement) = e.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
                require(it.size == mesh.positions.size && it.all(Float::isFinite)) { "Invalid generation parent mesh geometry" }
            }
            val grid = change.getValue("geometry").takeIf { it != JsonNull }?.jsonObject?.let { g ->
                RasterMeshCreation.decodeGrid(g, result) { MeshDeltaForm(points(it)) }
            }
            val blends = change.getValue("blends").jsonArray
            require(blends.size == original.blendShapes.size) { "Generation parent mesh blend inventory has changed" }
            d.copy(mesh = DrawableMesh(points(change.getValue("positions")), mesh.uvs, mesh.indices), geometryGrid = grid,
                blendShapes = original.blendShapes.mapIndexed { index, binding ->
                    val forms = blends[index].jsonArray; require(forms.size == binding.forms.size)
                    binding.copy(forms = binding.forms.mapIndexed { f, form ->
                        require((forms[f] == JsonNull) == (form == null)) { "Generation parent blend key has changed" }
                        form?.let { MeshForm(points(forms[f]), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
                    })
                })
        })
        require(meshById.keys.all { id -> result.drawables.any { it.id.raw == id } }) { "Generation parent mesh is missing" }
        val ids = result.deformers.mapTo(HashSet()) { it.id }
        require(result.deformers.all { it.parent == null || it.parent in ids } && result.drawables.all { it.parentDeformerId == null || it.parentDeformerId in ids }) { "Generation frame parent is missing" }
        result.deformers.forEach { d ->
            val seen = HashSet<DeformerId>(); var p: DeformerId? = d.id
            while (p != null) { require(seen.add(p)) { "Generation frame hierarchy contains a cycle" }; p = result.deformers.single { it.id == p }.parent }
        }
        require(command.getValue("frames").jsonObject.values.all { DeformerId(it.jsonPrimitive.content) in ids }) { "Generation frame inventory is missing" }
        return result
    }

    private fun encodeMesh(before: Drawable, after: Drawable) = buildJsonObject {
        fun floats(v: FloatArray) = JsonArray(v.map(::JsonPrimitive))
        put("id", before.id.raw); put("expected", RasterMeshJournal.fingerprint(requireNotNull(before.mesh)))
        put("expected_parent", before.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
        put("positions", floats(requireNotNull(after.mesh).positions))
        put("geometry", after.geometryGrid?.let { g -> RasterMeshCreation.grid(g) { floats(it.positionDeltas) } } ?: JsonNull)
        put("blends", JsonArray(after.blendShapes.map { binding -> JsonArray(binding.forms.map { it?.let { f -> floats(f.positionDeltas) } ?: JsonNull }) }))
    }

    private fun convertMeshParent(before: PuppetModel, model: PuppetModel, original: Drawable, newParent: DeformerId?, checkpoint: () -> Unit): Pair<Drawable, (FloatArray) -> FloatArray> {
        val mesh = requireNotNull(original.mesh)
        val old = requireNotNull(drawableSpaceMapping(before, emptyMap(), original.id))
        val next = requireNotNull(drawableSpaceMapping(model.copy(drawables = model.drawables.map {
            if (it.id == original.id) it.copy(parentDeformerId = newParent) else it
        }), emptyMap(), original.id))
        val vertices = (0 until mesh.vertexCount).toSet()
        fun map(values: FloatArray): FloatArray {
            checkpoint(); val world = old.localToWorld(values)
            return invert(next, world, FloatArray(world.size) { 0.5f }, vertices, 0.01f) ?: throw IllegalArgumentException("Generation mesh neutral frame cannot be inverted")
        }
        val base = map(mesh.positions)
        val delta: (FloatArray) -> FloatArray = { values ->
            require(values.size == base.size) { "Generation mesh offset has stale topology" }
            val converted = map(FloatArray(values.size) { mesh.positions[it] + values[it] })
            FloatArray(values.size) { converted[it] - base[it] }
        }
        return original.copy(parentDeformerId = newParent, mesh = DrawableMesh(base, mesh.uvs, mesh.indices),
            geometryGrid = original.geometryGrid?.let { g -> KeyformGrid(g.axes, g.cells.map { KeyformCell(it.coordinate, MeshDeltaForm(delta(it.form.positionDeltas))) }) },
            blendShapes = original.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
                MeshForm(delta(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor)
            } }) }) to delta
    }

    /**
     * Local points [next] maps onto [world] within [tolerance], or null. The engine's warp inverse stops at a tolerance
     * relative to the lattice, about a hundredth of a pixel on a whole-body warp chain. Points it leaves further off take
     * Newton steps on the forward map, its Jacobian by finite differences. Points the plain inverse already places
     * are unchanged.
     */
    private fun invert(next: DrawableSpaceMapping, world: FloatArray, seed: FloatArray, vertices: Set<Int>, tolerance: Float): FloatArray? {
        val local = next.worldToLocalLinearized(world, seed, world, vertices)
        fun misses(actual: FloatArray) = (0 until world.size / 2).filter { v ->
            kotlin.math.abs(actual[2 * v] - world[2 * v]) >= tolerance || kotlin.math.abs(actual[2 * v + 1] - world[2 * v + 1]) >= tolerance
        }
        var actual = next.localToWorld(local)
        var off = misses(actual)
        repeat(4) {
            if (off.isEmpty()) return local
            // The step is small next to a deformer's extent and large next to float round-off of canvas coordinates.
            val step = 1e-3f
            val dx = next.localToWorld(FloatArray(local.size) { if (it % 2 == 0) local[it] + step else local[it] })
            val dy = next.localToWorld(FloatArray(local.size) { if (it % 2 == 1) local[it] + step else local[it] })
            for (v in off) {
                val x = 2 * v; val y = x + 1
                val a = (dx[x] - actual[x]) / step; val b = (dy[x] - actual[x]) / step
                val c = (dx[y] - actual[y]) / step; val d = (dy[y] - actual[y]) / step
                val det = a * d - b * c
                if (!det.isFinite() || kotlin.math.abs(det) < 1e-12f) return null
                val ex = world[x] - actual[x]; val ey = world[y] - actual[y]
                local[x] += (d * ex - b * ey) / det; local[y] += (a * ey - c * ex) / det
            }
            actual = next.localToWorld(local)
            off = misses(actual)
        }
        return local.takeIf { off.isEmpty() }
    }

    private fun commonParent(model: PuppetModel, ids: List<DrawableId>): DeformerId? {
        val chains = ids.map { id ->
            val result = mutableListOf<DeformerId>(); var p = model.drawables.singleOrNull { it.id == id }?.parentDeformerId
            while (p != null) { require(p !in result); result += p; p = model.deformers.single { it.id == p }.parent }
            result
        }
        return chains.firstOrNull()?.firstOrNull { p -> chains.all { p in it } }
    }

    /** The common automatic chain is carried by runtime frames, so only lower feature axes are baked. */
    private fun project(model: PuppetModel, common: Map<DrawableId, DeformerId?>, checkpoint: () -> Unit): PuppetModel {
        // Common roots differ per subtree. Duplicate only the needed generated parent chain per drawable.
        val deformers = mutableListOf<Deformer>()
        val drawables = model.drawables.map { drawable ->
            checkpoint()
            val frozen = mutableSetOf<DeformerId>(); var p = common[drawable.id]
            while (p != null && frozen.add(p)) p = model.deformers.single { it.id == p }.parent
            val mapping = model.deformers.associate { it.id to DeformerId("Projection_${drawable.id.raw}_${it.id.raw}") }
            deformers += model.deformers.map { d ->
                val neutral = if (d.id in frozen) neutral(model, d) else d
                parent(neutral, neutral.parent?.let(mapping::getValue), mapping.getValue(d.id))
            }
            drawable.copy(parentDeformerId = drawable.parentDeformerId?.let(mapping::getValue))
        }
        return model.copy(deformers = deformers, drawables = drawables)
    }

    private fun parent(d: Deformer, p: DeformerId?, id: DeformerId = d.id): Deformer = when (d) {
        is Deformer.Warp -> d.copy(id = id, parent = p)
        is Deformer.Rotation -> d.copy(id = id, parent = p)
    }

    private fun neutral(model: PuppetModel, d: Deformer): Deformer = when (d) {
        is Deformer.Warp -> d.copy(geometryGrid = d.geometryGrid?.let { g -> KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
            WarpLatticeForm(sample(g, model, values = { it.controlPoints }))))) })
        is Deformer.Rotation -> d.copy(geometryGrid = d.geometryGrid?.let { g -> val f = sample(g, model, values = { floatArrayOf(it.originX, it.originY, it.angle, it.scale) })
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(f[0], f[1], f[2], f[3])))) })
    }

    private fun <T: Any> sample(grid: KeyformGrid<T>, model: PuppetModel, values: (T) -> FloatArray, pose: Map<ParameterId, Float> = emptyMap()): FloatArray {
        val corners = requireNotNull(gridCorners(grid) { id -> val axis = grid.axes.single { it.parameterId == id }
            (pose[id] ?: model.parameters.singleOrNull { it.id == id }?.default ?: 0f).coerceIn(axis.keys.first(), axis.keys.last()) })
        val result = FloatArray(values(grid.cells.first().form).size)
        for (corner in corners) grid.cellsByLinearIndex[corner.linearIndex]?.form?.let { f ->
            values(f).forEachIndexed { i, value -> result[i] += corner.weight * value }
        }
        return result
    }

    private fun removeGenerated(model: PuppetModel, actual: Deformer, old: Deformer, checkpoint: () -> Unit): Deformer {
        require(actual::class == old::class) { "Generated frame kind has changed" }
        fun <T: Any> grid(a: KeyformGrid<T>?, b: KeyformGrid<T>?, values: (T) -> FloatArray, form: (FloatArray) -> T): KeyformGrid<T>? {
            if (a == null || b == null) return a
            val axes = (a.axes + b.axes).filter { axis -> model.parameters.any { it.id == axis.parameterId } }.groupBy { it.parameterId }.map { (id, parts) ->
                KeyformAxis(id, parts.flatMap { it.keys.toList() }.distinct().sorted().toFloatArray())
            }
            val count = axes.fold(1L) { n, axis -> Math.multiplyExact(n, axis.keys.size.toLong()) }
            require(count <= 262144 && count * values(a.cells.first().form).size <= 16777216) { "Authored frame residual exceeds allocation limit" }
            val rest = sample(b, model, values)
            val cells = (0 until count.toInt()).map { index ->
                checkpoint(); var n = index
                val coordinate = IntArray(axes.size) { (n % axes[it].keys.size).also { _ -> n /= axes[it].keys.size } }
                val pose = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
                val current = sample(a, model, values, pose); val generated = sample(b, model, values, pose)
                KeyformCell(coordinate, form(FloatArray(rest.size) { current[it] - generated[it] + rest[it] }))
            }
            val first = values(cells.first().form)
            return if (cells.all { cell -> values(cell.form).indices.all { kotlin.math.abs(values(cell.form)[it] - first[it]) < 1e-6f } })
                KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), form(first)))) else KeyformGrid(axes, cells)
        }
        return when (actual) {
            is Deformer.Warp -> {
                old as Deformer.Warp
                val oldGrid = old.geometryGrid?.let { g ->
                    if (actual.rows == old.rows && actual.columns == old.columns) g else KeyformGrid(g.axes, g.cells.map { cell ->
                        checkpoint()
                        val points = FloatArray((actual.rows + 1) * (actual.columns + 1) * 2)
                        for (row in 0..actual.rows) for (column in 0..actual.columns) {
                            warpApply(cell.form.controlPoints, old.columns, old.rows, old.isQuadTransform,
                                column.toFloat() / actual.columns, row.toFloat() / actual.rows, points, (row * (actual.columns + 1) + column) * 2)
                        }
                        KeyformCell(cell.coordinate, WarpLatticeForm(points))
                    })
                }
                val replacement = grid(actual.geometryGrid, oldGrid, { it.controlPoints }, ::WarpLatticeForm)
                // Both grids have the same neutral lattice: the residual removes motion, not the author's reference.
                actual.copy(geometryGrid = replacement)
            }
            is Deformer.Rotation -> {
                old as Deformer.Rotation
                actual.copy(geometryGrid = grid(actual.geometryGrid, old.geometryGrid,
                    { floatArrayOf(it.originX, it.originY, it.angle, it.scale) }, { RotationPivotForm(it[0], it[1], it[2], it[3]) }))
            }
        }
    }

    private fun convertParent(before: PuppetModel, after: PuppetModel, original: Deformer, newParent: DeformerId?, checkpoint: () -> Unit): Deformer {
        if (original.parent == newParent) return original
        val dummyId = DrawableId("__generation_parent_probe__")
        val dummy = Drawable(dummyId, "", original.parent, BlendMode.Normal, emptyList(), null, null)
        val oldMap = requireNotNull(drawableSpaceMapping(before.copy(drawables = before.drawables + dummy), emptyMap(), dummyId))
        val nextMap = requireNotNull(drawableSpaceMapping(after.copy(drawables = after.drawables + dummy.copy(parentDeformerId = newParent)), emptyMap(), dummyId))
        fun points(values: FloatArray): FloatArray {
            checkpoint()
            val world = oldMap.localToWorld(values)
            return invert(nextMap, world, values, (0 until values.size / 2).toSet(), Math.nextUp(0.01f))
                ?: throw IllegalArgumentException("Generation parent neutral frame cannot be inverted")
        }
        return when (original) {
            is Deformer.Warp -> original.copy(parent = newParent,
                geometryGrid = original.geometryGrid?.let { g -> KeyformGrid(g.axes, g.cells.map { KeyformCell(it.coordinate, WarpLatticeForm(points(it.form.controlPoints))) }) },
                blendShapes = original.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { f -> f?.let {
                    WarpForm(points(it.controlPoints), it.opacity, it.multiplyColor, it.screenColor)
                } }) })
            is Deformer.Rotation -> {
                fun pivot(f: RotationPivotForm): RotationPivotForm {
                    checkpoint()
                    val neutral = original.copy(geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), f))), blendShapes = emptyList())
                    val posed = before.copy(deformers = before.deformers.map { if (it.id == original.id) neutral else it }, drawables = before.drawables + dummy.copy(parentDeformerId = original.id))
                    val old = requireNotNull(drawableSpaceMapping(posed, emptyMap(), dummyId)).localToWorld(floatArrayOf(0f, 0f, 0f, -1f))
                    val origin = points(floatArrayOf(f.originX, f.originY))
                    val unit = neutral.copy(parent = newParent, baseAngle = 0f, geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(origin[0], origin[1], 0f, 1f)))))
                    val basis = after.copy(deformers = after.deformers.map { if (it.id == original.id) unit else it }, drawables = after.drawables + dummy.copy(parentDeformerId = original.id))
                    val next = requireNotNull(drawableSpaceMapping(basis, emptyMap(), dummyId)).localToWorld(floatArrayOf(0f, 0f, 0f, -1f))
                    val ax = old[2] - old[0]; val ay = old[3] - old[1]; val bx = next[2] - next[0]; val by = next[3] - next[1]
                    val length = sqrt(bx * bx + by * by); require(length > 1e-8f) { "Generation parent rigid frame is degenerate" }
                    return RotationPivotForm(origin[0], origin[1], (Math.toDegrees((atan2(by, bx) - atan2(ay, ax)).toDouble()).toFloat() - original.baseAngle), sqrt(ax * ax + ay * ay) / length)
                }
                original.copy(parent = newParent,
                    geometryGrid = original.geometryGrid?.let { g -> KeyformGrid(g.axes, g.cells.map { KeyformCell(it.coordinate, pivot(it.form)) }) },
                    blendShapes = original.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { f -> f?.let {
                        val p = pivot(RotationPivotForm(it.originX, it.originY, it.angle, it.scale))
                        RotationForm(p.originX, p.originY, p.angle, p.scale, it.flipX, it.flipY, it.opacity, it.multiplyColor, it.screenColor)
                    } }) })
            }
        }
    }

    private fun encode(d: Deformer): JsonObject {
        val base = when (d) { is Deformer.Warp -> d.copy(blendShapes = emptyList()); is Deformer.Rotation -> d.copy(blendShapes = emptyList()) }
        fun <T: Any> bindings(values: List<BlendShapeBinding<T>>, form: (T) -> JsonElement) = JsonArray(values.map { b -> buildJsonObject {
            put("parameter", b.parameterId.raw); put("keys", JsonArray(b.keys.map(::JsonPrimitive))); put("neutral", b.neutralIndex)
            put("forms", JsonArray(b.forms.map { it?.let(form) ?: JsonNull }))
            put("limits", JsonArray(b.limits.map { limit -> buildJsonObject {
                put("parameter", limit.parameterId.raw); put("points", JsonArray(limit.points.map { point -> buildJsonArray { add(point.value); add(point.weight) } }))
            } }))
        } })
        fun floats(a: FloatArray) = JsonArray(a.map(::JsonPrimitive))
        fun channels(opacity: Float, multiply: ColorRgb, screen: ColorRgb) = buildJsonArray {
            add(opacity); add(multiply.red); add(multiply.green); add(multiply.blue); add(screen.red); add(screen.green); add(screen.blue)
        }
        val blends = when (d) {
            is Deformer.Warp -> bindings(d.blendShapes) { buildJsonObject { put("points", floats(it.controlPoints)); put("channels", channels(it.opacity, it.multiplyColor, it.screenColor)) } }
            is Deformer.Rotation -> bindings(d.blendShapes) { buildJsonObject {
                put("pivot", floats(floatArrayOf(it.originX, it.originY, it.angle, it.scale))); put("flip_x", it.flipX); put("flip_y", it.flipY)
                put("channels", channels(it.opacity, it.multiplyColor, it.screenColor))
            } }
        }
        return JsonObject(GeneratedRigJournalCodec.deformer(base) + ("blends" to blends))
    }

    private fun decode(value: JsonObject, model: PuppetModel): Deformer {
        val base = GeneratedRigJournalCodec.deformer(JsonObject(value - "blends"), model)
        fun numbers(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.float.also { v -> require(v.isFinite()) } }
        fun <T: Any> bindings(form: (JsonObject) -> T) = value.getValue("blends").jsonArray.map { element ->
            val b = element.jsonObject; val id = ParameterId(b.getValue("parameter").jsonPrimitive.content)
            require(model.parameters.any { it.id == id }) { "Generation frame blend parameter is missing" }
            val keys = numbers(b.getValue("keys")).toFloatArray(); val neutral = b.getValue("neutral").jsonPrimitive.int
            val forms = b.getValue("forms").jsonArray.map { if (it == JsonNull) null else form(it.jsonObject) }
            require(keys.isNotEmpty() && keys.indices.drop(1).all { keys[it] > keys[it - 1] } && neutral in keys.indices && forms.size == keys.size) { "Invalid generation frame blend" }
            val limits = b.getValue("limits").jsonArray.map { item -> val limit = item.jsonObject
                BlendWeightLimit(ParameterId(limit.getValue("parameter").jsonPrimitive.content), limit.getValue("points").jsonArray.map { p ->
                    val point = numbers(p); require(point.size == 2 && point[1] in 0f..1f); BlendWeightLimitPoint(point[0], point[1])
                })
            }
            BlendShapeBinding(id, keys, neutral, forms, limits)
        }
        fun channel(f: JsonObject): List<Float> = numbers(f.getValue("channels")).also { require(it.size == 7) }
        return when (base) {
            is Deformer.Warp -> base.copy(blendShapes = bindings { f ->
                val p = numbers(f.getValue("points")).toFloatArray(); require(p.size == (base.rows + 1) * (base.columns + 1) * 2)
                val c = channel(f); WarpForm(p, c[0], ColorRgb(c[1], c[2], c[3]), ColorRgb(c[4], c[5], c[6]))
            })
            is Deformer.Rotation -> base.copy(blendShapes = bindings { f ->
                val p = numbers(f.getValue("pivot")); require(p.size == 4); val c = channel(f)
                RotationForm(p[0], p[1], p[2], p[3], f.getValue("flip_x").jsonPrimitive.boolean, f.getValue("flip_y").jsonPrimitive.boolean,
                    c[0], ColorRgb(c[1], c[2], c[3]), ColorRgb(c[4], c[5], c[6]))
            })
        }
    }
}
