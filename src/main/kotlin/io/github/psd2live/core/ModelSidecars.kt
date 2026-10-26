package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter
import kotlin.math.abs

/** How an expression combines one parameter with the value the motion gives it; Cubism's exp3 `Blend`. */
enum class ExpressionBlend(val exp3: String) { ADD("Add"), MULTIPLY("Multiply"), OVERWRITE("Overwrite") }

/** One parameter an expression sets: [value] is added to, multiplied into or written over the motion's value. */
data class ExpressionEntry(val parameterId: String, val blend: ExpressionBlend, val value: Float) {
	init {
		require(parameterId.isNotBlank()) { "An expression entry needs a parameter" }
		require(value.isFinite()) { "Expression values must be finite" }
	}
}

/**
 * A facial expression a host switches to over whatever motion plays (Cubism's exp3): a few parameters, each with its
 * blend, faded in and out over [fadeIn] and [fadeOut] seconds. [id] names its exported file, [name] is what the
 * model's settings list it as.
 */
data class ModelExpression(
	val id: String,
	val name: String,
	val fadeIn: Float = DEFAULT_FADE,
	val fadeOut: Float = DEFAULT_FADE,
	val entries: List<ExpressionEntry> = emptyList(),
) {
	init {
		require(ID.matches(id)) { "Expression ID must be 1-64 ASCII letters, digits, underscores or hyphens" }
		require(name.isNotBlank() && name.length <= MAX_NAME && name.none(Char::isISOControl)) { "Invalid expression name" }
		require(fadeIn.isFinite() && fadeIn in 0f..MAX_FADE && fadeOut.isFinite() && fadeOut in 0f..MAX_FADE) {
			"Expression fades must be within 0..$MAX_FADE seconds"
		}
		require(entries.size <= MAX_ENTRIES) { "An expression sets at most $MAX_ENTRIES parameters" }
		require(entries.map { it.parameterId }.distinct().size == entries.size) { "An expression sets each parameter once" }
	}

	fun entry(parameterId: String): ExpressionEntry? = entries.firstOrNull { it.parameterId == parameterId }

	companion object {
		/** Half a second each way, as Cubism Editor's expression settings start. */
		const val DEFAULT_FADE = 0.5f
		const val MAX_FADE = 10f
		const val MAX_NAME = 64
		const val MAX_ENTRIES = 256
		val ID = Regex("[A-Za-z0-9_-]{1,64}")
	}
}

/** A part of a pose group, and the parts that show and hide with it (Cubism's pose3 `Link`). */
data class PosePart(val partId: String, val links: List<String> = emptyList()) {
	init {
		require(partId.isNotBlank() && links.none { it.isBlank() }) { "Pose parts need IDs" }
	}

	val ids: List<String> get() = listOf(partId) + links
}

/** Parts of which one shows at a time, as arm A and arm B; at load the first shows. */
data class PoseGroup(val parts: List<PosePart>) {
	init {
		require(parts.isNotEmpty()) { "A pose group needs a part" }
	}
}

/** The model's pose (Cubism's pose3): its groups, and the seconds a switch inside a group fades over. */
data class ModelPose(val fadeIn: Float = DEFAULT_FADE, val groups: List<PoseGroup> = emptyList()) {
	init {
		require(fadeIn.isFinite() && fadeIn in 0f..ModelExpression.MAX_FADE) { "Pose fade must be within 0..${ModelExpression.MAX_FADE} seconds" }
		require(groups.size <= MAX_GROUPS && groups.all { it.parts.size <= MAX_GROUPS }) { "A pose has at most $MAX_GROUPS groups of $MAX_GROUPS parts" }
		val ids = groups.flatMap { group -> group.parts.flatMap { it.ids } }
		require(ids.distinct().size == ids.size) { "A part is in one pose group once" }
	}

	companion object {
		/** Cubism's default pose fade. */
		const val DEFAULT_FADE = 0.5f
		const val MAX_GROUPS = 64
	}
}

/**
 * The project's expressions. Until they are first edited the model has the standard ones ([standard]), made from the
 * standard face parameters it has; the first edit takes them into the document, after which only the document's list
 * exports.
 */
object ModelExpressions {
	const val MAX_EXPRESSIONS = 128

	/** Below this a parameter is at its default: a pose captured into an expression leaves it out. */
	private const val EPSILON = 1e-4f

	private fun both(name: String, blend: ExpressionBlend, value: Float) =
		listOf(ExpressionEntry(name.replace("*", "L"), blend, value), ExpressionEntry(name.replace("*", "R"), blend, value))

	/**
	 * What every generated rig gets: the standard face moved into its moods, each where the rig has its parameters, the
	 * rig modules' parameters (eye forms, brow angles, the cheek) carrying each further where the rig has them.
	 * Named in the interface language, as the runtime export has always named them; the first edit keeps those names.
	 */
	val standard: List<ModelExpression> get() = listOf(
		ModelExpression("smile", tr("model.expression.smile"), entries = listOf(
			ExpressionEntry(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, 1f),
			ExpressionEntry(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.75f),
			ExpressionEntry(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.75f),
			ExpressionEntry(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, 0.3f),
			ExpressionEntry(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, 0.3f),
		) + both("ParamEye*Smile", ExpressionBlend.OVERWRITE, 1f) + both("ParamBrow*Angle", ExpressionBlend.ADD, 0.2f) +
			ExpressionEntry("ParamCheek", ExpressionBlend.ADD, 0.4f)),
		ModelExpression("sad", tr("model.expression.sad"), entries = listOf(
			ExpressionEntry(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, -0.8f),
			ExpressionEntry(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.85f),
			ExpressionEntry(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.85f),
			ExpressionEntry(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, -0.5f),
			ExpressionEntry(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, -0.5f),
		) + both("ParamEye*Form", ExpressionBlend.OVERWRITE, -0.8f) + both("ParamBrow*Angle", ExpressionBlend.OVERWRITE, 0.9f) +
			both("ParamBrow*Form", ExpressionBlend.OVERWRITE, 0.4f)),
		ModelExpression("angry", tr("model.expression.angry"), entries = listOf(
			ExpressionEntry(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, -0.5f),
			ExpressionEntry(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.9f),
			ExpressionEntry(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.9f),
			ExpressionEntry(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, -0.8f),
			ExpressionEntry(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, -0.8f),
		) + both("ParamEye*Form", ExpressionBlend.OVERWRITE, 0.6f) + both("ParamBrow*Angle", ExpressionBlend.OVERWRITE, -0.8f) +
			both("ParamEye*Squint", ExpressionBlend.OVERWRITE, 0.1f)),
		ModelExpression("surprised", tr("model.expression.surprised"), entries = listOf(
			ExpressionEntry(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.OVERWRITE, 1f),
			ExpressionEntry(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.OVERWRITE, 1f),
			ExpressionEntry(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, 0.8f),
			ExpressionEntry(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, 0.8f),
			ExpressionEntry(StandardParameters.MOUTH_OPEN.raw, ExpressionBlend.OVERWRITE, 0.6f),
		) + both("ParamBrow*Angle", ExpressionBlend.OVERWRITE, 0.3f) + ExpressionEntry("ParamO", ExpressionBlend.OVERWRITE, 0.5f)),
		// The moods only the rig modules can show: shy needs the cheek, sleepy the fitted eyes' squint, gloom its shade.
		ModelExpression("shy", tr("model.expression.shy"), entries = listOf(
			ExpressionEntry("ParamCheek", ExpressionBlend.OVERWRITE, 1f),
			ExpressionEntry(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, 0.4f),
			ExpressionEntry(StandardParameters.EYE_BALL_Y.raw, ExpressionBlend.OVERWRITE, -0.4f),
		) + both("ParamEye*Squint", ExpressionBlend.OVERWRITE, 0.35f) + both("ParamBrow*Angle", ExpressionBlend.OVERWRITE, 0.5f)),
		ModelExpression("sleepy", tr("model.expression.sleepy"), entries = listOf(
			ExpressionEntry(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.3f),
			ExpressionEntry(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.3f),
			ExpressionEntry(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, -0.3f),
			ExpressionEntry(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, -0.3f),
		) + both("ParamEye*Squint", ExpressionBlend.OVERWRITE, 0.2f) + both("ParamBrow*Angle", ExpressionBlend.OVERWRITE, 0.3f)),
		ModelExpression("gloom", tr("model.expression.gloom"), entries = listOf(
			ExpressionEntry("ParamGloom", ExpressionBlend.OVERWRITE, 1f),
			ExpressionEntry(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, -0.5f),
			ExpressionEntry(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.8f),
			ExpressionEntry(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.8f),
		) + both("ParamBrow*Angle", ExpressionBlend.OVERWRITE, 0.6f)),
	)

	/** The parameter a standard expression shows only with: without it the mood is not the rig's to show. */
	private val requires = mapOf("shy" to "ParamCheek", "sleepy" to "ParamEyeLSquint", "gloom" to "ParamGloom")

	/**
	 * The standard expressions on the parameters [parameterIds] holds; one with none of them is left out. [renames] are
	 * the journal's ID conversions of parameters ([RigEditOverlay.idRenames]): a standard parameter is found under its new ID.
	 */
	fun standardFor(parameterIds: Set<String>, renames: Map<String, String> = emptyMap()): List<ModelExpression> = standard.mapNotNull { expression ->
		requires[expression.id]?.let { required -> if ((renames[required] ?: required) !in parameterIds) return@mapNotNull null }
		expression.entries.map { entry -> renames[entry.parameterId]?.let { entry.copy(parameterId = it) } ?: entry }
			.filter { it.parameterId in parameterIds }.takeIf { it.isNotEmpty() }?.let { expression.copy(entries = it) }
	}

	/**
	 * The expressions of [overlay] on a rig with [parameterIds]: the document's once edited; before that the standard
	 * ones on a generated rig, and none on an imported model, whose rigger made its face without them.
	 */
	fun effective(overlay: RigEditOverlay, parameterIds: Set<String>): List<ModelExpression> =
		overlay.expressions ?: if (overlay.importedCmo3 != null) emptyList() else standardFor(parameterIds, overlay.idRenames.parameters)

	/**
	 * What an export writes: [effective], each without the parameters the rig no longer has. An authored expression
	 * left with none still exports, as the neutral expression a host resets to.
	 */
	fun exported(overlay: RigEditOverlay, parameterIds: Set<String>): List<ModelExpression> =
		effective(overlay, parameterIds).map { expression -> expression.copy(entries = expression.entries.filter { it.parameterId in parameterIds }) }

	/** The parameters [expression] names that [parameterIds] lacks: left by an earlier edit, skipped at export. */
	fun missing(expression: ModelExpression, parameterIds: Set<String>): List<String> =
		expression.entries.map { it.parameterId }.filter { it !in parameterIds }

	/**
	 * An expression of the pose [values]: every parameter away from its default, added on as its offset from the
	 * default, as an expression made in Cubism's animation view starts.
	 */
	fun entriesOfPose(values: Map<String, Float>, parameters: List<Parameter>): List<ExpressionEntry> = parameters.mapNotNull { parameter ->
		val value = values[parameter.id.raw]?.takeIf(Float::isFinite)?.coerceIn(parameter.min, parameter.max) ?: return@mapNotNull null
		val offset = value - parameter.default
		if (abs(offset) < EPSILON) null else ExpressionEntry(parameter.id.raw, ExpressionBlend.ADD, offset)
	}

	/** [entry] blended as [blend] instead, its value converted so the parameter at its default ends where it did. */
	fun withBlend(entry: ExpressionEntry, blend: ExpressionBlend, default: Float): ExpressionEntry {
		if (blend == entry.blend) return entry
		val result = result(entry, default)
		val value = when (blend) {
			ExpressionBlend.ADD -> result - default
			ExpressionBlend.OVERWRITE -> result
			ExpressionBlend.MULTIPLY -> if (abs(default) < EPSILON) 1f else result / default
		}
		return entry.copy(blend = blend, value = value)
	}

	/** What [entry] makes of a parameter at [base]. */
	fun result(entry: ExpressionEntry, base: Float): Float = when (entry.blend) {
		ExpressionBlend.ADD -> base + entry.value
		ExpressionBlend.MULTIPLY -> base * entry.value
		ExpressionBlend.OVERWRITE -> entry.value
	}

	/** [values] with [expression] applied at full weight, each parameter held in its range; the preview of an expression. */
	fun applied(expression: ModelExpression, values: Map<String, Float>, parameters: List<Parameter>): Map<String, Float> {
		val byId = parameters.associateBy { it.id.raw }
		return expression.entries.mapNotNull { entry ->
			val parameter = byId[entry.parameterId] ?: return@mapNotNull null
			entry.parameterId to result(entry, values[entry.parameterId] ?: parameter.default).coerceIn(parameter.min, parameter.max)
		}.toMap()
	}

	/** An expression ID unused by [expressions]. */
	fun newId(expressions: List<ModelExpression>): String {
		var index = expressions.size + 1
		while (expressions.any { it.id == "exp_$index" }) index++
		return "exp_$index"
	}

	/** [base], or [base] with the first free number, unused as a name in [expressions]. */
	fun uniqueName(expressions: List<ModelExpression>, base: String): String {
		val taken = expressions.mapTo(HashSet()) { it.name.lowercase() }
		if (base.lowercase() !in taken) return base
		var index = 2
		while ("$base $index".lowercase() in taken) index++
		return "$base $index"
	}

	/** [expressions] once known to be a valid list, every entry on one of [parameterIds] when given. */
	fun validated(expressions: List<ModelExpression>, parameterIds: Set<String>? = null): List<ModelExpression> {
		require(expressions.size <= MAX_EXPRESSIONS) { "A model has at most $MAX_EXPRESSIONS expressions" }
		require(expressions.map { it.id.lowercase() }.distinct().size == expressions.size) { "Duplicate expression IDs" }
		require(expressions.map { it.name.lowercase() }.distinct().size == expressions.size) { "Duplicate expression names" }
		if (parameterIds != null) expressions.forEach { expression ->
			expression.entries.firstOrNull { it.parameterId !in parameterIds }?.let { throw IllegalArgumentException("Unknown expression parameter: ${it.parameterId}") }
		}
		return expressions
	}

	fun toJson(expression: ModelExpression): JsonObject = buildJsonObject {
		put("id", expression.id); put("name", expression.name)
		put("fade_in", expression.fadeIn); put("fade_out", expression.fadeOut)
		putJsonArray("entries") {
			for (entry in expression.entries) add(buildJsonObject {
				put("parameter", entry.parameterId); put("blend", entry.blend.name); put("value", entry.value)
			})
		}
	}

	fun fromJson(o: JsonObject): ModelExpression = ModelExpression(
		id = o.getValue("id").jsonPrimitive.content,
		name = o.getValue("name").jsonPrimitive.content,
		fadeIn = o["fade_in"]?.jsonPrimitive?.float ?: ModelExpression.DEFAULT_FADE,
		fadeOut = o["fade_out"]?.jsonPrimitive?.float ?: ModelExpression.DEFAULT_FADE,
		entries = o["entries"]?.jsonArray.orEmpty().map { element ->
			val entry = element.jsonObject
			ExpressionEntry(entry.getValue("parameter").jsonPrimitive.content,
				ExpressionBlend.valueOf(entry.getValue("blend").jsonPrimitive.content.uppercase()), entry.getValue("value").jsonPrimitive.float)
		},
	)
}

object ModelPoses {
	/** Every part [pose] names, links included. */
	fun partIds(pose: ModelPose): List<String> = pose.groups.flatMap { group -> group.parts.flatMap { it.ids } }

	/** [pose] once known to name only parts of [partIds], when given. */
	fun validated(pose: ModelPose, partIds: Set<String>? = null): ModelPose {
		if (partIds != null) partIds(pose).firstOrNull { it !in partIds }?.let {
			throw IllegalArgumentException("Part not found: $it")
		}
		return pose
	}

	/** What an export writes: [pose] without the parts the rig no longer has, and without the groups left empty. */
	fun exported(pose: ModelPose?, partIds: Set<String>): ModelPose? {
		val groups = pose?.groups.orEmpty().mapNotNull { group ->
			group.parts.filter { it.partId in partIds }.map { it.copy(links = it.links.filter(partIds::contains)) }
				.takeIf { it.isNotEmpty() }?.let(::PoseGroup)
		}
		return if (pose == null || groups.isEmpty()) null else pose.copy(groups = groups)
	}

	fun toJson(pose: ModelPose): JsonObject = buildJsonObject {
		put("fade_in", pose.fadeIn)
		putJsonArray("groups") {
			for (group in pose.groups) addJsonArray {
				for (part in group.parts) add(buildJsonObject {
					put("part", part.partId)
					if (part.links.isNotEmpty()) putJsonArray("links") { part.links.forEach { add(JsonPrimitive(it)) } }
				})
			}
		}
	}

	fun fromJson(o: JsonObject): ModelPose = ModelPose(
		fadeIn = o["fade_in"]?.jsonPrimitive?.float ?: ModelPose.DEFAULT_FADE,
		groups = o["groups"]?.jsonArray.orEmpty().map { group ->
			PoseGroup(group.jsonArray.map { element ->
				val part = element.jsonObject
				PosePart(part.getValue("part").jsonPrimitive.content, part["links"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content })
			})
		},
	)
}

/**
 * What the model's sidecar data - motions, expressions and the pose - keeps when the rig's objects change: they name
 * parameters and parts by ID, so a deleted parameter or an ID changed in the rig updates them in the same edit.
 */
object ModelSidecars {
	/**
	 * [overlay] with every reference to the [kind] (`parameter` or `part`) [from] renamed [to]: motion curves and
	 * expression entries for a parameter, pose parts and links for a part. Expressions not taken into the document yet
	 * are the standard ones, which follow the rig by themselves.
	 */
	fun withIdRenamed(overlay: RigEditOverlay, kind: String, from: String, to: String): RigEditOverlay {
		if (from == to) return overlay
		return when (kind) {
			"parameter" -> overlay.copy(
				motionClips = overlay.motionClips.map { clip ->
					if (clip.curves.none { it.parameterId == from }) clip
					else clip.copy(curves = clip.curves.filterNot { it.parameterId == to }.map { if (it.parameterId == from) it.copy(parameterId = to) else it })
				},
				expressions = overlay.expressions?.map { expression ->
					if (expression.entries.none { it.parameterId == from }) expression
					else expression.copy(entries = expression.entries.filterNot { it.parameterId == to }.map { if (it.parameterId == from) it.copy(parameterId = to) else it })
				},
			)
			"part" -> overlay.copy(pose = overlay.pose?.let { pose ->
				fun id(value: String) = if (value == from) to else value
				pose.copy(groups = pose.groups.map { group -> PoseGroup(group.parts.map { PosePart(id(it.partId), it.links.map(::id)) }) })
			})
			else -> overlay
		}
	}

	/** [overlay] without the expression entries on parameter [parameterId], once it is deleted. */
	fun withoutParameter(overlay: RigEditOverlay, parameterId: String): RigEditOverlay {
		val expressions = overlay.expressions ?: return overlay
		val next = expressions.map { expression -> expression.copy(entries = expression.entries.filterNot { it.parameterId == parameterId }) }
		return if (next == expressions) overlay else overlay.copy(expressions = next)
	}

	/** [overlay] with the values its expressions write over parameter [parameterId] held within [min]..[max]. */
	fun withParameterRange(overlay: RigEditOverlay, parameterId: String, min: Float, max: Float): RigEditOverlay {
		val expressions = overlay.expressions ?: return overlay
		val next = expressions.map { expression ->
			expression.copy(entries = expression.entries.map {
				if (it.parameterId == parameterId && it.blend == ExpressionBlend.OVERWRITE) it.copy(value = it.value.coerceIn(min, max)) else it
			})
		}
		return if (next == expressions) overlay else overlay.copy(expressions = next)
	}
}
