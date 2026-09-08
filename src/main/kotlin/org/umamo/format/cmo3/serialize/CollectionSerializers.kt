package org.umamo.format.cmo3.serialize

import org.jdom.Element

internal const val ATTR_ENUM_VALUE: String = "v"
internal const val ATTR_KEY_TYPE: String = "keyType"
internal const val TAG_ENTRY: String = "entry"
internal const val NAME_KEY: String = "key"
internal const val NAME_VALUE: String = "value"
private const val ARRAY_SEPARATOR: String = " "

/**
 * Serializer for a Kotlin enum class: `<EnumTag xs.n="field" v="CONSTANT" />`.
 * The enum constant name is stored in attribute `v`.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
internal class EnumSerializer(private val tag: String, private val enumClass: Class<*>) : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val element = Element(tag)
		element.setFieldName(name)
		element.setAttribute(ATTR_ENUM_VALUE, (value as Enum<*>).name)
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val constantName = element.getAttributeValue(ATTR_ENUM_VALUE)
		// Class.getEnumConstants() is @Nullable in Java (null for non-enum classes), so Kotlin types it
		// Array<...>?. This serializer is only ever constructed for an enum class, so the array is
		// non-null by construction - assert it with !! rather than threading a spurious null path.
		return enumClass.enumConstants!!.first { (it as Enum<*>).name == constantName }
	}
}

/**
 * Serializer for a primitive typed array: `<tag count="n">v0 v1 v2 ...</tag>` (space-joined text).
 * Values are space-separated in the element text.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
internal class ArraySerializer(
	private val tag: String,
	private val size: (Any) -> Int,
	private val encode: (Any) -> String,
	private val decode: (List<String>) -> Any,
) : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val element = Element(tag)
		element.setFieldName(name)
		element.setAttribute(ATTR_COUNT, size(value).toString())
		element.text = encode(value)
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val text = element.text.trim()
		val tokens = if (text.isEmpty()) emptyList() else text.split(ARRAY_SEPARATOR)
		return decode(tokens)
	}

	internal companion object {
		/** Joins a typed array's elements with the array separator. */
		fun join(values: Sequence<Any?>): String = values.joinToString(ARRAY_SEPARATOR)
	}
}

/**
 * Serializer for sets: `<tag count="n" xs.n="field">` with positional item children (no xs.n).
 * Covers LinkedHashSet ("linked_set"). Reads into the [factory] type to preserve order.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
internal class SetSerializer(private val tag: String, private val factory: () -> MutableSet<Any?>) : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val set = value as Set<*>
		val element = Element(tag)
		element.setFieldName(name)
		element.setAttribute(ATTR_COUNT, set.size.toString())
		for (item in set) element.addContent(ctx.createElementFromObject(null, item))
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val out = factory()
		@Suppress("UNCHECKED_CAST")
		for (child in element.children as List<Element>) out.add(ctx.createObjectFromElement(child))
		return out
	}
}

/**
 * Serializer for maps: `<tag count="n" [keyType="string"]>` with `<entry>` children, each holding a
 * `key` and `value` child. Covers HashMap ("hash_map") and LinkedHashMap ("linked_map").
 *
 * EN: Reads into a LinkedHashMap so entry order is preserved for faithful re-emit.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
internal class MapSerializer(
	private val tag: String,
	private val factory: () -> MutableMap<Any?, Any?>,
) : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val map = value as Map<*, *>
		val element = Element(tag)
		element.setFieldName(name)
		element.setAttribute(ATTR_COUNT, map.size.toString())
		// CMO3: string-keyed maps carry keyType="string" and store each value as <Value xs.n="key">;
		// object-keyed maps omit keyType and wrap each pair in <entry><key/><value/></entry>.
		val stringKeyed = map.isEmpty() || map.keys.all { it is String }
		if (stringKeyed) {
			element.setAttribute(ATTR_KEY_TYPE, "string")
			for ((key, mapValue) in map) element.addContent(ctx.createElementFromObject(key as String?, mapValue))
		} else {
			for ((key, mapValue) in map) {
				val entry = Element(TAG_ENTRY)
				entry.addContent(ctx.createElementFromObject(NAME_KEY, key))
				entry.addContent(ctx.createElementFromObject(NAME_VALUE, mapValue))
				element.addContent(entry)
			}
		}
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any {
		val out = factory()

		@Suppress("UNCHECKED_CAST")
		val children = element.children as List<Element>
		if (element.getAttributeValue(ATTR_KEY_TYPE) == "string") {
			for (valueElement in children) {
				val key = valueElement.getAttributeValue(ATTR_NAME)
				out[key] = ctx.createObjectFromElement(valueElement)
			}
		} else {
			for (entry in children) {
				if (entry.name != TAG_ENTRY) continue
				@Suppress("UNCHECKED_CAST")
				val parts = entry.children as List<Element>
				val keyElement = parts.firstOrNull { it.getAttributeValue(ATTR_NAME) == NAME_KEY }
				val valueElement = parts.firstOrNull { it.getAttributeValue(ATTR_NAME) == NAME_VALUE }
				out[keyElement?.let { ctx.createObjectFromElement(it) }] =
					valueElement?.let { ctx.createObjectFromElement(it) }
			}
		}
		return out
	}
}