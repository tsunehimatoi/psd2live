package io.github.autolive2d

import io.github.autolive2d.core.AutoLive2DPipeline
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.ProgressListener
import io.github.autolive2d.i18n.AppLanguage
import io.github.autolive2d.i18n.I18n
import io.github.autolive2d.i18n.tr
import io.github.autolive2d.ui.AutoLive2DFrame
import java.nio.file.Path
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.io.path.absolutePathString

fun main(arguments: Array<String>) {
	configureLanguage(arguments)
	if (arguments.isEmpty()) {
		UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
		SwingUtilities.invokeLater { AutoLive2DFrame().isVisible = true }
		return
	}
	if (arguments.any { it == "--help" || it == "-h" }) {
		printUsage()
		return
	}
	val options = CliOptions.parse(arguments)
	val config = PipelineConfig(
		atlasSize = options.int("--atlas", 4096),
		meshSpacing = options.int("--mesh-spacing", 64),
		headTurnStrength = options.float("--head-strength", 1f),
		bodyStrength = options.float("--body-strength", 1f),
		generatePhysics = !options.flags.contains("--no-physics"),
		exportCmo3 = !options.flags.contains("--no-cmo3"),
		exportMoc3 = !options.flags.contains("--no-moc3"),
	)
	require(config.exportCmo3 || config.exportMoc3) { tr("cli.exportFormatRequired") }
	val input = Path.of(options.required("--input"))
	val output = Path.of(options.value("--output") ?: input.toAbsolutePath().parent.resolve("autolive2d-output").toString())
	println(tr("cli.start", input.toAbsolutePath(), output.toAbsolutePath()))
	val result = AutoLive2DPipeline().run(input, output, config, ProgressListener { stage, fraction ->
		println("%3d%%  %s".format((fraction * 100).toInt(), stage))
	})
	println(tr("cli.complete", result.exportedFiles.size))
	result.exportedFiles.forEach { println("  ${it.path.absolutePathString()} (${it.bytes} bytes)") }
	result.warnings.forEach { System.err.println(tr("cli.warning", it)) }
}

private fun configureLanguage(arguments: Array<String>) {
	val index = arguments.indexOf("--lang")
	if (index < 0) return
	require(index + 1 < arguments.size) { tr("cli.missingValue", "--lang") }
	val raw = arguments[index + 1]
	val language = AppLanguage.fromTag(raw) ?: error(tr("cli.invalidLanguage", raw))
	I18n.setLanguage(language)
}

private fun printUsage() = println(tr("cli.usage"))

private data class CliOptions(val values: Map<String, String>, val flags: Set<String>) {
	fun value(name: String): String? = values[name]
	fun required(name: String): String = value(name) ?: error(tr("cli.missingRequired", name))
	fun int(name: String, default: Int): Int = value(name)?.toIntOrNull() ?: default
	fun float(name: String, default: Float): Float = value(name)?.toFloatOrNull() ?: default

	companion object {
		private val flagNames = setOf("--no-physics", "--no-cmo3", "--no-moc3")
		private val valueNames = setOf("--input", "--output", "--lang", "--atlas", "--mesh-spacing", "--head-strength", "--body-strength")
		fun parse(arguments: Array<String>): CliOptions {
			val values = linkedMapOf<String, String>()
			val flags = linkedSetOf<String>()
			var index = 0
			while (index < arguments.size) {
				val name = arguments[index]
				require(name in flagNames || name in valueNames) { tr("cli.unknownOption", name) }
				if (name in flagNames) {
					flags += name
					index++
				} else {
					require(index + 1 < arguments.size) { tr("cli.missingValue", name) }
					values[name] = arguments[index + 1]
					index += 2
				}
			}
			return CliOptions(values, flags)
		}
	}
}
