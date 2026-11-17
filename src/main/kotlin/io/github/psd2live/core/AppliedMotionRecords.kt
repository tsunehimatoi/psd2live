package io.github.psd2live.core

import io.github.psd2live.format.model.*
import io.github.psd2live.core.modifier.AppliedModifierNode
import kotlinx.serialization.json.*

/** Editable parameter keys accompanied by their exact materialized clip, including channels the editor does not expose. */
internal object AppliedMotionRecords {
    fun source(record: MotionClip, clip: Clip): JsonObject = buildJsonObject {
        put("record", MotionClips.toJson(record))
        put("source", AppliedModifierNode.encode(RigIR(Canvas(1f, 1f), emptyList(), clips = listOf(clip))))
    }

    fun compile(record: MotionClip, source: JsonObject): Clip {
        val previous = MotionClips.fromJson(source.getValue("record").jsonObject)
        val clip = AppliedModifierNode.decode(source.getValue("source").jsonObject).clips.single()
        require(record.id == previous.id && record.id == clip.id) { "Materialized motion identity mismatch" }
        return update(record, previous, clip)
    }

    fun renameReference(source: JsonObject, kind: String, from: String, to: String): JsonObject {
        val previous = MotionClips.fromJson(source.getValue("record").jsonObject)
        val record = ModelSidecars.withIdRenamed(RigEditOverlay(motionClips = listOf(previous)), kind, from, to).motionClips.single()
        val clip = AppliedModifierNode.decode(source.getValue("source").jsonObject).clips.single()
        val renamed = when (kind) {
            "parameter" -> clip.copy(curves = if (clip.curves.none { it.parameter == from }) clip.curves else
                clip.curves.filterNot { it.parameter == to }.map { if (it.parameter == from) it.copy(parameter = to) else it })
            "part" -> clip.copy(targetCurves = clip.targetCurves.map {
                if (it.target == CurveTarget.PartOpacity(from)) it.copy(target = CurveTarget.PartOpacity(to)) else it
            })
            else -> clip
        }
        return AppliedMotionRecords.source(record, renamed)
    }

    fun record(clip: Clip, parameters: List<Parameter> = emptyList()): MotionClip = MotionClip(
        clip.id, clip.name, loop = clip.loop, duration = clip.duration, fps = clip.fps,
        fadeIn = clip.fadeIn ?: 1f, fadeOut = clip.fadeOut ?: 1f,
        curves = clip.curves.map(::curve), events = clip.events.map { MotionEvent(it.time, it.value) },
        fileStem = clip.file, exportGroup = clip.group, runtimeDefaultFades = clip.fadeIn == null && clip.fadeOut == null,
        capturedRanges = parameters.filter { p -> clip.curves.any { it.parameter == p.id } }
            .associate { it.id to MotionParameterRange(it.min, it.max, it.repeat) })

    private fun curve(source: Curve): MotionCurve {
        val keys = arrayListOf(MotionKey(source.startTime, source.startValue, MotionInterpolation.LINEAR))
        for (segment in source.segments) {
            val previous = keys.last()
            val interpolation = when (segment) {
                is CurveSegment.Linear -> MotionInterpolation.LINEAR
                is CurveSegment.Bezier -> MotionInterpolation.BEZIER
                is CurveSegment.Stepped -> MotionInterpolation.STEPPED
                is CurveSegment.InverseStepped -> MotionInterpolation.INVERSE_STEPPED
            }
            var next = MotionKey(segment.time, segment.value, MotionInterpolation.LINEAR)
            var start = previous.copy(interpolation = interpolation)
            if (segment is CurveSegment.Bezier) {
                val span = segment.time - previous.time
                start = start.copy(outHandle = MotionHandle(if (span == 0f) 0f else (segment.c1Time - previous.time) / span,
                    segment.c1Value - previous.value))
                next = next.copy(inHandle = MotionHandle(if (span == 0f) 0f else (segment.time - segment.c2Time) / span,
                    segment.c2Value - segment.value))
            }
            keys[keys.lastIndex] = start
            keys += next
        }
        return MotionCurve(source.parameter, keys)
    }

    fun update(clip: MotionClip, previous: MotionClip, source: Clip?): Clip {
        fun fade(value: Float, oldValue: Float, original: Float?): Float? =
            if (source != null && value == oldValue && clip.runtimeDefaultFades == previous.runtimeDefaultFades) original
            else value.takeUnless { clip.runtimeDefaultFades }
        return Clip(clip.id, clip.name, clip.exportGroup ?: if (clip.loop) "Idle" else clip.name,
            requireNotNull(clip.fileStem ?: source?.file), clip.duration, clip.fps, clip.loop,
            fade(clip.fadeIn, previous.fadeIn, source?.fadeIn), fade(clip.fadeOut, previous.fadeOut, source?.fadeOut),
            clip.curves.map { curve ->
                val original = source?.curves?.firstOrNull { it.parameter == curve.parameterId }
                if (previous.curves.firstOrNull { it.parameterId == curve.parameterId } == curve && original != null) original
                else MotionGenerator.curve(curve).copy(fadeIn = original?.fadeIn, fadeOut = original?.fadeOut)
            }, clip.events.map { ClipEvent(it.time, it.value) }, source?.targetCurves.orEmpty())
    }
}
