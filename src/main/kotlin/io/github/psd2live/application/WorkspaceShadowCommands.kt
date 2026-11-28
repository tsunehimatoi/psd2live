package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object WorkspaceShadowSchemas {
    private val s = WorkspaceResultSchema

    val worldSet = WorkspaceCommandSchema(
        properties = buildJsonObject {
            put("state", s.string())
            put("azimuth", s.number(-180.0, 180.0))
            put("elevation", s.number(0.0, 90.0))
            put("style", s.choices("cel", "soft", "painterly"))
            put("color_mode", s.choices("auto_skin", "custom"))
            put("custom_color", s.string())
            put("intensity", s.number(0.0, 2.0))
            put("composite_mode", s.choices("auto", "offscreen", "compat_merged"))
            put("preview_enabled", s.boolean())
        },
        required = listOf("state"),
    )

    val autoGenerate = WorkspaceCommandSchema(
        properties = buildJsonObject {
            put("state", s.string())
            put("include_covered", s.boolean())
        },
        required = listOf("state"),
    )

    val put = WorkspaceCommandSchema(
        properties = buildJsonObject {
            put("state", s.string())
            put("id", s.string())
            put("kind", s.choices("cast", "form"))
            put("name", s.string())
            put("enabled", s.boolean())
            put("casters", s.array(s.handle(), 1, 64))
            put("receivers", s.array(s.handle(), 1, 64))
            put("targets", s.array(s.handle(), 1, 64))
            put("distance", s.number(0.0, 200.0))
            put("coverage", s.number(0.05, 0.85))
            put("opacity_override", s.nullable(s.number(0.0, 1.0)))
            put("softness_override", s.nullable(s.number(0.0, 48.0)))
            put("color_override", s.nullable(s.string()))
            put("root_pinning", s.number(0.0, 1.0))
            put("parallax", s.number(0.0, 3.0))
            put("placement", s.choices("above_face", "above_eyes", "below_caster"))
            put("proxy", s.choices("face_ellipsoid", "sphere", "cylinder"))
            put("cutter_expand_px", s.number(0.0, 16.0))
            put("skip_covered_casters", s.boolean())
        },
        required = listOf("state"),
    )

    val delete = WorkspaceCommandSchema(
        properties = buildJsonObject {
            put("state", s.string())
            put("id", s.handle())
        },
        required = listOf("state", "id"),
    )

    val apply = WorkspaceCommandSchema(
        properties = buildJsonObject {
            put("state", s.string())
            put("id", s.handle())
        },
        required = listOf("state", "id"),
    )
}

internal fun registerShadowCommands(catalog: WorkspaceCommands, workspace: WorkspaceBackend) {
    val definitions: List<Triple<String, String, WorkspaceCommandSchema>> = listOf(
        Triple("shadow_world_set", "Update the shared world lighting parameters used by all shadow items.", WorkspaceShadowSchemas.worldSet),
        Triple("shadow_auto_generate", "Generate or refresh cast (bangs->face, face->neck) and form (face side) shadows on visible source meshes. Preserve existing item settings and applied shadows. Use include_covered to include caster/receiver pairs with an authored projection.", WorkspaceShadowSchemas.autoGenerate),
        Triple("shadow_put", "Create or update a cast or form shadow item.", WorkspaceShadowSchemas.put),
        Triple("shadow_delete", "Delete a shadow item.", WorkspaceShadowSchemas.delete),
        Triple("shadow_apply", "Bake a shadow item into ordinary editable meshes and keyforms.", WorkspaceShadowSchemas.apply),
    )
    for ((id, description, schema) in definitions) {
        catalog.register(id, description, schema, hints = MUTATING) { request ->
            val edits = listOf(WorkspaceDocumentOperation(id, request.arguments))
            suspend fun edit(): WorkspaceOperationOutput = WorkspaceOperationOutput(
                workspace.applyDocumentEdits(
                    request.arguments.getValue("state").jsonPrimitive.content,
                    "Shadow edit",
                    edits,
                    MutationAuthor.AGENT,
                ).toJson()
            )
            val context = currentCoroutineContext()
            val completion = context[WorkspaceJobCompletion]
            if (completion == null) edit() else withContext(WorkspaceBatchJobExecution(edits, completion, context[WorkspaceJobContext]) {
                WorkspaceOperationOutput(normalizedAuthoringMutation(it.toJson(), id))
            }) { edit() }
        }
    }
}
