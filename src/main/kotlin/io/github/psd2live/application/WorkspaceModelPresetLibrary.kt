package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.prefs.Preferences

@Serializable
internal data class WorkspaceModelPresetEntry(
    val id: String,
    val name: String,
    val nameKey: String? = null,
    val baseline: String? = null,
    val choices: WorkspaceInitialPreset = WorkspaceInitialPreset(),
    val stack: JsonObject? = null,
    val targetNames: Map<String, String> = emptyMap(),
)

/** Reusable model settings are JSON in preferences, including the bundled defaults. */
internal class WorkspaceModelPresetLibrary(private val node: Preferences) {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val defaults = json.decodeFromString<List<WorkspaceModelPresetEntry>>(
        requireNotNull(javaClass.getResourceAsStream("/presets/model-presets.json")).bufferedReader().use { it.readText() })
    private val changes = MutableStateFlow(load())
    val state = changes.asStateFlow()

    @Synchronized fun snapshot(): List<WorkspaceModelPresetEntry> = changes.value
    @Synchronized fun get(id: String): WorkspaceModelPresetEntry = snapshot().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Preset not found: $id")

    @Synchronized fun save(name: String, raw: JsonObject, model: RigPreviewModel): WorkspaceModelPresetEntry {
        val stack = RigModifierStack.fromJson(raw)
        require(stack.appliedCapabilities.isEmpty()) { "Save a preset before applying its modifiers; applied content belongs to the model" }
        val names = model.rig.puppet.drawables.associate { it.id.raw to it.name } +
            model.rig.puppet.deformers.associate { it.id.raw to it.name } +
            model.rig.puppet.parts.associate { it.id.raw to it.name } +
            model.analysis.source.layers.associate { it.id.raw to it.name }
        val clean = stack.copy(organization = RigModifierOrganization.nodes(stack), instances = stack.instances.map { entry ->
            entry.copy(inputFingerprint = null, config = if (entry.type == "simulation")
                JsonObject(entry.config.filterKeys { it != "bake" } + ("auto_bake" to JsonPrimitive(true))) else entry.config)
        }, overrides = emptyMap(), retiredIds = emptySet(), appliedCapabilities = emptyList())
        val existing = snapshot().firstOrNull { it.nameKey == null && it.name == name.trim() }
        return store(WorkspaceModelPresetEntry(existing?.id ?: UUID.randomUUID().toString(), name.trim(),
            stack = clean.toJson(), targetNames = names.filterKeys { id -> stack.instances.any { item -> item.targets.any { it.id == id } } }))
    }

    @Synchronized fun importJson(raw: String): WorkspaceModelPresetEntry {
        require(raw.length <= 8 * 1024 * 1024) { "Preset JSON is too large" }
        val source = json.decodeFromString<WorkspaceModelPresetEntry>(raw)
        return store(source.copy(id = UUID.randomUUID().toString(), nameKey = null))
    }
    @Synchronized fun exportJson(id: String): String = json.encodeToString(WorkspaceModelPresetEntry.serializer(), get(id))
    @Synchronized fun delete(id: String) {
        node.node(id).removeNode(); node.flush()
        // Defaults are restored through their JSON resource, then read by the same path as user presets.
        changes.value = load()
    }

    private fun validate(entry: WorkspaceModelPresetEntry) {
        require(entry.id.matches(Regex("[A-Za-z0-9_.:-]+")) && entry.name.isNotBlank() && entry.name.length <= 160) { "Invalid preset name or identity" }
        require((entry.baseline != null) != (entry.stack != null)) { "A preset must contain either a baseline or a modifier stack" }
        require(entry.baseline == null || entry.baseline in setOf("legacy", "modern", "empty")) { "Unknown preset baseline" }
        entry.stack?.let { raw ->
            val stack = RigModifierStack.fromJson(raw)
            require(stack.instances.size <= 128 && stack.overrides.isEmpty() && stack.appliedCapabilities.isEmpty()) { "Presets contain at most 128 modifiers and no applied content" }
            stack.instances.forEach { RigModifierAlgorithms.registry.definition(it) }
            RigModifierAlgorithms.evaluator().validateOrder(stack)
            RigModifierOrganization.validate(stack, RigModifierOrganization.nodes(stack))
        }
    }
    private fun store(entry: WorkspaceModelPresetEntry): WorkspaceModelPresetEntry {
        validate(entry)
        write(entry)
        changes.value = load()
        return entry
    }
    private fun write(entry: WorkspaceModelPresetEntry) {
        val content = json.encodeToString(WorkspaceModelPresetEntry.serializer(), entry)
        val target = node.node(entry.id)
        val chunks = content.chunked(Preferences.MAX_VALUE_LENGTH)
        val previous = target.keys().associateWith { target.get(it, "") }
        try {
            chunks.forEachIndexed { index, chunk -> target.put("part$index", chunk) }
            target.putInt("parts", chunks.size)
            target.flush()
        } catch (failure: Exception) {
            runCatching { target.clear(); previous.forEach { (key, value) -> target.put(key, value) }; target.flush() }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun load(): List<WorkspaceModelPresetEntry> {
        for (entry in defaults) if (!node.nodeExists(entry.id)) write(entry)
        return node.childrenNames().map { id ->
            val target = node.node(id)
            val count = target.getInt("parts", 0)
            require(count in 1..2048) { "Invalid preset JSON chunks: $id" }
            val raw = (0 until count).joinToString("") { requireNotNull(target.get("part$it", null)) }
            json.decodeFromString<WorkspaceModelPresetEntry>(raw).also(::validate)
        }.sortedWith(compareBy<WorkspaceModelPresetEntry> { defaults.indexOfFirst { item -> item.id == it.id }.let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }.thenBy { it.name })
    }
    companion object {
        val shared by lazy { WorkspaceModelPresetLibrary(Preferences.userRoot().node("io.github.psd2live.settings/model-presets")) }
    }
}
