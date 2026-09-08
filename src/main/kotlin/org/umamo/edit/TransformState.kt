package org.umamo.edit

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * The default major grid spacing in world units (canvas px): the built-in fallback value for
 * [GridConfig.scale] when neither a settings default (settings key viewport.grid.scale) nor a per-file
 * value has been resolved.  The grid snaps round to the finest subdivision, not to this spacing
 * directly - see [GridConfig.snapStep].
 */
const val SNAP_GRID_WORLD_UNITS = 100f

/**
 * The default number of minor subdivisions per major grid cell: the built-in fallback for
 * [GridConfig.subdivisions] (settings key viewport.grid.subdivisions).
 */
const val DEFAULT_GRID_SUBDIVISIONS = 10

/**
 * The viewport grid's geometry: the major line spacing in world units and how many minor
 * (subdivision) lines divide each major cell.  Drives both the drawn backdrop grid and the grid snap.
 * Held per-document (transient session state today, persisted per-file once the UMA format lands);
 * seeded from the global-default settings for formats - like CMO3 - that do not store grid info.
 *
 * ビューポートグリッドの間隔設定。主線間隔（ワールド単位）と 1 セルあたりの副線分割数。背景グリッドと
 * グリッドスナップの両方を駆動する。ドキュメント単位（現状は一時状態、UMA 実装後はファイルに永続化）。
 *
 * @property Float scale The major grid line spacing, in world units.
 * @property Int subdivisions The minor lines per major cell (must be at least 1).
 */
data class GridConfig(
	val scale: Float = SNAP_GRID_WORLD_UNITS,
	val subdivisions: Int = DEFAULT_GRID_SUBDIVISIONS,
) {
	/**
	 * The grid snap increment in world units: the finest visible spacing (major / subdivisions), so
	 * Selection- and Cursor-to-Grid round to the minor grid lines the backdrop draws.  Subdivisions is
	 * clamped to at least 1 so the step is always a positive, finite value.
	 */
	val snapStep: Float
		get() = scale / subdivisions.coerceAtLeast(1)
}

/**
 * Rounds a world coordinate to the nearest grid line, measured from [origin] (the world origin the grid
 * is drawn around) rather than from world 0, so a snap lands on the same lines the backdrop grid draws.
 *
 * @param Float value  The world coordinate to snap.
 * @param Float origin The world origin the grid lattice is anchored on (a line passes through it).
 * @param Float step   The grid snap increment (see [GridConfig.snapStep]).
 * @return Float The snapped world coordinate.
 */
fun snapToGrid(value: Float, origin: Float, step: Float): Float = round((value - origin) / step) * step + origin

/**
 * The 2D cursor: a placeable world-space anchor, the 2D analog of Blender's 3D cursor.  Placed with
 * Shift+RightClick in the viewport, drawn by the HUD overlay, usable as a transform pivot
 * ([TransformPivotMode.Cursor]) and as the source / target of the Shift+S snap operations.  Session
 * state, transient by design: cursor moves are deliberately NOT undo steps (they ride outside
 * [EditorSnapshot]), and the snap menu makes recovering a lost placement cheap.
 *
 * 2D カーソル。Blender の 3D カーソルの 2D 版。ピボットやスナップの基準点になるワールド座標の
 * アンカー。取り消し履歴には乗らない一時状態。
 *
 * @property Float worldX The cursor's world-space x.
 * @property Float worldY The cursor's world-space y.
 */
data class Cursor2d(val worldX: Float, val worldY: Float)

/**
 * The UV editor's own 2D cursor: a placeable anchor in normalized atlas coordinates, the texture-space
 * sibling of [Cursor2d] (Blender's UV editor likewise carries its own cursor, separate from the 3D
 * one).  Placed with Shift+RightClick in the UV editor, drawn by its overlay, and read as the UV
 * transform pivot in [TransformPivotMode.Cursor].  Session state, transient by design like [Cursor2d]:
 * cursor moves are deliberately NOT undo steps.
 *
 * UV エディタ専用の 2D カーソル。正規化アトラス座標のアンカーで、UV 変形のカーソルピボットになる。
 * 取り消し履歴には乗らない一時状態。
 *
 * @property Float u The cursor's normalized atlas u coordinate.
 * @property Float v The cursor's normalized atlas v coordinate.
 */
data class UvCursor(val u: Float, val v: Float)

/**
 * What a modal Scale / Rotate turns the selection about (Blender's pivot point selector, the Period
 * pie).  MedianPoint is the covered vertices' centroid (the default); IndividualOrigins splits the
 * selection into connectivity islands (edit mode) or per drawable (object mode), each turning about
 * its own centroid; ActiveElement anchors on the active element (or active drawable); Cursor anchors
 * on the 2D cursor.
 *
 * 変形の基準点の種類（Blender のピボットポイント）。中点・各自の原点・アクティブ要素・2D カーソル。
 */
enum class TransformPivotMode {
	MedianPoint,
	IndividualOrigins,
	ActiveElement,
	Cursor,
}

/**
 * The axis a modal Grab / Scale is locked to, toggled by pressing X or Z during the gesture (Blender's
 * axis constraint; Rotate is excluded - there is only one 2D rotation axis).  Named by the DISPLAYED
 * axes: the project presents the 2D plane as X (horizontal) and Z (vertical, world +y) per the
 * Y+ forward, Z+ up convention, so AxisZ constrains the position arrays' y coordinates.
 *
 * モーダル変形の軸ロック（X / Z キー）。表示規約は Y+ 前、Z+ 上なので、AxisZ は配列の y 成分に対応する。
 */
enum class TransformAxisConstraint {
	AxisX,
	AxisZ,
}

/**
 * Which radial pie menu is open over the viewport, or none (null in the session flow).  The pie
 * entries dispatch through the command registry; the session only coordinates which pie is showing
 * (a transient latch like the modal operators).
 *
 * 表示中のパイメニューの種類。エントリはコマンドレジストリ経由で実行される。
 */
enum class PieMenuKind {
	PivotMode,
	Snap,
	UvSnap,
	MergeTarget,
}

/**
 * A latched modal transform operator together with the viewport area that initiated it.  The area id
 * is an opaque workspace-leaf id (the same currency as [EditorSession.zoomRegionArmedArea]): the
 * session never interprets it, but the UI gates gesture capture, HUD drawing, and confirm delivery to
 * the initiating area, so a gesture latched in one split viewport can never be driven or committed
 * from another.  Both fields publish atomically in one flow emission - a paired flow could tear.
 *
 * ラッチされたモーダル変形操作と、それを開始したビューポートエリア。エリア ID は不透明な文字列で、
 * UI 側がジェスチャの捕捉・HUD 描画・確定の配送を開始エリアに限定するために使う。
 *
 * @property MeshOperatorKind kind The latched operator (Grab / Scale / Rotate / VertexSlide).
 * @property String areaId The initiating viewport's area id.
 */
data class ActiveOperator(
	val kind: MeshOperatorKind,
	val areaId: String,
)

/**
 * The geometry-dependent snap operations (Blender's Shift+S) the viewport overlay executes: the
 * cursor-to-geometry moves need the posed world projection and the selection-to-target moves edit the
 * model through the deformer-chain inverse, both of which live with the overlay - so the session
 * carries the request and the active mode's overlay performs it.  The purely arithmetical snaps
 * (cursor to world origin / to grid) are handled directly by their command handlers and never appear
 * here.
 *
 * ジオメトリ依存のスナップ操作の種類。オーバーレイが実行する。
 */
enum class SnapKind {
	CursorToSelected,
	CursorToActive,
	SelectionToCursor,
	SelectionToCursorOffset,
	SelectionToGrid,
	SelectionToActive,
}

/**
 * The UV editor's snap operations (its own Shift+S pie), the texture-space sibling of [SnapKind].
 * Every one edits or reads in the UV editor's texel display space, so the UV overlay - which owns the
 * shown page's dimensions and display geometry - executes them, the same request-through-overlay split
 * as [SnapKind].  The op set differs from the world snaps by design: UV has pixel-corner snaps (a
 * texel boundary is a natural target for artwork-edge accuracy) and no Active-element snaps, while the
 * world grid has no meaning here (the UV grid subdivides the atlas page instead - see the UV editor's
 * display mapping).
 *
 * UV エディタのスナップ操作の種類。テクセル表示空間で動作し、UV オーバーレイが実行する。
 */
enum class UvSnapKind {
	SelectionToPixels,
	SelectionToCursor,
	SelectionToCursorOffset,
	SelectionToGrid,
	CursorToPixels,
	CursorToSelected,
	CursorToGrid,
}

/**
 * One pivot group of a modal transform: a set of vertices turning about one pivot.  Median / Active /
 * Cursor pivot modes produce a single group per mesh (every covered vertex about the one shared
 * anchor); IndividualOrigins produces one group per connectivity island (edit mode) or per drawable
 * (object mode), each about its own centroid.
 *
 * 変形のピボットグループ。1つのピボットを共有する頂点集合。各自の原点モードでは島ごとに分かれる。
 *
 * @property Set<Int> vertexIndices The group's vertex indices (into the mesh's interleaved array).
 * @property Float pivotX The group pivot's x, in the positions' coordinate space.
 * @property Float pivotY The group pivot's y, in the positions' coordinate space.
 */
data class TransformPivotGroup(
	val vertexIndices: Set<Int>,
	val pivotX: Float,
	val pivotY: Float,
)

/**
 * Pure pivot-group builders for the modal transforms (the [TransformPivotMode] machinery).
 *
 * ピボットグループの純粋な構築関数。
 */
object TransformPivots {
	/**
	 * One group turning every covered vertex about a shared anchor - the shape Median Point, Active
	 * Element, and Cursor pivots all reduce to (they differ only in where the anchor is).
	 *
	 * @param Set<Int> coveredIndices The vertices the gesture moves.
	 * @param Float pivotX The shared anchor's x.
	 * @param Float pivotY The shared anchor's y.
	 * @return List<TransformPivotGroup> The single shared-pivot group.
	 */
	fun sharedGroup(coveredIndices: Set<Int>, pivotX: Float, pivotY: Float): List<TransformPivotGroup> =
		listOf(TransformPivotGroup(coveredIndices, pivotX, pivotY))

	/**
	 * Per-island groups for Individual Origins in edit mode: the covered vertices split into
	 * connectivity islands (components of the sub-graph the selection induces), each turning about its
	 * own centroid.
	 *
	 * @param FloatArray positions The mesh's interleaved positions (the pivots' coordinate space).
	 * @param Set<Int> coveredIndices The vertices the gesture moves.
	 * @param IntArray triangleIndices The mesh triangle vertex indices (for connectivity).
	 * @return List<TransformPivotGroup> One group per island.
	 */
	fun islandGroups(
		positions: FloatArray,
		coveredIndices: Set<Int>,
		triangleIndices: IntArray,
	): List<TransformPivotGroup> {
		val vertexCount = positions.size / 2
		val adjacency = MeshTopology.buildVertexAdjacency(vertexCount, triangleIndices)
		return MeshTopology.selectionIslands(adjacency, coveredIndices).map { island ->
			val pivot = MeshTransforms.medianPivot(positions, island)
			TransformPivotGroup(island, pivot.first, pivot.second)
		}
	}

	/**
	 * Splits a proportional influence map into per-group weight maps, index-parallel to [groups]: each
	 * influenced vertex follows the pivot group that OWNS its nearest covered vertex, so with Individual
	 * Origins the halo around an island turns about that island's pivot, not the shared gesture anchor.
	 * With a single shared group everything lands in it (whose pivot IS the gesture anchor), so the
	 * shared-pivot modes keep their behavior.  An influence whose nearest covered vertex is in no group
	 * (impossible today - the groups partition the covered set) falls into the first group rather than
	 * dropping motion.
	 *
	 * @param Map<Int, ProportionalInfluence> influences The influenced vertices (weight + nearest covered).
	 * @param List<TransformPivotGroup> groups The gesture's pivot groups.
	 * @return List<Map<Int, Float>> One weight map per group, parallel to [groups].
	 */
	fun partitionInfluencesByGroup(
		influences: Map<Int, ProportionalInfluence>,
		groups: List<TransformPivotGroup>,
	): List<Map<Int, Float>> {
		if (groups.isEmpty()) {
			return emptyList()
		}
		val partitions = List(groups.size) { LinkedHashMap<Int, Float>() }
		for ((vertexIndex, influence) in influences) {
			val ownerIndex = groups.indexOfFirst { group -> influence.nearestCoveredIndex in group.vertexIndices }
			partitions[if (ownerIndex >= 0) ownerIndex else 0][vertexIndex] = influence.weight
		}
		return partitions
	}
}

/**
 * Accumulates a modal rotate gesture's angle from per-move increments, each wrapped into (-pi, pi], so
 * the total walks smoothly through the atan2 branch cut at +-pi and supports multi-turn rotation.  A
 * raw start-to-current atan2 subtraction cannot: a far pivot (the 2D cursor off to the side) places the
 * start angle right AT the cut, where the difference jumps by ~2*pi and the gesture's arc direction
 * latches (it can never reverse past its start).  One tracker lives per gesture capture; feeding the
 * same pointer angle twice adds zero, so re-derives without pointer motion are safe.
 */
class RotationAngleTracker {
	private var previousPointerAngle: Float? = null

	/** The accumulated gesture angle in radians (screen-space sign; the caller negates into world space). */
	var totalAngle: Float = 0f
		private set

	/**
	 * Advances the accumulator to the pointer's current angle about the pivot and returns the total.
	 *
	 * @param Float pointerAngle The pointer's atan2 angle about the rotation pivot, radians.
	 * @return Float The accumulated gesture angle, radians.
	 */
	fun advance(pointerAngle: Float): Float {
		val previous = previousPointerAngle
		if (previous != null) {
			totalAngle += wrapAngle(pointerAngle - previous)
		}
		previousPointerAngle = pointerAngle
		return totalAngle
	}
}

/**
 * Wraps an angle difference into (-pi, pi] - the shortest signed arc between two angles.
 *
 * @param Float delta The raw angle difference, radians.
 * @return Float The wrapped difference.
 */
fun wrapAngle(delta: Float): Float = atan2(sin(delta), cos(delta))