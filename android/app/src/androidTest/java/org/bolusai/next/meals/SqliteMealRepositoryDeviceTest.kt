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

    @Test fun selectionSurvivesRestartAndStaysPinnedUntilExplicitReselection() = withDatabase { name ->
        val first = SqliteMealRepository(context, name)
        assertEquals(MealSelection.Missing, first.readSelection())
        val original = (first.save(MealRecord("selected", 0, MealKind.DRAFT,
            MealContent(carbs = draftField("0"), notes = draftField("  exacto  ")),
            DishReference("source", 7))) as MealSave.Saved).record
        assertEquals(MealSelection.Reviewed(original, original), first.select(original))
        assertEquals(first.readSelection(), first.select(original))
        first.close()
        SqliteMealRepository(context, name).use { reopened ->
            assertEquals(MealSelection.Reviewed(original, original), reopened.readSelection())
            val latest = (reopened.save(original.copy(content = original.content.copy(
                carbs = draftField("texto nuevo")))) as MealSave.Saved).record
            val obsolete = MealSelection.Reviewed(original, latest)
            assertEquals(obsolete, reopened.readSelection())
            assertEquals(MealSelection.Failed(MealFailure.CONFLICT), reopened.select(original))
            assertEquals(obsolete, reopened.readSelection())
            assertEquals(MealSelection.Failed(MealFailure.CONFLICT),
                reopened.select(latest.copy(content = MealContent())))
            assertEquals(MealSelection.Reviewed(latest, latest), reopened.select(latest))
            assertFalse(reopened.readSelection().allowsCalculation)
            assertFalse(reopened.readSelection().allowsTreatment)
        }
    }

    @Test fun selectionSaveFailureRollsBackAndReadFailureIsNotMissing() = withDatabase { name ->
        val repository = SqliteMealRepository(context, name)
        val record = (repository.save(MealRecord("dish", 0, MealKind.DISH, MealContent())) as MealSave.Saved).record
        repository.select(record)
        val latest = (repository.save(record.copy(content = MealContent(name = draftField("nuevo")))) as MealSave.Saved).record
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fail_selection BEFORE UPDATE ON meal_selection BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        }
        assertEquals(MealSelection.Failed(MealFailure.SAVE_FAILED), repository.select(latest))
        assertEquals(MealSelection.Reviewed(record, latest), repository.readSelection())
        repository.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE meal_selection")
        }
        SqliteMealRepository(context, name).use {
            assertEquals(MealSelection.Failed(MealFailure.READ_FAILED), it.readSelection())
        }
    }

    @Test fun migrationFromV1PreservesEveryRevisionAndBackupCanBeRestored() = withDatabase { name ->
        withDatabase { backup ->
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
                // Frozen v1 schema, independent of the production onCreate implementation.
                db.execSQL("""
                    CREATE TABLE meal_revisions (
                        id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0),
                        schema_version INTEGER NOT NULL CHECK(schema_version = 1),
                        kind TEXT NOT NULL CHECK(kind IN ('DRAFT','DISH')),
                        basis TEXT NOT NULL CHECK(basis IN ('UNSPECIFIED','TOTAL_GRAMS')),
                        name TEXT, carbs TEXT, fat TEXT, protein TEXT, fiber TEXT, notes TEXT,
                        source_id TEXT, source_revision INTEGER,
                        PRIMARY KEY(id, revision),
                        CHECK((source_id IS NULL AND source_revision IS NULL) OR
                            (kind = 'DRAFT' AND source_id IS NOT NULL AND source_revision > 0))
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO meal_revisions(id,revision,schema_version,kind,basis,carbs) VALUES ('v1',1,1,'DRAFT','UNSPECIFIED','0')")
                db.execSQL("INSERT INTO meal_revisions(id,revision,schema_version,kind,basis,carbs) VALUES ('v1',2,1,'DRAFT','UNSPECIFIED','  nuevo  ')")
                db.version = 1
            }
            context.getDatabasePath(name).copyTo(context.getDatabasePath(backup))
            for (database in listOf(name, backup)) {
                SqliteMealRepository(context, database).use { migrated ->
                    assertEquals(MealSelection.Missing, migrated.readSelection())
                    val latest = (migrated.read(MealKind.DRAFT) as MealRead.Loaded).records.single()
                    assertEquals(2, latest.revision)
                    assertEquals(draftField("  nuevo  "), latest.content.carbs)
                    assertEquals(DraftField.Missing, latest.content.fat)
                    assertEquals(MealSelection.Reviewed(latest, latest), migrated.select(latest))
                }
                SQLiteDatabase.openDatabase(context.getDatabasePath(database).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    assertEquals(2, db.version)
                    db.rawQuery("SELECT carbs FROM meal_revisions WHERE id='v1' ORDER BY revision", null).use {
                        assertEquals(2, it.count)
                        assertTrue(it.moveToFirst()); assertEquals("0", it.getString(0))
                        assertTrue(it.moveToNext()); assertEquals("  nuevo  ", it.getString(0))
                    }
                }
            }
        }
    }
}
