package org.umamo.edit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The session's request buses: fire-and-forget signals from command handlers to the area that
 * executes them - a viewport or UV editor overlay, or the UV editor space itself.  Every one exists
 * for the same reason - the operation needs something only the receiving area has (the pointer
 * position, the projected geometry, the in-flight working positions, the area's own view state), so
 * the session cannot execute it directly; it signals here and the observing collector executes.
 * Pure plumbing with no other session state involved, hence a separate collaborator; [EditorSession]
 * exposes each flow and request method unchanged.
 */

internal class SessionRequestBus {
	private val mutableSnapRequests = MutableSharedFlow<SnapRequest>(extraBufferCapacity = 4)

	/** The geometry-dependent snap requests (see [EditorSession.snapRequests]). */
	val snapRequests: SharedFlow<SnapRequest> = mutableSnapRequests.asSharedFlow()

	private val mutableSelectLinkedRequests = MutableSharedFlow<SelectLinkedRequest>(extraBufferCapacity = 4)

	/** The Select Linked requests (see [EditorSession.selectLinkedRequests]). */
	val selectLinkedRequests: SharedFlow<SelectLinkedRequest> = mutableSelectLinkedRequests.asSharedFlow()

	private val mutableUvSnapRequests = MutableSharedFlow<UvSnapRequest>(extraBufferCapacity = 4)

	/** The UV editor snap requests (see [EditorSession.uvSnapRequests]). */
	val uvSnapRequests: SharedFlow<UvSnapRequest> = mutableUvSnapRequests.asSharedFlow()

	private val mutableUvMirrorRequests = MutableSharedFlow<UvMirrorRequest>(extraBufferCapacity = 4)

	/** The UV editor mirror requests (see [EditorSession.uvMirrorRequests]). */
	val uvMirrorRequests: SharedFlow<UvMirrorRequest> = mutableUvMirrorRequests.asSharedFlow()

	private val mutableUvPageRequests = MutableSharedFlow<UvPageRequest>(extraBufferCapacity = 4)

	/** The UV editor page-switch requests (see [EditorSession.uvPageRequests]). */
	val uvPageRequests: SharedFlow<UvPageRequest> = mutableUvPageRequests.asSharedFlow()

	private val mutableSwitchObjectRequests = MutableSharedFlow<String?>(extraBufferCapacity = 1)

	/** The Alt+Q edited-mesh switch requests, each carrying its executing area (see [EditorSession.switchObjectRequests]). */
	val switchObjectRequests: SharedFlow<String?> = mutableSwitchObjectRequests.asSharedFlow()

	private val mutableRipRequests = MutableSharedFlow<String?>(extraBufferCapacity = 1)

	/** The rip-at-pointer requests, each carrying its executing area (see [EditorSession.ripRequests]). */
	val ripRequests: SharedFlow<String?> = mutableRipRequests.asSharedFlow()

	private val mutableMeshConfirm = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The modal-gesture confirm requests (see [EditorSession.meshConfirmRequests]). */
	val meshConfirmRequests: SharedFlow<Unit> = mutableMeshConfirm.asSharedFlow()

	private val mutableMeshGestureCancel = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The selection-gesture cancel requests (see [EditorSession.meshGestureCancelRequests]). */
	val meshGestureCancelRequests: SharedFlow<Unit> = mutableMeshGestureCancel.asSharedFlow()

	/**
	 * Requests a geometry-dependent snap.
	 *
	 * @param SnapRequest request The snap to perform plus the dispatch-time resolved area.
	 */
	fun requestSnap(request: SnapRequest) {
		mutableSnapRequests.tryEmit(request)
	}

	/**
	 * Requests a Select Linked for one area's overlay to execute.
	 *
	 * @param SelectLinkedRequest request The flood variant plus the dispatch-time resolved area.
	 */
	fun requestSelectLinked(request: SelectLinkedRequest) {
		mutableSelectLinkedRequests.tryEmit(request)
	}

	/**
	 * Requests a UV snap for one UV editor area's overlay to execute.
	 *
	 * @param UvSnapRequest request The snap operation plus the dispatch-time resolved area.
	 */
	fun requestUvSnap(request: UvSnapRequest) {
		mutableUvSnapRequests.tryEmit(request)
	}

	/**
	 * Requests a mirror for one UV editor area's overlay to execute.
	 *
	 * @param UvMirrorRequest request The mirror axis plus the dispatch-time resolved area.
	 */
	fun requestUvMirror(request: UvMirrorRequest) {
		mutableUvMirrorRequests.tryEmit(request)
	}

	/**
	 * Requests a page switch for one UV editor area to execute.
	 *
	 * @param UvPageRequest request The page operation plus the dispatch-time resolved area.
	 */
	fun requestUvPage(request: UvPageRequest) {
		mutableUvPageRequests.tryEmit(request)
	}

	/**
	 * Requests an Alt+Q edited-mesh switch for one area's overlay to execute.
	 *
	 * @param String? areaId The dispatch-time resolved viewport area, or null when the pointer is elsewhere.
	 */
	fun requestSwitchObjectUnderCursor(areaId: String?) {
		mutableSwitchObjectRequests.tryEmit(areaId)
	}

	/**
	 * Requests a rip at the pointer for one area's overlay to execute.
	 *
	 * @param String? areaId The dispatch-time resolved viewport area, or null when the pointer is elsewhere.
	 */
	fun requestRip(areaId: String?) {
		mutableRipRequests.tryEmit(areaId)
	}

	/** Requests that the gizmo overlay confirm the in-flight modal gesture. */
	fun requestMeshConfirm() {
		mutableMeshConfirm.tryEmit(Unit)
	}

	/** Requests that the gizmo overlay abandon any in-flight box / circle selection gesture. */
	fun requestMeshGestureCancel() {
		mutableMeshGestureCancel.tryEmit(Unit)
	}
}

/**
 * One Select Linked request: which flood variant, and which area's overlay executes it.  The area is
 * resolved ONCE at command dispatch (the hovered surface at that instant) and carried in the payload,
 * so two collectors reading a volatile independently can never double- or zero-execute when the
 * pointer crosses areas between resumptions.
 *
 * @property Boolean fromSelection True to flood from the whole selection (Ctrl+L), false from the cursor (L).
 * @property String? areaId The area whose overlay executes, or null when no surface was ever touched
 *   (then no collector matches and the request is a clean no-op).
 */
data class SelectLinkedRequest(val fromSelection: Boolean, val areaId: String?)

/**
 * One UV snap request: which operation, and which UV editor area's overlay executes it.  The area is
 * resolved ONCE at command dispatch (the hovered surface at that instant) and carried in the payload,
 * exactly like [SelectLinkedRequest] - there is no area answer anywhere else for a collector to fall back
 * on, so gating on this id is the whole mechanism.
 *
 * @property UvSnapKind kind The snap operation to perform.
 * @property String? areaId The UV editor area whose overlay executes, or null when the hovered surface
 *   was not a UV editor (then no collector matches and the request is a clean no-op).
 */
data class UvSnapRequest(val kind: UvSnapKind, val areaId: String?)

/**
 * One mirror request: which axis, and which UV editor area's overlay executes it.
 *
 * Mirror is the one texture-coordinate operation expressed purely in normalized coordinates, so
 * unlike the geometry-dependent snaps it could once run without knowing which editor it meant.  It
 * cannot any more: the axis it reflects about depends on the surface being authored over, and only
 * the overlay knows which that is.  So it takes the same route the snaps do - the area resolved ONCE
 * at command dispatch, carried here, and the overlay supplying the frame when it executes.
 *
 * @property Boolean mirrorU True to mirror horizontally, false vertically.
 * @property String? areaId The UV editor area whose overlay executes, or null when the hovered surface
 *   was not a UV editor (then no collector matches and the request is a clean no-op).
 */
data class UvMirrorRequest(val mirrorU: Boolean, val areaId: String?)

/**
 * The page operations a UV editor area's texture selection understands: cycling pins the next or
 * previous atlas page with wrap-around from the currently shown page, and FollowSelection clears the
 * pin back to the auto-follow default.  These retarget a VIEW (the receiving area's per-area page
 * pin), never the model, which is why they ride the request bus rather than the Change pipeline.
 */
enum class UvPageKind {
	NextPage,
	PreviousPage,
	FollowSelection,
}

/**
 * One page-switch request: which operation, and which UV editor area executes it.  The area is
 * resolved ONCE at command dispatch (the hovered surface at that instant) and carried in the payload,
 * exactly like [UvSnapRequest] - there is no area answer anywhere else for a collector to fall back
 * on, so gating on this id is the whole mechanism.
 *
 * @property UvPageKind kind The page operation to perform.
 * @property String? areaId The UV editor area that executes, or null when the hovered surface was not
 *   a UV editor (then no collector matches and the request is a clean no-op).
 */
data class UvPageRequest(val kind: UvPageKind, val areaId: String?)

/**
 * One world-space snap request: which operation, and which viewport area's overlay executes it.  The area
 * is resolved ONCE at command dispatch (the hovered surface at that instant) and carried in the payload,
 * exactly like [SelectLinkedRequest] and [UvSnapRequest].
 *
 * The handlers ignore the area - a snap acts on the model, not on one viewport - so its only job here is
 * ELECTION: every open viewport composes a collector, and without an id in the payload each would have to
 * read a shared volatile to decide whether it is the one, which can double- or zero-execute when the
 * pointer crosses areas between resumptions.
 *
 * @property SnapKind kind The snap operation to perform.
 * @property String? areaId The viewport area whose overlay executes, or null when the pointer is not on a
 *   viewport (then no collector matches and the request is a clean no-op).
 */
data class SnapRequest(val kind: SnapKind, val areaId: String?)