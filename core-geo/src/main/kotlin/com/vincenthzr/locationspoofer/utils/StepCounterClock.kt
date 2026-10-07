package com.vincenthzr.locationspoofer.utils

/** A published clock, shared by all app processes; subscriptions do not own the counter. */
object StepCounterClock {
    const val CONFIG_KEY = "step_counter_clock"
    const val INITIAL_STEPS = 2350.0

    data class Parameters(
        val epochMillis: Long,
        val cadenceSpm: Double,
        val running: Boolean,
        val endMillis: Long = Long.MAX_VALUE,
        val realismLevel: Int = 0,
        val speedFluctuationPct: Int = 0
    )

    data class State(val baseSteps: Double, val anchorMillis: Long, val parameters: Parameters,
                     val bootCount: Long? = null) {
        /** TYPE_STEP_COUNTER is cumulative within one boot, not across device restarts. */
        fun inBoot(currentBootCount: Long, now: Long): State {
            require(currentBootCount >= 0)
            return if (bootCount == currentBootCount) this
            else State(0.0, now, parameters, currentBootCount)
        }

        fun countAt(now: Long): Double {
            if (!parameters.running) return baseSteps
            val until = minOf(now, parameters.endMillis).coerceAtLeast(anchorMillis)
            val session = MotionRealism.session(parameters.epochMillis, parameters.realismLevel,
                parameters.speedFluctuationPct)
            val fromSeconds = (anchorMillis - parameters.epochMillis).coerceAtLeast(0L) / 1000.0
            val untilSeconds = (until - parameters.epochMillis).coerceAtLeast(0L) / 1000.0
            return baseSteps + (session.boundedSteps(parameters.cadenceSpm, untilSeconds) -
                session.boundedSteps(parameters.cadenceSpm, fromSeconds)).coerceAtLeast(0.0)
        }

        fun encode(): String = (listOf(if (bootCount == null) 1 else 2, baseSteps, anchorMillis, parameters.epochMillis,
            parameters.cadenceSpm, if (parameters.running) 1 else 0, parameters.endMillis,
            parameters.realismLevel, parameters.speedFluctuationPct) +
            if (bootCount == null) emptyList() else listOf(bootCount)).joinToString(";")
    }

    /** Configuration changes anchor the new rate to the old count, including pause/resume. */
    fun transition(previous: State, parameters: Parameters, now: Long): State =
        if (previous.parameters == parameters) previous
        else State(previous.countAt(now), now, parameters, previous.bootCount)

    fun decode(value: String): State? = runCatching {
        val fields = value.split(';')
        require((fields.size == 9 && fields[0] == "1") || (fields.size == 10 && fields[0] == "2"))
        val bootCount = if (fields[0] == "2") fields[9].toLong().also { require(it >= 0) } else null
        val base = fields[1].toDouble()
        val cadence = fields[4].toDouble()
        require(base.isFinite() && base >= 0 && cadence.isFinite() && cadence in 80.0..240.0)
        require(fields[5] == "0" || fields[5] == "1")
        require(fields[2].toLong() >= 0 && fields[3].toLong() >= 0 && fields[6].toLong() >= 0)
        State(base, fields[2].toLong(), Parameters(fields[3].toLong(), cadence,
            fields[5] == "1", fields[6].toLong(), fields[7].toInt(), fields[8].toInt()), bootCount)
    }.getOrNull()

    fun autoCadence(speedMs: Double): Int = when {
        speedMs <= 0.8 -> 95
        speedMs <= 1.5 -> (100 + (speedMs - 0.8) / 0.7 * 25).toInt()
        speedMs <= 2.8 -> (135 + (speedMs - 1.5) / 1.3 * 25).toInt()
        speedMs <= 4.0 -> (160 + (speedMs - 2.8) / 1.2 * 20).toInt()
        speedMs <= 5.5 -> (180 + (speedMs - 4.0) / 1.5 * 15).toInt()
        else -> 200
    }
}
