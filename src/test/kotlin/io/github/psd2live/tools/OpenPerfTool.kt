package io.github.psd2live.tools

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.MaterializedRigStore
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.bridge.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Time to open a saved project and build its head's preview as the app does (ProjectRepository.open, then a fresh
 * WorkspacePreviewBuilder with no current model), for three archives of one project: as the app saves it now, with
 * each revision's authored rig (rig/revisions/, built from without generation or replay) and the head cache; with the
 * head cache only, as archives from before stored rigs open (generation and replay, the skeleton bake seeded); with
 * neither (generation, replay and the skeleton bake).
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*OpenPerfTool'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default). The project has the auto skeleton, two swings and one baked
 * simulation. Each run starts from empty authored rig and skeleton caches, as a freshly started app does; other
 * in-process caches (atlas pages, generated geometry) are warm after the first run for every variant alike. Every
 * variant must build the saved model. Writes build/tools/open-perf/report.json and report.md.
 */
class OpenPerfTool {
	private fun r1(value: Double) = Math.round(value * 10) / 10.0

	@Test fun profile() {
		requireTools()
		System.setProperty("psd2live.validatePreviewBundles", "false")
		val sample = Sample.fromEnvironment()
		val out = output("open-perf")
		val builder = WorkspacePreviewBuilder()
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
		runBlocking {
			WorkspaceSourceImporter(runtime).importPsd(sample.path.toAbsolutePath(), null, runtime.state.value.state, initialConfig = PipelineConfig())
			val plain = runtime.capture()
			val spec = SkeletonAutoBuilder.build(plain.model.analysis, plain.model.rig)
			val skeletal = plain.document.copy(rigEdits = plain.document.rigEdits.copy(skeleton = spec))
			val base = builder.build(skeletal)
			val puppet = base.baseRig.puppet
			val layers = base.analysis.layers.associateBy { it.source.id.raw }
			fun meshes(tag: SemanticTag) = base.rig.puppet.drawables.filter { d ->
				d.mesh != null && layers[base.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag
			}
			val front = meshes(SemanticTag.FRONT_HAIR)
			val back = meshes(SemanticTag.BACK_HAIR).first()
			val accent = front.getOrNull(1) ?: meshes(SemanticTag.HEADWEAR).first()
			var overlay = skeletal.rigEdits
			overlay = SwingAuthoring.put(overlay, overlay.applyTo(puppet), RigSwingEdit.single("front", "Front", SwingKind.VERTICAL,
				listOf(front.first().id.raw), listOf("ParamSwingFront"), shape = SwingShape(magnitude = 0.1f)))
			overlay = SwingAuthoring.put(overlay, overlay.applyTo(puppet), RigSwingEdit.single("accent", "Accent", SwingKind.LATERAL,
				listOf(accent.id.raw), listOf("ParamSwingAccent"), shape = SwingShape(magnitude = 0.2f, parallel = 0.7f)))
			val world = CpuDeformationEvaluator().evaluate(base.rig.puppet, emptyMap()).worldPositions.getValue(back.id)
			val ys = (1 until world.size step 2).map { world[it] }
			val top = ys.max(); val bottom = ys.min()
			val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN,
				FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
			overlay = overlay.copy(authoringJournal = overlay.authoringJournal + VertexGroupJournal.encode(pin))
			overlay = SimAuthoring.put(overlay, overlay.applyTo(puppet), RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw),
				modes = 1, keys = 3, inputs = RigSimEdit.defaultInputs(puppet.parameters.mapTo(HashSet()) { it.id.raw }, SimKind.HAIR)))
			overlay = SimAuthoring.withBake(overlay, "back", SimBaker.bake(SimAuthoring.unbakedModel(overlay, puppet, "back"), overlay.simEdits.single()))
			val document = skeletal.copy(rigEdits = overlay)
			val model = builder.build(document)
			runtime.install(runtime.state.value.state, plain.projectId, document, model, discardUnsaved = true)
			val expected = ContentHash.of(PuppetIr.toIr(model.rig.puppet))

			val storeRoot = Files.createTempDirectory("psd2live-open-perf-")
			val store = WorkspaceStore(storeRoot)
			val capture = ProjectSaveCapture(plain.projectId, runtime.history(), JsonObject(emptyMap()), sample.path.toAbsolutePath(), store)
			class Archive(val variant: String, val file: java.nio.file.Path, val saveMs: Double)
			val archives = listOf(
				Triple("authored rig", "authored.psd2live", ProjectRepository()),
				Triple("head cache only", "cached.psd2live", ProjectRepository()),
				Triple("neither", "plain.psd2live", ProjectRepository(writeHeadCache = false)),
			).map { (variant, name, repository) ->
				val file = out.toPath().resolve(name)
				Files.deleteIfExists(file)
				val (_, ms) = timed {
					if (variant == "authored rig") repository.save(capture, file) else withoutMaterializedRigs { repository.save(capture, file) }
				}
				Archive(variant, file, ms)
			}

			class Run(val variant: String, val openMs: Double, val rebuildMs: Double, val bakes: Int)
			val runs = ArrayList<Run>()
			repeat(3) {
				for (archive in archives) {
					MaterializedRigStore.clear()
					SkeletonRig.clearCache()
					val (opened, openMs) = timed { ProjectRepository().open(archive.file) }
					val misses = SkeletonRig.cacheMisses
					// Inside the open, as the app builds it: the stored rigs read their objects from the extracted archive.
					val (rebuilt, rebuildMs) = opened.use { timed { WorkspacePreviewBuilder().build(it.history.head().snapshot) } }
					assertEquals(expected, ContentHash.of(PuppetIr.toIr(rebuilt.rig.puppet)), "${archive.variant} builds the saved model")
					runs += Run(archive.variant, openMs, rebuildMs, SkeletonRig.cacheMisses - misses)
				}
			}
			MaterializedRigStore.clear()
			fun median(values: List<Double>) = values.sorted()[values.size / 2]
			val report = buildJsonObject {
				put("sample", sample.name)
				putJsonArray("archives") { archives.forEach { archive -> addJsonObject {
					put("variant", archive.variant); put("bytes", Files.size(archive.file)); put("save_ms", r1(archive.saveMs))
				} } }
				putJsonArray("runs") { runs.forEach { run -> addJsonObject {
					put("variant", run.variant); put("open_ms", r1(run.openMs)); put("rebuild_ms", r1(run.rebuildMs))
					put("total_ms", r1(run.openMs + run.rebuildMs)); put("skeleton_bakes", run.bakes)
				} } }
			}
			out.resolve("report.json").writeText(report.toString())
			val table = StringBuilder()
			table.appendLine("# Open a project (${sample.name})").appendLine()
			table.appendLine(archives.joinToString("; ") { "%s: archive %.1f MB, save %.0f ms".format(it.variant, Files.size(it.file) / 1e6, it.saveMs) }).appendLine()
			table.appendLine("| archive | open (extract, unpack, seed, adopt) | build head | total = first display | skeleton bakes |")
			table.appendLine("| --- | ---: | ---: | ---: | ---: |")
			for (archive in archives) {
				val mine = runs.filter { it.variant == archive.variant }
				table.appendLine("| ${archive.variant} | %.0f ms | %.0f ms | %.0f ms | %s |".format(median(mine.map { it.openMs }), median(mine.map { it.rebuildMs }),
					median(mine.map { it.openMs + it.rebuildMs }), mine.joinToString("/") { it.bakes.toString() }))
			}
			table.appendLine().appendLine("medians of 3 runs; all runs: " + runs.joinToString { "%s %.0f+%.0f".format(it.variant, it.openMs, it.rebuildMs) })
			out.resolve("report.md").writeText(table.toString())
			println(table)
			storeRoot.toFile().deleteRecursively()
		}
	}
}
