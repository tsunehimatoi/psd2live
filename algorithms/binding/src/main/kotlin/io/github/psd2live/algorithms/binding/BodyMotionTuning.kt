package io.github.psd2live.algorithms.binding

data class BodyMotionTuning(
    val hipShift: Float = 2.5f,
    val sink: Float = 3.5f,
    val rise: Float = 1.2f,
    val kneesIn: Float = 35f,
    val kneesInRest: Float = 10f,
    val turnDegrees: Float = 12f,
    val armSwingDegrees: Float = 4f,
    val turnFade: Float = 0.5f,
    val torsoDepth: Float = 70f,
    val turnCameraDistance: Float = 3f,
    val leanCameraDistance: Float = 9f,
    val openShorten: Float = 1.5f,
    val openWiden: Float = 2f,
    val standStretch: Float = 1.5f,
    val standNarrow: Float = 1.5f,
    val leanDegrees: Float = 28f,
    val leanBackDegrees: Float = 12f,
    val pelvisShare: Float = 30f,
    val headGrow: Float = 15f,
    val chestGrow: Float = 8f,
    val bodyShrink: Float = 6f,
    val legShrink: Float = 10f,
    val bodyZDegrees: Float = 3f,
    val breathLift: Float = 3f,
    val breathWiden: Float = 2.5f,
    val faceLeanDown: Float = 12f,
    val faceLeanUp: Float = 8f,
)

data class TorsoFrame(val centerX: Float, val shoulderY: Float, val waistY: Float, val halfWidth: Float) {
    val length: Float get() = waistY - shoulderY
}
