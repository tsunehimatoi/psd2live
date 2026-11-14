package io.github.psd2live.tools

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Tool-only OS sampling; never call from a timed operation. No production cache dependencies. */
internal object SyntheticProcessMemory {
    data class Sample(val pid: Long, val alive: Boolean, val source: String,
                      val residentBytes: Long? = null, val privateBytes: Long? = null,
                      val privateKind: String? = null, val unavailable: String? = null)

    fun snapshot(pids: Set<Long>): List<Sample> {
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.startsWith("windows") -> windows(pids)
                os.startsWith("linux") -> pids.map(::linux)
                else -> pids.map { unavailable(it, "OS memory provider unavailable: $os") }
            }
        } catch (error: Exception) {
            pids.map { unavailable(it, "${error.javaClass.simpleName}: ${error.message}") }
        }
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    private fun unavailable(pid: Long, reason: String) = Sample(pid, alive(pid), "unavailable", unavailable = reason)

    private fun windows(pids: Set<Long>): List<Sample> {
        // Only numeric PIDs enter this fixed script; one subprocess serves the whole snapshot.
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}rows = @(foreach (${'$'}targetPid in @(${pids.joinToString(",")})) {
                try {
                    ${'$'}process = [System.Diagnostics.Process]::GetProcessById(${'$'}targetPid)
                    try {
                        ${'$'}process.Refresh()
                        [pscustomobject]@{ pid = ${'$'}targetPid; resident = ${'$'}process.WorkingSet64; private = ${'$'}process.PrivateMemorySize64 }
                    } finally { ${'$'}process.Dispose() }
                } catch {
                    [pscustomobject]@{ pid = ${'$'}targetPid; unavailable = ${'$'}_.Exception.Message }
                }
            })
            ConvertTo-Json -InputObject ${'$'}rows -Compress
        """.trimIndent()
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "OS memory snapshot timed out" }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.exitValue() == 0) { "OS memory snapshot failed (${process.exitValue()})" }
            val rows = Json.parseToJsonElement(output.trim().removePrefix("\uFEFF")).jsonArray.associate { element ->
                val row = element.jsonObject
                row.getValue("pid").jsonPrimitive.long to row
            }
            return pids.map { pid ->
                val row = rows[pid]
                Sample(pid, alive(pid), "windows-process", row?.get("resident")?.jsonPrimitive?.longOrNull,
                    row?.get("private")?.jsonPrimitive?.longOrNull, "private-committed",
                    row?.get("unavailable")?.jsonPrimitive?.content ?: if (row == null) "PID missing from snapshot" else null)
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }

    private fun linux(pid: Long): Sample {
        if (!alive(pid)) return Sample(pid, false, "linux-proc", unavailable = "process exited")
        return try {
            val root = Path.of("/proc", pid.toString())
            fun kilobytes(lines: List<String>, key: String): Long? = lines.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
            val resident = kilobytes(Files.readAllLines(root.resolve("status")), "VmRSS")
            val private = runCatching {
                val lines = Files.readAllLines(root.resolve("smaps_rollup"))
                val clean = checkNotNull(kilobytes(lines, "Private_Clean"))
                val dirty = checkNotNull(kilobytes(lines, "Private_Dirty"))
                clean + dirty + (kilobytes(lines, "Private_Hugetlb") ?: 0)
            }
            Sample(pid, alive(pid), "linux-proc", resident, private.getOrNull(), "private-resident",
                private.exceptionOrNull()?.let { "Private memory unavailable: ${it.message}" }
                    ?: if (resident == null) "VmRSS unavailable" else null)
        } catch (error: Exception) { unavailable(pid, "${error.javaClass.simpleName}: ${error.message}") }
    }
}
