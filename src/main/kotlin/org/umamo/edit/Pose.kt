package org.umamo.edit

import org.umamo.runtime.model.ParameterId

/**
 * A pose: the live value of every parameter (the current scrub position the renderer deforms to). Held
 * as ephemeral editor state outside [org.umamo.runtime.model.PuppetModel] - it is not document content
 * (keyforms are). A small immutable map, so capturing one is cheap.
 *
 * ポーズ：各パラメータの現在値。ドキュメント内容ではない一時的な編集状態。
 */
typealias Pose = Map<ParameterId, Float>
