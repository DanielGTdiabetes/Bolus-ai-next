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

/** Revision zero is an unsaved editor. Persisted revisions start at one. No consumption time exists. */
data class MealRecord(
    val id: String,
    val revision: Long,
    val kind: MealKind,
    val content: MealContent,
    val copiedFrom: DishReference? = null,
    val schemaVersion: Int = 1,
) {
    init {
        require(id.isNotBlank() && revision >= 0 && schemaVersion == 1)
        require(kind != MealKind.DISH || copiedFrom == null)
    }
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
}
