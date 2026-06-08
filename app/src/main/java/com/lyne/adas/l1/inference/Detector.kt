package com.lyne.adas.l1.inference

import java.io.Closeable

/**
 * Common contract for every on-device model. Implementations are single-threaded and called
 * only from the inference thread; they own reusable buffers internally (zero per-frame alloc).
 *
 * @param T the decoded result type (e.g. List<Detection>, LaneResult, SignResult).
 */
interface Detector<out T> : Closeable {
    val name: String

    /** The backend actually in use after delegate probing, surfaced to the PerfOverlay/status strip. */
    val backend: Backend

    /** True for the bundled stub (no real weights); UI surfaces this honestly. */
    val isStub: Boolean

    /** Run one inference. Must not retain [frame] or its bitmap after returning. */
    fun detect(frame: CameraFrame): T
}
