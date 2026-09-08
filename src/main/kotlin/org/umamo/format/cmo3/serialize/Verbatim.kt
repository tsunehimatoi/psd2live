package org.umamo.format.cmo3.serialize

import org.jdom.Element

/**
 * A not-yet-typed subtree, held verbatim as its detached JDOM element so it round-trips unchanged.
 *
 * EN: When the engine meets a tag with no registered typed serializer it falls back to this, instead
 *     of failing - so newer Live2D format additions still load and save losslessly. As subsystems
 *     get typed, these are replaced by concrete classes.
 * JA: 未対応タグはこのノードとして JDOM 要素のまま保持し、無損失で往復させる。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public class VerbatimNode internal constructor(
	/** The detached source element (a clone; safe to re-attach to a new tree). */
	internal val element: Element,
) {
	/** The element tag this node stands in for (the unmodeled class name). */
	public val tag: String get() = element.name
}

/**
 * Receives notifications when the engine falls back to [VerbatimNode] for an unmodeled tag - so
 * gaps in model coverage (or new Live2D additions) are visible rather than silent.
 *
 * EN: Functional interface; default is [None] (no-op).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public fun interface SerializeDiagnostics {
	/**
	 * Called once per element whose tag has no registered typed serializer.
	 *
	 * @param String tag  The unmodeled element tag (class name).
	 * @param String path The owning field path within the document (best-effort).
	 */
	fun onUnmodeledTag(tag: String, path: String)

	public companion object {
		/** A diagnostics sink that ignores everything. */
		public val None: SerializeDiagnostics = SerializeDiagnostics { _, _ -> }
	}
}

// TODO(diagnostics): enrich `path` with the full owner stack (e.g. CModelSource.drawables[3].<tag>)
// by threading a name stack through ReadContext. Currently best-effort (immediate owner field name).