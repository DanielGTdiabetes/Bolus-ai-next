package org.bolusai.meals

/** A durable review selection, never an intake, recommendation or confirmation. */
sealed interface MealSelection {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
    val blockCode: String

    data object Missing : MealSelection {
        override val blockCode = "meal.selection.missing"
    }

    data class Reviewed(val snapshot: MealRecord, val latest: MealRecord) : MealSelection {
        init {
            require(snapshot.revision > 0)
            require(snapshot.id == latest.id && snapshot.kind == latest.kind)
            require(snapshot.copiedFrom == latest.copiedFrom)
            require(latest.revision >= snapshot.revision)
            require(latest.revision != snapshot.revision || latest == snapshot)
        }
        val obsolete: Boolean get() = latest.revision > snapshot.revision
        override val blockCode: String get() =
            if (obsolete) "meal.selection.obsolete" else snapshot.clinicalBlockCode
    }

    data class Failed(val reason: MealFailure) : MealSelection {
        override val blockCode: String get() = reason.code
    }
}

interface MealSelectionRepository {
    /** Read the pinned revision and the latest revision in one consistent local transaction. */
    fun readSelection(): MealSelection
    /** Persist only if the exact reviewed record is still latest. Return after commit. */
    fun select(record: MealRecord): MealSelection
}

class ReviewMealSelection(private val repository: MealSelectionRepository) {
    fun read(): MealSelection = repository.readSelection()
    fun select(record: MealRecord): MealSelection =
        if (record.revision <= 0) MealSelection.Failed(MealFailure.INVALID_RECORD)
        else repository.select(record)
}
