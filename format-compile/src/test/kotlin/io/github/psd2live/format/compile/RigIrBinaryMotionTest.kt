package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*
import java.io.*
import kotlin.test.*

class RigIrBinaryMotionTest {
    @Test fun allMotionChannelsSurviveCacheAndStoredObjects() {
        val segments = listOf(CurveSegment.Linear(.2f, .4f), CurveSegment.Bezier(.3f, .5f, .8f, .9f, 1f, 1f),
            CurveSegment.Stepped(1.5f, .2f), CurveSegment.InverseStepped(2f, .8f))
        val clip = Clip("clip", "Clip", "Idle", "clip", 2f, 30f, true, curves = listOf(
            Curve("P", .1f, .2f, segments, .3f, .4f)), events = listOf(ClipEvent(.5f, "event")),
            targetCurves = listOf(CurveTarget.PartOpacity("part"), CurveTarget.ModelOpacity,
                CurveTarget.EyeBlink, CurveTarget.LipSync).map { TargetCurve(it, .1f, .2f, segments, .6f, .7f) })
        val rig = RigIR(Canvas(1f, 1f), emptyList(), clips = listOf(clip))
        val encoded = RigIrBinary.encode(rig)
        val header = DataInputStream(encoded.inputStream())
        header.readInt()
        assertEquals(7, header.readInt())
        assertEquals(rig, RigIrBinary.decode(encoded))
        assertEquals(rig, RigIrObjects.join(RigIrObjects.split(rig)))
    }

    @Test fun ordinaryInputsRemainReadableByVersionSixWorkers() {
        val clip = Clip("clip", "Clip", "Idle", "clip", 1f, 30f, true,
            curves = listOf(Curve("P", 0f, 0f, listOf(CurveSegment.Linear(1f, 1f)))))
        val rig = RigIR(Canvas(1f, 1f), emptyList(), clips = listOf(clip))
        val resource = RigIrBinary.encode(rig)
        val resourceInput = DataInputStream(resource.inputStream())
        resourceInput.readInt()
        assertEquals(6, resourceInput.readInt())
        assertEquals(rig, RigIrBinary.Reader(resourceInput, 6).rig())
        assertEquals(-1, resourceInput.read())
        assertEquals(rig, RigIrBinary.decode(resource))
        val bytes = RigIrObjects.split(rig).frame
        val input = DataInputStream(bytes.inputStream())
        input.readInt()
        assertEquals(6, input.readInt())
        input.readInt()
        assertEquals(rig, RigIrBinary.Reader(input, 6).rig())
        assertEquals(-1, input.read())
        val extended = RigIrObjects.split(rig.copy(clips = listOf(clip.copy(events = listOf(ClipEvent(.5f, "event")))))).frame
        val header = DataInputStream(extended.inputStream())
        header.readInt()
        assertEquals(7, header.readInt())
    }

    @Test fun versionSixClipDefaultsNewFieldsWithoutConsumingFollowingBytes() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            val writer = RigIrBinary.Writer(out)
            with(writer) {
                string("old"); string("Old"); string("Idle"); string("old")
                float(1f); float(30f); bool(false)
                nullable<Float>(null, ::float); nullable<Float>(null, ::float)
                int(1); string("P"); float(0f); float(.5f); int(1); int(0); float(1f); float(1f)
                int(12345)
            }
        }
        val reader = RigIrBinary.Reader(DataInputStream(output.toByteArray().inputStream()), 6)
        assertEquals(Clip("old", "Old", "Idle", "old", 1f, 30f, false,
            curves = listOf(Curve("P", 0f, .5f, listOf(CurveSegment.Linear(1f, 1f))))), reader.clip())
        assertEquals(12345, reader.int())
    }
}
