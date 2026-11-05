package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

internal class ModifierSchemaException(val fieldPath: String, detail: String) :
    IllegalArgumentException("$fieldPath: $detail")

/** Validates the supported JSON Schema keywords as published, including exact oneOf semantics. */
internal fun validateModifierSchema(value: JsonElement, schema: JsonObject, path: String = "request") =
    validateSchema(value, schema, path, schema)

private fun validateSchema(value: JsonElement, schema: JsonObject, path: String, root: JsonObject,
                           references: Set<String> = emptySet()) {
    fun fail(message: String): Nothing = throw ModifierSchemaException(path, message)
    schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
        if (reference in references) fail("schema reference does not advance to a child value")
        validateSchema(value, localDefinition(root, reference), path, root, references + reference)
    }
    schema["oneOf"]?.jsonArray?.let { branches ->
        val outcomes = branches.map { branch ->
            try { validateSchema(value, branch.jsonObject, path, root, references); null }
            catch (failure: ModifierSchemaException) { failure }
        }
        when (outcomes.count { it == null }) {
            1 -> Unit
            0 -> {
                // Report a useful field error when an explicit discriminator identifies one branch.
                val matching = if (value is JsonObject) branches.indices.filter { index ->
                    val constants = branches[index].jsonObject["properties"]?.jsonObject.orEmpty()
                        .filterValues { it is JsonObject && "const" in it }
                    constants.isNotEmpty() && constants.all { (key, field) -> value[key] == field.jsonObject["const"] }
                } else emptyList()
                if (matching.size == 1) throw requireNotNull(outcomes[matching.single()])
                fail("must match exactly one declared operation")
            }
            else -> fail("matches more than one declared operation")
        }
    }
    schema["const"]?.let { if (it != value) fail("expected $it") }
    schema["enum"]?.jsonArray?.let { if (value !in it) fail("expected one of $it") }
    val types = when (val type = schema["type"]) {
        is JsonArray -> type.map { it.jsonPrimitive.content }
        is JsonPrimitive -> listOf(type.content)
        else -> emptyList()
    }
    fun matches(type: String): Boolean = when (type) {
        "null" -> value is JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "number", "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let {
            it.isFinite() && (type != "integer" || it % 1.0 == 0.0)
        } == true
        else -> error("Unsupported schema type: $type")
    }
    if (types.isNotEmpty() && types.none(::matches)) fail("expected ${types.joinToString(" or ")}")
    when (value) {
        is JsonObject -> {
            val properties = schema["properties"]?.jsonObject.orEmpty()
            val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            val missing = required - value.keys
            if (missing.isNotEmpty()) fail("missing ${missing.joinToString()}")
            if (value.size < (schema["minProperties"]?.jsonPrimitive?.int ?: 0)) fail("object must not be empty")
            value.forEach { (key, field) ->
                val rule = properties[key] ?: schema["additionalProperties"]
                if (rule is JsonObject) validateSchema(field, rule, "$path.$key", root)
                else if (rule == JsonPrimitive(false)) throw ModifierSchemaException("$path.$key", "unknown field")
            }
        }
        is JsonArray -> {
            if (value.size < (schema["minItems"]?.jsonPrimitive?.int ?: 0) ||
                value.size > (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) fail("array length outside allowed range")
            if (schema["uniqueItems"] == JsonPrimitive(true)) {
                val seen = HashSet<Any>()
                value.forEachIndexed { index, item ->
                    if (!seen.add(schemaValueKey(item)))
                        throw ModifierSchemaException("$path[$index]", "array items must be unique")
                }
            }
            schema["items"]?.jsonObject?.let { rule -> value.forEachIndexed { index, item -> validateSchema(item, rule, "$path[$index]", root) } }
        }
        is JsonPrimitive -> {
            if (value.isString) {
                val length = value.content.codePointCount(0, value.content.length)
                if (length < (schema["minLength"]?.jsonPrimitive?.int ?: 0) ||
                    length > (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) fail("string length outside allowed range")
                schema["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(value.content)) fail("invalid format") }
            } else value.doubleOrNull?.let { number ->
                if (!number.isFinite()) fail("must be finite")
                if (number < (schema["minimum"]?.jsonPrimitive?.double ?: Double.NEGATIVE_INFINITY) ||
                    number > (schema["maximum"]?.jsonPrimitive?.double ?: Double.POSITIVE_INFINITY)) fail("outside allowed range")
                schema["exclusiveMinimum"]?.jsonPrimitive?.double?.let { if (number <= it) fail("must be greater than $it") }
                schema["exclusiveMaximum"]?.jsonPrimitive?.double?.let { if (number >= it) fail("must be less than $it") }
            }
        }
    }
}

/** JSON numbers compare by value; object property order does not affect item uniqueness. */
private fun schemaValueKey(value: JsonElement): Any = when (value) {
    is JsonObject -> value.mapValues { (_, field) -> schemaValueKey(field) }
    is JsonArray -> value.map(::schemaValueKey)
    is JsonPrimitive -> if (value.isString) value else value.content.toBigDecimalOrNull()?.stripTrailingZeros() ?: value
}

/** Resolve local definitions only; executing a schema never loads URLs or external resources. */
private fun localDefinition(root: JsonObject, reference: String): JsonObject {
    require(reference.startsWith("#/\$defs/") && reference.removePrefix("#/\$defs/").matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
        "Only named local schema definitions are supported: $reference"
    }
    return requireNotNull(root["\$defs"]?.jsonObject?.get(reference.removePrefix("#/\$defs/")) as? JsonObject) {
        "Schema definition not found: $reference"
    }
}

/** Reject unsupported constraints during registration rather than silently failing to enforce them. */
internal fun checkModifierSchema(schema: JsonObject) = checkSchema(schema, schema)

private fun checkSchema(schema: JsonObject, root: JsonObject) {
    val supported = setOf("type", "properties", "required", "additionalProperties", "minProperties", "items",
        "minItems", "maxItems", "enum", "const", "oneOf", "pattern", "minimum", "maximum", "minLength", "maxLength",
        "exclusiveMinimum", "exclusiveMaximum", "uniqueItems", "description", "examples", "title", "default", "\$defs", "\$ref")
    require(schema.keys.all { it in supported }) { "Unsupported operation schema keywords: ${schema.keys - supported}" }
    schema["uniqueItems"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "uniqueItems must be boolean" } }
    schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
        val chain = mutableSetOf(reference)
        var target = localDefinition(root, reference)
        while ("\$ref" in target) {
            val next = target.getValue("\$ref").jsonPrimitive.content
            require(chain.add(next)) { "Schema references form a cycle without a value constraint" }
            target = localDefinition(root, next)
        }
    }
    schema["\$defs"]?.jsonObject?.values?.forEach { checkSchema(it.jsonObject, root) }
    schema["properties"]?.jsonObject?.values?.forEach { checkSchema(it.jsonObject, root) }
    schema["items"]?.let { checkSchema(it.jsonObject, root) }
    schema["additionalProperties"]?.let { if (it is JsonObject) checkSchema(it, root) }
    schema["oneOf"]?.jsonArray?.forEach { checkSchema(it.jsonObject, root) }
}

internal object ModifierPluginSchema {
    fun check(schema: JsonObject) = checkModifierSchema(schema)
    fun validate(value: JsonElement, schema: JsonObject) = validateModifierSchema(value, schema)
}
