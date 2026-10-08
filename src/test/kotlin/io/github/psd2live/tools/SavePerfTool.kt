package io.github.psd2live.tools

import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import io.github.psd2live.project.SyntheticProject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test

/**
 * Time to save a generated project of realistic size, the first time, again unchanged, and after reopening it.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SavePerfTool'
 * PSD2LIVE_SAVE_LAYERS (default 384), PSD2LIVE_SAVE_SIZE (pixels per side, default 320) and
 * PSD2LIVE_SAVE_REVISIONS (default 40) set the project's size; see [SyntheticProject]. The head cache is off,
 * since the generated layers have no rig worth caching. Writes build/tools/save-perf/report.txt.
 */
class SavePerfTool {
	private fun since(start: Long) = (System.nanoTime() - start) / 1_000_000

	@Test fun profile() {
		requireTools()
		val out = output("save-perf")
		val directory = Files.createTempDirectory("save-perf-")
		val project = SyntheticProject(directory, setting("PSD2LIVE_SAVE_LAYERS", "384").toInt(),
			setting("PSD2LIVE_SAVE_SIZE", "320").toInt(), setting("PSD2LIVE_SAVE_REVISIONS", "40").toInt())
		val repository = ProjectRepository(writeHeadCache = false)
		val lines = ArrayList<String>()
		runBlocking {
			val target = directory.resolve("project.psd2live")
			repeat(3) { run ->
				val start = System.nanoTime()
				repository.save(project.capture(), target)
				lines += "save ${run + 1}: ${since(start)} ms"
			}
			lines += "archive: ${Files.size(target) / 1024} KiB"
			repository.open(target).use { opened ->
				val capture = ProjectSaveCapture(opened.projectId, opened.history.state(), JsonObject(emptyMap()), opened.source, opened.store)
				repeat(2) { run ->
					val start = System.nanoTime()
					repository.save(capture, directory.resolve("reopened.psd2live"))
					lines += "save after open ${run + 1}: ${since(start)} ms"
				}
			}
			val start = System.nanoTime()
			repository.open(target).close()
			lines += "open: ${since(start)} ms"
		}
		lines.forEach(::println)
		out.resolve("report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
		directory.toFile().deleteRecursively()
	}
}
