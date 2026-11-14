package io.github.psd2live.algorithm.worker

import io.github.psd2live.format.model.*

/** Structural checks before a decoded worker result may enter the host candidate. */
object AlgorithmResultValidation {
    fun validate(rig: RigIR) {
        fun ids(values: List<String>): Set<String> {
            require(values.all { it.isNotBlank() && it.none(Char::isISOControl) } && values.distinct().size == values.size) { "Invalid or duplicate output identity" }
            return values.toSet()
        }
        fun finite(vararg values: Float) { require(values.all(Float::isFinite)) { "Non-finite algorithm output" } }
        val parameters = ids(rig.parameters.map { it.id })
        val treeParameters = HashSet<String>()
        val treeGroups = HashSet<String>()
        fun parameterTree(nodes: List<ParameterNode>) {
            for (node in nodes) when (node) {
                is ParameterNode.Param -> require(node.id in parameters && treeParameters.add(node.id)) {
                    "Missing or duplicate parameter tree reference: ${node.id}"
                }
                is ParameterNode.Group -> {
                    require(node.id.isNotBlank() && node.id.none(Char::isISOControl) && treeGroups.add(node.id)) {
                        "Invalid or duplicate parameter group: ${node.id}"
                    }
                    parameterTree(node.children)
                }
            }
        }
        parameterTree(rig.parameterTree)
        rig.parameterLinks.forEach { link ->
            require(link.horizontal in parameters && link.vertical in parameters) { "Missing parameter link reference" }
        }
        val meshes = ids(rig.meshes.map { it.id })
        val deformers = ids(rig.deformers.map { it.id })
        val parts = ids(rig.parts.map { it.id })
        ids(rig.physics.groups.map { it.id }); ids(rig.clips.map { it.id }); ids(rig.glues.mapNotNull { it.id })
        finite(rig.canvas.width, rig.canvas.height, rig.canvas.originX, rig.canvas.originY)
        require(rig.canvas.width > 0 && rig.canvas.height > 0)
        rig.parameters.forEach { p -> finite(p.min, p.max, p.default); require(p.min < p.max && p.default in p.min..p.max) }
        val parents = rig.deformers.associate { it.id to it.parent }
        val done = HashSet<String>()
        for (id in deformers) {
            val chain = HashSet<String>(); var current: String? = id
            while (current != null && current !in done) {
                require(current in deformers && chain.add(current)) { "Missing or cyclic output deformer parent" }
                current = parents[current]
            }
            done += chain
        }
        fun vector(values: Floats, size: Int) { require(values.size == size); require(values.shared().all(Float::isFinite)) }
        fun <T> grid(value: KeyGrid<T>?, form: (T) -> Unit) {
            if (value == null) return
            require(value.axes.map { it.parameter }.distinct().size == value.axes.size)
            value.axes.forEach { axis ->
                require(axis.parameter in parameters && axis.keys.size > 0)
                require(axis.keys.shared().all(Float::isFinite))
                require((1 until axis.keys.size).all { axis.keys[it - 1] < axis.keys[it] })
            }
            val coordinates = HashSet<Ints>()
            value.cells.forEach { cell ->
                require(cell.coordinate.size == value.axes.size && coordinates.add(cell.coordinate))
                require(value.axes.indices.all { cell.coordinate[it] in 0 until value.axes[it].keys.size })
                form(cell.form)
            }
        }
        fun channels(value: Channels) = value.values.forEach { channel -> grid(channel) { form -> when (form) {
            is ChannelValue.Scalar -> finite(form.value)
            is ChannelValue.Color -> finite(form.color.red, form.color.green, form.color.blue)
            is ChannelValue.Flag -> Unit
        } } }
        val partParents = HashMap<String, String>()
        fun children(owner: String?, values: List<ChildRef>) {
            require(values.distinct().size == values.size) { "Duplicate hierarchy child" }
            values.forEach { child -> when (child) {
                is ChildRef.MeshRef -> require(child.id in meshes) { "Missing hierarchy mesh" }
                is ChildRef.PartRef -> {
                    require(child.id in parts) { "Missing hierarchy part" }
                    if (owner != null) require(partParents.put(child.id, owner) == null) { "Part has multiple parents" }
                }
            } }
        }
        children(null, rig.rootChildren)
        rig.parts.forEach { p -> children(p.id, p.children); channels(p.channels) }
        val checkedParts = HashSet<String>()
        for (part in parts) {
            val chain = HashSet<String>(); var current: String? = part
            while (current != null && current !in checkedParts) {
                require(chain.add(current)) { "Cyclic output part hierarchy" }
                current = partParents[current]
            }
            checkedParts += chain
        }
        require(rig.rootPart == null || rig.rootPart in parts)
        rig.deformers.forEach { d ->
            require(d.part == null || d.part in parts); finite(d.opacity); require(d.opacity in 0f..1f)
            channels(d.channels)
            when (d) {
                is Deformer.Warp -> {
                    require(d.rows in 1..4096 && d.columns in 1..4096)
                    grid(d.lattice) { vector(it.points, (d.rows + 1) * (d.columns + 1) * 2) }
                }
                is Deformer.Rotation -> { finite(d.baseAngle); grid(d.pivot) { finite(it.x, it.y, it.angle, it.scale) } }
            }
        }
        rig.meshes.forEach { m ->
            require(m.parent == null || m.parent in deformers)
            require(m.maskedBy.all { it in meshes && it != m.id } && (m.textureSource == null || m.textureSource in meshes)) {
                "Invalid output mesh references for ${m.id}: masks=${m.maskedBy.filter { it !in meshes || it == m.id }}, " +
                    "textureSource=${m.textureSource?.takeIf { it !in meshes }}"
            }
            finite(m.opacity, m.drawOrder); require(m.opacity in 0f..1f)
            channels(m.channels)
            m.geometry?.let { g ->
                require(g.positions.size % 2 == 0 && g.indices.size % 3 == 0)
                vector(g.positions, g.positions.size); vector(g.uvs, g.positions.size)
                require(g.indices.shared().all { it in 0 until g.positions.size / 2 })
                grid(m.offsets) { vector(it.deltas, g.positions.size) }
            }
        }
        val meshById = rig.meshes.associateBy { it.id }
        rig.glues.forEach { glue ->
            require(glue.meshA in meshes && glue.meshB in meshes && glue.meshA != glue.meshB)
            val a = meshById.getValue(glue.meshA).geometry?.positions?.size?.div(2) ?: 0
            val b = meshById.getValue(glue.meshB).geometry?.positions?.size?.div(2) ?: 0
            finite(glue.intensity); require(glue.intensity in 0f..1f); channels(glue.channels)
            glue.pairs.forEach { pair ->
                require(pair.a in 0 until a && pair.b in 0 until b)
                finite(pair.weightA, pair.weightB); require(pair.weightA in 0f..1f && pair.weightB in 0f..1f)
            }
        }
        val outputs = HashSet<String>()
        rig.physics.fps?.let { finite(it); require(it > 0) }
        rig.physics.groups.forEach { group ->
            require(group.segments.isNotEmpty())
            group.inputs.forEach { require(it.parameter in parameters); finite(it.weight); require(it.weight in 0f..100f) }
            group.outputs.forEach { require(it.parameter in parameters && outputs.add(it.parameter)); finite(it.weight, it.scale)
                require(it.weight in 0f..100f && it.vertex in 1..group.segments.size) }
            group.segments.forEach { finite(it.length, it.mobility, it.delay, it.acceleration)
                require(it.length > 0 && it.mobility in 0f..1f && it.delay > 0 && it.acceleration >= 0) }
            with(group.normalization) {
                finite(positionMin, positionDefault, positionMax, angleMin, angleDefault, angleMax)
                require(positionMin < positionMax && positionDefault in positionMin..positionMax)
                require(angleMin < angleMax && angleDefault in angleMin..angleMax)
            }
        }
        rig.restPose.forEach { (id, value) -> require(id in parameters); finite(value) }
        rig.parameterRoles.forEach { role -> require(role.parameters.all { it in parameters }) }
        fun fade(value: Float?) { value?.let { finite(it); require(it >= 0f) } }
        val files = HashSet<String>()
        rig.clips.forEach { clip ->
            finite(clip.duration, clip.fps); require(clip.duration > 0 && clip.fps > 0)
            require(clip.file.isNotBlank() && clip.file.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } && files.add(clip.file.lowercase())) { "Invalid or duplicate motion filename" }
            fade(clip.fadeIn); fade(clip.fadeOut)
            fun curve(start: Float, value: Float, segments: List<CurveSegment>) {
                finite(start, value); require(start in 0f..clip.duration)
                var previous = start
                segments.forEach { segment ->
                    finite(segment.time, segment.value); require(segment.time >= previous && segment.time <= clip.duration) { "Invalid segment in clip '${clip.file}': time=${segment.time}, previous=$previous, duration=${clip.duration}" }
                    if (segment is CurveSegment.Bezier) finite(segment.c1Time, segment.c1Value, segment.c2Time, segment.c2Value)
                    previous = segment.time
                }
            }
            clip.curves.forEach { c -> require(c.parameter in parameters); fade(c.fadeIn); fade(c.fadeOut); curve(c.startTime, c.startValue, c.segments) }
            clip.targetCurves.forEach { c ->
                val target = c.target
                if (target is CurveTarget.PartOpacity) require(target.part in parts)
                fade(c.fadeIn); fade(c.fadeOut); curve(c.startTime, c.startValue, c.segments)
            }
            clip.events.forEach { finite(it.time); require(it.time in 0f..clip.duration) }
        }
    }
}
