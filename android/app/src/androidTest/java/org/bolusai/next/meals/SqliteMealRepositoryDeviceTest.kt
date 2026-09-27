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

    @Test fun clearingMissingCurrentAndObsoleteSelectionIsDurableAndPreservesEveryRevision() = withDatabase { name ->
        lateinit var original: MealRecord
        lateinit var latest: MealRecord
        lateinit var dish: MealRecord
        SqliteMealRepository(context, name).use { repository ->
            repeat(2) { assertEquals(MealSelection.Missing, repository.clearSelection()) }
            original = (repository.save(MealRecord("clear-draft", 0, MealKind.DRAFT,
                MealContent(carbs = draftField("0"), notes = draftField("  exacto  ")),
                DishReference("clear-dish", 1))) as MealSave.Saved).record
            dish = (repository.save(MealRecord("clear-dish", 0, MealKind.DISH,
                MealContent(name = draftField("Plato sintético")))) as MealSave.Saved).record
            repository.select(dish)
            assertEquals(MealSelection.Missing, repository.clearSelection())
            repository.select(original)
            latest = (repository.save(original.copy(content = original.content.copy(
                notes = draftField("revisión")))) as MealSave.Saved).record
            assertEquals(MealSelection.Reviewed(original, latest), repository.readSelection())
            repeat(2) { assertEquals(MealSelection.Missing, repository.clearSelection()) }
        }
        SqliteMealRepository(context, name).use { reopened ->
            assertEquals(MealSelection.Missing, reopened.readSelection())
            assertFalse(reopened.readSelection().allowsCalculation)
            assertFalse(reopened.readSelection().allowsTreatment)
            assertEquals(listOf(latest), (reopened.read(MealKind.DRAFT) as MealRead.Loaded).records)
            assertEquals(listOf(dish), (reopened.read(MealKind.DISH) as MealRead.Loaded).records)
            assertEquals(MealSelection.Reviewed(latest, latest), reopened.select(latest))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, revision, carbs, fat, notes, source_id, source_revision FROM meal_revisions ORDER BY id, revision", null).use {
                assertEquals(3, it.count)
                assertTrue(it.moveToPosition(1))
                assertEquals(original.id, it.getString(0)); assertEquals(1L, it.getLong(1))
                assertEquals("0", it.getString(2)); assertTrue(it.isNull(3))
                assertEquals("  exacto  ", it.getString(4))
                assertEquals("clear-dish", it.getString(5)); assertEquals(1L, it.getLong(6))
            }
        }
    }

    @Test fun failedClearRollsBackAndRetryCanRemoveSelectionAfterRestart() = withDatabase { name ->
        lateinit var record: MealRecord
        SqliteMealRepository(context, name).use { repository ->
            record = (repository.save(MealRecord("clear-failure", 0, MealKind.DISH, MealContent())) as MealSave.Saved).record
            repository.select(record)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                // Fail after deletion to exercise transaction rollback, not just a rejected precondition.
                it.execSQL("CREATE TRIGGER fail_clear AFTER DELETE ON meal_selection BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            }
            assertEquals(MealSelection.Failed(MealFailure.SAVE_FAILED), repository.clearSelection())
            assertEquals(MealSelection.Reviewed(record, record), repository.readSelection())
        }
        SqliteMealRepository(context, name).use { reopened ->
            assertEquals(MealSelection.Reviewed(record, record), reopened.readSelection())
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TRIGGER fail_clear")
            }
            assertEquals(MealSelection.Missing, reopened.clearSelection())
            assertEquals(listOf(record), (reopened.read(MealKind.DISH) as MealRead.Loaded).records)
        }
        SqliteMealRepository(context, name).use { assertEquals(MealSelection.Missing, it.readSelection()) }
    }

    @Test fun revisionHistoryIsReadAfterRestartWithoutChangingSelectionOrRevisions() = withDatabase { name ->
        lateinit var first: MealRecord
        lateinit var second: MealRecord
        SqliteMealRepository(context, name).use { repository ->
            assertEquals(MealRead.Loaded(emptyList()), repository.readRevisions("history"))
            first = (repository.save(MealRecord("history", 0, MealKind.DRAFT,
                MealContent(carbs = draftField("0"), notes = draftField("  exacto  ")),
                DishReference("history-dish", 2))) as MealSave.Saved).record
            repository.select(first)
            second = (repository.save(first.copy(content = first.content.copy(
                carbs = draftField("texto nuevo"), fat = draftField("0"),
                basis = NutritionBasis.TOTAL_GRAMS))) as MealSave.Saved).record
            repository.save(MealRecord("history-other", 0, MealKind.DISH, MealContent(name = draftField("otro"))))
        }
        SqliteMealRepository(context, name).use { reopened ->
            assertEquals(MealRead.Loaded(listOf(first, second)), reopened.readRevisions("history"))
            val useCase = ReadMealHistory(reopened)
            val history = useCase.read("history", MealKind.DRAFT) as MealHistory.Loaded
            assertEquals(listOf(second, first), history.revisions)
            assertEquals(DraftField.Entered("0"), history.revisions.last().content.carbs)
            assertEquals(DraftField.Missing, history.revisions.last().content.fat)
            assertEquals(DraftField.Entered("0"), history.latest.content.fat)
            assertEquals(DraftField.Entered("  exacto  "), history.revisions.last().content.notes)
            assertEquals(DishReference("history-dish", 2), history.latest.copiedFrom)
            assertFalse(history.allowsCalculation)
            assertFalse(history.allowsTreatment)
            assertEquals(MealHistory.Missing, useCase.read("absent", MealKind.DRAFT))
            assertEquals(MealHistory.Failed(MealFailure.INVALID_RECORD), useCase.read("history", MealKind.DISH))
            // Reading history is a query: selection, latest records and stored rows stay as they were.
            assertEquals(MealSelection.Reviewed(first, second), reopened.readSelection())
            assertEquals(listOf(second), (reopened.read(MealKind.DRAFT) as MealRead.Loaded).records)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM meal_revisions", null).use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)) }
        }
    }

    @Test fun revisionHistoryReadFailuresAreNotReportedAsMissing() = withDatabase { name ->
        SqliteMealRepository(context, name).use {
            it.save(MealRecord("history-failure", 0, MealKind.DISH, MealContent()))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE meal_selection")
            it.execSQL("DROP TABLE meal_revisions")
        }
        SqliteMealRepository(context, name).use { repository ->
            assertEquals(MealRead.Failed(MealFailure.READ_FAILED), repository.readRevisions("history-failure"))
            assertEquals(MealHistory.Failed(MealFailure.READ_FAILED),
                ReadMealHistory(repository).read("history-failure", MealKind.DISH))
        }
        withDatabase { unknown ->
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(unknown), null).use {
                it.execSQL("CREATE TABLE unknown_legacy_table(value TEXT)")
            }
            SqliteMealRepository(context, unknown).use {
                assertEquals(MealHistory.Failed(MealFailure.UNSUPPORTED_SCHEMA),
                    ReadMealHistory(it).read("history-failure", MealKind.DISH))
            }
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
