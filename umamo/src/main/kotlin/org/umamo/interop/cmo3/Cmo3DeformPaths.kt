package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.model.drawable.CoordType
import org.umamo.format.cmo3.model.drawable.MeshPointRef
import org.umamo.format.cmo3.model.drawable.PointInTriangle
import org.umamo.format.cmo3.model.drawable.PointOnCurve
import org.umamo.format.cmo3.model.gen.*
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.runtime.model.*
import java.util.UUID
import kotlin.math.sqrt

/** Native editor controllers; the runtime intentionally sees only the baked ArtMesh forms. */
internal object Cmo3DeformPaths {
    private data class BoundCurve(val guid: Guid, val points: List<Pair<Float, Float>>, val closed: Boolean)

    private data class Projection(val totalT: Float, val distance: Float, val nearest: Pair<Float, Float>)

    /**
     * Cubism's CArtMeshSource.getDefaultKeyForm() selects the middle of the keyform pool; null when the keyform
     * lowering left the pool empty (it reported why), which leaves nothing to bind a controller to.
     */
    private fun defaultKeyform(source: CArtMeshSource): CArtMeshForm? {
        val forms = Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CArtMeshForm>()
        return forms.getOrNull(forms.size / 2)
    }

    /** Cubism binds a controller to every point of the ArtMesh's default keyform. */
    private fun bindTargets(source: CArtMeshSource, form: CArtMeshForm, curves: List<BoundCurve>): CArrayList<Any?> {
        val positions = form.positions as? FloatArray ?: error("Deform path default keyform has no positions")
        val coordType = form.coordType as? CoordType ?: error("Deform path default keyform has no coordinate type")
        val pointUids = Cmo3Import.editableMeshOf(source)?.pointUid as? IntArray
            ?: error("Deform path ArtMesh has no editable point UIDs")
        val artMeshGuid = source.guid as? Guid ?: error("Deform path ArtMesh has no GUID")
        require(positions.size == pointUids.size * 2) { "Deform path keyform and editable mesh have different vertex counts" }

        return CArrayList<Any?>().apply {
            for (vertex in pointUids.indices) {
                val position = positions[vertex * 2] to positions[vertex * 2 + 1]
                val effects = CArrayList<Any?>()
                for (curve in curves) {
                    val projection = nearestOnCurve(curve, position)
                    effects.add(Effect().apply {
                        effectorPt = PointOnCurve().apply {
                            curveId = curve.guid
                            totalT = projection.totalT
                            distance = projection.distance
                            _posOnLocal = GVector2().apply {
                                x = projection.nearest.first
                                y = projection.nearest.second
                            }
                        }
                        weight = 1f
                    })
                }
                add(TargetPoint().apply {
                    _point = MeshPointRef().apply {
                        pointUid = pointUids[vertex].toLong()
                        this.coordType = coordType
                        keyForm = form
                        this.positions = positions
                        step = 0
                        _artMeshSource = source
                        _index = vertex
                        this.artMeshGuid = artMeshGuid
                    }
                    this.effects = effects
                })
            }
        }
    }

    private fun nearestOnCurve(curve: BoundCurve, point: Pair<Float, Float>): Projection {
        var best = Projection(0f, Float.POSITIVE_INFINITY, curve.points.first())
        val segmentCount = if (curve.closed) curve.points.size else curve.points.size - 1
        for (segment in 0 until segmentCount) {
            val start = curve.points[segment]
            val end = curve.points[(segment + 1) % curve.points.size]
            val dx = end.first - start.first
            val dy = end.second - start.second
            val lengthSquared = dx * dx + dy * dy
            val fraction = if (lengthSquared <= 1e-12f) 0f else
                (((point.first - start.first) * dx + (point.second - start.second) * dy) / lengthSquared).coerceIn(0f, 1f)
            val nearest = (start.first + dx * fraction) to (start.second + dy * fraction)
            val distance = sqrt(
                ((point.first - nearest.first) * (point.first - nearest.first) +
                    (point.second - nearest.second) * (point.second - nearest.second)).toDouble()
            ).toFloat()
            if (distance < best.distance) best = Projection(segment + fraction, distance, nearest)
        }
        return best.copy(totalT = editorSafeT(best.totalT, curve))
    }

    /**
     * Cubism splits curves at corner points; an open curve evaluated exactly at its last index lands in a
     * one-node segment and throws on every drag frame. A closed curve at `size` wraps back to 0.
     */
    private fun editorSafeT(t: Float, curve: BoundCurve): Float {
        val size = curve.points.size.toFloat()
        return if (curve.closed) (if (t >= size) 0f else t)
        else t.coerceAtMost(Math.nextDown(size - 1f))
    }

    fun read(sources: List<CArtMeshSource>): List<DeformPath> = buildList {
        for(source in sources) {
            val id=Cmo3Import.idStrOf(source.id) ?: continue
            for(extension in Cmo3Import.elementsOf(source._extensions).filterIsInstance<CControllerExtension>()) {
                for(curve in Cmo3Import.elementsOf(extension.controlCurves).filterIsInstance<CControllerCurve>()) {
                    val points=Cmo3Import.elementsOf(curve._curvePoints).filterIsInstance<CControllerPoint>()
                    if(points.size !in 2..128 || points.any { it.pointInTriangle !is PointInTriangle }) continue
                    val curveId=Cmo3Import.uuidOf(curve.curveId) ?: continue
                    if(extension.editLevel !in 2..3) continue
                    val safeLineWidth = curve.lineWidth.coerceAtLeast(0f)
                    val safeHardness = curve.lineHardnessPercent.coerceIn(0f, 100f)
                    add(DeformPath(curveId,DrawableId(id),points.map { p ->
                        val b=p.pointInTriangle as PointInTriangle
                        DeformPathPoint(
                            b.ptIndex1.coerceAtLeast(0),
                            b.ptIndex2.coerceAtLeast(0),
                            b.ptIndex3.coerceAtLeast(0),
                            b.weight1,b.weight2,b.weight3,p.isCorner
                        )
                    },safeLineWidth,safeHardness,!curve.isOpen,extension.editLevel))
                }
            }
        }
    }

    fun write(model: PuppetModel, baseline: PuppetModel, index: Cmo3GraphIndex, editor: Cmo3GraphEditor) {
        for(source in index.drawableSources) {
            val id=Cmo3Import.idStrOf(source.id) ?: continue
            val paths=model.deformPaths.filter { it.drawableId.raw==id }
            val drawable=model.drawables.single { it.id.raw==id }
            if(paths==baseline.deformPaths.filter { it.drawableId.raw==id }) continue
            val defaultForm=defaultKeyform(source) ?: continue
            val vertices=defaultForm.positions as? FloatArray ?: error("Deform path default keyform has no positions")
            val canvasVertices=requireNotNull(drawable.mesh).positions
            val extensions=CArrayList<Any?>().apply {
                addAll(Cmo3Import.elementsOf(source._extensions).filterNot {
                    it is CControllerExtension || it is CTopologyObserverExtension
                })
            }
            val controllers=ArrayList<CControllerExtension>()
            for((level,curves) in paths.groupBy { it.editLevel }) {
                val controls=CArrayList<Any?>()
                val nativeCurves=CArrayList<Any?>()
                val boundCurves=ArrayList<BoundCurve>()
                val extension=CControllerExtension().apply {
                    guid=guid("CExtensionGuid");_owner=source;editLevel=level
                    maxBindCount=3;bindMethod=BindMethod.LINE_AND_DIRECTION
                    controlPoints=controls;controlCurves=nativeCurves
                    targetPoints=CArrayList<Any?>();subArtMeshGuids=ArrayList<Any?>()
                }
                for(path in curves) {
                    val curve=CControllerCurve().apply {
                        curveId=Guid("CControllerCurveGuid").apply { uuid=path.id }
                        lineWidth=path.safeWidth;lineHardnessPercent=path.safeHardness;isOpen=!path.closed
                    }
                    curve._curvePoints=CArrayList<Any?>().apply {
                        for(p in path.points) {
                            val xy=p.position(vertices)
                            val canvas=p.position(canvasVertices)
                            val point=CControllerPoint().apply {
                                assignedCurve=curve;isCorner=p.corner;_owner=extension
                                ctrlPtId=guid("CControllerPointGuid")
                                coordType=CoordType().apply { coordName=if(drawable.parentDeformerId==null) "Canvas" else "DeformerLocal" }
                                pointInTriangle=PointInTriangle().apply {
                                    ptIndex1=p.a;ptIndex2=p.b;ptIndex3=p.c
                                    weight1=p.wa;weight2=p.wb;weight3=p.wc
                                }
                                posOnLocalOfDefaultKeyform=GVector2().apply { x=xy.first;y=xy.second }
                                pointOnCanvasForRecovery=GVector2().apply { x=canvas.first;y=canvas.second }
                                totalEffectToPointInTriangle=1f
                            }
                            add(point);controls.add(point)
                        }
                    }
                    nativeCurves.add(curve)
                    boundCurves.add(BoundCurve(curve.curveId as Guid, path.points.map { it.position(vertices) }, path.closed))
                }
                extension.targetPoints = bindTargets(source, defaultForm, boundCurves)
                extensions.add(extension)
                controllers.add(extension)
            }
            if(controllers.isNotEmpty()) {
                extensions.add(CTopologyObserverExtension().apply {
                    guid=guid("CExtensionGuid");_owner=source
                    observers=CArrayList<Any?>().apply { addAll(controllers) }
                })
            }
            source._extensions=extensions
            editor.ensureChildSlot(source,"ACParameterControllableSource","_extensions")
        }
    }

    private fun guid(kind: String)=Guid(kind).apply { uuid=UUID.randomUUID().toString() }
}
