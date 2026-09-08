package org.umamo.format.cmo3.serialize.annotations

/**
 * Marks a property as serialized.
 *
 * EN: The editor serializes a property unless told otherwise; this annotation makes intent explicit
 *     and carries [readOnly] (deserialized but treated as read-only by the editor UI).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Serialize(val readOnly: Boolean = false)

/** Excludes a property from serialization. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DontSerialize

/**
 * Skips a property when its value equals the class's default-instance value.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DontSerializeIfDefault

/**
 * Suppresses serialization of the superclass as a nested `<super>` element.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SuppressSerializeSuperClass

/**
 * Pins the serialized element tag for a class (overrides the default simple-name mapping) and,
 * optionally, the schema version emitted as a `<?version tag:version?>` PI.
 *
 * EN: The editor derives the tag from the class (simple name, or an `<?import?>`-qualified name).
 *     We make it explicit per class so the registry and version PIs are driven from the model.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SerialTag(val tag: String, val version: Int = -1)

/**
 * Serializes a property as an XML attribute on the owning element rather than a child element.
 * For value classes whose scalar fields are written as attributes rather than child elements
 * (e.g. CImageResource width/height, CModelSource isDefaultKeyformLocked).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SerialAttribute(val name: String = "")

/**
 * Overrides the serialized field name (xs.n) for a child-element property when it differs from the
 * Kotlin property name - e.g. an on-disk name that is not a legal Kotlin identifier (`parameters.keys`).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SerialName(val name: String)