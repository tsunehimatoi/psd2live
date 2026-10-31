package io.github.psd2live.format.compile

import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.RigIR
import java.util.Properties

/** What kind of output a target writes, which decides the input it lowers from. */
public enum class TargetFamily {
	/** A structured rig another runtime evaluates (moc3, cmo3, Spine, DragonBones ...). */
	RIG,
	/** Layers posed for an art or compositing tool (layered PSD). */
	TIMELINE,
	/** Rendered pixels only (image sequences, sprite sheets, GIF, video). */
	RASTER,
}

public enum class PhysicsSupport { NONE, PARAMETER_PENDULUM, CONSTRAINT }

public enum class MaskSupport { NONE, POLYGON, TEXTURE_ALPHA }

/** What a target's format can hold; lowering compares the IR against it. */
public data class CapabilityProfile(
	val bones: Boolean = false,
	val skinning: Boolean = false,
	val warpLattice: Boolean = false,
	/** Dimensions of parameter keyform grids the format holds; 0 when it has no parameters. */
	val parameterGrid: Int = 0,
	val blendShapes: Boolean = false,
	val timeline: Boolean = false,
	val physics: PhysicsSupport = PhysicsSupport.NONE,
	val blendModes: Set<ColorBlend> = setOf(ColorBlend.NORMAL),
	val masks: MaskSupport = MaskSupport.NONE,
	val keyedDrawOrder: Boolean = false,
	val glue: Boolean = false,
	val maxTextureSize: Int = 16384,
	val powerOfTwo: Boolean = false,
	/** Whether the output keeps the rig's structure at all (false for raster outputs). */
	val structure: Boolean = true,
	/** Whether the format carries the rig's hit areas ([RigIR.hitAreas]). */
	val hitAreas: Boolean = false,
)

/** An IR capability a target may be unable to hold. */
public enum class Feature {
	STRUCTURE, BONES, SKINNING, WARP_LATTICE, PARAMETER_GRID, BLEND_SHAPES, BLEND_MODE, MASK,
	KEYED_DRAW_ORDER, GLUE, PHYSICS, TIMELINE, TEXTURE_SIZE, PARAMETERS,
}

/** How a lost capability is handled: kept visually at a size cost, approximated, or dropped. */
public enum class Handling { BAKED, APPROXIMATED, DROPPED }

/**
 * One entry of a loss report. [objectId] is a stable IR id, or "*" for the whole rig. [error] is an
 * estimated deviation (pixels or vertex distance) when one was measured.
 */
public data class LossEntry(
	val objectId: String, val feature: Feature, val handling: Handling, val error: Float? = null, val note: String,
)

/**
 * Per-export choices. [handling] overrides how a feature is lowered where the target supports more than
 * one way; [settings] are target-specific options (each target documents its keys).
 */
public data class ExportOptions(
	val baseName: String,
	val handling: Map<Feature, Handling> = emptyMap(),
	val settings: Map<String, String> = emptyMap(),
) {
	init {
		require(baseName.isNotBlank() && baseName.none { it in "/\\:*?\"<>|" || it.isISOControl() }) { "Invalid export base name: $baseName" }
	}
	public fun setting(key: String): String? = settings[key]
	public fun int(key: String, default: Int): Int = settings[key]?.toIntOrNull() ?: default
	public fun float(key: String, default: Float): Float = settings[key]?.toFloatOrNull() ?: default
	public fun choice(key: String, default: String, values: List<String>): String {
		val value = settings[key] ?: default
		require(value in values) { "Invalid $key: $value (expected ${values.joinToString()})" }
		return value
	}
	public fun flag(key: String, default: Boolean): Boolean = settings[key]?.toBooleanStrictOrNull() ?: default
}

/** One key a target reads from [ExportOptions.settings], declared so hosts can offer it without knowing the target. */
public sealed interface TargetSetting {
	public val key: String

	/** A clip id. With [rest], leaving it out poses the rig at rest; otherwise it falls back to the first clip. */
	public data class ClipChoice(override val key: String, val rest: Boolean) : TargetSetting

	public data class Choice(override val key: String, val default: String, val values: List<String>) : TargetSetting {
		init { require(values.isNotEmpty() && values.distinct().size == values.size && default in values) }
	}

	public data class Flag(override val key: String, val default: Boolean) : TargetSetting

	/** A number in [min]..[max]; a null [default] means the target derives it (e.g. from the clip). */
	public data class Number(
		override val key: String, val default: Double?, val min: Double, val max: Double, val step: Double, val decimals: Int,
	) : TargetSetting

	/** Free text, such as a pose list or a tool path; [hint] describes the format. */
	public data class Text(override val key: String, val hint: String) : TargetSetting

	/** Settings several baking targets share. */
	public companion object {
		public val CLIPS: TargetSetting = Flag("clips", true)
		public val KEY_TOLERANCE: TargetSetting = Number("key_tolerance", 0.25, 0.0, 10.0, 0.05, 2)
		public val SAMPLE_PAIRS: TargetSetting = Number("sample_pairs", 48.0, 0.0, 1000.0, 8.0, 0)
	}
}

/** Receives exported files by relative path. Exporters never touch the file system directly. */
public fun interface OutputSink {
	public fun write(path: String, bytes: ByteArray)
}

/** A lowered export: everything decided and measured, ready to write. */
public interface LoweredExport {
	public val losses: List<LossEntry>
	public fun write(sink: OutputSink)
}

/**
 * An exporter: a capability profile, a lowering from the IR and a writer. The same IR and the same
 * compiler version always produce the same bytes; no timestamps or randomness enter an export.
 */
public interface ExportTarget {
	public val id: String
	public val family: TargetFamily
	public val capabilities: CapabilityProfile
	/** File extension or a short description for UIs, e.g. "moc3 + model3.json". */
	public val description: String
	/** The settings [plan] reads; keys outside this list are ignored. */
	public val settings: List<TargetSetting> get() = emptyList()
	public fun plan(ir: RigIR, options: ExportOptions): LoweredExport
}

/** The outcome of one export. */
public data class ExportReport(
	val target: String, val compiler: String, val files: List<String>, val losses: List<LossEntry>,
) {
	public fun toJson(): String = buildString {
		append("{\"target\":").append(quote(target)).append(",\"compiler\":").append(quote(compiler))
		append(",\"files\":[").append(files.joinToString(",", transform = ::quote)).append("]")
		append(",\"losses\":[")
		losses.forEachIndexed { index, loss ->
			if (index > 0) append(',')
			append("{\"object\":").append(quote(loss.objectId)).append(",\"feature\":").append(quote(loss.feature.name.lowercase()))
			append(",\"handling\":").append(quote(loss.handling.name.lowercase()))
			loss.error?.let { append(",\"error\":").append(it) }
			append(",\"note\":").append(quote(loss.note)).append('}')
		}
		append("]}")
	}

	private fun quote(text: String) = buildString {
		append('"')
		for (c in text) when (c) {
			'"' -> append("\\\"")
			'\\' -> append("\\\\")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
		}
		append('"')
	}
}

public object Compiler {
	/** This compiler's version, recorded in every export so differing outputs can be traced. */
	public val version: String by lazy {
		val properties = Properties()
		Compiler::class.java.getResourceAsStream("compiler.properties")?.use(properties::load)
		properties.getProperty("version") ?: "dev"
	}

	/** Lowers [ir] for [target] and writes it, collecting the file list and the loss report. */
	public fun export(target: ExportTarget, ir: RigIR, options: ExportOptions, sink: OutputSink): ExportReport {
		val lowered = target.plan(ir, options)
		val files = ArrayList<String>()
		lowered.write { path, bytes ->
			require(path.isNotBlank() && !path.startsWith("/") && path.split('/', '\\').none { it == ".." || it.isEmpty() }) {
				"Exporter wrote outside its output: $path"
			}
			require(path !in files) { "Exporter wrote $path twice" }
			files += path
			sink.write(path, bytes)
		}
		return ExportReport(target.id, version, files, lowered.losses)
	}
}

/** The targets one host offers, by id. */
public class ExportRegistry(targets: List<ExportTarget>) {
	public val targets: List<ExportTarget> = targets.toList()
	init {
		require(this.targets.map { it.id }.distinct().size == this.targets.size) { "Duplicate export target ids" }
	}
	public operator fun get(id: String): ExportTarget =
		targets.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown export target: $id (${targets.joinToString { it.id }})")
}
