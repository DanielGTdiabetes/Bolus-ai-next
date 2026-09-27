package org.bolusai.meals

/**
 * Read-only query of the saved capture revisions of one dish or draft.
 * It is not clinical history or selection, never writes and never unlocks calculation.
 * Restoration (ADR 0011) is a separate, explicit editor action built on a loaded history.
 */
sealed interface MealHistory {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
    val blockCode: String

    /**
     * Every persisted revision, ordered newest first and contiguous down to revision one.
     * Identity, class and copy reference are immutable across revisions.
     */
    data class Loaded(val id: String, val kind: MealKind, val revisions: List<MealRecord>) : MealHistory {
        init {
            require(id.isNotBlank() && revisions.isNotEmpty())
            val newest = revisions.first()
            require(revisions.all { it.id == id && it.kind == kind && it.copiedFrom == newest.copiedFrom })
            // Compare count and neighbours only: a corrupt huge revision must fail without expanding a range.
            require(newest.revision == revisions.size.toLong() && revisions.last().revision == 1L)
            require(revisions.zipWithNext().all { (newer, older) -> newer.revision - 1 == older.revision })
            // Restoration provenance must name an existing revision older than the one it replaced.
            require(revisions.all { it.hasValidSavedProvenance })
        }
        val latest: MealRecord get() = revisions.first()
        override val blockCode: String get() = READ_ONLY_CODE
    }

    /** The store answered successfully and holds no revision for this identity. */
    data object Missing : MealHistory {
        override val blockCode = "meal.history.missing"
    }

    /** The store could not prove the revisions; never presented as missing or empty. */
    data class Failed(val reason: MealFailure) : MealHistory {
        override val blockCode: String get() = reason.code
    }

    companion object {
        const val READ_ONLY_CODE = "meal.history.read_only"
    }
}

interface MealHistoryRepository {
    /**
     * Read every persisted revision of [id] in one consistent local read, in any order.
     * An empty list means the identity has no saved revision; failures stay explicit.
     */
    fun readRevisions(id: String): MealRead
}

class ReadMealHistory(private val repository: MealHistoryRepository) {
    fun read(id: String, kind: MealKind): MealHistory {
        if (id.isBlank()) return MealHistory.Failed(MealFailure.INVALID_RECORD)
        return when (val result = repository.readRevisions(id)) {
            is MealRead.Failed -> MealHistory.Failed(result.reason)
            is MealRead.Loaded -> if (result.records.isEmpty()) MealHistory.Missing else try {
                MealHistory.Loaded(id, kind, result.records.sortedByDescending { it.revision })
            } catch (_: IllegalArgumentException) {
                // Gaps, duplicates or identity drift cannot be shown as a trustworthy history.
                MealHistory.Failed(MealFailure.INVALID_RECORD)
            }
        }
    }
}
