package org.umamo.interop.cmo3

/**
 * The graph collection field as the mutable list the XML reader materialized, or null when the
 * field is absent or holds no list.  The reader's collections erase to MutableList<Any?>, so this
 * is the one home for the unavoidable unchecked cast every in-place list mutation needs.
 *
 * @param Any? field The owner's collection field value.
 * @return MutableList? The field's mutable list, or null.
 */
@Suppress("UNCHECKED_CAST")
internal fun mutableGraphListOf(field: Any?): MutableList<Any?>? = field as? MutableList<Any?>