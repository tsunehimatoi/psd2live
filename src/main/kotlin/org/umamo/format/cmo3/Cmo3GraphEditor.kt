package org.umamo.format.cmo3

import org.jdom.Element
import org.umamo.format.cmo3.serialize.ATTR_REF
import org.umamo.format.cmo3.serialize.ChildSlot
import org.umamo.format.cmo3.serialize.ModelGraph
import org.umamo.format.cmo3.serialize.VerbatimNode

/**
 * Mutation support for a read [Cmo3Model]'s graph, for callers lowering editor state back onto it.
 *
 * The writer replays each read object's recorded child order and attribute presence, so a plain
 * field assignment only reaches the re-emitted document when the source already carried that
 * element/attribute.  This facade exposes the bookkeeping a lowering needs on top of the bare
 * assignments: recording missing slots/attributes, distinguishing read objects from fresh ones,
 * and dropping shared-pool entries orphaned by structural deletes.
 *
 * @see Cmo3Model.setTargetVersionNo The original single-field precedent this generalizes.
 */
public class Cmo3GraphEditor internal constructor(private val graph: ModelGraph) {
	/**
	 * Ensures the child element for [propertyName] of [owner] (at the [tag] serializer level) is
	 * emitted on write even when the source document carried no such element.  Call after assigning
	 * a field the source omitted; without the slot the writer drops the assignment silently.  No-op
	 * for fresh objects (they have no recorded order and already emit every declared field).
	 *
	 * @param Any     owner              The object whose recorded child order is amended.
	 * @param String  tag                The serializer tag level the order was recorded under.
	 * @param String  propertyName       The Kotlin property name of the assigned field.
	 * @param String? beforePropertyName The property to insert before (where the editor writes the
	 *                                   field), or null to append.
	 */
	public fun ensureChildSlot(owner: Any, tag: String, propertyName: String, beforePropertyName: String? = null) {
		graph.ensureKnownChildSlot(owner, tag, propertyName, beforePropertyName)
	}

	/**
	 * Ensures the @SerialAttribute property [propertyName] of [owner] (at the [tag] serializer level)
	 * is emitted on write even when the source document omitted the attribute.  The attribute twin of
	 * [ensureChildSlot]; attributes emit in declaration order, so no anchor is needed.  No-op when no
	 * attribute record exists (the writer then already emits every non-default attribute value).
	 *
	 * @param Any    owner        The object whose attribute record is amended.
	 * @param String tag          The serializer tag level the record was captured under.
	 * @param String propertyName The Kotlin property name of the assigned attribute.
	 */
	public fun ensurePresentAttr(owner: Any, tag: String, propertyName: String) {
		graph.ensurePresentAttr(owner, tag, propertyName)
	}

	/**
	 * True when [owner] has a recorded child order at the [tag] level, i.e. it was read from the
	 * source document.  A fresh object has no record and emits super + every declared field in
	 * declaration order, honoring the serializer's default-skipping annotations.
	 *
	 * @param Any    owner The object to query.
	 * @param String tag   The serializer tag level.
	 * @return Boolean Whether a recorded order exists.
	 */
	public fun hasRecordedOrder(owner: Any, tag: String): Boolean = graph.childOrder[owner]?.get(tag) != null

	/**
	 * Drops shared-pool entries no longer reachable from the model root, after structural deletes
	 * removed the objects that referenced them.  Survivors keep their preserved xs.id and xs.idx
	 * (the reader keys the pool by id, so index gaps are tolerated).  Reachability follows typed
	 * fields, collections, and xs.ref attributes inside verbatim subtrees, so partially-typed
	 * documents never lose a pool entry their untyped content still references.
	 */
	public fun pruneUnreachableShared() {
		val sharedById = HashMap<String, Any>()
		for (instance in graph.sharedOrder) {
			graph.sharedInfo[instance]?.let { sharedRef -> sharedById[sharedRef.id] = instance }
		}
		val reachable = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		val stack = ArrayDeque<Any?>()
		graph.root?.let(stack::addLast)
		while (stack.isNotEmpty()) {
			val obj = stack.removeLast() ?: continue
			if (obj is CharSequence || obj is Number || obj is Boolean || obj is Char || obj is Enum<*>) {
				continue
			}
			if (!reachable.add(obj)) {
				continue
			}
			// xs.ref attributes inside this object's verbatim children target pool entries no typed
			// field holds; those targets stay live.
			graph.childOrder[obj]?.values?.forEach { slotsAtTag ->
				for (slot in slotsAtTag) {
					if (slot is ChildSlot.VerbatimChild) {
						addVerbatimRefTargets(slot.element, sharedById, stack)
					}
				}
			}
			when (obj) {
				is VerbatimNode -> addVerbatimRefTargets(obj.element, sharedById, stack)
				is Iterable<*> -> obj.forEach(stack::addLast)
				is Map<*, *> -> {
					obj.keys.forEach(stack::addLast)
					obj.values.forEach(stack::addLast)
				}

				is Array<*> -> obj.forEach(stack::addLast)
				else ->
					if (shouldWalkFields(obj)) {
						var currentClass: Class<*>? = obj::class.java
						while (currentClass != null && currentClass != Any::class.java) {
							for (field in currentClass.declaredFields) {
								if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
									continue
								}
								field.isAccessible = true
								stack.addLast(field.get(obj))
							}
							currentClass = currentClass.superclass
						}
					}
			}
		}
		val unreachable = graph.sharedOrder.filter { instance -> instance !in reachable }
		if (unreachable.isEmpty()) {
			return
		}
		graph.sharedOrder.removeAll { instance -> instance !in reachable }
		for (instance in unreachable) {
			graph.sharedInfo.remove(instance)
		}
	}

	/**
	 * True when the reachability walk should descend into [obj]'s declared fields.  Pruning must
	 * never drop an entry a live object still holds, so the walk descends into every graph-attached
	 * class rather than allow-listing packages; only platform and JDOM types (which cannot hold
	 * shared model references) are excluded.
	 *
	 * @param Any obj The object under consideration.
	 * @return Boolean Whether to walk its fields.
	 */
	private fun shouldWalkFields(obj: Any): Boolean {
		val className = obj::class.java.name
		return !className.startsWith("java.") &&
			!className.startsWith("javax.") &&
			!className.startsWith("kotlin.") &&
			!className.startsWith("org.jdom.")
	}

	/**
	 * Pushes the shared objects targeted by xs.ref attributes anywhere in [element]'s subtree onto
	 * the reachability [stack].
	 *
	 * @param Element    element    The verbatim subtree to scan.
	 * @param Map        sharedById The pool keyed by preserved xs.id.
	 * @param ArrayDeque stack      The walk stack to extend.
	 */
	private fun addVerbatimRefTargets(element: Element, sharedById: Map<String, Any>, stack: ArrayDeque<Any?>) {
		element.getAttributeValue(ATTR_REF)?.let { refId -> sharedById[refId]?.let(stack::addLast) }
		@Suppress("UNCHECKED_CAST")
		for (child in element.children as List<Element>) {
			addVerbatimRefTargets(child, sharedById, stack)
		}
	}
}

/**
 * The mutation facade over this model's graph, for lowering editor state back onto it before a
 * [Cmo3.write].
 *
 * @return Cmo3GraphEditor The editor bound to this model's graph.
 */
public fun Cmo3Model.edit(): Cmo3GraphEditor = Cmo3GraphEditor(graph)