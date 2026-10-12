package org.bolusai.engine.iob

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Remaining fraction of one unit of insulin `t` minutes after delivery, ported from Legacy
 * `backend/app/services/math/curves.py` at [CandidateIobRules.LEGACY_SHA] (ADR 0026). The arithmetic keeps the
 * Legacy operation order so that IEEE doubles match. Candidate, not approved.
 *
 * Callers validate the action first ([CandidateIob]); the Legacy fallbacks for an invalid Walsh peak are never reached.
 */
internal object InsulinCurves {
    fun remaining(curve: InsulinCurve, tMinutes: Double, durationMinutes: Double, peakMinutes: Double): Double =
        when (curve) {
            InsulinCurve.WALSH -> walshRemaining(tMinutes, peakMinutes, durationMinutes)
            InsulinCurve.BILINEAR -> bilinearRemaining(tMinutes, peakMinutes, durationMinutes)
            InsulinCurve.FIASP -> FIASP.remaining(tMinutes, durationMinutes)
            InsulinCurve.NOVORAPID -> NOVORAPID.remaining(tMinutes, durationMinutes)
            // Legacy `get_iob` has no explicit `linear` branch: the name reaches the final `else`.
            InsulinCurve.LINEAR -> max(0.0, 1.0 - tMinutes / durationMinutes)
        }

    /** `_walsh_tau`. A non-positive result means Legacy would fall back to the linear curve; Next blocks instead. */
    fun walshTau(peak: Double, duration: Double): Double {
        if (duration <= 0.0) return 0.0
        val denom = 1 - 2 * peak / duration
        if (denom == 0.0) return 0.0
        return peak * (1 - peak / duration) / denom
    }

    /** Denominator `FD - F0` of `exponential_iob`; zero would make Legacy return 0 and Next blocks instead. */
    fun walshArea(peak: Double, duration: Double): Double {
        val tau = walshTau(peak, duration)
        return walshF(duration, duration, tau) - walshF(0.0, duration, tau)
    }

    private fun walshF(t: Double, duration: Double, tau: Double): Double {
        if (tau == 0.0) return 0.0
        val term = (tau / duration) - 1 + (t / duration)
        return tau * exp(-t / tau) * term
    }

    private fun walshRemaining(t: Double, peak: Double, duration: Double): Double {
        if (t <= 0) return 1.0
        if (t >= duration) return 0.0
        val tau = walshTau(peak, duration)
        check(tau > 0) { "engine.iob.walsh_not_validated" }
        val f0 = walshF(0.0, duration, tau)
        val fd = walshF(duration, duration, tau)
        val ft = walshF(t, duration, tau)
        val denom = fd - f0
        check(denom != 0.0) { "engine.iob.walsh_not_validated" }
        return (fd - ft) / denom
    }

    private fun bilinearRemaining(t: Double, peak: Double, duration: Double): Double {
        if (t <= 0) return 1.0
        if (t >= duration) return 0.0
        val h = 2.0 / duration
        val consumed = if (t < peak) {
            val currentH = h * (t / peak)
            0.5 * t * currentH
        } else {
            val remBase = duration - t
            val remHeight = h * (remBase / (duration - peak))
            1.0 - 0.5 * remBase * remHeight
        }
        return max(0.0, 1.0 - consumed)
    }

    /**
     * Legacy `InterpolatedCurves`: activity tables in % of maximum GIR, Legacy citing EMA/EPAR studies (Fiasp
     * 445421/2016, NovoRapid 3211/2005). The source of the figures is Legacy, not a reviewed clinical reference.
     */
    class Interpolated(private val points: List<Pair<Int, Int>>) {
        private val totalArea: Double
        private val cumulative: List<Double>
        private val maxTime: Int = points.last().first

        init {
            var total = 0.0
            val cdf = mutableListOf(0.0)
            for (i in 1 until points.size) {
                val (t0, y0) = points[i - 1]
                val (t1, y1) = points[i]
                val segment = (t1 - t0) * ((y0 + y1) / 2.0)
                total += segment
                cdf += total
            }
            totalArea = total
            cumulative = cdf
        }

        fun remaining(tMinutes: Double, durationOverride: Double): Double {
            var t = tMinutes
            if (durationOverride > 0 && abs(durationOverride - maxTime) > 1.0) {
                t /= durationOverride / maxTime
            }
            if (t <= 0) return 1.0
            if (t >= maxTime) return 0.0
            var area = 0.0
            for (i in 1 until points.size) {
                if (t <= points[i].first) {
                    val (tStart, yStart) = points[i - 1]
                    val (tEnd, yEnd) = points[i]
                    val ratio = (t - tStart) / (tEnd - tStart)
                    val yAtT = yStart + ratio * (yEnd - yStart)
                    val dt = t - tStart
                    area = cumulative[i - 1] + dt * (yStart + yAtT) / 2.0
                    break
                }
            }
            return max(0.0, 1.0 - area / totalArea)
        }
    }

    val FIASP: Interpolated = Interpolated(listOf(
        0 to 0, 15 to 8, 30 to 25, 45 to 50, 60 to 75, 75 to 90, 90 to 98, 105 to 100, 120 to 95, 135 to 88,
        150 to 78, 165 to 65, 180 to 50, 195 to 38, 210 to 28, 225 to 20, 240 to 12, 255 to 7, 270 to 3, 285 to 1,
        300 to 0,
    ))

    val NOVORAPID: Interpolated = Interpolated(listOf(
        0 to 0, 15 to 0, 30 to 12, 45 to 28, 60 to 50, 75 to 70, 90 to 85, 105 to 95, 120 to 100, 135 to 98,
        150 to 90, 165 to 80, 180 to 65, 195 to 50, 210 to 38, 225 to 28, 240 to 18, 255 to 10, 270 to 5, 285 to 2,
        300 to 0,
    ))
}
