package org.umamo.format.cmo3.serialize

import org.jdom.Document
import org.jdom.Element
import org.jdom.ProcessingInstruction
import org.umamo.format.cmo3.serialize.annotations.SerialTag
import java.util.IdentityHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Resolves element tags <-> classes and owns the serializer instances (primitives, collections, and
 * lazily-built reflective class serializers).
 */
internal class SerializerRegistry {
	private val tagToClass = HashMap<String, KClass<*>>()
	private val classToTag = HashMap<KClass<*>, String>()
	private val classSerializerCache = HashMap<KClass<*>, ReflectiveClassSerializer>()

	private val intSerializer = PrimitiveSerializer("i") { it.toInt() }
	private val floatSerializer = PrimitiveSerializer("f") { it.toFloat() }
	private val doubleSerializer = PrimitiveSerializer("d") { it.toDouble() }
	private val longSerializer = PrimitiveSerializer("l") { it.toLong() }
	private val shortSerializer = PrimitiveSerializer("short") { it.toShort() }
	private val byteSerializer = PrimitiveSerializer("byte") { it.toByte() }
	private val charSerializer = PrimitiveSerializer("char") { it[0] }
	private val boolSerializer = PrimitiveSerializer("b") { it == "true" }
	private val stringSerializer = PrimitiveSerializer("s") { it }
	private val arrayListSerializer = ListSerializer("array_list") { ArrayList() }
	private val carrayListSerializer = ListSerializer("carray_list") { org.umamo.format.cmo3.type.CArrayList() }

	// hash_map reads into an order-preserving CHashMap so non-empty maps re-emit in document order.
	private val hashMapSerializer = MapSerializer("hash_map") { org.umamo.format.cmo3.type.CHashMap() }
	private val linkedMapSerializer = MapSerializer("linked_map") { LinkedHashMap() }
	private val linkedSetSerializer = SetSerializer("linked_set") { LinkedHashSet() }

	private val floatArraySerializer =
		ArraySerializer(
			"float-array",
			{ (it as FloatArray).size },
			{ (it as FloatArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> FloatArray(tokens.size) { tokens[it].toFloat() } },
		)
	private val intArraySerializer =
		ArraySerializer(
			"int-array",
			{ (it as IntArray).size },
			{ (it as IntArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> IntArray(tokens.size) { tokens[it].toInt() } },
		)
	private val longArraySerializer =
		ArraySerializer(
			"long-array",
			{ (it as LongArray).size },
			{ (it as LongArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> LongArray(tokens.size) { tokens[it].toLong() } },
		)
	private val shortArraySerializer =
		ArraySerializer(
			"short-array",
			{ (it as ShortArray).size },
			{ (it as ShortArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> ShortArray(tokens.size) { tokens[it].toShort() } },
		)
	private val doubleArraySerializer =
		ArraySerializer(
			"double-array",
			{ (it as DoubleArray).size },
			{ (it as DoubleArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> DoubleArray(tokens.size) { tokens[it].toDouble() } },
		)
	private val byteArraySerializer =
		ArraySerializer(
			"byte-array",
			{ (it as ByteArray).size },
			{ (it as ByteArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> ByteArray(tokens.size) { tokens[it].toByte() } },
		)
	private val charArraySerializer =
		ArraySerializer(
			"char-array",
			{ (it as CharArray).size },
			{ (it as CharArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> CharArray(tokens.size) { tokens[it][0] } },
		)
	private val boolArraySerializer =
		ArraySerializer(
			"bool-array",
			{ (it as BooleanArray).size },
			{ (it as BooleanArray).joinToString(" ") { value -> value.toString() } },
			{ tokens -> BooleanArray(tokens.size) { tokens[it] == "true" } },
		)

	private val enumSerializerCache = HashMap<KClass<*>, EnumSerializer>()
	private val customByClass = HashMap<KClass<*>, XmlSerializer>()
	private val customByTag = HashMap<String, XmlSerializer>()

	/** Registers a model class so its tag resolves on read and its version PI can be emitted. */
	fun register(kClass: KClass<*>) {
		val tag = tagFor(kClass)
		tagToClass[tag] = kClass
		classToTag[kClass] = tag
	}

	/** Registers a class with a hand-written (e.g. attribute-based) serializer, e.g. value-types. */
	fun registerCustom(kClass: KClass<*>, serializer: XmlSerializer) {
		register(kClass)
		customByClass[kClass] = serializer
		customByTag[classToTag.getValue(kClass)] = serializer
	}

	/**
	 * Registers a custom serializer for an explicit [tag] backed by [kClass]. Lets one class cover
	 * many tags (e.g. a single Guid type carrying its kind for all *Guid tags); the serializer must
	 * derive the element tag from the value on write.
	 *
	 * @param String       tag        The element tag to handle.
	 * @param KClass       kClass     The backing model class (shared across tags).
	 * @param XmlSerializer serializer The hand-written serializer.
	 */
	fun registerCustomTag(tag: String, kClass: KClass<*>, serializer: XmlSerializer) {
		tagToClass[tag] = kClass
		classToTag.putIfAbsent(kClass, tag)
		customByClass[kClass] = serializer
		customByTag[tag] = serializer
	}

	private fun enumSerializerFor(enumClass: Class<*>): EnumSerializer {
		val kClass = enumClass.kotlin
		return enumSerializerCache.getOrPut(kClass) { EnumSerializer(tagFor(kClass), enumClass) }
	}

	private fun enumClassOf(value: Enum<*>): Class<*> =
		value.javaClass.let { if (it.isEnum) it else it.superclass }

	/** The element tag for [kClass]: its @SerialTag, else its simple name. */
	fun tagFor(kClass: KClass<*>): String =
		classToTag[kClass] ?: (
			kClass.findAnnotation<SerialTag>()?.tag ?: kClass.simpleName
				?: error("anonymous class cannot be serialized: $kClass")
		)

	/** The class registered under [tag], or null if unknown (caller may fall back to verbatim). */
	fun classForTag(tag: String): KClass<*>? = tagToClass[tag]

	/** Cached reflective serializer for [kClass]. */
	fun classSerializer(kClass: KClass<*>): ReflectiveClassSerializer =
		classSerializerCache.getOrPut(kClass) { ReflectiveClassSerializer(kClass, this) }

	/** Serializer for a runtime value, dispatched by type (write path). */
	fun serializerForValue(value: Any): XmlSerializer {
		customByClass[value::class]?.let { return it }
		return when (value) {
			is Enum<*> -> enumSerializerFor(enumClassOf(value))
			is Int -> intSerializer
			is Float -> floatSerializer
			is Double -> doubleSerializer
			is Long -> longSerializer
			is Short -> shortSerializer
			is Byte -> byteSerializer
			is Char -> charSerializer
			is Boolean -> boolSerializer
			is String -> stringSerializer
			is FloatArray -> floatArraySerializer
			is IntArray -> intArraySerializer
			is LongArray -> longArraySerializer
			is ShortArray -> shortArraySerializer
			is DoubleArray -> doubleArraySerializer
			is ByteArray -> byteArraySerializer
			is CharArray -> charArraySerializer
			is BooleanArray -> boolArraySerializer
			// Order matters: subtypes before supertypes so the on-disk tag round-trips exactly.
			is org.umamo.format.cmo3.type.CHashMap<*, *> -> hashMapSerializer
			is LinkedHashMap<*, *> -> linkedMapSerializer
			is Map<*, *> -> hashMapSerializer
			is org.umamo.format.cmo3.type.CArrayList<*> -> carrayListSerializer
			is List<*> -> arrayListSerializer
			is Set<*> -> linkedSetSerializer
			else -> classSerializer(value::class)
		}
	}

	/** Serializer for an element tag (read path), or null if the tag is unknown. */
	fun serializerForTag(tag: String): XmlSerializer? {
		customByTag[tag]?.let { return it }
		return when (tag) {
			"i" -> intSerializer
			"f" -> floatSerializer
			"d" -> doubleSerializer
			"l" -> longSerializer
			"short" -> shortSerializer
			"byte" -> byteSerializer
			"char" -> charSerializer
			"b" -> boolSerializer
			"s" -> stringSerializer
			"array_list" -> arrayListSerializer
			"carray_list" -> carrayListSerializer
			"hash_map" -> hashMapSerializer
			"linked_map" -> linkedMapSerializer
			"linked_set" -> linkedSetSerializer
			"float-array" -> floatArraySerializer
			"int-array" -> intArraySerializer
			"long-array" -> longArraySerializer
			"short-array" -> shortArraySerializer
			"double-array" -> doubleArraySerializer
			"byte-array" -> byteArraySerializer
			"char-array" -> charArraySerializer
			"bool-array" -> boolArraySerializer
			else ->
				classForTag(tag)?.let { kClass ->
					if (kClass.java.isEnum) enumSerializerFor(kClass.java) else classSerializer(kClass)
				}
		}
	}

	/** Serializer for the serializable superclass of [kClass], or null. Never instantiated directly. */
	fun superSerializerOf(kClass: KClass<*>, suppress: Boolean): ReflectiveClassSerializer? {
		if (suppress) return null
		val superJava = kClass.java.superclass ?: return null
		if (superJava == Any::class.java || superJava.isInterface) return null
		return classSerializer(superJava.kotlin)
	}

	/** Registered (tag -> version) pairs for classes that declare a @SerialTag version. */
	fun versions(): List<Pair<String, Int>> =
		classToTag.entries.mapNotNull { (kClass, tag) ->
			kClass.findAnnotation<SerialTag>()?.version?.takeIf { it >= 0 }?.let { tag to it }
		}
}

/** Identity record for an object already written, with its element and global write order. */
internal class WrittenRecord(val element: Element, val index: Int)

/** A shared (referenced >= 2x) object's definition element, its xs.id, and write order (xs.idx). */
internal class SharedDef(val element: Element, val refId: String, val index: Int)

/**
 * A shared object's preserved identity (xs.id), pool index (xs.idx), and exact def tag, captured at
 * read time. The [tag] is reused verbatim for references so it always matches the def - even where
 * one class covers many tags (Guid/Id) or the tag differs from the class name (float-array, etc.).
 */
internal class SharedRef(val id: String, val index: Int, val tag: String)

/**
 * One child slot of a reflective object, captured on read so the exact child sequence can be replayed
 * on write. This makes typed classes tolerant of format evolution (added, removed, or reordered
 * fields) across schema versions while staying byte-identical: a known field is re-serialized from the
 * typed value, an unrecognised one is re-emitted verbatim, and the superclass keeps its position.
 */
internal sealed interface ChildSlot {
	/** A field the serializer recognised, replayed from the typed property [propertyName]. */
	class KnownField(val propertyName: String) : ChildSlot

	/** A child newer than (or unknown to) our schema, replayed verbatim. */
	class VerbatimChild(val element: Element) : ChildSlot

	/** The `<super>` element (superclass fields), replayed via the super serializer. */
	object Super : ChildSlot
}

/**
 * The first fresh ref id not colliding with a preserved pool id ("#N" numeric ids only; the pool's
 * non-numeric ids can never collide with the "#counter" form fresh ids take).
 *
 * @param IdentityHashMap? preassigned The preserved shared identities, or null outside reconcile mode.
 * @return Int The starting counter value.
 */
private fun nextFreshRefId(preassigned: IdentityHashMap<Any, SharedRef>?): Int {
	val maxPreserved =
		preassigned?.values?.maxOfOrNull { sharedRef ->
			sharedRef.id.removePrefix("#").toIntOrNull() ?: -1
		} ?: -1
	return maxPreserved + 1
}

/**
 * Mutable write-side state: object-identity tracking and the shared pool (written-object map,
 * shared list, ref-id and write-index counters).
 *
 * EN: In reconcile mode ([preassignedShared] != null) objects that were shared on read are emitted
 *     as xs.ref using their preserved id - so a round-trip stays byte-identical even when only some
 *     classes are typed. Any other object referenced more than once - fresh or newly-shared, in or
 *     out of reconcile mode - goes through the same hoist-on-second-use path, with fresh ids
 *     continuing past the preserved maximum so they never collide with one.
 */
internal class WriteContext(
	private val registry: SerializerRegistry,
	private val preassignedShared: IdentityHashMap<Any, SharedRef>? = null,
	private val preservedChildOrder: IdentityHashMap<Any, MutableMap<String, MutableList<ChildSlot>>>? = null,
	private val preservedPresentAttrs: IdentityHashMap<Any, MutableMap<String, MutableSet<String>>>? = null,
) {
	private val written = IdentityHashMap<Any, WrittenRecord>()
	val sharedDefs = ArrayList<SharedDef>()

	// Fresh ids continue past the preserved pool's numeric ids: the reader keys references by id,
	// so a new shared object reusing an id already bound by the source document would hijack every
	// later xs.ref to that id.
	private var refIdCounter = nextFreshRefId(preassignedShared)
	private var writtenIndexCounter = 0

	/** Records [value]'s element on first write (subsequent calls are no-ops, matching the editor). */
	fun setWritten(value: Any, element: Element) {
		if (!written.containsKey(value)) written[value] = WrittenRecord(element, writtenIndexCounter++)
	}

	/**
	 * The child sequence read for [owner] at the [tag] level, or null for a freshly constructed object.
	 * When non-null it is replayed verbatim (order, presence and unknown children all preserved), so a
	 * round-trip reproduces the source exactly - independent of schema version.
	 */
	fun childOrderFor(owner: Any, tag: String): List<ChildSlot>? = preservedChildOrder?.get(owner)?.get(tag)

	/** The attributes present when [owner] was read at the [tag] level, or null for a fresh object. */
	fun presentAttrsFor(owner: Any, tag: String): Set<String>? = preservedPresentAttrs?.get(owner)?.get(tag)

	/**
	 * Builds the element for a field/item value: `<null>` for null, an xs.ref for an already-written
	 * object (hoisting its def into the shared pool on first reuse), else delegates to the serializer.
	 *
	 * @param String? name  The owning field name (xs.n), or null for collection items.
	 * @param Any?    value The value to serialize.
	 * @return Element The element (def, reference, or null marker).
	 */
	fun createElementFromObject(name: String?, value: Any?): Element {
		if (value == null) {
			val nullElement = Element(TAG_NULL)
			nullElement.setFieldName(name)
			return nullElement
		}
		// Reconcile mode: a known-shared object is always emitted as a reference to its preserved id,
		// reusing the def's exact tag captured at read time.
		preassignedShared?.get(value)?.let { sharedRef ->
			val reference = Element(sharedRef.tag)
			reference.setFieldName(name)
			reference.setAttribute(ATTR_REF, sharedRef.id)
			return reference
		}
		if (value is VerbatimNode) {
			// Re-emit the preserved subtree, re-stamping the owning field name (or dropping it for items).
			val clone = value.element.clone() as Element
			if (name != null) clone.setAttribute(ATTR_NAME, name) else clone.removeAttribute(ATTR_NAME)
			return clone
		}
		written[value]?.let { record ->
			var id = record.element.getAttributeValue(ATTR_ID)
			if (id == null) {
				id = "#${refIdCounter++}"
				record.element.setAttribute(ATTR_ID, id)
				sharedDefs.add(SharedDef(record.element, id, record.index))
			}
			val reference = Element(record.element.name)
			reference.setFieldName(name)
			reference.setAttribute(ATTR_REF, id)
			return reference
		}
		return registry.serializerForValue(value).createElement(name, value, this)
	}
}

/** Mutable read-side state: the xs.id -> instance map used to resolve xs.ref. */
internal class ReadContext(
	private val registry: SerializerRegistry,
	private val diagnostics: SerializeDiagnostics,
) {
	val references = HashMap<String, Any>()

	/** The ordered child sequence read for each object, keyed by owner then serializer tag (level). */
	val childOrder = IdentityHashMap<Any, MutableMap<String, MutableList<ChildSlot>>>()

	/** Names of the attributes actually present on read, keyed by owner then serializer tag. */
	val presentAttrs = IdentityHashMap<Any, MutableMap<String, MutableSet<String>>>()

	private fun slots(owner: Any, tag: String) =
		childOrder.getOrPut(owner) { HashMap() }.getOrPut(tag) { ArrayList() }

	/** Records, in document order, a recognised child field of [owner] at the [tag] level. */
	fun recordKnownChild(owner: Any, tag: String, propertyName: String) =
		slots(owner, tag).add(ChildSlot.KnownField(propertyName))

	/** Records, in document order, a child of [owner] the [tag] serializer did not recognise. */
	fun recordVerbatimChild(owner: Any, tag: String, element: Element) =
		slots(owner, tag).add(ChildSlot.VerbatimChild(element.clone() as Element))

	/** Records, in document order, the position of the `<super>` element of [owner] at the [tag] level. */
	fun recordSuperChild(owner: Any, tag: String) = slots(owner, tag).add(ChildSlot.Super)

	/** Records that attribute [attrName] of [owner] (at the [tag] level) was present in the source. */
	fun recordPresentAttr(owner: Any, tag: String, attrName: String) {
		presentAttrs.getOrPut(owner) { HashMap() }.getOrPut(tag) { HashSet() }.add(attrName)
	}

	/**
	 * Materialises the value for [element]: null marker, a resolved xs.ref, or a freshly
	 * created+populated value via the tag's serializer.
	 *
	 * @param Element element The source element.
	 * @return Any? The created value.
	 */
	fun createObjectFromElement(element: Element): Any? {
		val tag = element.name
		if (tag == TAG_NULL) return null
		element.getAttributeValue(ATTR_REF)?.let { return references[it] }
		val serializer = registry.serializerForTag(tag)
		if (serializer == null) {
			// Unmodeled tag: keep verbatim so the document still round-trips, and report it.
			diagnostics.onUnmodeledTag(tag, element.getAttributeValue(ATTR_NAME) ?: "(item)")
			return VerbatimNode(element.clone() as Element)
		}
		// A typed serializer may still fail on evolved/unknown content (e.g. a newer enum constant,
		// an added field, a missing no-arg constructor). Fall back to verbatim + report, so format
		// evolution never crashes or loses data - it just isn't typed yet.
		return try {
			val instance = serializer.createInstance(element, this) ?: return null
			element.getAttributeValue(ATTR_ID)?.let { references[it] = instance }
			serializer.setupInstance(element, instance, this)
			instance
		} catch (failure: Exception) {
			diagnostics.onUnmodeledTag(tag, "deserialize-failed: ${failure.message}")
			VerbatimNode(element.clone() as Element)
		}
	}
}

/**
 * A deserialized model with the metadata needed to re-emit it byte-identically: the main root
 * object, the shared pool in document order (with preserved xs.id/xs.idx), the processing
 * instructions, and the `<root>` attributes.
 *
 * EN: [root] (and the shared objects) are typed where a serializer is registered, else [VerbatimNode].
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public class ModelGraph internal constructor(
	/** The `<main>` root object (e.g. CModelSource), typed or verbatim. */
	public val root: Any?,
	internal val sharedOrder: MutableList<Any>,
	internal val sharedInfo: IdentityHashMap<Any, SharedRef>,
	internal val processingInstructions: List<ProcessingInstruction>,
	internal val rootAttributes: List<Pair<String, String>>,
	internal val childOrder: IdentityHashMap<Any, MutableMap<String, MutableList<ChildSlot>>>,
	internal val presentAttrs: IdentityHashMap<Any, MutableMap<String, MutableSet<String>>>,
) {
	/**
	 * Ensures the replayed child order for [owner] at the [tag] level contains a KnownField slot for
	 * [propertyName], so a field assigned after read reaches the re-emitted document even when the
	 * source carried no such element (the writer replays recorded slots and would otherwise drop the
	 * assignment silently).  Amends only an EXISTING recorded order: an owner without one already
	 * emits every declared field, and creating an order here would collapse its output to this
	 * single slot.  The new slot lands immediately before [beforePropertyName]'s slot when that
	 * anchor exists (matching where the editor writes the field), else at the end.
	 *
	 * @param Any     owner              The object whose child order is amended.
	 * @param String  tag                The serializer tag level the order was recorded under.
	 * @param String  propertyName       The typed property the slot replays.
	 * @param String? beforePropertyName The anchor property to insert before, or null to append.
	 */
	internal fun ensureKnownChildSlot(owner: Any, tag: String, propertyName: String, beforePropertyName: String?) {
		val slots = childOrder[owner]?.get(tag) ?: return
		val alreadyRecorded = slots.any { slot -> slot is ChildSlot.KnownField && slot.propertyName == propertyName }
		if (alreadyRecorded) {
			return
		}
		val newSlot = ChildSlot.KnownField(propertyName)
		val anchorIndex =
			beforePropertyName?.let { anchor ->
				slots.indexOfFirst { slot -> slot is ChildSlot.KnownField && slot.propertyName == anchor }
			} ?: -1
		if (anchorIndex >= 0) {
			slots.add(anchorIndex, newSlot)
		} else {
			slots.add(newSlot)
		}
	}

	/**
	 * Ensures the @SerialAttribute property [propertyName] of [owner] (at the [tag] level) is emitted
	 * on write even when the source document omitted the attribute.  The writer emits exactly the
	 * attributes recorded as present at read time, so an attribute assigned after read would otherwise
	 * be dropped silently.  Amends only an EXISTING record: an owner with no recorded attributes at
	 * [tag] already falls back to emitting every non-default attribute value, and a fresh object never
	 * has a record.  Attributes emit in declaration order regardless of record order, so no anchor is
	 * needed.
	 *
	 * @param Any    owner        The object whose attribute record is amended.
	 * @param String tag          The serializer tag level the record was captured under.
	 * @param String propertyName The Kotlin property name of the attribute to emit.
	 */
	internal fun ensurePresentAttr(owner: Any, tag: String, propertyName: String) {
		presentAttrs[owner]?.get(tag)?.add(propertyName)
	}
}

/**
 * Top-level model (de)serializer: turns a typed root object into a `<root><shared/><main/></root>`
 * JDOM document (and back), reproducing the editor's shared-pool hoisting and `<?version?>` PIs.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public class SerializeEngine internal constructor(
	private val registry: SerializerRegistry,
	private val diagnostics: SerializeDiagnostics,
) {
	/**
	 * Serializes [root] into a model document.
	 *
	 * @param Any root The model root object (e.g. CModelSource).
	 * @return Document The `<root>` document with shared pool and version PIs.
	 */
	public fun writeRoot(root: Any): Document {
		val context = WriteContext(registry)
		val rootElement = Element(ModelDocument.ROOT_ELEMENT)
		val sharedElement = Element(ModelDocument.SHARED_ELEMENT)
		val mainElement = Element(ModelDocument.MAIN_ELEMENT)
		rootElement.addContent(sharedElement)
		rootElement.addContent(mainElement)

		mainElement.addContent(context.createElementFromObject(null, root))

		// Hoist each shared def into <shared> and replace its first (inline) occurrence with a ref.
		for (def in context.sharedDefs.sortedBy { it.index }) {
			val defElement = def.element
			val parent = defElement.parent as Element
			val position = parent.indexOf(defElement)
			val reference = Element(defElement.name)
			defElement.getAttributeValue(ATTR_NAME)?.let { reference.setAttribute(ATTR_NAME, it) }
			reference.setAttribute(ATTR_REF, def.refId)
			parent.removeContent(defElement)
			parent.addContent(position, reference)
			defElement.removeAttribute(ATTR_NAME)
			defElement.setAttribute(ATTR_INDEX, def.index.toString())
			sharedElement.addContent(defElement)
		}

		val document = Document(rootElement)
		registry.versions().forEachIndexed { piIndex, (tag, version) ->
			document.addContent(piIndex, ProcessingInstruction("version", "$tag:$version"))
		}
		return document
	}

	/**
	 * Deserializes a model document's `<main>` root object, resolving the shared pool first.
	 *
	 * @param Document document The model document.
	 * @return Any? The reconstructed root object.
	 */
	public fun readRoot(document: Document): Any? {
		val rootElement = document.rootElement
		val context = ReadContext(registry, diagnostics)

		@Suppress("UNCHECKED_CAST")
		val sharedChildren = (rootElement.getChild(ModelDocument.SHARED_ELEMENT)?.children as List<Element>?).orEmpty()
		val created = ArrayList<Pair<Element, Any>>()
		for (element in sharedChildren) {
			val id = element.getAttributeValue(ATTR_ID) ?: continue
			val serializer = registry.serializerForTag(element.name) ?: continue
			val instance = serializer.createInstance(element, context) ?: continue
			context.references[id] = instance
			created.add(element to instance)
		}
		for ((element, instance) in created) {
			registry.serializerForTag(element.name)?.setupInstance(element, instance, context)
		}

		@Suppress("UNCHECKED_CAST")
		val mainChild =
			(rootElement.getChild(ModelDocument.MAIN_ELEMENT)?.children as List<Element>?)
				?.firstOrNull() ?: return null
		return context.createObjectFromElement(mainChild)
	}

	/**
	 * Reads a model document into a [ModelGraph]: the `<main>` root object plus the shared pool
	 * (preserving each shared object's xs.id/xs.idx and order), the processing instructions, and the
	 * `<root>` attributes - everything needed to re-emit byte-identically.
	 *
	 * @param Document document The model document.
	 * @return ModelGraph The reconstructed graph with preserved identity metadata.
	 */
	public fun readModel(document: Document): ModelGraph {
		val rootElement = document.rootElement
		val context = ReadContext(registry, diagnostics)
		val sharedOrder = ArrayList<Any>()
		val sharedInfo = IdentityHashMap<Any, SharedRef>()

		@Suppress("UNCHECKED_CAST")
		val sharedChildren = (rootElement.getChild(ModelDocument.SHARED_ELEMENT)?.children as List<Element>?).orEmpty()
		val typedToSetup = ArrayList<Pair<Element, Any>>()
		for (element in sharedChildren) {
			val id = element.getAttributeValue(ATTR_ID) ?: continue
			val index = element.getAttributeValue(ATTR_INDEX)?.toIntOrNull() ?: -1
			val serializer = registry.serializerForTag(element.name)
			val instance: Any =
				if (serializer == null) {
					diagnostics.onUnmodeledTag(element.name, "(shared)")
					VerbatimNode(element.clone() as Element)
				} else {
					serializer.createInstance(element, context) ?: continue
				}
			context.references[id] = instance
			sharedInfo[instance] = SharedRef(id, index, element.name)
			sharedOrder.add(instance)
			if (serializer != null) typedToSetup.add(element to instance)
		}
		for ((element, instance) in typedToSetup) {
			registry.serializerForTag(element.name)?.setupInstance(element, instance, context)
		}

		@Suppress("UNCHECKED_CAST")
		val mainChild = (rootElement.getChild(ModelDocument.MAIN_ELEMENT)?.children as List<Element>?)?.firstOrNull()
		val rootObject = mainChild?.let { context.createObjectFromElement(it) }

		val instructions =
			document.content.filterIsInstance<ProcessingInstruction>().map { it.clone() as ProcessingInstruction }

		@Suppress("UNCHECKED_CAST")
		val rootAttributes = (rootElement.attributes as List<org.jdom.Attribute>).map { it.name to it.value }
		return ModelGraph(
			rootObject,
			sharedOrder,
			sharedInfo,
			instructions,
			rootAttributes,
			context.childOrder,
			context.presentAttrs,
		)
	}

	/**
	 * Re-emits a [ModelGraph] to a document, reusing the preserved shared ids/order and PIs so an
	 * unedited graph round-trips byte-identical. Known-shared objects become xs.ref by identity.
	 * Fresh objects (added after read) referenced more than once are hoisted into the shared pool
	 * after the preserved entries, with ids and indexes continuing past the preserved maxima.
	 *
	 * @param ModelGraph graph The graph to serialize.
	 * @return Document The reconstructed model document.
	 */
	public fun writeModel(graph: ModelGraph): Document {
		val rootElement = Element(ModelDocument.ROOT_ELEMENT)
		for ((attrName, attrValue) in graph.rootAttributes) rootElement.setAttribute(attrName, attrValue)
		val sharedElement = Element(ModelDocument.SHARED_ELEMENT)
		val mainElement = Element(ModelDocument.MAIN_ELEMENT)
		rootElement.addContent(sharedElement)
		rootElement.addContent(mainElement)

		val context = WriteContext(registry, graph.sharedInfo, graph.childOrder, graph.presentAttrs)
		graph.root?.let { mainElement.addContent(context.createElementFromObject(null, it)) }

		for (instance in graph.sharedOrder) {
			val sharedRef = graph.sharedInfo.getValue(instance)
			val defElement: Element =
				if (instance is VerbatimNode) {
					instance.element.clone() as Element
				} else {
					val element = registry.serializerForValue(instance).createElement(null, instance, context)
					element.setAttribute(ATTR_ID, sharedRef.id)
					element.setAttribute(ATTR_INDEX, sharedRef.index.toString())
					element
				}
			sharedElement.addContent(defElement)
		}

		// Hoist fresh shared defs (objects added after read whose second use stamped an xs.id on the
		// first inline def) into <shared>, the same replace-with-ref move writeRoot performs.  This
		// runs after both the main and the preserved-pool emission so defs nested inside preserved
		// shared subtrees are caught too.  xs.idx continues past the preserved maximum, keeping fresh
		// entries clear of the source document's pool indexes.
		var nextSharedIndex = (graph.sharedInfo.values.maxOfOrNull { sharedRef -> sharedRef.index } ?: -1) + 1
		for (def in context.sharedDefs.sortedBy { it.index }) {
			val defElement = def.element
			val parent = defElement.parent as Element
			val position = parent.indexOf(defElement)
			val reference = Element(defElement.name)
			defElement.getAttributeValue(ATTR_NAME)?.let { reference.setAttribute(ATTR_NAME, it) }
			reference.setAttribute(ATTR_REF, def.refId)
			parent.removeContent(defElement)
			parent.addContent(position, reference)
			defElement.removeAttribute(ATTR_NAME)
			defElement.setAttribute(ATTR_INDEX, nextSharedIndex.toString())
			sharedElement.addContent(defElement)
			nextSharedIndex++
		}

		val document = Document(rootElement)
		graph.processingInstructions.forEachIndexed { piIndex, instruction ->
			document.addContent(piIndex, instruction.clone() as ProcessingInstruction)
		}
		return document
	}

	public companion object {
		/**
		 * Builds an engine for the given model classes (registered by their @SerialTag / simple name).
		 *
		 * @param Collection         classes     The model classes to register.
		 * @param SerializeDiagnostics diagnostics Sink for unmodeled-tag reports (default no-op).
		 * @return SerializeEngine A ready engine.
		 */
		public fun of(
			classes: Collection<KClass<*>>,
			diagnostics: SerializeDiagnostics = SerializeDiagnostics.None,
		): SerializeEngine {
			val registry = SerializerRegistry()
			classes.forEach(registry::register)
			return SerializeEngine(registry, diagnostics)
		}
	}
}