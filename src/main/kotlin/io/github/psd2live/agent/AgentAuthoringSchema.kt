package io.github.psd2live.agent

import kotlinx.serialization.json.*

/** Validate the same small JSON Schema subset we publish; reject unknown fields before any write. */
internal fun validateAuthoringSchema(value: JsonElement, schema: JsonObject, path: String = "request") {
    fun fail(message: String): Nothing = throw IllegalArgumentException("$path: $message")
    schema["oneOf"]?.jsonArray?.let { branches ->
        val choices = if (value is JsonObject) branches.filter { branch ->
            branch.jsonObject["properties"]?.jsonObject.orEmpty().all { (key, field) ->
                field.jsonObject["const"]?.let { value[key] == it } ?: true
            }
        } else branches
        if (choices.size != 1) fail("choose one declared operation")
        validateAuthoringSchema(value, choices.single().jsonObject, path)
        return
    }
    schema["const"]?.let { if (it != value) fail("expected $it") }
    schema["enum"]?.jsonArray?.let { if (value !in it) fail("expected one of $it") }
    when (schema["type"]?.jsonPrimitive?.content) {
        "object" -> {
            if (value !is JsonObject) fail("expected object")
            val properties = schema["properties"]?.jsonObject.orEmpty()
            val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            val missing = required - value.keys
            if (missing.isNotEmpty()) fail("missing ${missing.joinToString()}")
            if (value.size < (schema["minProperties"]?.jsonPrimitive?.int ?: 0)) fail("object must not be empty")
            for ((key, field) in value) {
                val rule = properties[key] ?: schema["additionalProperties"]
                if (rule is JsonObject) validateAuthoringSchema(field, rule, "$path.$key")
                else if (rule == JsonPrimitive(false)) fail("unknown field $key")
            }
        }
        "array" -> {
            if (value !is JsonArray) fail("expected array")
            if (value.size < (schema["minItems"]?.jsonPrimitive?.int ?: 0) || value.size > (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) fail("array length outside allowed range")
            schema["items"]?.jsonObject?.let { item -> value.forEachIndexed { i, v -> validateAuthoringSchema(v, item, "$path[$i]") } }
        }
        "string" -> {
            if (value !is JsonPrimitive || !value.isString) fail("expected string")
            schema["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(value.content)) fail("invalid format") }
        }
        "boolean" -> if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) fail("expected boolean")
        "number", "integer" -> {
            if (value !is JsonPrimitive || value.isString) fail("expected number")
            val number = value.doubleOrNull ?: fail("expected number")
            if (!number.isFinite()) fail("must be finite")
            if (schema["type"]?.jsonPrimitive?.content == "integer" && number % 1.0 != 0.0) fail("expected integer")
            if (number < (schema["minimum"]?.jsonPrimitive?.double ?: Double.NEGATIVE_INFINITY) || number > (schema["maximum"]?.jsonPrimitive?.double ?: Double.POSITIVE_INFINITY)) fail("outside allowed range")
        }
    }
}
