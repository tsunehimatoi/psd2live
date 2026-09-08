package org.umamo.format.cmo3.model.type

/**
 * A `<file>` element, which the editor writes in two distinct shapes under the same tag:
 *  - `<file path="entry.png"/>` - an embedded CAFF entry; [archivePath] set.
 *  - `<file>C:\path\to.psd</file>` - a java.io.File path as text content (the File serializer);
 *    [textPath] set.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container / §3 Payload</a>
 */
public class FileRef {
	/** Set when the file is an embedded archive entry (the `path` attribute). */
	public var archivePath: String? = null

	/** Set when the file is a plain filesystem path stored as element text. */
	public var textPath: String? = null
}