package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract for "did this command change anything".
 *
 * Both directions matter and they are not equally visible. A command that is wrongly *kept* costs one
 * extra history node; a command that is wrongly *dropped* loses a real edit with no signal anywhere. The
 * cases below are written so the second failure mode is the one that fails loudly.
 */
class RigCommandDeltaTest {

    private val param = Parameter(ParameterId("ParamAngleX"), "AngleX", -30f, 30f, 0f)

    /** A real parameter that no grid keys, so a `set` naming it takes the seeding path rather than throwing. */
    private val otherParam = Parameter(ParameterId("ParamOther"), "Other", -1f, 1f, 0f)

    private fun mesh(positions: FloatArray, grid: KeyformGrid<MeshDeltaForm>? = null): PuppetModel = PuppetModel(
        parameters = listOf(param, otherParam),
        parts = emptyList(),
        deformers = emptyList(),
        drawables = listOf(
            Drawable(
                id = DrawableId("artmesh_1"),
                name = "ArtMesh1",
                parentDeformerId = null,
                blendMode = BlendMode.Normal,
                maskedBy = emptyList(),
                mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
                geometryGrid = grid,
            ),
        ),
        rootChildren = emptyList(),
        rootPartId = null,
    )

    /** The smallest model with a part to move into, for the structure cases that need one. */
    private fun warpModel(partId: PartId?): PuppetModel = PuppetModel(
        parameters = listOf(param, otherParam),
        parts = listOf(Part(PartId("part_1"), "Part1", emptyList(), true)),
        deformers = listOf(
            Deformer.Warp(
                id = DeformerId("warp_1"), name = "Warp1", parent = null, partId = partId,
                rows = 2, columns = 2, isQuadTransform = false, isVisible = true,
                geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(FloatArray(18))))),
            ),
        ),
        drawables = emptyList(),
        rootChildren = emptyList(),
        rootPartId = null,
    )

    /** One key at angle 0 holding [deltas], so a `set` at that coordinate addresses a real cell. */
    private fun keyedGrid(deltas: FloatArray): KeyformGrid<MeshDeltaForm> = KeyformGrid(
        listOf(KeyformAxis(ParameterId("ParamAngleX"), floatArrayOf(0f))),
        listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(deltas))),
    )

    /** Six vertices, so a topology delete still leaves a valid mesh behind. */
    private val flat = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f, 5f, 0f, 5f, 10f)

    private val vertexCount get() = flat.size

    /** The commands `compile` keeps. Empty means it judged every one of them ineffective. */
    private fun kept(model: PuppetModel, vararg commands: JsonObject): List<JsonObject> =
        RigAuthoringJournal.compile(model, JsonArray(commands.toList())).second

    private fun geometryCommand(points: FloatArray): JsonObject = buildJsonObject {
        put("op", "canvas_geometry"); put("kind", "mesh"); put("id", "artmesh_1")
        putJsonObject("key") { }
        put("points", JsonArray(points.map(::JsonPrimitive)))
    }

    private fun setCommand(parameter: String, deltas: FloatArray, value: Float = 0f): JsonObject = buildJsonObject {
        put("op", "set"); put("target", "artmesh:artmesh_1")
        putJsonObject("key") { put(parameter, value) }
        putJsonObject("geometry") { put("positionDeltas", JsonArray(deltas.map(::JsonPrimitive))) }
    }

    @Test
    fun aCanvasGeometryWriteOfTheSamePointsIsDropped() {
        val model = mesh(flat)
        assertTrue(kept(model, geometryCommand(flat)).isEmpty(), "writing the points the mesh already has changed nothing")
    }

    @Test
    fun aCanvasGeometryWriteOfMovedPointsIsKept() {
        val model = mesh(flat)
        val moved = flat.copyOf().also { it[0] = 5f }
        val kept = kept(model, geometryCommand(moved))
        assertEquals(1, kept.size, "a real move must survive the filter")
    }

    /** A drag that ends a hair off its start is float noise, not an edit. */
    @Test
    fun canvasGeometryJitterBelowTheEpsilonIsDropped() {
        val model = mesh(flat)
        val jittered = flat.copyOf().also { it[0] = 1e-8f }
        assertTrue(kept(model, geometryCommand(jittered)).isEmpty())
    }

    /** ...and a move large enough to see must not be swallowed by the same epsilon. */
    @Test
    fun canvasGeometryMovementAboveTheEpsilonIsKept() {
        val model = mesh(flat)
        val nudged = flat.copyOf().also { it[0] = 1e-3f }
        assertEquals(1, kept(model, geometryCommand(nudged)).size)
    }

    /**
     * The case the address test exists for. `seed` means "insert a key holding the current shape", so a
     * payload that happens to equal the evaluated value is still a structural edit.
     */
    @Test
    fun aSetThatWouldSeedAnAxisIsKeptEvenWhenTheValueMatches() {
        val unkeyed = mesh(flat)                                   // no geometryGrid at all
        assertEquals(1, kept(unkeyed, setCommand("ParamAngleX", FloatArray(vertexCount))).size, "seeding an axis is an edit")

        val keyed = mesh(flat, keyedGrid(FloatArray(vertexCount)))
        // Same value, but the coordinate names a parameter the grid does not key: apply seeds it.
        assertEquals(1, kept(keyed, setCommand("ParamOther", FloatArray(vertexCount))).size, "an unknown axis is seeded, not ignored")
    }

    /** The plain case: overwriting a cell with what it already holds. */
    @Test
    fun aSetOfTheValueAnAddressedCellAlreadyHoldsIsDropped() {
        val model = mesh(flat, keyedGrid(FloatArray(vertexCount)))
        assertTrue(kept(model, setCommand("ParamAngleX", FloatArray(vertexCount))).isEmpty())
    }

    @Test
    fun aSetThatMovesAnAddressedCellIsKept() {
        val model = mesh(flat, keyedGrid(FloatArray(vertexCount)))
        val moved = FloatArray(vertexCount).also { it[0] = 3f }
        assertEquals(1, kept(model, setCommand("ParamAngleX", moved)).size)
    }

    /** `part` moves a deformer's Parts-panel membership, which is a field like any other. */
    @Test
    fun aDeformerPartMoveIsJudgedOnTheCurrentMembership() {
        fun partCommand(partId: String?) = buildJsonObject {
            put("op", "structure")
            putJsonArray("edits") {
                add(buildJsonObject {
                    put("action", "part"); put("kind", "warp"); put("id", "warp_1")
                    put("part_id", partId?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
        val root = warpModel(partId = null)
        assertTrue(kept(root, partCommand(null)).isEmpty(), "already at the root")
        assertEquals(1, kept(root, partCommand("part_1")).size)

        val member = warpModel(partId = PartId("part_1"))
        assertTrue(kept(member, partCommand("part_1")).isEmpty())
        assertEquals(1, kept(member, partCommand(null)).size)
    }

    @Test
    fun aQuadTransformWriteOfTheCurrentValueIsDropped() {
        val model = warpModel(partId = null)
        // withDeformerQuadTransform refuses anything but a warp, so the flag only means something there.
        fun quad(kind: String, value: Boolean) = buildJsonObject {
            put("op", "structure")
            putJsonArray("edits") {
                add(buildJsonObject { put("action", "static"); put("kind", kind); put("id", "warp_1"); put("quad", JsonPrimitive(value)) })
            }
        }
        assertTrue(kept(model, quad("warp", false)).isEmpty())
        assertEquals(1, kept(model, quad("warp", true)).size)
        // Off a warp the command is rejected outright rather than judged, so there is nothing to assert
        // about the no-op path here.
    }

    /** An op with no single addressed slot must never be filtered, however plausible it looks. */
    @Test
    fun opsWithoutAnAddressedSlotAreAlwaysKept() {
        val model = mesh(flat)
        val topology = buildJsonObject {
            put("op", "canvas_topology"); put("id", "artmesh_1"); put("action", "delete")
            put("vertices", JsonArray(listOf(JsonPrimitive(4), JsonPrimitive(5))))
        }
        assertEquals(1, kept(model, topology).size)
    }

    /** A batch that writes the same slot twice keeps the first write and drops the second. */
    @Test
    fun theSecondWriteToTheSameSlotIsDropped() {
        val model = mesh(flat, keyedGrid(FloatArray(vertexCount)))
        val moved = FloatArray(vertexCount).also { it[0] = 3f }
        val kept = kept(model, setCommand("ParamAngleX", moved), setCommand("ParamAngleX", moved))
        assertEquals(1, kept.size, "the second write addresses a cell the first one already filled")
    }

    private fun staticCommand(vararg fields: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("op", "structure")
        putJsonArray("edits") {
            add(buildJsonObject {
                put("action", "static"); put("kind", "mesh"); put("id", "artmesh_1")
                fields.forEach { (key, value) -> put(key, value) }
            })
        }
    }

    /** Writing a value the object already holds is what a field session that ends where it started looks like. */
    @Test
    fun aStaticWriteOfTheCurrentValueIsDropped() {
        val model = mesh(flat)
        assertTrue(kept(model, staticCommand("opacity" to JsonPrimitive(1f))).isEmpty())
        assertTrue(kept(model, staticCommand("culling" to JsonPrimitive(false))).isEmpty())
        assertTrue(kept(model, staticCommand("multiply_color" to JsonArray(listOf(JsonPrimitive(1f), JsonPrimitive(1f), JsonPrimitive(1f))))).isEmpty())
    }

    @Test
    fun aStaticWriteOfANewValueIsKept() {
        val model = mesh(flat)
        assertEquals(1, kept(model, staticCommand("opacity" to JsonPrimitive(0.5f))).size)
        assertEquals(1, kept(model, staticCommand("culling" to JsonPrimitive(true))).size)
    }

    /** An edit that names no property writes nothing — that is not the same as one that changed nothing. */
    @Test
    fun anEmptyStaticEditIsNotTreatedAsANoOp() {
        val model = mesh(flat)
        assertEquals(1, kept(model, staticCommand()).size)
    }

    @Test
    fun aRenameToTheCurrentNameIsDropped() {
        val model = mesh(flat)
        fun rename(name: String) = buildJsonObject {
            put("op", "structure")
            putJsonArray("edits") {
                add(buildJsonObject { put("action", "rename"); put("kind", "mesh"); put("id", "artmesh_1"); put("name", name) })
            }
        }
        assertTrue(kept(model, rename("ArtMesh1")).isEmpty())
        // apply trims the name, so a padded no-op is still a no-op.
        assertTrue(kept(model, rename("  ArtMesh1  ")).isEmpty())
        assertEquals(1, kept(model, rename("Renamed")).size)
    }

    /** A no-op followed by a real edit must keep exactly the real one, and leave the model consistent. */
    @Test
    fun aNoOpInABatchDoesNotHideTheRealEditBesideIt() {
        val model = mesh(flat, keyedGrid(FloatArray(vertexCount)))
        val moved = FloatArray(vertexCount).also { it[0] = 3f }
        val result = RigAuthoringJournal.compile(
            model,
            JsonArray(listOf(setCommand("ParamAngleX", FloatArray(vertexCount)), setCommand("ParamAngleX", moved))),
        )
        assertEquals(1, result.second.size)
        // The evaluated model has to be the one a full apply would have produced, not a half-applied one.
        val applied = RigAuthoringJournal.compile(model, JsonArray(listOf(setCommand("ParamAngleX", moved)))).first
        val evaluated = result.first.drawables.single().geometryGrid!!.cells.single().form.positionDeltas
        val expected = applied.drawables.single().geometryGrid!!.cells.single().form.positionDeltas
        assertTrue(evaluated.contentEquals(expected), "the folded model drifted from the journal it kept")
    }

}
