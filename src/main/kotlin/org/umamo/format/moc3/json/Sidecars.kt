package org.umamo.format.moc3.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The `Json` configuration that reproduces the Cubism editor's sidecar output.
 *
 * Tab-indented, pretty-printed, properties in declaration order; optional fields are omitted
 * when absent (`explicitNulls = false`). Numeric leaves the editor prints without a trailing
 * `.0` (e.g. `"Y": -1`, `"Weight": 60`) are modelled as
 * [kotlinx.serialization.json.JsonPrimitive] so the original token round-trips exactly.
 * Decoding tolerates unknown keys (`ignoreUnknownKeys`): newer Cubism exports add manifest
 * fields the schema does not model yet (e.g. Cubism 5's FileReferences.MotionSync), and a
 * reader that rejects them refuses valid real-world models.  The flag affects decoding only,
 * so encoded output is unchanged - but it means decode-then-encode DROPS unmodelled keys, so
 * a future sidecar re-emit path must preserve unknown keys explicitly (raw JsonObject
 * passthrough) rather than relying on the serializer.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §JSON sidecars</a>
 */
@OptIn(ExperimentalSerializationApi::class)
internal val SidecarJson: Json =
	Json {
		prettyPrint = true
		prettyPrintIndent = "\t"
		explicitNulls = false
		encodeDefaults = true
		ignoreUnknownKeys = true
	}