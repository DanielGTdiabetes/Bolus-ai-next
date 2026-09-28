package org.bolusai.next.profile

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.meals.*
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.profile.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ADR 0012. Synthetic, isolated database files only; the app's clinical-profile.db is never opened. */
@RunWith(AndroidJUnit4::class)
class SqliteClinicalProfileRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
    private val mmol: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MMOL_L)
    private val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun content(unit: Setting<GlucoseUnit> = mgdl, ratio: ProfileValue = entered("10"),
                        sensitivity: ProfileValue = entered("40"), target: ProfileValue = entered("110")) =
        ProfileContent(1, unit, madrid, listOf(
            ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ratio),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, target)))

    private fun withDatabase(run: (String) -> Unit) {
        val name = "synthetic-profile-${UUID.randomUUID()}.db"
        try { run(name) } finally { context.deleteDatabase(name) }
    }

    private fun write(base: Long, body: ProfileContent, origin: ProfileOrigin = ProfileOrigin.MANUAL, from: Long? = null) =
        ProfileWrite(base, body, origin, from)

    private fun SqliteClinicalProfileRepository.saved(write: ProfileWrite, at: Long = 1_000): ProfileVersion =
        (save(write, at, "test/synthetic") as ProfileSave.Saved).version

    private fun SqliteClinicalProfileRepository.all(): List<ProfileVersion> =
        (readVersions() as ProfileRead.Loaded).versions.sortedBy { it.version }

    private fun raw(name: String, run: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(run)

    private fun dump(name: String): List<String> {
        val rows = mutableListOf<String>()
        raw(name) { db ->
            listOf("SELECT * FROM profile_versions ORDER BY version",
                "SELECT * FROM profile_segments ORDER BY version, parameter, start_minute").forEach { query ->
                db.rawQuery(query, null).use { c ->
                    while (c.moveToNext()) rows.add((0 until c.columnCount).joinToString("|") {
                        if (c.isNull(it)) "NULL" else "${c.getType(it)}:${c.getString(it)}"
                    })
                }
            }
        }
        return rows
    }

    @Test fun saveRestartAndReadBackExactlyWithAbsenceDistinctFromZero() = withDatabase { name ->
        val v1 = SqliteClinicalProfileRepository(context, name).use {
            assertEquals(ProfileRead.Loaded(emptyList()), it.readVersions())
            it.saved(write(0, content(ratio = entered("0"), sensitivity = ProfileValue.NotConfigured)), at = 1_234)
        }
        SqliteClinicalProfileRepository(context, name).use { reopened ->
            val stored = reopened.all().single()
            assertEquals(v1, stored)
            assertEquals(entered("0"), stored.content.schedule(ProfileParameter.CARB_RATIO).segments.single().value)
            assertEquals(ProfileValue.NotConfigured,
                stored.content.schedule(ProfileParameter.INSULIN_SENSITIVITY).segments.single().value)
            assertEquals(1_234, stored.createdAtEpochMs)
            assertEquals("test/synthetic", stored.writer)
            assertEquals(ProfileCodec.sha256(stored.content), stored.contentSha256)
            assertFalse(stored.allowsCalculation || stored.allowsTreatment)
        }
        assertTrue(dump(name).any { it.contains("carb_ratio|1:0|1:1440|3:0") })
        assertTrue(dump(name).any { it.contains("insulin_sensitivity|1:0|1:1440|NULL") })
    }

    @Test fun identicalRetryAndStaleEditorAddNoRows() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val v1 = repository.saved(write(0, content()))
            val second = write(1, content(ratio = entered("11")))
            val v2 = repository.saved(second)
            assertEquals(ProfileSave.Saved(v2), repository.save(second, 99_999, "otro/escritor"))
            assertEquals(ProfileSave.Failed(ProfileFailure.CONFLICT), repository.save(write(1, content(ratio = entered("12"))), 1, "w"))
            assertEquals(ProfileSave.Failed(ProfileFailure.UNCHANGED), repository.save(write(2, content(ratio = entered("11"))), 1, "w"))
            assertEquals(listOf(v1, v2), repository.all())
        }
    }

    @Test fun twoConnectionsCannotBothWriteTheNextVersion() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { it.saved(write(0, content())) }
        val first = SqliteClinicalProfileRepository(context, name)
        val second = SqliteClinicalProfileRepository(context, name)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val a = pool.submit<ProfileSave> { start.await(); first.save(write(1, content(ratio = entered("11"))), 2, "a") }
            val b = pool.submit<ProfileSave> { start.await(); second.save(write(1, content(ratio = entered("12"))), 3, "b") }
            start.countDown()
            val results = listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it is ProfileSave.Saved })
            val failed = results.filterIsInstance<ProfileSave.Failed>().single()
            assertTrue(failed.reason in setOf(ProfileFailure.CONFLICT, ProfileFailure.SAVE_FAILED))
            val winner = (results.first { it is ProfileSave.Saved } as ProfileSave.Saved).version
            assertEquals(listOf(1L, 2L), first.all().map { it.version })
            assertEquals(winner, first.all().last())
        } finally {
            pool.shutdownNow(); first.close(); second.close()
        }
    }

    @Test fun triggersKeepEveryVersionImmutable() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use {
            it.saved(write(0, content())); it.saved(write(1, content(ratio = entered("11"))))
        }
        val before = dump(name)
        raw(name) { db ->
            listOf(
                "UPDATE profile_versions SET glucose_unit = 'mmol/L' WHERE version = 1",
                "UPDATE profile_segments SET value = '12' WHERE version = 1",
                "DELETE FROM profile_versions WHERE version = 2",
                "DELETE FROM profile_segments WHERE version = 1",
                "INSERT INTO profile_segments(version, parameter, start_minute, end_minute, value) VALUES (1, 'carb_ratio', 0, 60, '9')",
            ).forEach { sql -> assertThrows(sql, android.database.SQLException::class.java) { db.execSQL(sql) } }
        }
        assertEquals(before, dump(name))
    }

    @Test fun restorationPersistsProvenanceAndStorageRejectsImpossibleOnes() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val v1 = repository.saved(write(0, content(ratio = entered("0"))))
            val v2 = repository.saved(write(1, content(ratio = entered("12"))))
            val v3 = repository.saved(write(2, v1.content, ProfileOrigin.RESTORED, 1))
            assertEquals(v1.contentSha256, v3.contentSha256)
            assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_ORIGIN),
                repository.save(write(3, content(ratio = entered("13")), ProfileOrigin.RESTORED, 2), 1, "w"))
            assertEquals(ProfileSave.Failed(ProfileFailure.ORIGIN_NOT_ENABLED),
                repository.save(write(3, content(ratio = entered("13")), ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED), 1, "w"))
            assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
                repository.save(write(3, content(ratio = entered("14")), ProfileOrigin.MANUAL, 9), 1, "w"))
            assertEquals(listOf(v1, v2, v3), repository.all())
        }
        val sha = "a".repeat(64)
        raw(name) { db ->
            // Same connection configuration as the adapter, which always enables foreign keys.
            db.setForeignKeyConstraintsEnabled(true)
            listOf(
                // restored_from must name an older, existing version other than the replaced one.
                "INSERT INTO profile_versions VALUES (4, 1, 'mg/dL', 'Europe/Madrid', '$sha', 'manual', 3, 1, 'w')",
                "INSERT INTO profile_versions VALUES (4, 1, 'mg/dL', 'Europe/Madrid', '$sha', 'manual', 0, 1, 'w')",
                "INSERT INTO profile_versions VALUES (9, 1, 'mg/dL', 'Europe/Madrid', '$sha', 'manual', 7, 1, 'w')",
                "INSERT INTO profile_versions VALUES (4, 1, 'mg/dL', 'Europe/Madrid', '$sha', 'restored', NULL, 1, 'w')",
                "INSERT INTO profile_versions VALUES (4, 1, 'mg/dL', 'Europe/Madrid', 'short', 'manual', NULL, 1, 'w')",
            ).forEach { sql -> assertThrows(sql, android.database.SQLException::class.java) { db.execSQL(sql) } }
        }
        // A row written by a connection without foreign keys (dangling source, no segments) fails closed on read.
        raw(name) { db ->
            db.execSQL("DROP TRIGGER profile_segments_latest_only")
            db.execSQL("INSERT INTO profile_versions VALUES (9, 1, 'mg/dL', 'Europe/Madrid', '$sha', 'manual', 7, 1, 'w')")
        }
        SqliteClinicalProfileRepository(context, name).use {
            assertEquals(ProfileRead.Failed(ProfileFailure.INVALID_RECORD), it.readVersions())
        }
    }

    @Test fun failedWriteRollsBackVersionAndSegmentsThenRetrySucceeds() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val v1 = repository.saved(write(0, content()))
            raw(name) {
                it.execSQL("CREATE TRIGGER synthetic_failure AFTER INSERT ON profile_segments " +
                    "WHEN NEW.parameter = 'glucose_target' BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            }
            val next = write(1, content(target = entered("100")))
            assertEquals(ProfileSave.Failed(ProfileFailure.SAVE_FAILED), repository.save(next, 2, "w"))
            assertEquals(listOf(v1), repository.all())
            raw(name) { it.execSQL("DROP TRIGGER synthetic_failure") }
            assertEquals(2L, repository.saved(next).version)
        }
    }

    @Test fun multiSegmentSchedulesPersistExactly() = withDatabase { name ->
        val split = ProfileContent(1, mgdl, madrid, listOf(
            ParameterSchedule(ProfileParameter.CARB_RATIO, listOf(TimeSegment(0, 360, entered("8")),
                TimeSegment(360, 720, entered("8")), TimeSegment(720, 1440, ProfileValue.NotConfigured))),
            ParameterSchedule(ProfileParameter.INSULIN_SENSITIVITY, listOf(TimeSegment(0, 1320, entered("0")),
                TimeSegment(1320, 1440, entered("45.5")))),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET)))
        val v1 = SqliteClinicalProfileRepository(context, name).use { it.saved(write(0, split)) }
        SqliteClinicalProfileRepository(context, name).use { assertEquals(listOf(v1), it.all()) }
    }

    @Test fun unitChangeNeverReinterpretsStoredValues() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val v1 = repository.saved(write(0, content()))
            val v1Rows = dump(name)
            // Same numbers under another unit, and new numbers in the same unit-changing version: both rejected.
            assertEquals(ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES),
                repository.save(write(1, content(unit = mmol)), 2, "w"))
            assertEquals(ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES),
                repository.save(write(1, content(unit = mmol, sensitivity = entered("2.2"), target = ProfileValue.NotConfigured)), 2, "w"))
            assertEquals(v1Rows, dump(name))
            val unitOnly = content(unit = mmol, sensitivity = ProfileValue.NotConfigured, target = ProfileValue.NotConfigured)
            val v2 = repository.saved(write(1, unitOnly))
            val v3 = repository.saved(write(2, content(unit = mmol, sensitivity = ProfileValue.NotConfigured, target = entered("6.1"))))
            assertEquals(listOf(v1, v2, v3), repository.all())
            // Version 1 rows are byte-for-byte what they were before any unit change.
            assertEquals(v1Rows, dump(name).filter { it.startsWith("1:1|") })
            assertEquals(mgdl, repository.all().first().content.glucoseUnit)
        }
    }

    @Test fun tamperedRowsFailClosedOnRead() {
        fun forged(setup: (SQLiteDatabase) -> Unit, expected: ProfileFailure) = withDatabase { name ->
            SqliteClinicalProfileRepository(context, name).use { it.saved(write(0, content())) }
            raw(name) { db ->
                listOf("profile_versions_immutable_update", "profile_versions_immutable_delete",
                    "profile_segments_immutable_update", "profile_segments_immutable_delete", "profile_segments_latest_only")
                    .forEach { db.execSQL("DROP TRIGGER $it") }
                setup(db)
            }
            SqliteClinicalProfileRepository(context, name).use {
                assertEquals(ProfileRead.Failed(expected), it.readVersions())
            }
        }
        fun insertVersion(db: SQLiteDatabase, version: Int, unit: String?, text: String, rows: List<Triple<String, Int, String?>>,
                          origin: String = "manual", restoredFrom: Int? = null) {
            db.execSQL("INSERT INTO profile_versions VALUES (?, 1, ?, 'Europe/Madrid', ?, ?, ?, 5, 'forged')",
                arrayOf<Any?>(version, unit, Sha256.hex(text.encodeToByteArray()), origin, restoredFrom))
            rows.forEach { (parameter, _, value) ->
                db.execSQL("INSERT INTO profile_segments VALUES (?, ?, 0, 1440, ?)", arrayOf<Any?>(version, parameter, value))
            }
        }
        fun canonical(unit: String, ratio: String, target: String, sensitivity: String) =
            "bolus-ai-next/clinical-profile/content\nschema=1\nglucose_unit=$unit\ntime_zone=Europe/Madrid\n" +
                "parameter=carb_ratio\nsegment=0-1440:$ratio\nparameter=glucose_target\nsegment=0-1440:$target\n" +
                "parameter=insulin_sensitivity\nsegment=0-1440:$sensitivity\n"

        // Altered value without a new fingerprint.
        forged({ it.execSQL("UPDATE profile_segments SET value = '11' WHERE parameter = 'carb_ratio'") }, ProfileFailure.INVALID_RECORD)
        // Unit silently changed on the stored version.
        forged({ it.execSQL("UPDATE profile_versions SET glucose_unit = 'mmol/L'") }, ProfileFailure.INVALID_RECORD)
        // Non-canonical spelling of the same number.
        forged({ it.execSQL("UPDATE profile_segments SET value = '10.0' WHERE parameter = 'carb_ratio'") }, ProfileFailure.INVALID_RECORD)
        // A version with a recomputed fingerprint that reinterprets the previous mg/dL values as mmol/L.
        forged({ insertVersion(it, 2, "mmol/L", canonical("mmol/L", "10", "110", "40"),
            listOf(Triple("carb_ratio", 0, "10"), Triple("glucose_target", 0, "110"), Triple("insulin_sensitivity", 0, "40"))) },
            ProfileFailure.INVALID_RECORD)
        // Dependent values without any unit, fingerprint recomputed.
        forged({ insertVersion(it, 2, null, canonical("~", "10", "110", "40"),
            listOf(Triple("carb_ratio", 0, "10"), Triple("glucose_target", 0, "110"), Triple("insulin_sensitivity", 0, "40"))) },
            ProfileFailure.INVALID_RECORD)
        // Reserved origin, unknown parameter, missing segment and gaps.
        forged({ it.execSQL("UPDATE profile_versions SET origin = 'system_proposal_accepted'") }, ProfileFailure.INVALID_RECORD)
        forged({ it.execSQL("UPDATE profile_segments SET parameter = 'dia' WHERE parameter = 'carb_ratio'") }, ProfileFailure.INVALID_RECORD)
        forged({ it.execSQL("DELETE FROM profile_segments WHERE parameter = 'glucose_target'") }, ProfileFailure.INVALID_RECORD)
        forged({ it.execSQL("UPDATE profile_segments SET end_minute = 600 WHERE parameter = 'carb_ratio'") }, ProfileFailure.INVALID_RECORD)
        forged({ it.execSQL("UPDATE profile_versions SET content_sha256 = '${"0".repeat(64)}'") }, ProfileFailure.INVALID_RECORD)
        // A content schema this build does not know is not guessed.
        forged({ it.execSQL("UPDATE profile_versions SET schema_version = 2") }, ProfileFailure.UNSUPPORTED_SCHEMA)
    }

    @Test fun saveRefusesToAppendToAnUnprovableHistory() {
        fun damaged(setup: (SQLiteDatabase) -> Unit) = withDatabase { name ->
            SqliteClinicalProfileRepository(context, name).use {
                it.saved(write(0, content())); it.saved(write(1, content(ratio = entered("11"))))
            }
            raw(name) { db ->
                listOf("profile_versions_immutable_update", "profile_versions_immutable_delete",
                    "profile_segments_immutable_update", "profile_segments_immutable_delete")
                    .forEach { db.execSQL("DROP TRIGGER $it") }
                setup(db)
            }
            val before = dump(name)
            SqliteClinicalProfileRepository(context, name).use {
                // The latest row still decodes, but an older one does not: nothing may be appended.
                assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
                    it.save(write(2, content(ratio = entered("12"))), 3, "w"))
            }
            assertEquals(before, dump(name))
        }
        damaged { it.execSQL("UPDATE profile_segments SET value = '9' WHERE version = 1 AND parameter = 'carb_ratio'") }
        damaged {
            it.execSQL("DELETE FROM profile_segments WHERE version = 1")
            it.execSQL("DELETE FROM profile_versions WHERE version = 1")
        }
        damaged { it.execSQL("UPDATE profile_versions SET glucose_unit = 'mmol/L' WHERE version = 1") }
    }

    @Test fun historyGapsAreRejectedByTheUseCase() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use {
            it.saved(write(0, content())); it.saved(write(1, content(ratio = entered("11"))))
        }
        raw(name) { db ->
            db.execSQL("DROP TRIGGER profile_versions_immutable_delete")
            db.execSQL("DROP TRIGGER profile_segments_immutable_delete")
            db.execSQL("DELETE FROM profile_segments WHERE version = 1")
            db.execSQL("DELETE FROM profile_versions WHERE version = 1")
        }
        SqliteClinicalProfileRepository(context, name).use {
            val history = ClinicalProfiles(it, ProfileClock { 0 }, "w", AndroidTimeZoneRules).read()
            assertEquals(ProfileHistory.Failed(ProfileFailure.INVALID_RECORD), history)
        }
    }

    @Test fun unknownFilesAndFutureSchemasFailClosedWithoutBeingTouched() {
        withDatabase { name ->
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
                it.execSQL("CREATE TABLE foreign_table(value TEXT)")
                it.execSQL("INSERT INTO foreign_table VALUES ('sintético')")
            }
            SqliteClinicalProfileRepository(context, name).use {
                assertEquals(ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), it.readVersions())
                assertEquals(ProfileSave.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), it.save(write(0, content()), 1, "w"))
            }
            raw(name) { db ->
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('android_metadata')", null).use {
                    val tables = buildList { while (it.moveToNext()) add(it.getString(0)) }
                    assertEquals(listOf("foreign_table"), tables)
                }
                assertEquals(0L, android.database.DatabaseUtils.longForQuery(db, "PRAGMA user_version", null))
            }
        }
        withDatabase { name ->
            SqliteClinicalProfileRepository(context, name).use { it.saved(write(0, content())) }
            raw(name) { it.version = 2 }
            SqliteClinicalProfileRepository(context, name).use {
                assertEquals(ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), it.readVersions())
            }
            raw(name) { assertEquals(2, it.version) }
        }
    }

    @Test fun closedFileCopyReopensAndValidatesAndMealDatabaseIsIndependent() = withDatabase { name ->
        val mealName = "synthetic-meals-${UUID.randomUUID()}.db"
        val backup = "synthetic-profile-backup-${UUID.randomUUID()}.db"
        try {
            val meal = SqliteMealRepository(context, mealName).use {
                val saved = (it.save(MealRecord("meal", 0, MealKind.DISH, MealContent(carbs = draftField("0")))) as MealSave.Saved).record
                it.select(saved); saved
            }
            val versions = SqliteClinicalProfileRepository(context, name).use {
                listOf(it.saved(write(0, content())), it.saved(write(1, content(ratio = entered("0")))))
            }
            // Copy only while every connection is closed, as a manual file backup would.
            context.getDatabasePath(name).copyTo(context.getDatabasePath(backup))
            SqliteClinicalProfileRepository(context, backup).use { assertEquals(versions, it.all()) }
            SqliteMealRepository(context, mealName).use {
                assertEquals(MealRead.Loaded(listOf(meal)), it.readRevisions("meal"))
                assertEquals(MealSelection.Reviewed(meal, meal), it.readSelection())
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(mealName).path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(3, it.version)
                assertEquals(0L, android.database.DatabaseUtils.longForQuery(it,
                    "SELECT count(*) FROM sqlite_master WHERE name LIKE 'profile_%'", null))
            }
            // A tampered backup copy fails closed instead of being repaired.
            raw(backup) { db ->
                db.execSQL("DROP TRIGGER profile_segments_immutable_update")
                db.execSQL("UPDATE profile_segments SET value = '9' WHERE version = 2 AND parameter = 'carb_ratio'")
            }
            SqliteClinicalProfileRepository(context, backup).use {
                assertEquals(ProfileRead.Failed(ProfileFailure.INVALID_RECORD), it.readVersions())
            }
            SqliteClinicalProfileRepository(context, name).use { assertEquals(versions, it.all()) }
        } finally {
            context.deleteDatabase(mealName); context.deleteDatabase(backup)
        }
    }
}
