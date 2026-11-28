package org.bolusai.mfp

/** Loads the diary page of a day. Every call is a new load; an implementation never returns an earlier page. */
fun interface MfpDiaryReader {
    fun read(date: MfpDate): MfpPageLoad
}

sealed interface MfpPageLoad {
    data class Loaded(val page: MfpDiaryPage) : MfpPageLoad

    /** The page could not be loaded; [failure] says why with a stable code. */
    data class Failed(val failure: MfpFailure) : MfpPageLoad
}

/** Waits between two loads. Injected so tests do not sleep and the platform chooses how to wait. */
fun interface MfpPause {
    fun pause(milliseconds: Long)
}

sealed interface MfpStableReading {
    /**
     * [meals] are the meals of the last load whose revision equals the one of the load before it. [changed] are the
     * identities that were not the same in the last two loads (edited, added or removed in between): they are not
     * imported and must be read again later. [loads] is how many loads were needed, two or three.
     */
    data class Stable(val date: MfpDate, val meals: List<MfpMeal>, val changed: List<MfpMealId>, val loads: Int) :
        MfpStableReading

    /** A load failed or was not a diary. Nothing from the loads before it is returned. */
    data class Failed(val failure: MfpFailure, val loads: Int) : MfpStableReading
}

/**
 * Reads the diary until two consecutive loads agree, as the Legacy synchroniser does: two loads separated by a
 * pause and, if they differ, a third. A meal is accepted only with two consecutive equal revisions. Reading while
 * MyFitnessPal is still being edited can bring a half-written meal, which is what this guards against.
 *
 * Every call starts from nothing: no reading is kept between calls and none is reused when a later load fails.
 */
class ReadStableMfpDiary(
    private val reader: MfpDiaryReader,
    private val pause: MfpPause,
    private val interpreter: MfpDiaryInterpreter,
) {
    fun read(date: MfpDate): MfpStableReading {
        var previous: MfpDiaryReading.Diary? = null
        for (load in 1..MAX_LOADS) {
            if (load > 1) pause.pause(PAUSE_BETWEEN_LOADS_MS)
            val current = when (val loaded = reader.read(date)) {
                is MfpPageLoad.Failed -> return MfpStableReading.Failed(loaded.failure, load)
                is MfpPageLoad.Loaded -> when (val reading = interpreter.interpret(date, loaded.page)) {
                    is MfpDiaryReading.Failed -> return MfpStableReading.Failed(reading.failure, load)
                    is MfpDiaryReading.Diary -> reading
                }
            }
            if (previous != null) {
                val before = previous.meals.associate { it.id to it.revision }
                val stable = current.meals.filter { before[it.id] == it.revision }
                val stableIds = stable.map { it.id }.toSet()
                val changed = (previous.meals.map { it.id } + current.meals.map { it.id }).distinct()
                    .filter { it !in stableIds }
                if (changed.isEmpty() || load == MAX_LOADS) {
                    return MfpStableReading.Stable(date, stable, changed, load)
                }
            }
            previous = current
        }
        error("Unreachable: the last load always returns")
    }

    companion object {
        /**
         * Pause between loads. Observed in Legacy `e48c4ab896751091f323379b17c6211aff793487`, Hermes synchroniser
         * (`scripts/hermes/sync_to_bolus.py`; ADR 0032, "Auditoría de Legacy"). An observed technical constant kept
         * as a candidate, not a clinical parameter.
         */
        const val PAUSE_BETWEEN_LOADS_MS: Long = 4_000

        /** Observed in the same source: at most three loads. */
        const val MAX_LOADS: Int = 3
    }
}
