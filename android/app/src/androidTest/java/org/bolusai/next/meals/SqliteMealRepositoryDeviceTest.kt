package org.bolusai.next.meals

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.meals.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteMealRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withDatabase(run: (String) -> Unit) {
        val name = "synthetic-meals-${UUID.randomUUID()}.db"
        try { run(name) } finally { assertTrue(context.deleteDatabase(name)) }
    }

    @Test fun createEditAndRecoverLatestRevisionAfterRepositoryRestart() = withDatabase { name ->
        val first = SqliteMealRepository(context, name)
        val original = MealRecord("synthetic-draft", 0, MealKind.DRAFT,
            MealContent(name = draftField("Comida sintética"), carbs = draftField("0"),
                fat = DraftField.Missing, basis = NutritionBasis.TOTAL_GRAMS),
            DishReference("synthetic-dish", 4))
        val revisionOne = (first.save(original) as MealSave.Saved).record
        first.close()

        val second = SqliteMealRepository(context, name)
        val recovered = (second.read(MealKind.DRAFT) as MealRead.Loaded).records.single()
        assertEquals(revisionOne, recovered)
        assertEquals(DraftField.Entered("0"), recovered.content.carbs)
        assertEquals(DraftField.Missing, recovered.content.fat)
        assertEquals(DishReference("synthetic-dish", 4), recovered.copiedFrom)
        val revisionTwo = (second.save(recovered.copy(content = recovered.content.copy(
            notes = draftField("revisión sintética")))) as MealSave.Saved).record
        second.close()

        SqliteMealRepository(context, name).use { third ->
            assertEquals(revisionTwo, (third.read(MealKind.DRAFT) as MealRead.Loaded).records.single())
            assertEquals(2, revisionTwo.revision)
        }
    }

    @Test fun identicalRetryIsIdempotentAndStaleCorrectionIsConflict() = withDatabase { name ->
        SqliteMealRepository(context, name).use { repository ->
            val editor = MealRecord("stable-id", 0, MealKind.DISH, MealContent())
            val saved = repository.save(editor) as MealSave.Saved
            assertEquals(saved, repository.save(editor))
            val conflict = repository.save(editor.copy(content = MealContent(name = draftField("cambio"))))
            assertEquals(MealFailure.CONFLICT, (conflict as MealSave.Failed).reason)
            assertEquals(saved.record, (repository.read(MealKind.DISH) as MealRead.Loaded).records.single())
        }
    }

    @Test fun unknownPreVersionedDatabaseFailsClosedInsteadOfPretendingEmpty() = withDatabase { name ->
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE unknown_legacy_table(value TEXT)")
        }
        SqliteMealRepository(context, name).use { repository ->
            assertEquals(MealFailure.UNSUPPORTED_SCHEMA,
                (repository.read(MealKind.DRAFT) as MealRead.Failed).reason)
        }
    }
}
