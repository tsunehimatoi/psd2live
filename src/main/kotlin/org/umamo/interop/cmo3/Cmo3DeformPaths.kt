package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.model.drawable.CoordType
import org.umamo.format.cmo3.model.drawable.PointInTriangle
import org.umamo.format.cmo3.model.gen.*
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.runtime.model.*
import org.umamo.runtime.eval.meshGridDefaultDeltas
import java.util.UUID

/** Native editor controllers; the runtime intentionally sees only the baked ArtMesh forms. */
internal object Cmo3DeformPaths {
    private fun localDefault(model: PuppetModel, drawable: Drawable): FloatArray {
        val base=requireNotNull(drawable.mesh).positions
        val deltas=meshGridDefaultDeltas(drawable) { id -> model.parameters.firstOrNull { it.id==id }?.default ?: 0f }
        return FloatArray(base.size) { base[it]+(deltas?.get(it) ?: 0f) }
    }

    /** Native widths are canvas distances; the path editor operates in the mesh's parent frame. */
    private fun canvasScale(model: PuppetModel, drawable: Drawable): Float {
        val local=localDefault(model,drawable)
        val mesh=requireNotNull(drawable.mesh)
        var canvasLength=0.0;var localLength=0.0
        for(i in mesh.indices.indices step 3) for(j in 0..2) {
            val a=mesh.indices[i+j]*2;val b=mesh.indices[i+(j+1)%3]*2
            canvasLength+=kotlin.math.hypot((mesh.positions[a]-mesh.positions[b]).toDouble(),(mesh.positions[a+1]-mesh.positions[b+1]).toDouble())
            localLength+=kotlin.math.hypot((local[a]-local[b]).toDouble(),(local[a+1]-local[b+1]).toDouble())
        }
        return if(localLength>1e-12 && canvasLength>1e-12) (canvasLength/localLength).toFloat() else 1f
    }

    fun toLocalWidths(model: PuppetModel): PuppetModel = model.copy(deformPaths=model.deformPaths.map { path ->
        val drawable=model.drawables.single { it.id==path.drawableId }
        path.copy(width=path.width/canvasScale(model,drawable))
    })

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
                    val safeHardness = (curve.lineHardnessPercent / 100f).coerceIn(0f, 1f)
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
            if(paths==baseline.deformPaths.filter { it.drawableId.raw==id }) continue
            val drawable=model.drawables.single { it.id.raw==id }
            val vertices=localDefault(model,drawable)
            val canvasVertices=requireNotNull(drawable.mesh).positions
            val widthScale=canvasScale(model,drawable)
            val extensions=CArrayList<Any?>().apply {
                addAll(Cmo3Import.elementsOf(source._extensions).filterNot {
                    it is CControllerExtension || it is CTopologyObserverExtension
                })
            }
            val controllers=ArrayList<CControllerExtension>()
            for((level,curves) in paths.groupBy { it.editLevel }) {
                val controls=CArrayList<Any?>()
                val nativeCurves=CArrayList<Any?>()
                val extension=CControllerExtension().apply {
                    guid=guid("CExtensionGuid");_owner=source;editLevel=level
                    maxBindCount=3;bindMethod=BindMethod.LINE_AND_DIRECTION
                    controlPoints=controls;controlCurves=nativeCurves
                    targetPoints=CArrayList<Any?>();subArtMeshGuids=ArrayList<Any?>()
                }
                for(path in curves) {
                    val curve=CControllerCurve().apply {
                        curveId=Guid("CControllerCurveGuid").apply { uuid=path.id }
                        lineWidth=(path.width*widthScale).coerceAtLeast(0f);lineHardnessPercent=path.hardness.coerceIn(0f, 1f)*100f;isOpen=!path.closed
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
                }
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
