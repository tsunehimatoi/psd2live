package org.umamo.format.cmo3

import org.jdom.Document
import org.jdom.Element
import org.jdom.ProcessingInstruction
import org.umamo.format.cmo3.serialize.cubismEngine
import org.umamo.format.cmo3.xml.XmlCodec

/**
 * Authors a main.xml for a NEVER-READ object graph, producing the full editor prologue that
 * `SerializeEngine.writeRoot` alone cannot: the `fileFormatVersion` root attribute, the
 * `<?version?>` class-version PIs, and the `<?import?>` tag-to-FQCN PIs (docs/format/CMO3.md §3
 * Document Shape).  Read graphs never come through here - `Cmo3.write` replays their recorded
 * prologue verbatim; this is the entry point for fresh-graph synthesis (a from-scratch CMO3 for a
 * MOC3-origin document), whose bytes are then wrapped in a CAFF archive and fed back through
 * `Cmo3.read` to obtain a reconcilable [Cmo3Model].
 */
public object Cmo3Author {
	/**
	 * The fileFormatVersion a fresh document writes.
	 *
	 * CMO3: root attribute fileFormatVersion (BareMinimum.cmo3, official editor 5.4 New -> Save
	 * As).  Pinned to the 5.4 era because the prologue tables (Cmo3PiTables) are transcribed from
	 * the 504000000 corpus samples; changing one without the other would author a mismatched
	 * prologue.
	 */
	public const val FRESH_FILE_FORMAT_VERSION: String = "504000000"

	/**
	 * Serializes [root] as a complete main.xml document with the 5.4-era prologue.
	 *
	 * The `<?import?>` list is derived from the document itself: one FQCN per distinct
	 * non-structural element tag actually emitted, sorted - exactly the rule every corpus file
	 * follows.  An element tag with no FQCN table entry is a hard error: it means an unregistered
	 * or newly modeled class whose import the official editor's reader would miss.
	 *
	 * @param Any root The fresh model root (a CModelSource web built in memory, never read).
	 * @return ByteArray The UTF-8 main.xml bytes, CRLF-framed like the editor's writer.
	 */
	public fun writeFreshMainXml(root: Any): ByteArray {
		val document = cubismEngine().writeRoot(root)
		val tags = HashSet<String>()
		collectTags(document.rootElement, tags)
		val modelTags = tags - CMO3_STRUCTURAL_TAGS
		val imports =
			modelTags
				.map { tag ->
					CMO3_TAG_TO_FQCN[tag]
						?: error("element tag <$tag> has no FQCN table entry; add it to Cmo3PiTables from a corpus sample")
				}
				.sorted()
		// CMO3: <?version Name:N?> PIs - the always-written pair, then per-tag entries in a
		// deterministic (sorted-tag) order; the editor's own order is HashMap iteration and not
		// reproducible, and readers key these by name.
		val versions = ArrayList(CMO3_VERSIONS_ALWAYS_5_4)
		for (tag in modelTags.sorted()) {
			CMO3_VERSIONS_BY_TAG_5_4[tag]?.let { versionEntry -> versions.add(versionEntry) }
		}
		// CMO3: root attribute fileFormatVersion - the root's only attribute in every corpus file.
		document.rootElement.setAttribute("fileFormatVersion", FRESH_FILE_FORMAT_VERSION)
		var instructionIndex = 0
		for ((piName, versionNumber) in versions) {
			document.addContent(instructionIndex++, ProcessingInstruction("version", "$piName:$versionNumber"))
		}
		for (fqcn in imports) {
			document.addContent(instructionIndex++, ProcessingInstruction("import", fqcn))
		}
		return XmlCodec.write(document)
	}

	/**
	 * Reconciles a written document's prologue PIs with the tags it actually contains.
	 *
	 * `Cmo3.write` replays the prologue recorded at read time, which is complete for an unedited
	 * graph but misses any class first introduced AFTER the read - a session-created glue in a
	 * glue-less document, or the whole created population of a fresh-graph (MOC3-origin) export.
	 * The official reader resolves element tags through the `<?import?>` list, so a missing import
	 * makes the file unreadable in the editor.  This pass restores the corpus invariant: the
	 * import list is exactly one sorted FQCN per distinct non-structural tag (an import whose tag
	 * left the document is pruned; corpus files never carry stale imports).  Version PIs are only
	 * ever APPENDED (5.4-era documents only - the per-tag numbers are era-specific), never pruned:
	 * corpus files do carry version PIs for classes with no matching element (BareMinimum's
	 * ModelStateSet:1), so pruning would break replay byte-identity.
	 *
	 * A document whose prologue is already complete is left untouched, keeping the unedited
	 * round trip byte-identical.
	 *
	 * @param Document document The written model document (mutated in place).
	 */
	public fun completePrologue(document: Document) {
		val tags = HashSet<String>()
		collectTags(document.rootElement, tags)
		val modelTags = tags - CMO3_STRUCTURAL_TAGS

		val instructions = document.content.filterIsInstance<ProcessingInstruction>()
		val versionInstructions = instructions.filter { it.target == "version" }
		val importInstructions = instructions.filter { it.target == "import" }
		val replayedImports = importInstructions.map { it.data.trim() }
		val replayedFqcnByTag = replayedImports.associateBy { fqcn -> fqcn.substringAfterLast('.').substringAfterLast('$') }
		val desiredImports =
			modelTags
				.map { tag ->
					// The document's own spelling wins - the 5.4 table only fills tags the read
					// never saw, so an older era's package layout is never rewritten.
					replayedFqcnByTag[tag]
						?: CMO3_TAG_TO_FQCN[tag]
						?: error("element tag <$tag> has no FQCN table entry and no replayed import; add it to Cmo3PiTables from a corpus sample")
				}
				.sorted()

		val replayedVersionNames = versionInstructions.map { it.data.trim().substringBeforeLast(':') }.toHashSet()
		val fileFormatVersion = document.rootElement.getAttributeValue("fileFormatVersion")
		val appendedVersions =
			if (fileFormatVersion == FRESH_FILE_FORMAT_VERSION) {
				modelTags.sorted().mapNotNull { tag ->
					CMO3_VERSIONS_BY_TAG_5_4[tag]?.takeIf { (piName, _) -> piName !in replayedVersionNames }
				}
			} else {
				emptyList()
			}

		if (desiredImports == replayedImports && appendedVersions.isEmpty()) {
			return
		}
		for (instruction in importInstructions) {
			document.removeContent(instruction)
		}
		var instructionIndex =
			versionInstructions.lastOrNull()?.let { instruction -> document.indexOf(instruction) + 1 } ?: 0
		for ((piName, versionNumber) in appendedVersions) {
			document.addContent(instructionIndex++, ProcessingInstruction("version", "$piName:$versionNumber"))
		}
		for (fqcn in desiredImports) {
			document.addContent(instructionIndex++, ProcessingInstruction("import", fqcn))
		}
	}

	/**
	 * Collects every element name in the subtree into [into].
	 *
	 * @param Element element The subtree root.
	 * @param HashSet into The tag collector.
	 */
	private fun collectTags(element: Element, into: HashSet<String>) {
		into.add(element.name)
		for (child in element.children) {
			collectTags(child as Element, into)
		}
	}
}