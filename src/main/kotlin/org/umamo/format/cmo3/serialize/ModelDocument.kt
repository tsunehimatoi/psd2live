package org.umamo.format.cmo3.serialize

import org.jdom.Document
import org.jdom.Element
import org.jdom.ProcessingInstruction
import org.umamo.format.cmo3.xml.XmlCodec

/**
 * The parsed model document (main.xml): the `<root>` graph plus its `<?version?>`/`<?import?>`
 * processing instructions, with navigable views over the shared pool and the main root.
 *
 * EN: This is the backbone the reflective serializer plugs into. It wraps the live JDOM
 *     [document], so [write] re-emits byte-identically (verbatim fallback) even before any class
 *     is typed; the parsed accessors (versions/imports/shared/main) drive the typed layer.
 *     The serializer model: `<root>` holds a
 *     `<shared>` pool of referenceable objects (each `xs.id`/`xs.idx`) and a `<main>` root; uses
 *     reference each def via `xs.ref`. To keep byte-identity while only some classes are typed, the
 *     writer must REUSE the `xs.id`/`xs.idx` and `<shared>` ordering captured here rather than
 *     recompute the editor's global refId/writtenIndex counters.
 * JA: main.xml を表すモデル文書。JDOM をそのまま保持するため、未対応クラスでも write はバイト一致する。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public class ModelDocument private constructor(
	/** The live JDOM document; mutating it changes what [write] emits. */
	public val document: Document,
) {
	/** `<?version Class:N?>` PIs as a className -> version map (e.g. "CModelSource" -> 13). */
	public val versions: Map<String, Int>

	/** `<?import fully.qualified.ClassName?>` PIs, in document order. */
	public val imports: List<String>

	/** The document root, `<root>` (carries the fileFormatVersion attribute). */
	public val rootElement: Element = document.rootElement

	/** Children of `<shared>`: the pool of cross-referenced objects (each has xs.id/xs.idx). */
	public val sharedElements: List<Element>

	/** Children of `<main>`: normally the single CModelSource root. */
	public val mainElements: List<Element>

	init {
		require(rootElement.name == ROOT_ELEMENT) { "expected <$ROOT_ELEMENT>, got <${rootElement.name}>" }

		val parsedVersions = LinkedHashMap<String, Int>()
		val parsedImports = ArrayList<String>()
		// PIs live as document content before the root element.
		for (content in document.content) {
			if (content !is ProcessingInstruction) continue
			when (content.target) {
				PI_VERSION -> {
					// data is "ClassName:version"
					val data = content.data
					val separator = data.lastIndexOf(':')
					if (separator > 0) {
						val className = data.substring(0, separator)
						data.substring(separator + 1).trim().toIntOrNull()?.let { parsedVersions[className] = it }
					}
				}
				PI_IMPORT -> parsedImports.add(content.data.trim())
			}
		}
		versions = parsedVersions
		imports = parsedImports

		@Suppress("UNCHECKED_CAST")
		sharedElements = (rootElement.getChild(SHARED_ELEMENT)?.children as List<Element>?).orEmpty()

		@Suppress("UNCHECKED_CAST")
		mainElements = (rootElement.getChild(MAIN_ELEMENT)?.children as List<Element>?).orEmpty()
	}

	/** Schema version of [className] as declared by a `<?version?>` PI, or null. */
	public fun versionOf(className: String): Int? = versions[className]

	/**
	 * Re-emits the document to bytes. Byte-identical to the source while the underlying JDOM is
	 * unmodified (verbatim fallback); reflects edits once the typed layer mutates [document].
	 *
	 * @return ByteArray UTF-8 XML in the editor's exact format.
	 */
	public fun write(): ByteArray = XmlCodec.write(document)

	public companion object {
		public const val ROOT_ELEMENT: String = "root"
		public const val SHARED_ELEMENT: String = "shared"
		public const val MAIN_ELEMENT: String = "main"
		private const val PI_VERSION = "version"
		private const val PI_IMPORT = "import"

		/**
		 * Parses model XML bytes (the decompressed main.xml) into a [ModelDocument].
		 *
		 * @param ByteArray bytes The decompressed main.xml.
		 * @return ModelDocument The parsed document.
		 */
		public fun parse(bytes: ByteArray): ModelDocument = ModelDocument(XmlCodec.parse(bytes))
	}
}

// TODO(diagnostics): when the reflective serializer materialises typed objects, route any element
// whose tag has no registered typed serializer through the verbatim fallback AND emit a diagnostic
// (configurable sink) naming the unimplemented tag + its stack path - so new Live2D format additions
// are visible rather than silently passed through.