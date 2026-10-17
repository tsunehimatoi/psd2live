package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import kotlinx.serialization.json.*

/** Fully described nested schemas shared by authoring discovery and inspection results. */
internal object WorkspaceInteriorSchemas {
	private val s = WorkspaceResultSchema

	private fun describe(value: JsonObject, text: String) =
		JsonObject(value + ("description" to JsonPrimitive(text)))

	val settingsFields =
		mapOf(
			"horizontal" to
				describe(
					s.number(0, 2),
					"Horizontal material deformation gain; independent of vertical.",
				),
			"vertical" to
				describe(
					s.number(0, 2),
					"Vertical material deformation gain; positive input presses downward.",
				),
			"bulge" to
				describe(
					s.number(0, 2),
					"Virtual arch height; zero disables surface displacement.",
				),
			"press" to
				describe(
					s.number(0, 2),
					"Virtual arch pressure; zero disables surface displacement.",
				),
			"folds" to
				describe(
					s.number(0, 2),
					"Contribution of FOLD guides; no effect without fold guides.",
				),
			"contour" to
				describe(
					s.number(0, 1),
					"Source alpha contour retention: 0 free, 1 retained; no root attachment.",
				),
		)
	val motionFields =
		mapOf(
			"schema" to JsonObject(s.integer() + ("const" to JsonPrimitive(1))),
			"enabled" to
				describe(
					s.boolean(),
					"Enable this effect's native physics; does not change other groups.",
				),
			"sensitivityX" to
				describe(
					s.number(0.1, 1),
					"Uniform horizontal mouse-audition gain; explicit poses bypass it.",
				),
			"sensitivityY" to
				describe(
					s.number(0.1, 1),
					"Uniform vertical mouse-audition gain; explicit poses bypass it.",
				),
			"responseSeconds" to
				describe(s.number(0, 0.6), "First region response time in seconds."),
			"transmissionSeconds" to
				describe(s.number(0, 0.5), "Following region propagation time in seconds."),
			"transmissionStrength" to
				describe(
					s.number(0, 1),
					"Following region weight; zero removes the spatial split.",
				),
			"from" to
				describe(
					s.array(s.number(0, 1), 2, 2),
					"Propagation start [x,y], normalized to the layer; y grows downward.",
				),
			"to" to
				describe(
					s.array(s.number(0, 1), 2, 2),
					"Propagation end [x,y]; normalized endpoint distance must be at least 0.05.",
				),
		)
	private val guideFields =
		mapOf(
			"id" to describe(s.handle(), "Stable guide ID, unique within this effect."),
			"purpose" to
				describe(
					s.choices("TOPOLOGY", "FOLD"),
					"Only FOLD produces local deformation; defaults to FOLD for authoring.",
				),
			"radius" to
				describe(s.number(0), "Strictly positive influence radius in source pixels."),
			"amount" to describe(s.number(0, 2), "Local fold gain."),
			"pitchOnly" to
				describe(s.boolean(), "Apply this fold only to the vertical material channel."),
			"points" to
				describe(
					s.array(s.vector(2), 2, 256),
					"Source-raster [x,y] pixels; consecutive points must differ.",
				),
		)
	val guide = s.obj(guideFields, setOf("points"))
	val inspectedGuide = s.obj(guideFields)
	private val target =
		describe(s.handle(), "Target drawable ID or mesh:<ID>; one effect per mesh.")
	val put =
		WorkspaceCommandSchema(
			properties =
				buildJsonObject {
					put("state", s.handle())
					put("target", target)
					put(
						"enabled",
						describe(
							s.boolean(),
							"Retain recipe while toggling; target/enabled-only updates do not rebake.",
						),
					)
					put(
						"settings",
						describe(
							s.obj(settingsFields, emptySet()),
							"Partial update; omitted fields retain saved values or defaults.",
						),
					)
					put(
						"motion",
						describe(
							s.obj(motionFields, emptySet()),
							"Partial native timing update; omitted fields retain saved values.",
						),
					)
					put(
						"parameters",
						describe(
							s.array(s.string(), 2, 2),
							"Source X/Y IDs. Empty ID creates a private input. Omitted binding prefers body X/Y.",
						),
					)
					put(
						"guides",
						describe(
							s.array(guide, 0, 256),
							"Replace guide list; empty list clears local folds. Does not remesh.",
						),
					)
				},
			required = listOf("target", "state"),
		)
	val delete =
		WorkspaceCommandSchema(
			properties =
				buildJsonObject {
					put("state", s.handle())
					put("target", target)
				},
			required = listOf("target", "state"),
		)
	val inspected =
		s.obj(
			mapOf(
				"id" to s.handle(),
				"target" to s.handle(),
				"name" to s.string(),
				"enabled" to s.boolean(),
				"settings" to s.obj(settingsFields),
				"motion" to s.obj(motionFields),
				"parameters" to s.array(s.handle(), 2, 2),
				"created" to s.array(s.handle()),
				"outputs" to s.array(s.handle()),
				"guides" to s.array(inspectedGuide),
				"errorPixels" to s.number(0),
				"tolerancePixels" to s.number(0),
				"issue" to s.nullable(s.string()),
			)
		)
}

internal fun registerInteriorCommands(catalog: WorkspaceCommands, workspace: WorkspaceBackend) {
	for ((id, schema) in
		mapOf(
			"interior_put" to WorkspaceInteriorSchemas.put,
			"interior_delete" to WorkspaceInteriorSchemas.delete,
		)) {
		catalog.register(
			id,
			if (id == "interior_put")
				"Create/update compact native interior keyforms and optional physics. Settings persist; source mesh, UVs, skin and unrelated keyforms stay intact. Read settings with workspace_inspect scope=interiors."
			else
				"Delete one interior effect and its owned physics. Preserve shared parameters and inputs referenced by other authored content.",
			schema,
			hints = MUTATING,
		) { request ->
			WorkspaceOperationOutput(
				workspace
					.applyDocumentEdits(
						request.arguments.getValue("state").jsonPrimitive.content,
						"Interior surface",
						listOf(WorkspaceDocumentOperation(id, request.arguments)),
						MutationAuthor.AGENT,
					)
					.toJson()
			)
		}
	}
}
