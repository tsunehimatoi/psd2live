package io.github.psd2live.agent

import io.github.psd2live.core.GeometrySafetyRejectedException
import io.github.psd2live.core.RigGeometryTools
import io.github.psd2live.history.StaleWorkspaceHeadException
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

class GeometryGatedAuthoringIntegrationTest {
    @TempDir lateinit var temp: Path

    private fun createPng(path: Path) {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..29) for (x in 2..29) image.setRGB(x, y, 0xffff6699.toInt())
        ImageIO.write(image, "png", path.toFile())
    }

    private fun modelSignature(viewModel: PSD2LiveViewModel): String {
        val model = viewModel.state.value.previewModel!!.rig.puppet
        val digest = MessageDigest.getInstance("SHA-256")
        model.drawables.sortedBy { it.id.raw }.forEach { drawable ->
            digest.update(drawable.id.raw.toByteArray())
            drawable.mesh?.let { mesh ->
                mesh.positions.forEach { value -> digest.update(value.toRawBits().toString().toByteArray()) }
                mesh.uvs.forEach { value -> digest.update(value.toRawBits().toString().toByteArray()) }
                mesh.indices.forEach { value -> digest.update(value.toString().toByteArray()) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun exportedBytes(directory: Path): Map<String, ByteArray> = Files.walk(directory).use { paths ->
        paths.iterator().asSequence().filter(Files::isRegularFile).associate { path ->
            directory.relativize(path).toString() to Files.readAllBytes(path)
        }
    }

    private fun shifted(points: FloatArray, dx: Float, dy: Float) = points.copyOf().also { result ->
        for (i in result.indices step 2) { result[i] += dx; result[i + 1] += dy }
    }

    private fun inverted(points: FloatArray, triangle: IntArray): FloatArray = points.copyOf().also { result ->
        val b = triangle[1]
        val c = triangle[2]
        val bx = result[b * 2]; val by = result[b * 2 + 1]
        result[b * 2] = result[c * 2]; result[b * 2 + 1] = result[c * 2 + 1]
        result[c * 2] = bx; result[c * 2 + 1] = by
    }

    @Test fun dryRunCommitParityAndRejectedBatchHaveZeroPersistentMutation() = runBlocking {
        val png = temp.resolve("art.png")
        createPng(png)
        PSD2LiveViewModel().use { viewModel ->
            ViewModelAgentWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachAgentWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") {
                        add(buildJsonObject { put("path", png.toString()); put("name", "art"); put("role", "objects") })
                        add(buildJsonObject { put("path", png.toString()); put("name", "back"); put("role", "objects") })
                    }
                })
                val layer = created.affectedLayerIds.first()
                val drawableId = viewModel.state.value.previewModel!!.rig.layerIdByDrawableId.entries.first { it.value == layer }.key
                val model = viewModel.state.value.previewModel!!.rig.puppet
                val drawable = model.drawables.single { it.id.raw == drawableId }
                val points = RigGeometryTools.geometry(model, "mesh", drawableId, emptyMap()).points
                val safePoints = shifted(points, 0.01f, 0.01f)
                val safeCommand = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", drawableId, emptyMap(), safePoints)
                val safeBatch = buildJsonArray { add(safeCommand) }

                val beforeDryHistory = workspace.history()
                val beforeDrySnapshot = workspace.snapshot()
                val beforeDryJournal = viewModel.state.value.rigEdits.authoringJournal.size
                val beforeDryModel = modelSignature(viewModel)
                val singleDryStarted = System.nanoTime()
                val dry = workspace.dryRunRig(created.historyNodeId, safeBatch, MutationAuthor.AGENT)
                val singleDryMs = (System.nanoTime() - singleDryStarted) / 1_000_000.0
                assertTrue(dry.acceptedByGeometryGate)
                assertTrue(dry.wouldChange)
                assertTrue(dry.wouldCommit)
                assertEquals(1, dry.compiledCommandCount)
                assertEquals(beforeDryHistory, workspace.history())
                assertEquals(beforeDrySnapshot.revisionId, workspace.snapshot().revisionId)
                assertEquals(beforeDrySnapshot.historyHeadNodeId, workspace.snapshot().historyHeadNodeId)
                assertEquals(beforeDrySnapshot.projectDirty, workspace.snapshot().projectDirty)
                assertEquals(beforeDryJournal, viewModel.state.value.rigEdits.authoringJournal.size)
                assertEquals(beforeDryModel, modelSignature(viewModel))

                val singleCommitStarted = System.nanoTime()
                val committed = workspace.authorRig(created.historyNodeId, safeBatch, MutationAuthor.AGENT)
                val singleCommitMs = (System.nanoTime() - singleCommitStarted) / 1_000_000.0
                assertTrue(committed.applied)
                assertEquals(dry.candidateRevision, committed.revisionId)
                assertEquals(dry.geometrySafety, committed.geometrySafety)
                assertEquals(beforeDryHistory.nodes.size + 1, workspace.history().nodes.size)
                assertContentEquals(safePoints, RigGeometryTools.geometry(
                    viewModel.state.value.previewModel!!.rig.puppet, "mesh", drawableId, emptyMap(),
                ).points)

                val postSinglePoints = RigGeometryTools.geometry(
                    viewModel.state.value.previewModel!!.rig.puppet, "mesh", drawableId, emptyMap(),
                ).points
                val multiBatch = buildJsonArray {
                    for (step in 1..4) add(canvasGeometryCommand(
                        EditHierarchyMode.EDIT, "mesh", drawableId, emptyMap(),
                        shifted(postSinglePoints, step * 0.002f, 0f),
                    ))
                }
                val multiDryStarted = System.nanoTime()
                val multiDry = workspace.dryRunRig(committed.historyNodeId, multiBatch, MutationAuthor.AGENT)
                val multiDryMs = (System.nanoTime() - multiDryStarted) / 1_000_000.0
                assertTrue(multiDry.acceptedByGeometryGate)
                assertEquals(4, multiDry.compiledCommandCount)
                val multiCommitStarted = System.nanoTime()
                val multiCommitted = workspace.authorRig(committed.historyNodeId, multiBatch, MutationAuthor.AGENT)
                val multiCommitMs = (System.nanoTime() - multiCommitStarted) / 1_000_000.0
                assertEquals(multiDry.candidateRevision, multiCommitted.revisionId)
                assertEquals(multiDry.geometrySafety, multiCommitted.geometrySafety)

                val currentModel = viewModel.state.value.previewModel!!.rig.puppet
                val currentDrawable = currentModel.drawables.single { it.id.raw == drawableId }
                val currentPoints = RigGeometryTools.geometry(currentModel, "mesh", drawableId, emptyMap()).points
                val unsafePoints = inverted(currentPoints, currentDrawable.mesh!!.indices.copyOfRange(0, 3))
                val unsafeCommand = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", drawableId, emptyMap(), unsafePoints)
                val unsafeBatch = buildJsonArray { add(unsafeCommand) }
                val unsafeDry = workspace.dryRunRig(multiCommitted.historyNodeId, unsafeBatch, MutationAuthor.AGENT)
                assertFalse(unsafeDry.acceptedByGeometryGate)
                assertFalse(unsafeDry.wouldCommit)
                assertTrue(unsafeDry.geometrySafety.getValue("newFlipCount").jsonPrimitive.int > 0)

                val beforeRejectHistory = workspace.history()
                val beforeRejectSnapshot = workspace.snapshot()
                val beforeRejectJournal = viewModel.state.value.rigEdits.authoringJournal.size
                val beforeRejectModel = modelSignature(viewModel)
                workspace.exportModel(multiCommitted.historyNodeId, temp.resolve("before-reject").toString())
                val exportedBefore = exportedBytes(temp.resolve("before-reject"))
                val rejection = assertFailsWith<GeometrySafetyRejectedException> {
                    workspace.authorRig(multiCommitted.historyNodeId, unsafeBatch, MutationAuthor.AGENT)
                }
                assertTrue(rejection.safetyReport.violations.any { it.reason.name == "GEOMETRY_NEW_FLIP" })
                assertEquals(beforeRejectHistory, workspace.history())
                assertEquals(beforeRejectSnapshot.revisionId, workspace.snapshot().revisionId)
                assertEquals(beforeRejectSnapshot.historyHeadNodeId, workspace.snapshot().historyHeadNodeId)
                assertEquals(beforeRejectSnapshot.projectDirty, workspace.snapshot().projectDirty)
                assertEquals(beforeRejectJournal, viewModel.state.value.rigEdits.authoringJournal.size)
                assertEquals(beforeRejectModel, modelSignature(viewModel))
                val exportedAfter = exportedBytes(temp.resolve("before-reject"))
                assertEquals(exportedBefore.keys, exportedAfter.keys)
                exportedBefore.forEach { (name, bytes) -> assertContentEquals(bytes, exportedAfter.getValue(name), name) }

                // The first edit is safe, the second is not: final-candidate rejection persists neither.
                val safeAgain = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", drawableId, emptyMap(), shifted(currentPoints, 0.02f, 0f))
                val atomicBatch = buildJsonArray { add(safeAgain); add(unsafeCommand) }
                assertFailsWith<GeometrySafetyRejectedException> {
                    workspace.authorRig(multiCommitted.historyNodeId, atomicBatch, MutationAuthor.AGENT)
                }
                assertEquals(beforeRejectHistory, workspace.history())
                assertEquals(beforeRejectModel, modelSignature(viewModel))

                val noOp = workspace.authorRig(multiCommitted.historyNodeId,
                    buildJsonArray { add(canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", drawableId, emptyMap(), currentPoints)) },
                    MutationAuthor.AGENT)
                assertFalse(noOp.applied)
                assertEquals(beforeRejectHistory, workspace.history())

                assertFailsWith<StaleWorkspaceHeadException> {
                    workspace.dryRunRig(created.historyNodeId, safeBatch, MutationAuthor.AGENT)
                }
                println(buildJsonObject {
                    put("event", "GEOMETRY_GATED_AUTHORING_PERFORMANCE")
                    put("singleGeometryDryRunMs", singleDryMs)
                    put("singleGeometryCommitMs", singleCommitMs)
                    put("fourCommandBatchDryRunMs", multiDryMs)
                    put("fourCommandBatchCommitMs", multiCommitMs)
                })
                Unit
            }
        }
    }
}
