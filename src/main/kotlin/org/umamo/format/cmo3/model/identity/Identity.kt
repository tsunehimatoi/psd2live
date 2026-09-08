package org.umamo.format.cmo3.model.identity

/**
 * A globally-unique entity reference (uuid) with a human-readable note:
 * every entity (drawable, deformer, part, parameter, …) has a typed `*Guid`; on disk each is its own
 * element tag carrying `uuid` + `note` attributes. [kind] holds that concrete tag.
 *
 * EN: One type covers all `*Guid` tags - they are opaque identity tokens with identical structure.
 * JA: 全 `*Guid` を 1 型で表し、[kind] にタグ名を保持する。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
public class Guid(public val kind: String) {
	/** The UUID string, e.g. "e9fe6eff-953b-4ce2-be7c-4a7c3913686b". */
	public var uuid: String = ""

	/** The editor's debug note, e.g. "Root Parameter Group" or "(no debug info)". */
	public var note: String = ""
}

/**
 * A string-keyed entity id: each `*Id` is its own element tag with an
 * `idstr` attribute. [kind] holds that concrete tag.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
public class Id(public val kind: String) {
	/** The id string, e.g. "filter0" or "ilf_outputLayerData". */
	public var idstr: String = ""
}