package org.bolusai.meals

/** Capture only. No clinical validation, numeric conversion, rounding or calculation. */
sealed interface DraftField {
    data object Missing : DraftField
    data class Entered(val text: String) : DraftField {
        init { require(text.isNotBlank()) }
    }
}

fun draftField(text: String): DraftField =
    if (text.isBlank()) DraftField.Missing else DraftField.Entered(text)

fun DraftField.editText(): String = when (this) {
    DraftField.Missing -> ""
    is DraftField.Entered -> text
}

enum class MealKind { DRAFT, DISH }
enum class NutritionBasis { UNSPECIFIED, TOTAL_GRAMS }

data class MealContent(
    val name: DraftField = DraftField.Missing,
    val carbs: DraftField = DraftField.Missing,
    val fat: DraftField = DraftField.Missing,
    val protein: DraftField = DraftField.Missing,
    val fiber: DraftField = DraftField.Missing,
    val notes: DraftField = DraftField.Missing,
    val basis: NutritionBasis = NutritionBasis.UNSPECIFIED,
) {
    val fields: List<DraftField> get() = listOf(name, carbs, fat, protein, fiber, notes)
    val hasMissingCaptureFields: Boolean get() = basis == NutritionBasis.UNSPECIFIED ||
        listOf(name, carbs, fat, protein, fiber).any { it == DraftField.Missing }
}

data class DishReference(val id: String, val revision: Long) {
    init { require(id.isNotBlank() && revision > 0) }
}

/**
 * Revision zero is an unsaved editor. Persisted revisions start at one. No consumption time exists.
 * [restoredFrom] is the earlier revision of this same record whose content was loaded as the starting
 * point (ADR 0011); null means the revision was not created from a restoration. In an editor, [revision]
 * is the latest saved revision it will replace, so the source is always older than that one.
 */
data class MealRecord(
    val id: String,
    val revision: Long,
    val kind: MealKind,
    val content: MealContent,
    val copiedFrom: DishReference? = null,
    val restoredFrom: Long? = null,
    val schemaVersion: Int = 1,
) {
    init {
        require(id.isNotBlank() && revision >= 0 && schemaVersion == 1)
        require(kind != MealKind.DISH || copiedFrom == null)
        require(restoredFrom == null || restoredFrom in 1 until revision)
    }
    /** Persisted-revision invariant: the source predates the revision this one replaced. */
    val hasValidSavedProvenance: Boolean get() = restoredFrom == null || restoredFrom < revision - 1
    val clinicalBlockCode: String get() = "meal.draft.not_clinically_validated"
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
}

enum class MealFailure(val code: String) {
    READ_FAILED("meal.storage.read_failed"), SAVE_FAILED("meal.storage.save_failed"),
    CONFLICT("meal.storage.revision_conflict"), INVALID_RECORD("meal.storage.invalid_record"),
    UNSUPPORTED_SCHEMA("meal.storage.unsupported_schema"), CORRUPT_STORAGE("meal.storage.corrupt"),
}

sealed interface MealRead {
    data class Loaded(val records: List<MealRecord>) : MealRead
    data class Failed(val reason: MealFailure) : MealRead
}

sealed interface MealSave {
    data class Saved(val record: MealRecord) : MealSave
    data class Failed(val reason: MealFailure) : MealSave
}

interface MealRepository {
    fun read(kind: MealKind): MealRead
    /** Compare revision, append atomically, return only after commit; identical retries are idempotent. */
    fun save(editor: MealRecord): MealSave
}

fun interface MealIds { fun next(): String }

class MealDrafts(private val repository: MealRepository, private val ids: MealIds) {
    fun new(kind: MealKind): MealRecord = MealRecord(ids.next(), 0, kind, MealContent())
    fun read(kind: MealKind): MealRead = repository.read(kind)
    fun save(editor: MealRecord): MealSave = repository.save(editor)
    fun copyDish(dish: MealRecord): MealRecord {
        require(dish.kind == MealKind.DISH && dish.revision > 0)
        return MealRecord(ids.next(), 0, MealKind.DRAFT, dish.content,
            DishReference(dish.id, dish.revision))
    }

    /**
     * Pure: loads an older saved revision as the editor's starting content. Nothing is read or written;
     * saving goes through [save] with the usual revision conflict control (ADR 0011).
     */
    fun restore(history: MealHistory.Loaded, revision: Long): MealRestore {
        val latest = history.latest
        val source = history.revisions.firstOrNull { it.revision == revision }
        if (source == null || revision >= latest.revision) return MealRestore.Rejected(MealFailure.INVALID_RECORD)
        return MealRestore.Ready(latest.copy(content = source.content, restoredFrom = revision), latest)
    }

    /**
     * Applies an editor change. Provenance is kept only while the editor started from a restoration
     * ([restoreSource]); an editor opened on a saved revision drops inherited provenance once it changes,
     * and returning to the saved content yields exactly the saved revision again.
     */
    fun change(editor: MealRecord, content: MealContent, baseline: MealRecord?, restoreSource: Long?): MealRecord =
        if (restoreSource == null && baseline != null && baseline.id == editor.id && content == baseline.content) baseline
        else editor.copy(content = content, restoredFrom = restoreSource)
}

/** Outcome of preparing a restoration. Never a write, intake, recommendation or selection. */
sealed interface MealRestore {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false

    /** [editor] carries the older content; [baseline] is the latest saved revision it will replace. */
    data class Ready(val editor: MealRecord, val baseline: MealRecord) : MealRestore {
        init {
            val source = requireNotNull(editor.restoredFrom)
            require(editor.id == baseline.id && editor.kind == baseline.kind && editor.copiedFrom == baseline.copiedFrom)
            require(editor.revision == baseline.revision && source < baseline.revision)
        }
    }

    data class Rejected(val reason: MealFailure) : MealRestore
}
