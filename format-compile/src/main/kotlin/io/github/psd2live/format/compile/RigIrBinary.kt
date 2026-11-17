package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * A compact, exact binary encoding of a whole [RigIR], for caches that must hand back the very value they
 * stored: floats keep their raw bits, collections keep their order, and every field is written. It is not an
 * interchange format - readers accept versions 6 through [VERSION] and reject anything else (and any truncated or
 * trailing bytes) with [IOException]. Inputs representable in version 6 remain usable by existing resource readers.
 *
 * A field added to the IR must be added here in the same change, with [VERSION] raised. [RigIrObjects] stores rigs
 * in this encoding for good, so the reader must keep reading every version from [RigIrObjects.MIN_VERSION] on: a
 * new field is read only when [Reader.version] is at least the version that added it, and takes its default before.
 *
 * From version 5 a float array is written as four byte planes - every value's first byte, then every second byte,
 * and so on - which DEFLATE compresses far better than interleaved floats; values and order are unchanged.
 */
public object RigIrBinary {
	public const val VERSION: Int = 7
	/** The first version writing float arrays as byte planes. */
	internal const val PLANAR_FLOATS: Int = 5
	/** The first version with the rig's own hit areas ([RigIR.hitAreas]). */
	internal const val HIT_AREAS: Int = 6
	private const val MAGIC = 0x50524952 // "PRIR"

	internal fun encodingVersion(ir: RigIR): Int = if (ir.clips.any { clip ->
        clip.events.isNotEmpty() || clip.targetCurves.isNotEmpty() || clip.curves.any { it.fadeIn != null || it.fadeOut != null }
    }) VERSION else 6

	public fun encode(ir: RigIR): ByteArray {
		val bytes = ByteArrayOutputStream(1 shl 16)
		DataOutputStream(bytes).use { out -> out.writeInt(MAGIC); out.writeInt(encodingVersion(ir)); Writer(out, encodingVersion(ir)).rig(ir) }
		return bytes.toByteArray()
	}

	public fun decode(bytes: ByteArray): RigIR {
		val input = DataInputStream(bytes.inputStream())
		try {
			if (input.readInt() != MAGIC) throw IOException("Not an encoded rig")
			val version = input.readInt()
			if (version !in 6..VERSION) throw IOException("Unsupported encoded rig version $version")
			val ir = Reader(input, version).rig()
			if (input.read() != -1) throw IOException("Trailing bytes after encoded rig")
			return ir
		} catch (failure: java.io.EOFException) {
			throw IOException("Truncated encoded rig", failure)
		} catch (failure: IllegalArgumentException) {
			throw IOException("Invalid encoded rig", failure)
		} catch (failure: IndexOutOfBoundsException) {
			throw IOException("Invalid encoded rig", failure)
		}
	}

	internal class Writer(private val out: DataOutputStream, private val version: Int = VERSION) {
		fun int(value: Int) = out.writeInt(value)
		fun float(value: Float) = out.writeInt(java.lang.Float.floatToRawIntBits(value))
		fun bool(value: Boolean) = out.writeBoolean(value)
		fun string(value: String) { val bytes = value.encodeToByteArray(); int(bytes.size); out.write(bytes) }
		fun <T> nullable(value: T?, write: (T) -> Unit) { bool(value != null); if (value != null) write(value) }
		fun <T> list(values: List<T>, write: (T) -> Unit) { int(values.size); values.forEach(write) }
		fun floats(values: Floats) {
			val array = values.shared()
			int(array.size)
			val n = array.size
			val planes = ByteArray(n * 4)
			for (i in array.indices) {
				val bits = java.lang.Float.floatToRawIntBits(array[i])
				planes[i] = (bits ushr 24).toByte(); planes[n + i] = (bits ushr 16).toByte()
				planes[2 * n + i] = (bits ushr 8).toByte(); planes[3 * n + i] = bits.toByte()
			}
			out.write(planes)
		}
		fun ints(values: Ints) { val array = values.shared(); int(array.size); for (v in array) int(v) }
		fun bytes(values: Bytes) { val array = values.shared(); int(array.size); out.write(array) }
		fun rgb(value: Rgb) { float(value.red); float(value.green); float(value.blue) }
		fun <E : Enum<E>> enum(value: E) = int(value.ordinal)

		fun rig(ir: RigIR) {
			ir.canvas.let { float(it.width); float(it.height); float(it.originX); float(it.originY); nullable(it.pixelsPerUnit, ::float) }
			list(ir.parameters) { p ->
				string(p.id); string(p.name); float(p.min); float(p.max); float(p.default); bool(p.blend); bool(p.repeat); nullable(p.keys, ::floats)
			}
			list(ir.parameterLinks) { string(it.horizontal); string(it.vertical) }
			list(ir.parameterTree, ::parameterNode)
			list(ir.parameterRoles) { string(it.role); list(it.parameters, ::string) }
			list(ir.parts) { part ->
				string(part.id); string(part.name); list(part.children, ::child)
				bool(part.visible); bool(part.sketch); bool(part.selectable); enum(part.groupMode); int(part.drawOrder)
				channels(part.channels); composite(part.composite)
				list(part.shapes) { binding(it) { s -> float(s.drawOrder); float(s.opacity); rgb(s.multiply); rgb(s.screen) } }
			}
			list(ir.rootChildren, ::child)
			nullable(ir.rootPart, ::string)
			list(ir.deformers, ::deformer)
			list(ir.meshes, ::mesh)
			list(ir.glues) { glue ->
				nullable(glue.id, ::string); string(glue.meshA); string(glue.meshB)
				list(glue.pairs) { int(it.a); int(it.b); float(it.weightA); float(it.weightB) }
				channels(glue.channels); float(glue.intensity)
			}
			renderGroup(ir.renderRoot)
			textures(ir.textures)
			physics(ir.physics)
			list(ir.clips, ::clip)
			list(ir.restPose.entries.toList()) { string(it.key); float(it.value) }
			authoring(ir.authoring)
			advanced(ir.advanced)
			list(ir.hitAreas) { string(it.id); string(it.name); list(it.meshes, ::string) }
		}

		fun advanced(a: AdvancedIR) {
			list(a.virtualBones, ::deformer)
			list(a.simulations) { s ->
				string(s.id); float(s.fps); int(s.substeps); float(s.gravityX); float(s.gravityY); float(s.windX); float(s.windY); float(s.pinCompliance)
				list(s.targets) { string(it.mesh); int(it.vertexCount) }
				s.particles.let { p ->
					floats(p.invMass); floats(p.damping); floats(p.windFactor); floats(p.pinWeight); floats(p.goalCompliance)
					floats(p.goalOffsetX); floats(p.goalOffsetY); list(p.anchorMesh, ::string); ints(p.anchorVertex)
				}
				s.stretch.let { ints(it.a); ints(it.b); floats(it.rest); floats(it.compliance); floats(it.compressionCompliance) }
				s.triangles.let { ints(it.a); ints(it.b); ints(it.c); floats(it.areaCompliance) }
				s.bends.let { ints(it.t1); ints(it.t2); floats(it.compliance) }
				s.welds.let { ints(it.a); ints(it.b); floats(it.weightA); floats(it.weightB); floats(it.compliance) }
				s.longRange.let { ints(it.particle); ints(it.root); floats(it.maxDistance) }
				list(s.parameters, ::string); list(s.physicsGroups, ::string)
				list(s.statics) { st -> string(st.parameter); floats(st.keys); list(st.offsets.entries.toList()) { string(it.key); list(it.value, ::floats) } }
				list(s.colliders, ::string)
			}
			list(a.colliders) { c ->
				string(c.id); bool(c.capsule); nullable(c.deformer, ::string); nullable(c.mesh, ::string); int(c.vertexA); int(c.vertexB)
				float(c.ax); float(c.ay); float(c.bx); float(c.by); float(c.radiusA); float(c.radiusB); float(c.friction)
			}
			list(a.expressions) { e ->
				string(e.id); string(e.name); float(e.fadeIn); float(e.fadeOut)
				list(e.parameters) { string(it.parameter); enum(it.blend); float(it.value) }
			}
			list(a.hitAreas) { string(it.id); string(it.name); list(it.meshes, ::string) }
			nullable(a.pose) { p -> float(p.fadeIn); list(p.groups) { g -> list(g) { string(it.part); list(it.links, ::string) } } }
		}

		fun parameterNode(node: ParameterNode) {
			when (node) {
				is ParameterNode.Param -> { int(0); string(node.id) }
				is ParameterNode.Group -> {
					int(1); string(node.id); string(node.name); bool(node.open); list(node.children, ::parameterNode)
					when (val label = node.label) {
						GroupLabel.None -> int(0)
						is GroupLabel.Preset -> { int(1); string(label.name) }
						is GroupLabel.Custom -> { int(2); int(label.argb) }
					}
				}
			}
		}

		fun child(child: ChildRef) = when (child) {
			is ChildRef.PartRef -> { int(0); string(child.id) }
			is ChildRef.MeshRef -> { int(1); string(child.id) }
		}

		fun composite(c: Composite) {
			enum(c.blend); enum(c.alphaBlend); list(c.maskedBy, ::string); list(c.maskedByParts, ::string); bool(c.invertMask)
			float(c.opacity); rgb(c.multiply); rgb(c.screen)
		}

		fun <F> grid(grid: KeyGrid<F>, form: (F) -> Unit) {
			list(grid.axes) { string(it.parameter); floats(it.keys) }
			list(grid.cells) { ints(it.coordinate); form(it.form) }
		}

		fun channels(channels: Channels) = list(channels.entries.toList()) { (channel, grid) ->
			enum(channel)
			grid(grid) { value ->
				when (value) {
					is ChannelValue.Scalar -> { int(0); float(value.value) }
					is ChannelValue.Color -> { int(1); rgb(value.color) }
					is ChannelValue.Flag -> { int(2); bool(value.value) }
				}
			}
		}

		fun <S> binding(binding: BlendBinding<S>, shape: (S) -> Unit) {
			string(binding.parameter); floats(binding.keys); int(binding.neutralIndex)
			list(binding.shapes) { nullable(it, shape) }
			list(binding.limits) { limit -> string(limit.parameter); list(limit.points) { float(it.value); float(it.weight) } }
		}

		fun deformer(deformer: Deformer) {
			when (deformer) {
				is Deformer.Warp -> {
					int(0); string(deformer.id); string(deformer.name); nullable(deformer.parent, ::string); nullable(deformer.part, ::string)
					int(deformer.rows); int(deformer.columns); bool(deformer.bilinear)
					nullable(deformer.lattice) { grid(it) { form -> floats(form.points) } }
					channels(deformer.channels); float(deformer.opacity); rgb(deformer.multiply); rgb(deformer.screen)
					bool(deformer.selectable); bool(deformer.visible); bool(deformer.enabled)
					list(deformer.shapes) { binding(it) { s -> floats(s.points); float(s.opacity); rgb(s.multiply); rgb(s.screen) } }
				}
				is Deformer.Rotation -> {
					int(1); string(deformer.id); string(deformer.name); nullable(deformer.parent, ::string); nullable(deformer.part, ::string)
					float(deformer.baseAngle)
					nullable(deformer.pivot) { grid(it) { p -> float(p.x); float(p.y); float(p.angle); float(p.scale) } }
					channels(deformer.channels); float(deformer.opacity); rgb(deformer.multiply); rgb(deformer.screen)
					bool(deformer.flipX); bool(deformer.flipY); bool(deformer.selectable); bool(deformer.visible); bool(deformer.enabled)
					list(deformer.shapes) { binding(it) { s ->
						float(s.x); float(s.y); float(s.angle); float(s.scale); bool(s.flipX); bool(s.flipY); float(s.opacity); rgb(s.multiply); rgb(s.screen)
					} }
					nullable(deformer.handleLength, ::float)
				}
			}
		}

		fun mesh(mesh: Mesh) {
			string(mesh.id); string(mesh.name); nullable(mesh.parent, ::string); enum(mesh.blend); enum(mesh.alphaBlend)
			list(mesh.maskedBy, ::string); bool(mesh.invertMask)
			nullable(mesh.geometry) { floats(it.positions); floats(it.uvs); ints(it.indices) }
			nullable(mesh.offsets) { grid(it) { form -> floats(form.deltas) } }
			channels(mesh.channels); float(mesh.drawOrder); float(mesh.opacity); rgb(mesh.multiply); rgb(mesh.screen)
			bool(mesh.culling); bool(mesh.visible); bool(mesh.selectable); nullable(mesh.textureSource, ::string); int(mesh.page)
			nullable(mesh.tile, ::string)
			list(mesh.shapes) { binding(it) { s -> floats(s.deltas); float(s.drawOrder); float(s.opacity); rgb(s.multiply); rgb(s.screen) } }
			string(mesh.userData)
		}

		fun renderGroup(group: RenderGroup) {
			nullable(group.part, ::string); int(group.drawOrder)
			list(group.children) { node ->
				when (node) {
					is RenderMesh -> { int(0); string(node.id) }
					is RenderGroup -> { int(1); renderGroup(node) }
				}
			}
			channels(group.channels); nullable(group.composite, ::composite)
		}

		fun textures(t: Textures) {
			list(t.pages) { int(it.width); int(it.height); bytes(it.png) }
			list(t.bindings.entries.toList()) { string(it.key); int(it.value) }
			list(t.tilePages) { int(it.width); int(it.height) }
			list(t.tiles) { tile ->
				string(tile.id); string(tile.name); int(tile.width); int(tile.height)
				nullable(tile.placement) { int(it.page); float(it.x); float(it.y); float(it.scaleX); float(it.scaleY); float(it.rotation) }
				nullable(tile.source) { string(it.source); string(it.layer); bool(it.stableKey) }
				bool(tile.pinned); nullable(tile.replaces, ::string)
			}
			bool(t.uvsAddressPages); int(t.alphaThreshold); int(t.extrude)
			list(t.tileArt) { string(it.tile); int(it.width); int(it.height); bytes(it.rgba) }
		}

		fun physics(p: Physics) {
			list(p.groups) { g ->
				string(g.id); string(g.name)
				list(g.inputs) { string(it.parameter); float(it.weight); enum(it.source); bool(it.reflect) }
				list(g.outputs) { string(it.parameter); int(it.vertex); float(it.scale); float(it.weight); enum(it.source); bool(it.reflect) }
				list(g.segments) { float(it.length); float(it.mobility); float(it.delay); float(it.acceleration) }
				g.normalization.let { float(it.positionMin); float(it.positionDefault); float(it.positionMax); float(it.angleMin); float(it.angleDefault); float(it.angleMax) }
			}
			nullable(p.fps, ::float)
		}

		private fun segments(values: List<CurveSegment>) = list(values) { s ->
			when (s) {
				is CurveSegment.Linear -> int(0)
				is CurveSegment.Bezier -> { int(1); float(s.c1Time); float(s.c1Value); float(s.c2Time); float(s.c2Value) }
				is CurveSegment.Stepped -> int(2)
				is CurveSegment.InverseStepped -> int(3)
			}
			float(s.time); float(s.value)
		}

		fun clip(c: Clip) {
            require(version >= 7 || c.events.isEmpty() && c.targetCurves.isEmpty() && c.curves.all { it.fadeIn == null && it.fadeOut == null }) {
                "Motion channels require rig object version 7"
            }
			string(c.id); string(c.name); string(c.group); string(c.file); float(c.duration); float(c.fps); bool(c.loop)
			nullable(c.fadeIn, ::float); nullable(c.fadeOut, ::float)
			list(c.curves) { curve ->
				string(curve.parameter); float(curve.startTime); float(curve.startValue); segments(curve.segments)
                if (version >= 7) { nullable(curve.fadeIn, ::float); nullable(curve.fadeOut, ::float) }
			}
            if (version < 7) return
			list(c.events) { float(it.time); string(it.value) }
			list(c.targetCurves) { curve ->
				when (val target = curve.target) {
					is CurveTarget.PartOpacity -> { int(0); string(target.part) }
					CurveTarget.ModelOpacity -> int(1)
					CurveTarget.EyeBlink -> int(2)
					CurveTarget.LipSync -> int(3)
				}
				float(curve.startTime); float(curve.startValue); segments(curve.segments)
				nullable(curve.fadeIn, ::float); nullable(curve.fadeOut, ::float)
			}
		}

		fun authoring(a: Authoring) {
			string(a.runtime); bool(a.rendersFromSourceLayers)
			list(a.sources) { s ->
				string(s.id); string(s.name); nullable(s.path, ::string); string(s.format)
				list(s.layers) { l ->
					string(l.key); string(l.name); string(l.groupPath); int(l.left); int(l.top); int(l.width); int(l.height); bool(l.visible)
					bool(l.present); nullable(l.hash, ::string); bool(l.empty); bool(l.replaced); bool(l.ignored)
				}
				nullable(s.hash, ::string); nullable(s.lastModified) { out.writeLong(it) }
			}
			list(a.paths) { p ->
				string(p.id); string(p.mesh)
				list(p.points) { int(it.a); int(it.b); int(it.c); float(it.wa); float(it.wb); float(it.wc); bool(it.corner) }
				float(p.width); float(p.hardness); bool(p.closed); int(p.editLevel)
			}
			list(a.vertexGroups) { string(it.name); string(it.mesh); string(it.kind); floats(it.weights) }
		}
	}

	/** [version] is the version the bytes were written with: a field added later is read only from its version on. */
	internal class Reader(private val input: DataInputStream, val version: Int = VERSION) {
		fun int() = input.readInt()
		fun float() = java.lang.Float.intBitsToFloat(input.readInt())
		fun bool() = input.readBoolean()
		fun count(): Int { val n = int(); if (n < 0 || n > input.available()) throw IOException("Invalid length $n"); return n }
		fun string(): String { val bytes = ByteArray(count()); input.readFully(bytes); return bytes.decodeToString() }
		fun <T> nullable(read: () -> T): T? = if (bool()) read() else null
		fun <T> list(read: () -> T): List<T> { val n = count(); return List(n) { read() } }
		/** A string-keyed map in stored order. (Not inside `apply` on a map, where `count()` is the map's own.) */
		fun <V> map(value: () -> V): Map<String, V> {
			val n = count(); val map = LinkedHashMap<String, V>(n * 2)
			repeat(n) { map[string()] = value() }
			return map
		}
		fun floats(): Floats {
			val n = count()
			if (version < PLANAR_FLOATS) return Floats.wrap(FloatArray(n) { float() })
			if (n.toLong() * 4 > input.available()) throw IOException("Invalid length $n")
			val planes = ByteArray(n * 4).also(input::readFully)
			return Floats.wrap(FloatArray(n) { i ->
				java.lang.Float.intBitsToFloat(((planes[i].toInt() and 255) shl 24) or ((planes[n + i].toInt() and 255) shl 16) or
					((planes[2 * n + i].toInt() and 255) shl 8) or (planes[3 * n + i].toInt() and 255))
			})
		}
		fun ints(): Ints { val n = count(); return Ints.wrap(IntArray(n) { int() }) }
		fun bytes(): Bytes { val array = ByteArray(count()); input.readFully(array); return Bytes.wrap(array) }
		fun rgb() = Rgb(float(), float(), float())
		inline fun <reified E : Enum<E>> enum(): E = enumValues<E>().getOrNull(int()) ?: throw IOException("Invalid ${E::class.simpleName}")
		fun tag(max: Int): Int { val tag = int(); if (tag !in 0..max) throw IOException("Invalid tag $tag"); return tag }

		fun rig(): RigIR {
			val canvas = Canvas(float(), float(), float(), float(), nullable(::float))
			val parameters = list { Parameter(string(), string(), float(), float(), float(), bool(), bool(), nullable(::floats)) }
			val links = list { ParameterLink(string(), string()) }
			val tree = list(::parameterNode)
			val roles = list { ParameterRole(string(), list(::string)) }
			val parts = list {
				Part(string(), string(), list(::child), bool(), bool(), bool(), enum<GroupMode>(), int(), channels(), composite(),
					list { binding { PartShape(float(), float(), rgb(), rgb()) } })
			}
			val rootChildren = list(::child)
			val rootPart = nullable(::string)
			val deformers = list(::deformer)
			val meshes = list(::mesh)
			val glues = list {
				Glue(nullable(::string), string(), string(), list { GluePair(int(), int(), float(), float()) }, channels(), float())
			}
			val renderRoot = renderGroup()
			val textures = textures()
			val physics = physics()
			val clips = list(::clip)
			val restPose = map(::float)
			val authoring = authoring()
			val advanced = advanced()
			val hitAreas = if (version < HIT_AREAS) emptyList() else list { HitAreaIR(string(), string(), list(::string)) }
			return RigIR(canvas, parameters, links, tree, roles, parts, rootChildren, rootPart, deformers, meshes, glues, renderRoot,
				textures, physics, clips, restPose, authoring, advanced, hitAreas)
		}

		fun advanced(): AdvancedIR {
			val bones = list { deformer() as? Deformer.Rotation ?: throw IOException("A virtual bone must be a rotation") }
			val simulations = list {
				val id = string(); val fps = float(); val substeps = int()
				val gx = float(); val gy = float(); val wx = float(); val wy = float(); val pin = float()
				val targets = list { SimTarget(string(), int()) }
				val particles = SimParticles(floats(), floats(), floats(), floats(), floats(), floats(), floats(), list(::string), ints())
				val stretch = SimStretch(ints(), ints(), floats(), floats(), floats())
				val triangles = SimTriangles(ints(), ints(), ints(), floats())
				val bends = SimBends(ints(), ints(), floats())
				val welds = SimWelds(ints(), ints(), floats(), floats(), floats())
				val longRange = SimLongRange(ints(), ints(), floats())
				val parameters = list(::string); val groups = list(::string)
				val statics = list { SimStatic(string(), floats(), map { list(::floats) }) }
				SimulationIR(id, fps, substeps, gx, gy, wx, wy, pin, targets, particles, stretch, triangles, bends, welds, longRange,
					parameters, groups, statics, list(::string))
			}
			val colliders = list {
				ColliderIR(string(), bool(), nullable(::string), nullable(::string), int(), int(), float(), float(), float(), float(), float(), float(), float())
			}
			val expressions = list { ExpressionIR(string(), string(), float(), float(), list { ExpressionParameter(string(), enum(), float()) }) }
			val hitAreas = list { HitAreaIR(string(), string(), list(::string)) }
			val pose = nullable { PoseIR(float(), list { list { PoseEntry(string(), list(::string)) } }) }
			return AdvancedIR(bones, simulations, colliders, expressions, hitAreas, pose)
		}

		fun parameterNode(): ParameterNode = when (tag(1)) {
			0 -> ParameterNode.Param(string())
			else -> {
				val id = string(); val name = string(); val open = bool(); val children = list(::parameterNode)
				val label = when (tag(2)) { 0 -> GroupLabel.None; 1 -> GroupLabel.Preset(string()); else -> GroupLabel.Custom(int()) }
				ParameterNode.Group(id, name, open, children, label)
			}
		}

		fun child(): ChildRef = if (tag(1) == 0) ChildRef.PartRef(string()) else ChildRef.MeshRef(string())

		fun composite() = Composite(enum(), enum(), list(::string), list(::string), bool(), float(), rgb(), rgb())

		fun <F> grid(form: () -> F): KeyGrid<F> = KeyGrid(list { KeyAxis(string(), floats()) }, list { KeyCell(ints(), form()) })

		fun channels(): Channels {
			val map = LinkedHashMap<Channel, KeyGrid<ChannelValue>>()
			repeat(count()) {
				val channel = enum<Channel>()
				map[channel] = grid {
					when (tag(2)) { 0 -> ChannelValue.Scalar(float()); 1 -> ChannelValue.Color(rgb()); else -> ChannelValue.Flag(bool()) }
				}
			}
			return map
		}

		fun <S> binding(shape: () -> S): BlendBinding<S> = BlendBinding(string(), floats(), int(), list { nullable(shape) },
			list { BlendLimit(string(), list { BlendLimitPoint(float(), float()) }) })

		fun deformer(): Deformer = when (tag(1)) {
			0 -> Deformer.Warp(string(), string(), nullable(::string), nullable(::string), int(), int(), bool(),
				nullable { grid { LatticePoints(floats()) } }, channels(), float(), rgb(), rgb(), bool(), bool(), bool(),
				list { binding { LatticeShape(floats(), float(), rgb(), rgb()) } })
			else -> {
				val id = string(); val name = string(); val parent = nullable(::string); val part = nullable(::string)
				val baseAngle = float()
				val pivot = nullable { grid { Pivot(float(), float(), float(), float()) } }
				val channels = channels(); val opacity = float(); val multiply = rgb(); val screen = rgb()
				val flipX = bool(); val flipY = bool(); val selectable = bool(); val visible = bool(); val enabled = bool()
				val shapes = list { binding { PivotShape(float(), float(), float(), float(), bool(), bool(), float(), rgb(), rgb()) } }
				Deformer.Rotation(id, name, parent, part, baseAngle, pivot, channels, opacity, multiply, screen, flipX, flipY,
					selectable, visible, enabled, shapes, nullable(::float))
			}
		}

		fun mesh(): Mesh {
			val id = string(); val name = string(); val parent = nullable(::string); val blend = enum<ColorBlend>(); val alpha = enum<AlphaBlend>()
			val maskedBy = list(::string); val invert = bool()
			val geometry = nullable { MeshGeometry(floats(), floats(), ints()) }
			val offsets = nullable { grid { MeshOffsets(floats()) } }
			val channels = channels(); val drawOrder = float(); val opacity = float(); val multiply = rgb(); val screen = rgb()
			val culling = bool(); val visible = bool(); val selectable = bool(); val textureSource = nullable(::string); val page = int()
			val tile = nullable(::string)
			val shapes = list { binding { MeshShape(floats(), float(), float(), rgb(), rgb()) } }
			return Mesh(id, name, parent, blend, alpha, maskedBy, invert, geometry, offsets, channels, drawOrder, opacity, multiply, screen,
				culling, visible, selectable, textureSource, page, tile, shapes, string())
		}

		fun renderGroup(): RenderGroup {
			val part = nullable(::string); val drawOrder = int()
			val children = list<RenderNode> { if (tag(1) == 0) RenderMesh(string()) else renderGroup() }
			return RenderGroup(part, drawOrder, children, channels(), nullable(::composite))
		}

		fun textures(): Textures {
			val pages = list { TexturePage(int(), int(), bytes()) }
			val bindings = map(::int)
			val tilePages = list { PageSize(int(), int()) }
			val tiles = list {
				TextureTile(string(), string(), int(), int(), nullable { TilePlacement(int(), float(), float(), float(), float(), float()) },
					nullable { SourceRef(string(), string(), bool()) }, bool(), nullable(::string))
			}
			return Textures(pages, bindings, tilePages, tiles, bool(), int(), int(), list { TileArt(string(), int(), int(), bytes()) })
		}

		fun physics() = Physics(list {
			PhysicsGroup(string(), string(),
				list { PhysicsInput(string(), float(), enum(), bool()) },
				list { PhysicsOutput(string(), int(), float(), float(), enum(), bool()) },
				list { PhysicsSegment(float(), float(), float(), float()) },
				PhysicsNormalization(float(), float(), float(), float(), float(), float()))
		}, nullable(::float))

		private fun segments(): List<CurveSegment> = list {
			when (tag(3)) {
				0 -> CurveSegment.Linear(float(), float())
				1 -> { val c1t = float(); val c1v = float(); val c2t = float(); val c2v = float(); CurveSegment.Bezier(c1t, c1v, c2t, c2v, float(), float()) }
				2 -> CurveSegment.Stepped(float(), float())
				else -> CurveSegment.InverseStepped(float(), float())
			}
		}

		fun clip() = Clip(string(), string(), string(), string(), float(), float(), bool(), nullable(::float), nullable(::float), list {
			Curve(string(), float(), float(), segments(),
				if (version >= 7) nullable(::float) else null, if (version >= 7) nullable(::float) else null)
		}, if (version >= 7) list { ClipEvent(float(), string()) } else emptyList(),
			if (version >= 7) list {
				val target = when (tag(3)) {
					0 -> CurveTarget.PartOpacity(string())
					1 -> CurveTarget.ModelOpacity
					2 -> CurveTarget.EyeBlink
					else -> CurveTarget.LipSync
				}
				TargetCurve(target, float(), float(), segments(), nullable(::float), nullable(::float))
			} else emptyList())

		fun authoring() = Authoring(string(), bool(),
			list {
				SourceFile(string(), string(), nullable(::string), string(),
					list { SourceLayer(string(), string(), string(), int(), int(), int(), int(), bool(), bool(), nullable(::string), bool(), bool(), bool()) },
					nullable(::string), nullable { input.readLong() })
			},
			list { DeformPath(string(), string(), list { PathPoint(int(), int(), int(), float(), float(), float(), bool()) }, float(), float(), bool(), int()) },
			list { VertexGroup(string(), string(), string(), floats()) })
	}
}
