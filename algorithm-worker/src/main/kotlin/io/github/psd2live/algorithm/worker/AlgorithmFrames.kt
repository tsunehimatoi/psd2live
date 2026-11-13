package io.github.psd2live.algorithm.worker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary-framed stdio transport for protocol 2 algorithm plugins:
 * `[u32le headerLen][utf8 json header][u32le blobCount]([u32le keyLen][utf8 key][u32le dataLen][bytes])*`.
 */
object AlgorithmFrames {
    private const val MAX_HEADER_BYTES = 16 * 1024 * 1024
    private const val MAX_BLOB_COUNT = 100_000
    private const val MAX_KEY_BYTES = 256
    private const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_BLOB_BYTES = 512L * 1024 * 1024

    data class Frame(val header: JsonObject, val blobs: Map<String, ByteArray>)

    fun write(output: OutputStream, header: JsonObject, blobs: Map<String, ByteArray>) {
        val headerBytes = header.toString().encodeToByteArray()
        require(headerBytes.size <= MAX_HEADER_BYTES) { "Plugin frame header exceeds size limit" }
        require(blobs.size <= MAX_BLOB_COUNT) { "Plugin frame has too many blobs" }
        var total = 0L
        val intBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        fun writeInt(value: Int) {
            intBuf.clear()
            intBuf.putInt(value)
            output.write(intBuf.array())
        }
        writeInt(headerBytes.size)
        output.write(headerBytes)
        writeInt(blobs.size)
        for ((key, data) in blobs) {
            val keyBytes = key.encodeToByteArray()
            require(keyBytes.size in 1..MAX_KEY_BYTES) { "Invalid plugin blob key length" }
            require(data.size <= MAX_BLOB_BYTES) { "Plugin blob exceeds size limit" }
            total += data.size
            require(total <= MAX_TOTAL_BLOB_BYTES) { "Plugin frame blobs exceed total size limit" }
            writeInt(keyBytes.size)
            output.write(keyBytes)
            writeInt(data.size)
            output.write(data)
        }
        output.flush()
    }

    fun read(input: InputStream): Frame? {
        val headerLen = readIntOrNull(input) ?: return null
        require(headerLen in 1..MAX_HEADER_BYTES) { "Invalid plugin frame header length: $headerLen" }
        val headerBytes = readExact(input, headerLen)
        val header = Json.parseToJsonElement(headerBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        val blobCount = readInt(input)
        require(blobCount in 0..MAX_BLOB_COUNT) { "Invalid plugin frame blob count: $blobCount" }
        val blobs = HashMap<String, ByteArray>(blobCount)
        var total = 0L
        repeat(blobCount) {
            val keyLen = readInt(input)
            require(keyLen in 1..MAX_KEY_BYTES) { "Invalid plugin blob key length: $keyLen" }
            val key = readExact(input, keyLen).decodeToString(throwOnInvalidSequence = true)
            val dataLen = readInt(input)
            require(dataLen in 0..MAX_BLOB_BYTES) { "Invalid plugin blob size: $dataLen" }
            total += dataLen
            require(total <= MAX_TOTAL_BLOB_BYTES) { "Plugin frame blobs exceed total size limit" }
            val data = readExact(input, dataLen)
            require(blobs.put(key, data) == null) { "Duplicate plugin blob key: $key" }
        }
        return Frame(header, blobs)
    }

    private fun readIntOrNull(input: InputStream): Int? {
        val buf = ByteArray(4)
        var offset = 0
        while (offset < 4) {
            val read = input.read(buf, offset, 4 - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw EOFException("Unexpected EOF reading frame length")
            }
            offset += read
        }
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readInt(input: InputStream): Int =
        readIntOrNull(input) ?: throw EOFException("Unexpected EOF reading frame integer")

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buf, offset, length - offset)
            if (read < 0) throw EOFException("Unexpected EOF reading frame payload")
            offset += read
        }
        return buf
    }
}
