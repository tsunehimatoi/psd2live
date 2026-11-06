package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** Captures only source measurements and configuration needed to reproduce a fitted mouth. */
data class MouthSourceContext(val fit: MouthFit, val options: MouthOptions, val lineWidth: Float?) {
    fun rig(): MouthRig = MouthRig(fit, options, lineWidth)

    fun encode(): ModifierAlgorithmContext {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            require(fit.width > 0f && (fit.axisX != 0f || fit.axisY != 0f))
            out.floats(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.width)
            require(fit.upper.size == MouthFit.SAMPLES && fit.lower.size == MouthFit.SAMPLES)
            fit.upper.forEach { out.finite(it) }
            fit.lower.forEach { out.finite(it) }
            out.writeBoolean(fit.layered != null)
            fit.layered?.let { out.layered(it) }

            out.floats(options.early, options.formStrength)
            options.curve.points.forEach { out.floats(it.x, it.y) }
            out.writeBoolean(options.exactClose)
            out.writeBoolean(options.vowels)
            out.writeBoolean(options.capture)
            out.writeBoolean(options.jaw)
            out.finite(options.jawShare)
            out.writeBoolean(options.depth)
            out.finite(options.depthAmount)
            out.writeBoolean(options.spring)
            out.writeBoolean(options.tongue)
            out.floats(options.inside, options.reach, options.teethWiden, options.narrow)
            out.writeBoolean(lineWidth != null)
            lineWidth?.let { out.finite(it) }
        }
        val resource = ModifierAlgorithmResource(bytes.toByteArray())
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("measurement", resource.digest)
        }, mapOf(resource.digest to resource))
    }

    companion object {
        const val SCHEMA = "mouth_source/1"
        private const val STATION_COORDINATES = 65 * 2
        private const val MAX_COLUMNS = 4 * 1024 * 1024
        private const val MAX_BYTES = 12 * MAX_COLUMNS + 4096

        fun decode(context: ModifierAlgorithmContext): MouthSourceContext {
            val digest = (context.values["measurement"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(context.values.keys == setOf("schema", "measurement") &&
                context.values["schema"] == JsonPrimitive(SCHEMA) && digest != null) { "Invalid mouth source schema" }
            val resource = context.resources[digest]
            require(resource != null && context.resources.size == 1 && resource.size in 1..MAX_BYTES &&
                resource.digest == digest) { "Invalid or missing mouth measurements" }
            try {
                return DataInputStream(resource.read().inputStream()).use { input ->
                    val fit = MouthFit(input.finite(), input.finite(), input.finite(), input.finite(), input.finite(),
                        List(MouthFit.SAMPLES) { input.finite() }, List(MouthFit.SAMPLES) { input.finite() },
                        if (input.flag()) input.layered() else null)
                    require(fit.width > 0f && (fit.axisX != 0f || fit.axisY != 0f))
                    val options = MouthOptions(
                        early = input.finite(), formStrength = input.finite(),
                        curve = MouthCurve(List(7) { MouthCurvePoint(input.finite(), input.finite()) }),
                        exactClose = input.flag(), vowels = input.flag(), capture = input.flag(),
                        jaw = input.flag(), jawShare = input.finite(), depth = input.flag(), depthAmount = input.finite(),
                        spring = input.flag(), tongue = input.flag(), inside = input.finite(), reach = input.finite(),
                        teethWiden = input.finite(), narrow = input.finite(),
                    )
                    val lineWidth = if (input.flag()) input.finite() else null
                    require(input.read() == -1) { "Trailing mouth measurements" }
                    MouthSourceContext(fit, options, lineWidth)
                }
            } catch (error: EOFException) {
                throw IllegalArgumentException("Truncated mouth measurements", error)
            }
        }

        private fun DataOutputStream.layered(frame: LayeredMouthFrame) {
            require(frame.frameWidth > 0f && (frame.exX != 0f || frame.exY != 0f))
            writeBoolean(frame.hasLines)
            floats(frame.centerX, frame.centerY, frame.exX, frame.exY, frame.frameWidth)
            for (rim in listOf(frame.upperWorld65, frame.lowerWorld65, frame.upperLocal65, frame.lowerLocal65)) {
                require(rim.size == STATION_COORDINATES)
                rim.forEach { finite(it) }
            }
            finite(frame.lineNorm)
            val columns = frame.openColsX.size
            require(columns in 2..MAX_COLUMNS && frame.openTopY.size == columns && frame.openBotY.size == columns)
            writeInt(columns)
            frame.openColsX.forEach { finite(it) }
            frame.openTopY.forEach { finite(it) }
            frame.openBotY.forEach { finite(it) }
            bounds(frame.upperLipBoundsLocal)
            bounds(frame.lowerLipBoundsLocal)
            bounds(frame.tongueBoundsLocal)
            floats(frame.hUtVis0, frame.utSideReserve, frame.hLtVis0)
        }

        private fun DataInputStream.layered(): LayeredMouthFrame {
            val hasLines = flag()
            val centerX = finite()
            val centerY = finite()
            val exX = finite()
            val exY = finite()
            val width = finite()
            require(width > 0f && (exX != 0f || exY != 0f))
            val upperWorld = FloatArray(STATION_COORDINATES) { finite() }
            val lowerWorld = FloatArray(STATION_COORDINATES) { finite() }
            val upperLocal = FloatArray(STATION_COORDINATES) { finite() }
            val lowerLocal = FloatArray(STATION_COORDINATES) { finite() }
            val lineNorm = finite()
            val count = readInt()
            require(count in 2..MAX_COLUMNS && count.toLong() * 12 <= available()) { "Invalid mouth opening column count" }
            return LayeredMouthFrame(hasLines, centerX, centerY, exX, exY, width,
                upperWorld, lowerWorld, upperLocal, lowerLocal, lineNorm,
                FloatArray(count) { finite() }, FloatArray(count) { finite() }, FloatArray(count) { finite() },
                bounds(), bounds(), bounds(), finite(), finite(), finite())
        }

        private fun DataOutputStream.bounds(value: Bounds?) {
            writeBoolean(value != null)
            if (value != null) {
                require(value.left <= value.right && value.top <= value.bottom)
                floats(value.left, value.top, value.right, value.bottom)
            }
        }

        private fun DataInputStream.bounds(): Bounds? = if (flag()) Bounds(finite(), finite(), finite(), finite())
            .also { require(it.left <= it.right && it.top <= it.bottom) } else null

        private fun DataOutputStream.floats(vararg values: Float) = values.forEach { finite(it) }

        private fun DataOutputStream.finite(value: Float) {
            require(value.isFinite()) { "Non-finite mouth measurement" }
            writeFloat(value)
        }

        private fun DataInputStream.finite() = readFloat().also { require(it.isFinite()) { "Non-finite mouth measurement" } }

        private fun DataInputStream.flag(): Boolean {
            val value = readUnsignedByte()
            require(value in 0..1) { "Invalid mouth measurement flag" }
            return value != 0
        }
    }
}
