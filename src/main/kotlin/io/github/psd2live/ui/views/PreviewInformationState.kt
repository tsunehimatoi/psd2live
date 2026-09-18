package io.github.psd2live.ui.views

import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.RigPreviewModel
import org.umamo.runtime.model.*

/**
 * Pose the canvas annotates with. A live pose outranks the edit values: the SDK frame is the
 * rendered truth, and the software renderer publishes its merged pose through [previewValues].
 *
 * [live] covers a running motion and a paused preview that still follows the pointer -- pausing
 * stops the motion, not the follow. Delivery only ever publishes frames that match the current
 * animation state, so a frame here always belongs to the pose on screen.
 */
internal fun informationPreviewPose(
    values: Map<ParameterId, Float>,
    previewValues: Map<ParameterId, Float>,
    frame: CubismSdkFrame?,
    live: Boolean,
): Map<ParameterId, Float> =
    if (!live) {
        values
    } else if (frame != null && frame.parameters.isNotEmpty()) {
        frame.parameters
    } else if (previewValues.isNotEmpty()) {
        previewValues
    } else {
        values
    }
