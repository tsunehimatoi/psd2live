package org.umamo.format.cmo3.serialize

import org.jdom.Element
import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLabelColor
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CRotationDeformerForm
import org.umamo.format.cmo3.model.custom.CSize
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.custom.FilterInstance
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.custom.RotationDeformerOriginalShape
import org.umamo.format.cmo3.model.custom.WarpDeformerOriginalShape
import org.umamo.format.cmo3.model.type.FileRef

/**
 * Serializer for the `<file xs.n="…" path="…"/>` element. Mirrors the editor's writeFile: the path
 * names a de-duplicated CAFF entry; the bytes are managed by the container, not the XML.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container / §3 Payload</a>
 */
internal object FileSerializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val element = Element("file")
		element.setFieldName(name)
		val fileRef = value as FileRef
		if (fileRef.archivePath != null) {
			element.setAttribute("path", fileRef.archivePath)
		} else {
			element.text = fileRef.textPath ?: ""
		}
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val path = element.getAttributeValue("path")
		return FileRef().apply {
			if (path != null) archivePath = path else textPath = element.text
		}
	}
}

/**
 * Registers the hand-written custom subsystem: the `<file>` element plus the attribute-serialized
 * leaf/value classes (modelled reflectively with @SerialAttribute), including the CModelSource root.
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerCustomSubsystem(registry: SerializerRegistry) {
	registry.registerCustomTag("file", FileRef::class, FileSerializer)
	registry.register(CFloatColor::class)
	registry.register(CLabelColor::class)
	registry.register(WarpDeformerOriginalShape::class)
	registry.register(RotationDeformerOriginalShape::class)
	registry.register(CSize::class)
	registry.register(CRotationDeformerForm::class)
	registry.register(CWritableImage::class)
	registry.register(CImageResource::class)
	registry.register(FilterInstance::class)
	registry.register(CModelImage::class)
	registry.register(GEditableMesh2::class)
	registry.register(CLayer::class)
	registry.register(CModelSource::class)
}