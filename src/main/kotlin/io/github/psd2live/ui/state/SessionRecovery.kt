package io.github.psd2live.ui.state

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Whether the last session ended with unsaved edits it never got to save or discard. The workspace store keeps
 * every project's history on disk as it is edited; this marker, kept beside it under [root], names the project
 * that had unsaved edits and where its history is. It is written as history persists while the project is
 * dirty, removed once the project is saved or the app quits through its own save-or-discard prompt, so one left
 * behind means the app was killed (a crash, the system running out of memory) with edits only in the store.
 */
internal class SessionRecovery(private val root: Path) {
    data class Session(val storeRoot: Path, val projectId: String, val projectFile: String?, val inputPath: String, val name: String) {
        /** Where the project's history lives; without it there is nothing to restore. */
        val head: Path get() = storeRoot.resolve(projectId).resolve("HEAD.json")
    }

    private val file = root.resolve("session.json")

    @Synchronized
    fun record(session: Session) {
        runCatching {
            Files.createDirectories(root)
            val json = buildJsonObject {
                put("version", 1); put("storeRoot", session.storeRoot.toString()); put("projectId", session.projectId)
                session.projectFile?.let { put("projectFile", it) }
                put("inputPath", session.inputPath); put("name", session.name)
            }
            val staged = Files.createTempFile(root, "session", ".tmp")
            Files.writeString(staged, json.toString())
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    @Synchronized
    fun forget() {
        runCatching { Files.deleteIfExists(file) }
    }

    /** The session the last run left unsaved, or null; a marker whose history is gone is dropped. */
    @Synchronized
    fun pending(): Session? {
        if (!Files.isRegularFile(file)) return null
        val session = runCatching {
            val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
            Session(Path.of(json.getValue("storeRoot").jsonPrimitive.content), json.getValue("projectId").jsonPrimitive.content,
                json["projectFile"]?.jsonPrimitive?.contentOrNull, json.getValue("inputPath").jsonPrimitive.content,
                json.getValue("name").jsonPrimitive.content)
        }.getOrNull()
        if (session == null || !Files.isRegularFile(session.head)) { forget(); return null }
        return session
    }
}
