package com.lyne.adas.l1.fusion

/**
 * Turns a noisy per-frame severity into a stable alert, in wall-clock time (so an 8 fps budget phone
 * and a 30 fps flagship behave the same):
 *  - onset: leaky evidence accumulators per level. Time spent at/above a level adds evidence, time
 *    below drains it at half rate, so a single dropped detection doesn't restart the clock but a
 *    one-frame spike can never fire. Activate when evidence reaches the level's hold time.
 *  - release: the active level is held until the raw signal has stayed below it for [offMs].
 *  - an event is emitted only on onset or escalation (not every frame), with a re-arm cooldown,
 *    so one incident = one logged warning.
 */
class AlertGate(
    private val cautionOnMs: Long,
    private val criticalOnMs: Long,
    private val offMs: Long = 700L,
    private val rearmMs: Long = 4000L,
) {
    var active: Severity = Severity.NONE; private set
    private var evCaution = 0f
    private var evCritical = 0f
    private var lastMs = -1L
    private var belowSinceMs = 0L
    private var lastEmitMs = Long.MIN_VALUE / 2
    private var lastEmitSev = Severity.NONE

    /** @return true if an event should be emitted this frame (onset or escalation). */
    fun update(raw: Severity, nowMs: Long): Boolean {
        val r = if (raw == Severity.INFO) Severity.NONE else raw
        val dt = if (lastMs < 0) 0f else (nowMs - lastMs).coerceIn(0L, 500L).toFloat()
        lastMs = nowMs
        evCaution = accumulate(evCaution, r.ordinal >= Severity.CAUTION.ordinal, dt, cautionOnMs)
        evCritical = accumulate(evCritical, r == Severity.CRITICAL, dt, criticalOnMs)
        // An active caution already proved the threat is real: escalation needs half the evidence.
        val critNeed = if (active == Severity.CAUTION) criticalOnMs / 2f else criticalOnMs.toFloat()
        val target = when {
            evCritical >= critNeed && evCritical > 0f -> Severity.CRITICAL
            evCaution >= cautionOnMs && evCaution > 0f -> Severity.CAUTION
            else -> Severity.NONE
        }

        var next = active
        if (target.ordinal > active.ordinal) {
            next = target; belowSinceMs = 0L
        } else if (r.ordinal < active.ordinal) {
            if (belowSinceMs == 0L) belowSinceMs = nowMs
            if (nowMs - belowSinceMs >= offMs) {
                // The raw signal has been below the active level for offMs: release to it and drop the
                // evidence above it, otherwise draining evidence would keep the old level alive.
                next = r
                if (r.ordinal < Severity.CRITICAL.ordinal) evCritical = 0f
                if (r.ordinal < Severity.CAUTION.ordinal) evCaution = 0f
                belowSinceMs = 0L
            }
        } else belowSinceMs = 0L

        val escalated = next.ordinal > active.ordinal
        active = next
        if (escalated) {
            val repeat = next.ordinal <= lastEmitSev.ordinal && nowMs - lastEmitMs < rearmMs
            if (!repeat) { lastEmitMs = nowMs; lastEmitSev = next; return true }
        }
        if (active == Severity.NONE && nowMs - lastEmitMs > rearmMs) lastEmitSev = Severity.NONE
        return false
    }

    private fun accumulate(ev: Float, on: Boolean, dt: Float, holdMs: Long): Float =
        if (on) (ev + dt).coerceAtMost(holdMs * 1.5f) else (ev - dt * 0.5f).coerceAtLeast(0f)

    /** Forget accumulated evidence (e.g. the tracked lead changed: the new one must earn its own). */
    fun resetEvidence() { evCaution = 0f; evCritical = 0f }

    fun reset() { active = Severity.NONE; evCaution = 0f; evCritical = 0f; lastMs = -1L; belowSinceMs = 0L }
}
