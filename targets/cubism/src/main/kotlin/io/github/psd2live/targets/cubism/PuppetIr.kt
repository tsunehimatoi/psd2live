package io.github.psd2live.targets.cubism

import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Composite
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.format.model.DeformPath as IrDeformPath
import io.github.psd2live.format.model.Glue as IrGlue
import io.github.psd2live.format.model.GluePair as IrGluePair
import io.github.psd2live.format.model.Parameter as IrParameter
import io.github.psd2live.format.model.ParameterLink as IrParameterLink
import io.github.psd2live.format.model.ParameterNode as IrParameterNode
import io.github.psd2live.format.model.Part as IrPart
import io.github.psd2live.format.model.RenderGroup as IrRenderGroup
import io.github.psd2live.format.model.VertexGroup as IrVertexGroup
import org.umamo.runtime.model.*
import org.umamo.runtime.model.ChannelValue
import io.github.psd2live.format.model.ChannelValue as IrChannelValue

/**
 * The two-way bridge between the engine's [PuppetModel] and the neutral [RigIR].
 *
 * Lossless by contract: `toPuppet(toIr(model))` reproduces every field of the model. A field added to
 * either side must be added here in the same change; the round-trip tests guard it. The packed texture
 * pages and their mesh bindings, physics and motions are not part of a [PuppetModel] and are passed to
 * [toIr] separately.
 */
public object PuppetIr {
	/** Convert parameter declarations without traversing geometry, textures or keyforms. */
	public fun parametersToIr(model: PuppetModel): List<IrParameter> = model.parameters.map {
		IrParameter(it.id.raw, it.name, it.min, it.max, it.default,
			it.kind == ParameterKind.BLEND_SHAPE, it.repeat, it.keys?.let { keys -> Floats.of(keys.toFloatArray()) })
	}
	public fun toIr(
		model: PuppetModel,
		pages: List<TexturePage> = emptyList(),
		bindings: Map<String, Int> = emptyMap(),
		physics: Physics = Physics(),
		clips: List<Clip> = emptyList(),
		parameterRoles: List<ParameterRole> = emptyList(),
	): RigIR = RigIR(
		canvas = Canvas(model.canvasWidth, model.canvasHeight, model.worldOriginX, model.worldOriginY, model.pixelsPerUnit),
		parameters = parametersToIr(model),
		parameterLinks = model.parameterLinks.map { IrParameterLink(it.horizontal.raw, it.vertical.raw) },
		parameterTree = model.parameterTree.map(::node),
		parameterRoles = parameterRoles,
		parts = model.parts.map { part -> IrPart(part.id.raw, part.name, part.children.map(::child), part.isVisible, part.isSketch,
			part.isSelectable, groupMode(part.groupMode), part.drawOrder, channels(part.channelGrids), composite(part.composite),
			part.blendShapes.map { binding(it) { f -> PartShape(f.drawOrder, f.opacity, rgb(f.multiplyColor), rgb(f.screenColor)) } }) },
		rootChildren = model.rootChildren.map(::child),
		rootPart = model.rootPartId?.raw,
		deformers = model.deformers.map(::deformer),
		meshes = model.drawables.map(::meshToIr),
		glues = model.glues.map { glue -> IrGlue(glue.id, glue.meshA.raw, glue.meshB.raw,
			glue.pairs.map { IrGluePair(it.indexA, it.indexB, it.weightA, it.weightB) }, channels(glue.channelGrids), glue.intensity) },
		renderRoot = renderGroup(model.renderRoot),
		textures = Textures(
			pages = pages,
			bindings = bindings,
			tilePages = model.atlas.pages.map { PageSize(it.width, it.height) },
			tiles = model.atlas.tiles.map { tile -> TextureTile(tile.id.raw, tile.name, tile.width, tile.height,
				tile.placement?.let { TilePlacement(it.pageIndex, it.positionX, it.positionY, it.scaleX, it.scaleY, it.rotationDegrees) },
				tile.source?.let { SourceRef(it.sourceId.raw, it.layerKey, it.stableKey) }, tile.pinned, tile.replaces?.raw) },
			uvsAddressPages = model.atlas.storedUvsAddressPages,
			alphaThreshold = model.atlas.composition.alphaThreshold,
			extrude = model.atlas.composition.extrude,
		),
		physics = physics,
		clips = clips,
		authoring = Authoring(
			runtime = model.runtimeTarget.name,
			rendersFromSourceLayers = model.rendersFromSourceLayers,
			sources = model.sources.map { source -> SourceFile(source.id.raw, source.name, source.path, source.format,
				source.layers.map { SourceLayer(it.key, it.name, it.groupPath, it.left, it.top, it.width, it.height, it.visible,
					it.present, it.contentHash, it.empty, it.replaced, it.ignored) }, source.contentHash, source.lastModified) },
			paths = model.deformPaths.map { path -> IrDeformPath(path.id, path.drawableId.raw,
				path.points.map { PathPoint(it.a, it.b, it.c, it.wa, it.wb, it.wc, it.corner) }, path.width, path.hardness, path.closed, path.editLevel) },
			vertexGroups = model.vertexGroups.map { IrVertexGroup(it.name, it.drawableId.raw, it.kind.jsonName, Floats.wrap(it.weights)) },
		),
	)

	public fun toPuppet(ir: RigIR): PuppetModel = PuppetModel(
		parameters = ir.parameters.map { Parameter(ParameterId(it.id), it.name, it.min, it.max, it.default,
			if (it.blend) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL, it.repeat, it.keys?.shared()?.toList()) },
		parts = ir.parts.map { part -> Part(PartId(part.id), part.name, part.children.map(::orgChild), part.visible, part.sketch,
			part.selectable, partGroupMode(part.groupMode), part.drawOrder, channelGrids(part.channels), partComposite(part.composite),
			part.shapes.map { binding(it) { s -> PartForm(s.drawOrder, s.opacity, color(s.multiply), color(s.screen)) } }) },
		deformers = ir.deformers.map(::engineDeformer),
		drawables = ir.meshes.map(::drawable),
		rootChildren = ir.rootChildren.map(::orgChild),
		rootPartId = ir.rootPart?.let(::PartId),
		glues = ir.glues.map { glue -> Glue(DrawableId(glue.meshA), DrawableId(glue.meshB),
			glue.pairs.map { GluePair(it.a, it.b, it.weightA, it.weightB) }, channelGrids(glue.channels), glue.intensity, glue.id) },
		renderRoot = engineRenderGroup(ir.renderRoot),
		parameterLinks = ir.parameterLinks.map { ParameterLink(ParameterId(it.horizontal), ParameterId(it.vertical)) },
		parameterTree = ir.parameterTree.map(::engineNode),
		canvasWidth = ir.canvas.width,
		canvasHeight = ir.canvas.height,
		worldOriginX = ir.canvas.originX,
		worldOriginY = ir.canvas.originY,
		pixelsPerUnit = ir.canvas.pixelsPerUnit,
		runtimeTarget = RuntimeTarget.valueOf(ir.authoring.runtime),
		rendersFromSourceLayers = ir.authoring.rendersFromSourceLayers,
		atlas = PuppetAtlas(
			pages = ir.textures.tilePages.map { AtlasPage(it.width, it.height) },
			tiles = ir.textures.tiles.map { tile -> AtlasTile(AtlasTileId(tile.id), tile.name, tile.width, tile.height,
				tile.placement?.let { AtlasPlacement(it.page, it.x, it.y, it.scaleX, it.scaleY, it.rotation) },
				tile.source?.let { SourceLayerRef(ArtSourceId(it.source), it.layer, it.stableKey) }, tile.pinned, tile.replaces?.let(::AtlasTileId)) },
			storedUvsAddressPages = ir.textures.uvsAddressPages,
			composition = AtlasComposition(ir.textures.alphaThreshold, ir.textures.extrude),
		),
		sources = ir.authoring.sources.map { source -> ArtSource(ArtSourceId(source.id), source.name, source.path, source.format,
			source.layers.map { ArtSourceLayer(it.key, it.name, it.groupPath, it.left, it.top, it.width, it.height, it.visible,
				it.present, it.hash, it.empty, it.replaced, it.ignored) }, source.hash, source.lastModified) },
		deformPaths = ir.authoring.paths.map { path -> DeformPath(path.id, DrawableId(path.mesh),
			path.points.map { DeformPathPoint(it.a, it.b, it.c, it.wa, it.wb, it.wc, it.corner) }, path.width, path.hardness, path.closed, path.editLevel) },
		vertexGroups = ir.authoring.vertexGroups.map { VertexGroup(it.name, DrawableId(it.mesh), VertexGroupKind.parse(it.kind), it.weights.shared()) },
	)

	// --- engine -> IR ---

	private fun rgb(color: ColorRgb) = Rgb(color.red, color.green, color.blue)
	private fun floats(values: FloatArray) = Floats.wrap(values)

	private fun node(node: ParameterNode): IrParameterNode = when (node) {
		is ParameterNode.Param -> IrParameterNode.Param(node.id.raw)
		is ParameterNode.Group -> IrParameterNode.Group(node.id.raw, node.name, node.initiallyOpen, node.children.map(::node),
			when (val label = node.labelColor) {
				ParameterLabelColor.None -> GroupLabel.None
				is ParameterLabelColor.Preset -> GroupLabel.Preset(label.kind.name)
				is ParameterLabelColor.Custom -> GroupLabel.Custom(label.argb)
			})
	}

	private fun child(child: OrgChild): ChildRef = when (child) {
		is OrgChild.Part -> ChildRef.PartRef(child.id.raw)
		is OrgChild.Drawable -> ChildRef.MeshRef(child.id.raw)
	}

	private fun groupMode(mode: PartGroupMode) = when (mode) {
		PartGroupMode.PassThrough -> GroupMode.PASS_THROUGH
		PartGroupMode.Grouped -> GroupMode.GROUPED
		PartGroupMode.Isolated -> GroupMode.ISOLATED
	}

	private fun composite(composite: PartComposite) = Composite(colorBlend(composite.blendMode), alphaBlend(composite.alphaBlendMode),
		composite.maskedBy.map { it.raw }, composite.maskedByParts.map { it.raw }, composite.invertMask, composite.opacity,
		rgb(composite.multiplyColor), rgb(composite.screenColor))

	private val colorBlends = mapOf(
		BlendMode.Normal to ColorBlend.NORMAL, BlendMode.AdditivePremultiplied to ColorBlend.ADD_PREMULTIPLIED,
		BlendMode.MultiplyPremultiplied to ColorBlend.MULTIPLY_PREMULTIPLIED, BlendMode.Additive to ColorBlend.ADD,
		BlendMode.AdditiveGlow to ColorBlend.ADD_GLOW, BlendMode.Darken to ColorBlend.DARKEN, BlendMode.Multiply to ColorBlend.MULTIPLY,
		BlendMode.ColorBurn to ColorBlend.COLOR_BURN, BlendMode.LinearBurn to ColorBlend.LINEAR_BURN, BlendMode.Lighten to ColorBlend.LIGHTEN,
		BlendMode.Screen to ColorBlend.SCREEN, BlendMode.ColorDodge to ColorBlend.COLOR_DODGE, BlendMode.Overlay to ColorBlend.OVERLAY,
		BlendMode.SoftLight to ColorBlend.SOFT_LIGHT, BlendMode.HardLight to ColorBlend.HARD_LIGHT, BlendMode.LinearLight to ColorBlend.LINEAR_LIGHT,
		BlendMode.Hue to ColorBlend.HUE, BlendMode.Color to ColorBlend.COLOR,
	)
	private val blendModes = colorBlends.entries.associate { (engine, ir) -> ir to engine }
	private val alphaBlends = mapOf(
		AlphaBlendMode.Over to AlphaBlend.OVER, AlphaBlendMode.Atop to AlphaBlend.ATOP, AlphaBlendMode.Out to AlphaBlend.OUT,
		AlphaBlendMode.Conjoint to AlphaBlend.CONJOINT, AlphaBlendMode.Disjoint to AlphaBlend.DISJOINT,
	)
	private val alphaBlendModes = alphaBlends.entries.associate { (engine, ir) -> ir to engine }
	init {
		check(colorBlends.size == BlendMode.entries.size && blendModes.size == ColorBlend.entries.size) { "Blend mode tables are incomplete" }
		check(alphaBlends.size == AlphaBlendMode.entries.size && alphaBlendModes.size == AlphaBlend.entries.size) { "Alpha blend tables are incomplete" }
	}

	private fun colorBlend(mode: BlendMode) = colorBlends.getValue(mode)
	private fun alphaBlend(mode: AlphaBlendMode) = alphaBlends.getValue(mode)

	private fun <F, G> grid(grid: KeyformGrid<F>, form: (F) -> G): KeyGrid<G> = KeyGrid(
		grid.axes.map { KeyAxis(it.parameterId.raw, floats(it.keys)) },
		grid.cells.map { KeyCell(Ints.wrap(it.coordinate), form(it.form)) })

	private fun channels(grids: ChannelGrids): Channels = grids.gridsByChannel.entries.associate { (channel, grid) ->
		Channel.valueOf(channel.name) to grid(grid) { value ->
			when (value) {
				is ChannelValue.Scalar -> IrChannelValue.Scalar(value.value)
				is ChannelValue.Color -> IrChannelValue.Color(rgb(value.color))
				is ChannelValue.Flag -> IrChannelValue.Flag(value.flag)
			}
		}
	}

	private fun <F : Any, S> binding(binding: BlendShapeBinding<F>, shape: (F) -> S) = BlendBinding(
		binding.parameterId.raw, floats(binding.keys), binding.neutralIndex, binding.forms.map { it?.let(shape) },
		binding.limits.map { limit -> BlendLimit(limit.parameterId.raw, limit.points.map { BlendLimitPoint(it.value, it.weight) }) })

	/** One engine deformer as the IR holds it. */
	public fun deformerToIr(deformer: Deformer): IrDeformer = deformer(deformer)

	private fun deformer(deformer: Deformer): IrDeformer = when (deformer) {
		is Deformer.Warp -> IrDeformer.Warp(deformer.id.raw, deformer.name, deformer.parent?.raw, deformer.partId?.raw,
			deformer.rows, deformer.columns, deformer.isQuadTransform,
			deformer.geometryGrid?.let { grid(it) { form -> LatticePoints(floats(form.controlPoints)) } },
			channels(deformer.channelGrids), deformer.opacity, rgb(deformer.multiplyColor), rgb(deformer.screenColor),
			deformer.isSelectable, deformer.isVisible, deformer.isEnabled,
			deformer.blendShapes.map { binding(it) { f -> LatticeShape(floats(f.controlPoints), f.opacity, rgb(f.multiplyColor), rgb(f.screenColor)) } })
		is Deformer.Rotation -> IrDeformer.Rotation(deformer.id.raw, deformer.name, deformer.parent?.raw, deformer.partId?.raw,
			deformer.baseAngle, deformer.geometryGrid?.let { grid(it) { f -> Pivot(f.originX, f.originY, f.angle, f.scale) } },
			channels(deformer.channelGrids), deformer.opacity, rgb(deformer.multiplyColor), rgb(deformer.screenColor),
			deformer.flipX, deformer.flipY, deformer.isSelectable, deformer.isVisible, deformer.isEnabled,
			deformer.blendShapes.map { binding(it) { f -> PivotShape(f.originX, f.originY, f.angle, f.scale, f.flipX, f.flipY,
				f.opacity, rgb(f.multiplyColor), rgb(f.screenColor)) } },
			deformer.handleLength)
	}

	fun meshToIr(drawable: Drawable) = Mesh(
		id = drawable.id.raw, name = drawable.name, parent = drawable.parentDeformerId?.raw,
		blend = colorBlend(drawable.blendMode), alphaBlend = alphaBlend(drawable.alphaBlendMode),
		maskedBy = drawable.maskedBy.map { it.raw }, invertMask = drawable.invertMask,
		geometry = drawable.mesh?.let { MeshGeometry(floats(it.positions), floats(it.uvs), Ints.wrap(it.indices)) },
		offsets = drawable.geometryGrid?.let { grid(it) { form -> MeshOffsets(floats(form.positionDeltas)) } },
		channels = channels(drawable.channelGrids), drawOrder = drawable.drawOrder, opacity = drawable.opacity,
		multiply = rgb(drawable.multiplyColor), screen = rgb(drawable.screenColor),
		culling = drawable.culling, visible = drawable.isVisible, selectable = drawable.isSelectable,
		textureSource = drawable.textureSourceId?.raw, page = drawable.texturePage, tile = drawable.atlasTileId?.raw,
		shapes = drawable.blendShapes.map { binding(it) { f -> MeshShape(floats(f.positionDeltas), f.drawOrder, f.opacity,
			rgb(f.multiplyColor), rgb(f.screenColor)) } },
		userData = drawable.userData,
	)

	private fun renderGroup(group: RenderGroup): IrRenderGroup = IrRenderGroup(group.partId?.raw, group.drawOrder,
		group.children.map { child -> when (child) {
			is RenderDrawable -> RenderMesh(child.id.raw)
			is RenderGroup -> renderGroup(child)
		} }, channels(group.channelGrids), group.composite?.let(::composite))

	// --- IR -> engine ---

	private fun color(rgb: Rgb) = ColorRgb(rgb.red, rgb.green, rgb.blue)

	private fun engineNode(node: IrParameterNode): ParameterNode = when (node) {
		is IrParameterNode.Param -> ParameterNode.Param(ParameterId(node.id))
		is IrParameterNode.Group -> ParameterNode.Group(ParameterGroupId(node.id), node.name, node.open, node.children.map(::engineNode),
			when (val label = node.label) {
				GroupLabel.None -> ParameterLabelColor.None
				is GroupLabel.Preset -> ParameterLabelColor.Preset(ParameterLabelColor.Preset.Kind.valueOf(label.name))
				is GroupLabel.Custom -> ParameterLabelColor.Custom(label.argb)
			})
	}

	private fun orgChild(child: ChildRef): OrgChild = when (child) {
		is ChildRef.PartRef -> OrgChild.Part(PartId(child.id))
		is ChildRef.MeshRef -> OrgChild.Drawable(DrawableId(child.id))
	}

	private fun partGroupMode(mode: GroupMode) = when (mode) {
		GroupMode.PASS_THROUGH -> PartGroupMode.PassThrough
		GroupMode.GROUPED -> PartGroupMode.Grouped
		GroupMode.ISOLATED -> PartGroupMode.Isolated
	}

	private fun partComposite(composite: Composite) = PartComposite(blendMode(composite.blend), alphaBlendMode(composite.alphaBlend),
		composite.maskedBy.map(::DrawableId), composite.maskedByParts.map(::PartId), composite.invertMask, composite.opacity,
		color(composite.multiply), color(composite.screen))

	private fun blendMode(mode: ColorBlend) = blendModes.getValue(mode)
	private fun alphaBlendMode(mode: AlphaBlend) = alphaBlendModes.getValue(mode)

	private fun <G, F> engineGrid(grid: KeyGrid<G>, form: (G) -> F): KeyformGrid<F> = KeyformGrid(
		grid.axes.map { KeyformAxis(ParameterId(it.parameter), it.keys.shared()) },
		grid.cells.map { KeyformCell(it.coordinate.shared(), form(it.form)) })

	private fun channelGrids(channels: Channels): ChannelGrids {
		if (channels.isEmpty()) return ChannelGrids.Empty
		return ChannelGrids(channels.entries.associate { (channel, grid) ->
			FormChannel.valueOf(channel.name) to engineGrid(grid) { value ->
				when (value) {
					is IrChannelValue.Scalar -> ChannelValue.Scalar(value.value)
					is IrChannelValue.Color -> ChannelValue.Color(color(value.color))
					is IrChannelValue.Flag -> ChannelValue.Flag(value.value)
				}
			}
		})
	}

	private fun <S, F : Any> binding(binding: BlendBinding<S>, form: (S & Any) -> F) = BlendShapeBinding(
		ParameterId(binding.parameter), binding.keys.shared(), binding.neutralIndex, binding.shapes.map { it?.let(form) },
		binding.limits.map { limit -> BlendWeightLimit(ParameterId(limit.parameter), limit.points.map { BlendWeightLimitPoint(it.value, it.weight) }) })

	private fun engineDeformer(deformer: IrDeformer): Deformer = when (deformer) {
		is IrDeformer.Warp -> Deformer.Warp(DeformerId(deformer.id), deformer.name, deformer.parent?.let(::DeformerId),
			deformer.part?.let(::PartId), deformer.rows, deformer.columns, deformer.bilinear,
			deformer.lattice?.let { engineGrid(it) { f -> WarpLatticeForm(f.points.shared()) } },
			channelGrids(deformer.channels), deformer.opacity, color(deformer.multiply), color(deformer.screen),
			deformer.selectable, deformer.visible, deformer.enabled,
			deformer.shapes.map { binding(it) { s -> WarpForm(s.points.shared(), s.opacity, color(s.multiply), color(s.screen)) } })
		is IrDeformer.Rotation -> Deformer.Rotation(DeformerId(deformer.id), deformer.name, deformer.parent?.let(::DeformerId),
			deformer.part?.let(::PartId), deformer.baseAngle,
			deformer.pivot?.let { engineGrid(it) { p -> RotationPivotForm(p.x, p.y, p.angle, p.scale) } },
			channelGrids(deformer.channels), deformer.opacity, color(deformer.multiply), color(deformer.screen),
			deformer.flipX, deformer.flipY, deformer.selectable, deformer.visible, deformer.enabled,
			deformer.shapes.map { binding(it) { s -> RotationForm(s.x, s.y, s.angle, s.scale, s.flipX, s.flipY,
				s.opacity, color(s.multiply), color(s.screen)) } },
			deformer.handleLength)
	}

	private fun drawable(mesh: Mesh) = Drawable(
		id = DrawableId(mesh.id), name = mesh.name, parentDeformerId = mesh.parent?.let(::DeformerId),
		blendMode = blendMode(mesh.blend), maskedBy = mesh.maskedBy.map(::DrawableId),
		mesh = mesh.geometry?.let { DrawableMesh(it.positions.shared(), it.uvs.shared(), it.indices.shared()) },
		geometryGrid = mesh.offsets?.let { engineGrid(it) { f -> MeshDeltaForm(f.deltas.shared()) } },
		channelGrids = channelGrids(mesh.channels), drawOrder = mesh.drawOrder, opacity = mesh.opacity,
		multiplyColor = color(mesh.multiply), screenColor = color(mesh.screen), invertMask = mesh.invertMask,
		alphaBlendMode = alphaBlendMode(mesh.alphaBlend), culling = mesh.culling, isVisible = mesh.visible,
		isSelectable = mesh.selectable, textureSourceId = mesh.textureSource?.let(::DrawableId), texturePage = mesh.page,
		atlasTileId = mesh.tile?.let(::AtlasTileId),
		blendShapes = mesh.shapes.map { binding(it) { s -> MeshForm(s.deltas.shared(), s.drawOrder, s.opacity, color(s.multiply), color(s.screen)) } },
		userData = mesh.userData,
	)

	private fun engineRenderGroup(group: IrRenderGroup): RenderGroup = RenderGroup(group.part?.let(::PartId), group.drawOrder,
		group.children.map { child -> when (child) {
			is RenderMesh -> RenderDrawable(DrawableId(child.id))
			is IrRenderGroup -> engineRenderGroup(child)
		} }, channelGrids(group.channels), group.composite?.let(::partComposite))
}
