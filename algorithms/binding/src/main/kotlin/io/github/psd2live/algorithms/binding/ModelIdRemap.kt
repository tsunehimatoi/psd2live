package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/**
 * Every reference a [PuppetModel] holds to its own objects, rewritten through ID maps: keyform axes, blend shapes and
 * their weight limits, parents, parts and the organizational tree, masks, glues, paths, vertex groups, the render tree,
 * parameter links and the parameter panel. An ID no map names stays. [model] rewrites a whole model;
 * the per-object functions rewrite copies that are not in it yet for later insertion.
 */
class ModelIdRemap(
	val parameters: Map<ParameterId, ParameterId> = emptyMap(),
	val groups: Map<ParameterGroupId, ParameterGroupId> = emptyMap(),
	val drawables: Map<DrawableId, DrawableId> = emptyMap(),
	val deformers: Map<DeformerId, DeformerId> = emptyMap(),
	val parts: Map<PartId, PartId> = emptyMap(),
) {
	val isEmpty: Boolean get() = parameters.isEmpty() && groups.isEmpty() && drawables.isEmpty() && deformers.isEmpty() && parts.isEmpty()

	fun parameter(id: ParameterId) = parameters[id] ?: id
	fun drawable(id: DrawableId) = drawables[id] ?: id
	fun deformer(id: DeformerId) = deformers[id] ?: id
	fun part(id: PartId) = parts[id] ?: id

	fun <T> grid(value: KeyformGrid<T>?): KeyformGrid<T>? {
		if (value == null || parameters.isEmpty() || value.axes.none { it.parameterId in parameters }) return value
		return KeyformGrid(value.axes.map { KeyformAxis(parameter(it.parameterId), it.keys) }, value.cells)
	}

	fun channels(value: ChannelGrids): ChannelGrids =
		if (value.isEmpty || parameters.isEmpty() || value.gridsByChannel.values.none { channel -> channel.axes.any { it.parameterId in parameters } }) value
		else ChannelGrids(value.gridsByChannel.mapValues { requireNotNull(grid(it.value)) })

	fun <T : Any> blendShapes(value: List<BlendShapeBinding<T>>): List<BlendShapeBinding<T>> =
		if (parameters.isEmpty() || value.none { it.parameterId in parameters || it.limits.any { limit -> limit.parameterId in parameters } }) value else value.map { binding ->
			binding.copy(parameterId = parameter(binding.parameterId), limits = binding.limits.map { it.copy(parameterId = parameter(it.parameterId)) })
		}

	fun drawable(value: Drawable): Drawable {
		val geometry = grid(value.geometryGrid)
		val tracks = channels(value.channelGrids)
		val shapes = blendShapes(value.blendShapes)
		if (drawable(value.id) == value.id && value.parentDeformerId?.let(::deformer) == value.parentDeformerId &&
			value.maskedBy.none { drawable(it) != it } && value.textureSourceId?.let(::drawable) == value.textureSourceId &&
			geometry === value.geometryGrid && tracks === value.channelGrids && shapes === value.blendShapes) return value
		return value.copy(id = drawable(value.id), parentDeformerId = value.parentDeformerId?.let(::deformer),
			maskedBy = if (drawables.isEmpty()) value.maskedBy else value.maskedBy.map(::drawable), textureSourceId = value.textureSourceId?.let(::drawable),
			geometryGrid = geometry, channelGrids = tracks, blendShapes = shapes)
	}

	fun deformer(value: Deformer): Deformer = when (value) {
		is Deformer.Warp -> value.copy(id = deformer(value.id), parent = value.parent?.let(::deformer), partId = value.partId?.let(::part),
			geometryGrid = grid(value.geometryGrid), channelGrids = channels(value.channelGrids), blendShapes = blendShapes(value.blendShapes))
		is Deformer.Rotation -> value.copy(id = deformer(value.id), parent = value.parent?.let(::deformer), partId = value.partId?.let(::part),
			geometryGrid = grid(value.geometryGrid), channelGrids = channels(value.channelGrids), blendShapes = blendShapes(value.blendShapes))
	}

	fun child(value: OrgChild): OrgChild = when (value) {
		is OrgChild.Part -> OrgChild.Part(part(value.id))
		is OrgChild.Drawable -> OrgChild.Drawable(drawable(value.id))
	}

	private fun composite(value: PartComposite) = value.copy(maskedBy = value.maskedBy.map(::drawable), maskedByParts = value.maskedByParts.map(::part))

	fun part(value: Part): Part = value.copy(id = part(value.id), children = value.children.map(::child),
		channelGrids = channels(value.channelGrids), composite = composite(value.composite), blendShapes = blendShapes(value.blendShapes))

	fun glue(value: Glue): Glue = value.copy(meshA = drawable(value.meshA), meshB = drawable(value.meshB), channelGrids = channels(value.channelGrids))

	private fun render(value: RenderNode): RenderNode = when (value) {
		is RenderDrawable -> RenderDrawable(drawable(value.id))
		is RenderGroup -> render(value)
	}

	private fun render(value: RenderGroup): RenderGroup = value.copy(partId = value.partId?.let(::part), children = value.children.map(::render),
		channelGrids = channels(value.channelGrids), composite = value.composite?.let(::composite))

	fun node(value: ParameterNode): ParameterNode = when (value) {
		is ParameterNode.Param -> ParameterNode.Param(parameter(value.id))
		is ParameterNode.Group -> value.copy(id = groups[value.id] ?: value.id, children = value.children.map(::node))
	}

	fun model(value: PuppetModel): PuppetModel = if (isEmpty) value else value.copy(
		parameters = if (parameters.isEmpty()) value.parameters else value.parameters.map { if (parameter(it.id) == it.id) it else it.copy(id = parameter(it.id)) },
		parts = if (parts.isEmpty() && drawables.isEmpty() && parameters.isEmpty()) value.parts else value.parts.map(::part),
		deformers = if (deformers.isEmpty() && parts.isEmpty() && parameters.isEmpty()) value.deformers else value.deformers.map(::deformer),
		drawables = if (drawables.isEmpty() && deformers.isEmpty() && parameters.isEmpty()) value.drawables else value.drawables.map(::drawable),
		rootChildren = if (parts.isEmpty() && drawables.isEmpty()) value.rootChildren else value.rootChildren.map(::child),
		rootPartId = value.rootPartId?.let(::part),
		glues = if (drawables.isEmpty() && parameters.isEmpty()) value.glues else value.glues.map(::glue),
		renderRoot = if (parts.isEmpty() && drawables.isEmpty() && parameters.isEmpty()) value.renderRoot else render(value.renderRoot),
		parameterLinks = if (parameters.isEmpty()) value.parameterLinks else value.parameterLinks.map { it.copy(horizontal = parameter(it.horizontal), vertical = parameter(it.vertical)) },
		parameterTree = if (parameters.isEmpty() && groups.isEmpty()) value.parameterTree else value.parameterTree.map(::node),
		deformPaths = if (drawables.isEmpty()) value.deformPaths else value.deformPaths.map { it.copy(drawableId = drawable(it.drawableId)) },
		vertexGroups = if (drawables.isEmpty()) value.vertexGroups else value.vertexGroups.map { it.copy(drawableId = drawable(it.drawableId)) },
	)
}
