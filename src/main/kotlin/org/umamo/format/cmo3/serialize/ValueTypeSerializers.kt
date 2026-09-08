package org.umamo.format.cmo3.serialize

import org.jdom.Element
import org.umamo.format.cmo3.model.type.CAffine

/**
 * Custom serializer for CAffine: `<CAffine m00="…" m01="…" m02="…" m10="…" m11="…" m12="…" />`.
 * The six matrix floats are written/read as attributes.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal object CAffineSerializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val affine = value as CAffine
		val element = Element("CAffine")
		element.setFieldName(name)
		element.setAttribute("m00", affine.m00.toString())
		element.setAttribute("m01", affine.m01.toString())
		element.setAttribute("m02", affine.m02.toString())
		element.setAttribute("m10", affine.m10.toString())
		element.setAttribute("m11", affine.m11.toString())
		element.setAttribute("m12", affine.m12.toString())
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any =
		CAffine().apply {
			m00 = element.getAttributeValue("m00").toFloat()
			m01 = element.getAttributeValue("m01").toFloat()
			m02 = element.getAttributeValue("m02").toFloat()
			m10 = element.getAttributeValue("m10").toFloat()
			m11 = element.getAttributeValue("m11").toFloat()
			m12 = element.getAttributeValue("m12").toFloat()
		}
}

/**
 * Registers the value-type subsystem (GVector2, CRect, CColor reflective; CAffine custom).
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerValueTypeSubsystem(registry: SerializerRegistry) {
	registry.register(org.umamo.format.cmo3.model.type.GVector2::class)
	registry.register(org.umamo.format.cmo3.model.type.CRect::class)
	registry.register(org.umamo.format.cmo3.model.type.CColor::class)
	registry.registerCustom(CAffine::class, CAffineSerializer)
}