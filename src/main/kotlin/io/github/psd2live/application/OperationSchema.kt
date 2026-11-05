package io.github.psd2live.application

import kotlinx.serialization.json.*

internal typealias WorkspaceValidationException = io.github.psd2live.core.modifier.ModifierSchemaException
internal fun validateOperationSchema(value: JsonElement, schema: JsonObject, path: String = "request") =
    io.github.psd2live.core.modifier.validateModifierSchema(value, schema, path)
internal fun checkOperationSchema(schema: JsonObject) = io.github.psd2live.core.modifier.checkModifierSchema(schema)
