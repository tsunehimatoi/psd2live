package io.github.psd2live.core

import org.umamo.format.art.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.*
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.png.PngCodec
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.interop.cmo3.Cmo3AtlasIngest
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.runtime.model.*
import org.umamo.render.restMeshesToCanvasSpace
import java.util.Base64
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import kotlin.math.ceil
import kotlin.math.floor
import io.github.psd2live.core.legacy.RigMeshActivation
import io.github.psd2live.core.legacy.RigGenerationTextures

enum class Cmo3ImportMode { REPLACE, NEW }

/** The embedded baseline is shared by history, project persistence, preview rebuilds and export. */
internal object Cmo3ModelImport {
    /**
     * A read model. [densityByDrawableId] is the page texels per canvas unit (x, y) each drawable's art holds,
     * from its model-image affine and its packing; drawables without one, or at most one texel per unit, are absent.
     */
    data class Document(val puppet: PuppetModel, val atlas: PackedAtlas, val pages: Map<String, Int>,
                        val physics: List<RigPhysicsEdit>, val fps: Int?,
                        val densityByDrawableId: Map<String, Pair<Float, Float>> = emptyMap())
    private var cached: Pair<String, Document>? = null

    @Synchronized
    fun decode(encoded: String): Document {
        cached?.takeIf { it.first == encoded }?.let { return it.second }
        return read(Base64.getDecoder().decode(encoded)).also { cached = encoded to it }
    }

    fun read(bytes: ByteArray): Document {
        val model = Cmo3.read(bytes)
        val root = model.root as? CModelSource ?: error("Invalid CMO3 model root")
        val puppet = Cmo3Import.fromModelSource(root)
        val textures = cmo3AtlasPages(root, model::extractLayerPng)
        val pages = textures.pageBytes.map { png ->
            val raster = PngCodec.read(png)
            AtlasPage(PreviewRenderer.rasterImage(raster.width, raster.height, raster.rgba), png)
        }
        val set = root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet
        val parameters = Cmo3Import.elementsOf((root.parameterSourceSet as? CParameterSourceSet)?._sources)
            .filterIsInstance<CParameterSource>().associate { Cmo3Import.uuidOf(it.guid) to Cmo3Import.idStrOf(it.id) }
        fun parameter(guid: Any?): String = parameters[Cmo3Import.uuidOf(guid)] ?: error("CMO3 physics references an unknown parameter")
        fun type(value: Any?) = if (value == CPhysicsSourceType.SRC_TO_X) PhysicsSourceType.X else PhysicsSourceType.ANGLE
        val physics = Cmo3Import.elementsOf(set?._sourceCubismPhysics).filterIsInstance<CPhysicsSettingsSource>().map { s ->
            RigPhysicsEdit(
                Cmo3Import.idStrOf(s.id) ?: error("CMO3 physics ID missing"), s.name.orEmpty().ifBlank { Cmo3Import.idStrOf(s.id)!! },
                Cmo3Import.elementsOf(s.inputs).filterIsInstance<CPhysicsInput>().map { PhysicsInput(parameter(it.source), it.weight, type(it.type), it.isReverse) },
                Cmo3Import.elementsOf(s.outputs).filterIsInstance<CPhysicsOutput>().map {
                    PhysicsOutput(parameter(it.destination), it.vertexIndex,
                        if (type(it.type) == PhysicsSourceType.X) (it.translationScale as? GVector2)?.x ?: 0f else it.angleScale,
                        it.weight, type(it.type), it.isReverse)
                },
                Cmo3Import.elementsOf(s.vertices).filterIsInstance<CPhysicsVertex>().drop(1).map {
                    PhysicsSegment(it.radius, it.mobility, it.delay, it.acceleration)
                },
                PhysicsNormalization(s.normalizedPositionValueMin, s.normalizedPositionDefaultValue, s.normalizedPositionValueMax,
                    s.normalizedAngleValueMin, s.normalizedAngleDefaultValue, s.normalizedAngleValueMax),
            )
        }
        return Document(puppet, PackedAtlas(pages, emptyMap()), textures.atlasIndexByDrawableId, physics, set?.settingFPS,
            textureDensities(cmo3AtlasIngest(root)))
    }

    /**
     * Page texels per canvas unit of each drawable's art: the packing scale (page texels per art pixel) over the
     * model-image scale (canvas units per art pixel). Art authored at canvas resolution and packed at 1 - every
     * file written before layers kept their resolution - is 1 and left out, as is art packed below it, so such
     * files import exactly as before; only denser art is listed.
     */
    private fun textureDensities(ingest: Cmo3AtlasIngest): Map<String, Pair<Float, Float>> {
        val tiles = ingest.atlas.tiles.associateBy { it.id }
        return ingest.tileIdByDrawableId.mapNotNull { (drawableId, tileId) ->
            val units = ingest.canvasUnitsPerTilePixel(tileId) ?: return@mapNotNull null
            val placement = tiles[tileId]?.placement
            if (placement != null && placement.rotationDegrees != 0f) return@mapNotNull null
            val x = (placement?.scaleX ?: 1f) / units.first; val y = (placement?.scaleY ?: 1f) / units.second
            if (!x.isFinite() || !y.isFinite() || (x <= 1f + DENSITY_EPSILON && y <= 1f + DENSITY_EPSILON)) return@mapNotNull null
            drawableId to (x.coerceAtLeast(1f) to y.coerceAtLeast(1f))
        }.toMap()
    }

    private const val DENSITY_EPSILON = 1e-3f

    fun prepare(bytes: ByteArray, mode: Cmo3ImportMode, current: RigPreviewModel?, config: PipelineConfig): Pair<SourceArt, PipelineConfig> {
        val incoming = read(bytes)
        val replace = mode == Cmo3ImportMode.REPLACE && current != null
        val layerIds = if (replace) current!!.rig.layerIdByDrawableId.toMutableMap() else mutableMapOf()
        val used = layerIds.values.toMutableSet()
        for (d in incoming.puppet.drawables) if (d.id.raw !in layerIds) {
            var id = d.id.raw
            while (id in used) id += "_cmo3"
            layerIds[d.id.raw] = id
            used += id
        }
        val document = if (replace) {
            val old = current!!
            val offset = old.atlas.pages.size
            val model = merge(restMeshesToCanvasSpace(old.rig.puppet), incoming.puppet)
            val pages = old.atlas.pages + incoming.atlas.pages
            val incomingIds = incoming.puppet.drawables.mapTo(HashSet()) { it.id.raw }
            val bindings = (old.rig.pageByDrawableId - incomingIds) + incoming.pages.mapValues { it.value + offset }
            val converted = Cmo3Conversion.freshCmo3(model.copy(atlas = PuppetAtlas.Empty, sources = emptyList(),
                drawables = model.drawables.map { it.copy(atlasTileId = null, texturePage = bindings[it.id.raw] ?: -1) }),
                pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) }, bindings,
                "Imported model", System.currentTimeMillis(), 0)
            Cmo3.write(converted.model)
        } else bytes
        val baseline = Base64.getEncoder().encodeToString(document)
        val decoded = decode(baseline)
        val importedLayers = layers(incoming, layerIds)
        val layers = if (replace) mergeById(current!!.analysis.source.layers, importedLayers) { it.id } else importedLayers
        val source = object : SourceArt {
            override val widthPx = decoded.puppet.canvasWidth.toInt().coerceAtLeast(1)
            override val heightPx = decoded.puppet.canvasHeight.toInt().coerceAtLeast(1)
            override val layers = layers
            override val groups = if (replace) current!!.analysis.source.groups else emptyList()
        }
        val oldPhysics = if (replace) PhysicsCatalog.groups(current!!.analysis, config,
            current.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }) else emptyList()
        val physics = mergeById(oldPhysics.map { it.setting }, incoming.physics) { it.id }
        val overlay = RigEditOverlay(
            importedCmo3 = baseline, importedLayerIds = layerIds,
            skeleton = if (replace) config.rigEdits.skeleton else SkeletonSpec.Disabled,
            physicsEdits = physics,
            disabledPhysicsIds = oldPhysics.filter { !it.enabled }.mapTo(HashSet()) { it.id } - incoming.physics.map { it.id }.toSet(),
            physicsOrder = physics.map { it.id },
            physicsFps = incoming.fps ?: if (incoming.physics.isNotEmpty()) RigEditOverlay.UNLIMITED_FPS
                else if (replace) config.rigEdits.physicsFps else RigEditOverlay.DEFAULT_PHYSICS_FPS,
            motionClips = if (replace) config.rigEdits.motionClips else emptyList(),
            motionPresets = if (replace) config.rigEdits.motionPresets else emptyMap(),
            swingEdits = if (replace) config.rigEdits.swingEdits else emptyList(),
            simEdits = if (replace) config.rigEdits.simEdits else emptyList(),
            authoringJournal = if (replace) current!!.rig.puppet.vertexGroups.filter { g ->
                incoming.puppet.drawables.none { it.id == g.drawableId }
            }.map(VertexGroupJournal::encode) else emptyList(),
        )
        return source to config.copy(rigEdits = overlay, runtimeTarget = decoded.puppet.runtimeTarget,
            mouthOutlineEnabled = false, generateDeformers = false, meshOnly = false,
            physicsFrontHair = false, physicsBackHair = false, physicsEyeJelly = false,
            generatePhysics = if (replace) config.generatePhysics else true,
            motionBasic = if (replace) config.motionBasic else false,
            motionIdle = if (replace) config.motionIdle else false, motionBlink = if (replace) config.motionBlink else false,
            motionNod = if (replace) config.motionNod else false, motionShake = if (replace) config.motionShake else false,
            motionSkeleton = if (replace) config.motionSkeleton else false,
            layerOverrides = if (replace) config.layerOverrides - importedLayers.map { it.id.raw }.toSet() else emptyMap(),
            layerVisibility = if (replace) config.layerVisibility - importedLayers.map { it.id.raw }.toSet() else emptyMap(),
            deletedLayerIds = if (replace) config.deletedLayerIds - importedLayers.map { it.id.raw }.toSet() else emptySet(),
            parentOverrides = emptyMap(), drawOrderOverrides = emptyMap(),
            meshOverrides = if (replace) config.meshOverrides - importedLayers.map { it.id.raw }.toSet() else emptyMap())
    }

    internal fun <T, K> mergeById(old: List<T>, incoming: List<T>, id: (T) -> K): List<T> {
        val replacements = incoming.associateBy(id)
        val oldIds = old.mapTo(HashSet(), id)
        return old.map { replacements[id(it)] ?: it } + incoming.filter { id(it) !in oldIds }
    }

    internal fun merge(old: PuppetModel, incoming: PuppetModel): PuppetModel {
        val incomingChildren = (incoming.rootChildren + incoming.parts.flatMap { it.children }).toSet()
        val incomingPartIds = incoming.parts.mapTo(HashSet()) { it.id }
        val oldParts = old.parts.map { p ->
            p.copy(children = p.children.filter { it !in incomingChildren })
        }
        // A replaced folder retains children absent from the import, while imported children move to their authored parent.
        val parts = mergeById(oldParts, incoming.parts.map { p ->
            p.copy(children = p.children + oldParts.firstOrNull { it.id == p.id }?.children.orEmpty())
        }) { it.id }
        val parameterIds = incoming.parameters.mapTo(HashSet()) { it.id }
        fun groups(tree: List<ParameterNode>): List<ParameterNode.Group> = tree.filterIsInstance<ParameterNode.Group>()
            .flatMap { listOf(it) + groups(it.children) }
        val oldGroups = groups(old.parameterTree).associateBy { it.id }
        val replacedGroups = groups(incoming.parameterTree).mapTo(HashSet()) { it.id }
        fun retainedNodes(tree: List<ParameterNode>): List<ParameterNode> = tree.mapNotNull { node -> when (node) {
            is ParameterNode.Param -> node.takeIf { it.id !in parameterIds }
            is ParameterNode.Group -> node.takeIf { it.id !in replacedGroups }?.copy(children = retainedNodes(node.children))
        } }
        fun importedNodes(tree: List<ParameterNode>): List<ParameterNode> = tree.map { node -> when (node) {
            is ParameterNode.Param -> node
            is ParameterNode.Group -> node.copy(children = importedNodes(node.children) + retainedNodes(oldGroups[node.id]?.children.orEmpty()))
        } }
        val oldTree = old.parameterTree.ifEmpty { old.parameters.map { ParameterNode.Param(it.id) } }
        val newTree = incoming.parameterTree.ifEmpty { incoming.parameters.map { ParameterNode.Param(it.id) } }
        return incoming.copy(
            parameters = mergeById(old.parameters, incoming.parameters) { it.id },
            parts = parts, deformers = mergeById(old.deformers, incoming.deformers) { it.id },
            drawables = mergeById(old.drawables, incoming.drawables) { it.id },
            rootChildren = incoming.rootChildren + old.rootChildren.filter { child ->
                child !in incomingChildren && !(child is OrgChild.Part && child.id in incomingPartIds)
            },
            glues = mergeById(old.glues, incoming.glues) { it.id?.let { id -> "id:$id" } ?: "pair:${it.meshA.raw}|${it.meshB.raw}" },
            parameterLinks = old.parameterLinks.filter { it.horizontal !in parameterIds && it.vertical !in parameterIds } + incoming.parameterLinks,
            parameterTree = importedNodes(newTree) + retainedNodes(oldTree),
            deformPaths = mergeById(old.deformPaths, incoming.deformPaths) { it.id },
            vertexGroups = old.vertexGroups,
        ).withDerivedRenderRoot()
    }

    fun analysis(source: SourceArt, config: PipelineConfig): PipelineAnalysis {
        val layers = source.layers.filter { it.id.raw !in config.deletedLayerIds }.map { CharacterAnalyzer.classify(it, config) }
        val box = Bounds(0f, 0f, source.widthPx.toFloat(), source.heightPx.toFloat())
        val anchors = if (layers.any { it.opaquePixels > 0 }) CharacterAnalyzer.anchorsFor(layers)
            else RigAnchors(box, box, box, box.centerX, box.centerY, box.centerX, box.bottom, box.centerY, box.bottom)
        return PipelineAnalysis(source, layers, anchors, source.warnings, PreviewRenderer.composite(source))
    }

    /** Imported atlas coordinates address image pages, whereas source partitions address canvas art.
     * Resolve through the immutable import triangle's UVs until that drawable has a canvas art tile. */
    fun textureCanvas(preview: RigPreviewModel, drawable: Drawable): FloatArray {
        val mesh = requireNotNull(drawable.mesh)
        if (preview.config.rigEdits.importedCmo3 == null ||
            preview.rig.puppet.atlas.tiles.single { it.id == drawable.atlasTileId }.source?.sourceId?.raw == PAINT_SOURCE_ID)
            return RasterMeshJournal.TextureCoordinates(preview.rig.puppet, drawable).toCanvas(mesh.uvs)
        val original = decode(requireNotNull(preview.config.rigEdits.importedCmo3)).puppet.drawables.single { it.id == drawable.id }.mesh!!
        if (mesh.uvs.contentEquals(original.uvs)) return original.positions.copyOf()
        return FloatArray(mesh.uvs.size).also { canvas ->
            for (vertex in 0 until mesh.vertexCount) {
                val bound = DeformPathTools.bind(original.uvs, original.indices, mesh.uvs[vertex * 2], mesh.uvs[vertex * 2 + 1])
                for (axis in 0..1) canvas[vertex * 2 + axis] = original.positions[bound.a * 2 + axis] * bound.wa +
                    original.positions[bound.b * 2 + axis] * bound.wb + original.positions[bound.c * 2 + axis] * bound.wc
            }
        }
    }

    const val PAINT_SOURCE_ID = "cmo3-painted-art"

    /** Canvas-space preparation is temporary; unrelated imported texture addresses stay durable. */
    fun paintingPreview(pipeline: PSD2LivePipeline, source: SourceArt, config: PipelineConfig): RigPreviewModel {
        val analysis = analysis(source, config)
        val (atlas, base) = baseRig(source, config, normalizeAllTextures = true)
        val rig = base.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
        val bundle = pipeline.buildRuntimeBundle("psd2live-preview", analysis, atlas, rig, config).first
        return RigPreviewModel(analysis, atlas, rig, config, bundle, PreviewRigSources.of(base))
    }

    fun baseRig(source: SourceArt, config: PipelineConfig, normalizeAllTextures: Boolean = false): Pair<PackedAtlas, BuiltRig> {
        val doc = decode(requireNotNull(config.rigEdits.importedCmo3))
        val ids = config.rigEdits.importedLayerIds
        val layers = source.layers.associateBy { it.id.raw }
        val puppet = doc.puppet.copy(runtimeTarget = config.runtimeTarget,
            drawables = doc.puppet.drawables.filter { ids[it.id.raw] !in config.deletedLayerIds }.map { d ->
                val visible = ids[d.id.raw]?.let { config.layerVisibility[it] ?: layers[it]?.visible }
                if (visible == null) d else d.copy(isVisible = visible)
            })
        val bounds = puppet.drawables.mapNotNull { d -> d.mesh?.positions?.takeIf { it.isNotEmpty() }?.let { d.id.raw to bounds(it) } }.toMap()
        val rig = BuiltRig(puppet, doc.pages, bounds, ids, source.widthPx / 2f, source.heightPx / 2f,
            source.widthPx / 2f, source.heightPx / 2f, emptyList())
        config.generationSource?.let { original ->
            val baseline = original.layers.associateBy { it.id.raw }
            val migrated = config.rigEdits.authoringJournal.mapNotNullTo(HashSet()) { record ->
                when (record["op"]?.jsonPrimitive?.content) {
                    RasterMeshJournal.OP -> record["id"]?.jsonPrimitive?.content?.let(ids::get)
                    RigMeshActivation.OP -> record["layer_id"]?.jsonPrimitive?.content
                    else -> null
                }
            }
            val frozenConfig = MeshGenerationBaseline.restore(config)
            val frozenLayers = MouthLipLayers.prepare(analysis(source, frozenConfig), frozenConfig).layers
            val live = MouthLipLayers.prepare(analysis(source, config), config)
            val inventory = live.copy(layers = (frozenLayers + RigGenerationTextures.layers(source, config) + live.layers)
                .associateBy { it.source.id.raw }.values.toList())
            val partitionCoverage = RigGenerationSource.partitionCoverage(config.rigEdits)
            val analysis = inventory.let { input -> input.copy(layers = input.layers.map { layer ->
                val coverage = baseline[layer.source.id.raw]?.bounds ?: partitionCoverage[layer.source.id.raw]
                val covered = coverage?.let { RigGenerationSource.padded(layer.source, it) } ?: layer.source
                layer.copy(source = covered, opaquePixels = layer.opaquePixels.coerceAtLeast(1))
            }.filter { layer ->
                val current = layers[layer.source.id.raw] ?: layer.source
                val old = baseline[current.id.raw]
                normalizeAllTextures || current.id.raw in migrated || old == null || current.bounds != old.bounds ||
                    current.raster.width != old.raster.width || current.raster.height != old.raster.height ||
                    !current.raster.rgba.contentEquals(old.raster.rgba)
            }) }
            if (analysis.layers.isEmpty()) return doc.atlas to rig
            val atlas = AtlasLayout.pack(analysis.layers, config)
            val packedLayers = analysis.layers.associateBy { it.source.id.raw }
            val offset = if (normalizeAllTextures) 0 else doc.atlas.pages.size
            val pages = doc.pages.toMutableMap()
            for ((drawableId, layerId) in ids) atlas.placementByLayerId[layerId]?.let { pages[drawableId] = it.page + offset }
            val drawables = puppet.drawables.map { drawable ->
                val id = ids[drawable.id.raw] ?: return@map drawable
                val layer = packedLayers[id] ?: return@map drawable
                val placement = atlas.placementByLayerId.getValue(id)
                val mesh = drawable.mesh ?: return@map drawable.copy(texturePage = placement.page + offset,
                    atlasTileId = PuppetSourceAtlas.tileIdFor(id, PAINT_SOURCE_ID))
                val page = atlas.pages[placement.page].image
                val uvs = LayerTexture.packed(layer, placement, page.width, page.height).toUvs(mesh.positions)
                pages[drawable.id.raw] = placement.page + offset
                drawable.copy(mesh = org.umamo.runtime.model.DrawableMesh(mesh.positions, uvs, mesh.indices),
                    texturePage = placement.page + offset, atlasTileId = PuppetSourceAtlas.tileIdFor(id, PAINT_SOURCE_ID))
            }
            val (paintAtlas, paintSources) = PuppetSourceAtlas.build(analysis, atlas, sourceIdRaw = PAINT_SOURCE_ID)
            val puppetAtlas = if (normalizeAllTextures) paintAtlas else puppet.atlas.copy(
                pages = puppet.atlas.pages + paintAtlas.pages,
                tiles = puppet.atlas.tiles.map { tile ->
                    if (puppet.atlas.storedUvsAddressPages) tile else tile.copy(placement = null)
                } + paintAtlas.tiles.map { tile ->
                    tile.copy(placement = tile.placement?.let { it.copy(pageIndex = it.pageIndex + puppet.atlas.pages.size) })
                }, storedUvsAddressPages = true)
            val sources = if (normalizeAllTextures) paintSources else puppet.sources + paintSources
            val packed = if (normalizeAllTextures) atlas else PackedAtlas(doc.atlas.pages + atlas.pages,
                atlas.placementByLayerId.mapValues { (_, placement) -> placement.copy(page = placement.page + offset) })
            return packed to rig.copy(puppet = puppet.copy(drawables = drawables, atlas = puppetAtlas, sources = sources), pageByDrawableId = pages)
        }
        return doc.atlas to rig
    }

    private fun bounds(points: FloatArray): Bounds {
        val xs = points.indices.filter { it % 2 == 0 }.map { points[it] }
        val ys = points.indices.filter { it % 2 == 1 }.map { points[it] }
        return Bounds(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun layers(doc: Document, ids: Map<String, String>): List<SourceLayer> = doc.puppet.drawables.mapIndexed { index, d ->
        val canvas = d.mesh?.positions?.takeIf { it.isNotEmpty() }
        val b = canvas?.let(::bounds) ?: Bounds(0f, 0f, 1f, 1f)
        val left = floor(b.left).toInt()
        val top = floor(b.top).toInt()
        val boundsWidth = (ceil(b.right).toInt() - left).coerceAtLeast(1)
        val boundsHeight = (ceil(b.bottom).toInt() - top).coerceAtLeast(1)
        // Art denser than the canvas keeps its texels: the layer covers the same bounds with a denser raster.
        val (width, height) = rasterSize(boundsWidth, boundsHeight, doc.densityByDrawableId[d.id.raw])
        val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val page = doc.pages[d.id.raw]?.let { doc.atlas.pages[it].image }
        val mesh = d.mesh
        if (page != null && mesh != null) {
            val g = raster.createGraphics()
            try {
                if (width != boundsWidth || height != boundsHeight)
                    g.scale(width / boundsWidth.toDouble(), height / boundsHeight.toDouble())
                // Reconstruct the rest artwork through the mesh, including rotated atlas materials.
                // Cropping the UV bounding box would also bring neighboring packed art into the layer.
                for (t in mesh.indices.indices step 3) {
                    val a = mesh.indices[t] * 2; val c = mesh.indices[t + 1] * 2; val e = mesh.indices[t + 2] * 2
                    val xy = requireNotNull(canvas); val uv = mesh.uvs
                    val destination = AffineTransform((xy[c] - xy[a]).toDouble(), (xy[c + 1] - xy[a + 1]).toDouble(),
                        (xy[e] - xy[a]).toDouble(), (xy[e + 1] - xy[a + 1]).toDouble(), (xy[a] - left).toDouble(), (xy[a + 1] - top).toDouble())
                    val texture = AffineTransform(((uv[c] - uv[a]) * page.width).toDouble(), ((uv[c + 1] - uv[a + 1]) * page.height).toDouble(),
                        ((uv[e] - uv[a]) * page.width).toDouble(), ((uv[e + 1] - uv[a + 1]) * page.height).toDouble(),
                        (uv[a] * page.width).toDouble(), (uv[a + 1] * page.height).toDouble())
                    if (kotlin.math.abs(texture.determinant) < 1e-10) continue
                    destination.concatenate(texture.createInverse())
                    g.clip = Path2D.Float().apply {
                        moveTo(xy[a] - left, xy[a + 1] - top); lineTo(xy[c] - left, xy[c + 1] - top)
                        lineTo(xy[e] - left, xy[e + 1] - top); closePath()
                    }
                    g.drawImage(page, destination, null)
                }
            } finally { g.dispose() }
        }
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val c = raster.getRGB(x, y); val i = (y * width + x) * 4
            rgba[i] = (c ushr 16).toByte(); rgba[i + 1] = (c ushr 8).toByte(); rgba[i + 2] = c.toByte(); rgba[i + 3] = (c ushr 24).toByte()
        }
        object : SourceLayer {
            override val id = LayerId(ids.getValue(d.id.raw))
            override val name = d.name
            override val groupPath = ""
            override val order = index
            override val visible = d.isVisible
            override val bounds = LayerBounds(left, top, boundsWidth, boundsHeight)
            override val opacity = d.opacity
            override val clipped = false
            override val blend = LayerBlend.Normal
            override val raster = LayerRaster(width, height, rgba)
        }
    }

    /**
     * The raster of a layer over [width] x [height] canvas units at [density] texels per unit, reduced
     * uniformly to stay within the layer size budget ([LayerSizeBudget]); the bounds themselves without one.
     */
    internal fun rasterSize(width: Int, height: Int, density: Pair<Float, Float>?): Pair<Int, Int> {
        if (density == null) return width to height
        var x = minOf(density.first, LayerSizeBudget.MAX_DENSITY); var y = minOf(density.second, LayerSizeBudget.MAX_DENSITY)
        val pixels = width.toDouble() * x * height * y
        if (pixels > LayerSizeBudget.MAX_PIXELS) {
            val shrink = kotlin.math.sqrt(LayerSizeBudget.MAX_PIXELS / pixels).toFloat()
            x = (x * shrink).coerceAtLeast(1f); y = (y * shrink).coerceAtLeast(1f)
        }
        val w = maxOf(width, kotlin.math.floor(width * x.toDouble() + 0.5).toInt())
        val h = maxOf(height, kotlin.math.floor(height * y.toDouble() + 0.5).toInt())
        return if (w.toLong() * h > LayerSizeBudget.MAX_PIXELS) width to height else w to h
    }
}
