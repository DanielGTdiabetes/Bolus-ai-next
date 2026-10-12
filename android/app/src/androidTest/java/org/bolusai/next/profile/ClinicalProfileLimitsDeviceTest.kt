package org.bolusai.next.profile

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.profile.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ADR 0025 on SQLite: `clinical-profile.db` (v3, now v4) stores the profile-wide limits of content schema 2. Migrations from v1
 * and v2 keep every row, fingerprint and confirmation, and fill in no limit. Synthetic isolated files only.
 */
@RunWith(AndroidJUnit4::class)
class ClinicalProfileLimitsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
    private val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))
    private val writer = "test/synthetic"

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun schemaOne(ratio: String = "10") = ProfileContent(1, mgdl, madrid, listOf(
        ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, entered(ratio)),
        ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, entered("40")),
        ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110"))))
    private fun limited(step: ProfileValue = entered("0.5"), bolus: ProfileValue = entered("0"),
                        correction: ProfileValue = ProfileValue.NotConfigured) =
        schemaOne().withLimit(ProfileLimit.ROUND_STEP, step).withLimit(ProfileLimit.MAX_BOLUS, bolus)
            .withLimit(ProfileLimit.MAX_CORRECTION, correction)

    private fun withDatabase(run: (String) -> Unit) {
        val name = "synthetic-profile-limits-${UUID.randomUUID()}.db"
        try { run(name) } finally { context.deleteDatabase(name) }
    }

    private fun path(name: String) = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }

    private fun raw(name: String, run: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(path(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(run)

    private fun userVersion(name: String): Int =
        SQLiteDatabase.openDatabase(path(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    private fun limitRows(name: String): List<String> = buildList {
        raw(name) { db ->
            db.rawQuery("SELECT version, limit_code, value FROM profile_limits ORDER BY version, limit_code", null).use { c ->
                while (c.moveToNext()) add("${c.getLong(0)}:${c.getString(1)}:${if (c.isNull(2)) "NULL" else c.getString(2)}")
            }
        }
    }

    /** A file as an older release wrote it: frozen statements, its user_version, policy-approved schema 1 rows. */
    private fun olderFile(name: String, statements: List<String>, userVersion: Int, confirmLatest: Boolean): List<ProfileVersion> {
        val versions = mutableListOf<ProfileVersion>()
        SQLiteDatabase.openOrCreateDatabase(path(name), null).use { db ->
            statements.forEach(db::execSQL)
            listOf(schemaOne(), schemaOne("12")).forEachIndexed { index, body ->
                val decision = ProfileWritePolicy.evaluate(ProfileWrite(index.toLong(), body, ProfileOrigin.MANUAL, null),
                    1_000L + index, "android/0.1.0-dev(1)", versions.lastOrNull(), null, null)
                val version = (decision as ProfileWriteDecision.Insert).version
                ClinicalProfileRows.insertVersion(db, version)
                versions.add(version)
            }
            if (confirmLatest) {
                val record = (ProfileRecord.validate(versions, emptyList()) as ProfileRecordRead.Loaded).record
                val latest = versions.last()
                val decision = ConfirmationPolicy.evaluate(ConfirmationRequest.Confirm(
                    OperationId("00000000-0000-4000-8000-000000000001"), latest.version, latest.contentSha256, 0),
                    2_000, writer, record, AndroidTimeZoneRules)
                ClinicalProfileRows.insertEvent(db, (decision as ConfirmationDecision.Insert).event)
            }
            db.version = userVersion
        }
        return versions
    }

    @Test fun migrationsFromV1AndV2KeepRowsAndConfirmationAndFillInNoLimit() {
        listOf(
            Triple(ClinicalProfileSchema.SCHEMA_V1, ClinicalProfileSchema.V1, false),
            Triple(ClinicalProfileSchema.SCHEMA_V2, ClinicalProfileSchema.V2, true),
        ).forEach { (statements, version, confirmed) ->
            withDatabase { name ->
                val versions = olderFile(name, statements, version, confirmed)
                SqliteClinicalProfileRepository(context, name).use { repository ->
                    assertEquals(ProfileRead.Loaded(versions), repository.readVersions())
                    val state = ProfileGateState.of(repository.readRecord(), AndroidTimeZoneRules) as ProfileGateState.Evaluated
                    assertEquals(confirmed, state.confirmation is VersionConfirmation.Active)
                    ProfileLimit.entries.forEach { assertNull(state.latest.content.limit(it)) }
                }
                assertEquals(ClinicalProfileSchema.V4, userVersion(name))
                assertEquals(emptyList<String>(), limitRows(name))
                raw(name) { ClinicalProfileSchema.verify(it, ClinicalProfileSchema.V4) }
            }
        }
    }

    @Test fun limitsPersistExactlyAcrossRestartWithAbsenceDistinctFromZero() = withDatabase { name ->
        val saved = SqliteClinicalProfileRepository(context, name).use { repository ->
            (repository.save(ProfileWrite(0, schemaOne(), ProfileOrigin.MANUAL, null), 1, writer) as ProfileSave.Saved)
            (repository.save(ProfileWrite(1, limited(), ProfileOrigin.MANUAL, null), 2, writer) as ProfileSave.Saved).version
        }
        assertEquals(listOf("2:max_bolus:0", "2:max_correction:NULL", "2:round_step:0.5"), limitRows(name))
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val read = (repository.readVersions() as ProfileRead.Loaded).versions.sortedBy { it.version }
            assertEquals(saved, read.last())
            assertEquals(entered("0"), read.last().content.limit(ProfileLimit.MAX_BOLUS))
            assertEquals(ProfileValue.NotConfigured, read.last().content.limit(ProfileLimit.MAX_CORRECTION))
            assertNull(read.first().content.limit(ProfileLimit.MAX_BOLUS))
            // A later edit carries the stored limits; nothing has to be typed again.
            val next = read.last().content.withDayValue(ProfileParameter.CARB_RATIO, entered("11"))
            val v3 = (repository.save(ProfileWrite(2, next, ProfileOrigin.MANUAL, null), 3, writer) as ProfileSave.Saved).version
            assertEquals(saved.content.limits, v3.content.limits)
            // Going back to schema 1 by a manual edit is refused and writes nothing.
            assertEquals(ProfileSave.Failed(ProfileFailure.SCHEMA_DOWNGRADE),
                repository.save(ProfileWrite(3, schemaOne("13"), ProfileOrigin.MANUAL, null), 4, writer))
        }
        assertEquals(6, limitRows(name).size)
    }

    private fun ProfileContent.withDayValue(parameter: ProfileParameter, value: ProfileValue): ProfileContent =
        (edited(SegmentOperation.SetValue(parameter, SegmentRef(0, ProfileCatalog.MINUTES_PER_DAY), value)) as ProfileEdit.Changed).content

    @Test fun limitRowsAreAppendOnlyAndOnlyForTheLatestVersion() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use {
            it.save(ProfileWrite(0, limited(), ProfileOrigin.MANUAL, null), 1, writer)
            it.save(ProfileWrite(1, limited(step = entered("0.1")), ProfileOrigin.MANUAL, null), 2, writer)
        }
        raw(name) { db ->
            listOf(
                "UPDATE profile_limits SET value = '1' WHERE version = 2 AND limit_code = 'max_bolus'",
                "DELETE FROM profile_limits WHERE version = 1",
                "INSERT INTO profile_limits(version, limit_code, value) VALUES (1, 'other', '1')",
            ).forEach { sql -> assertThrows(SQLiteConstraintException::class.java) { db.execSQL(sql) } }
        }
        assertEquals(6, limitRows(name).size)
    }

    @Test fun tamperedLimitRowsFailClosedOnRead() {
        fun tampered(change: (SQLiteDatabase) -> Unit) = withDatabase { name ->
            SqliteClinicalProfileRepository(context, name).use {
                it.save(ProfileWrite(0, schemaOne(), ProfileOrigin.MANUAL, null), 1, writer)
                it.save(ProfileWrite(1, limited(), ProfileOrigin.MANUAL, null), 2, writer)
            }
            raw(name) { db ->
                listOf("profile_limits_immutable_update", "profile_limits_immutable_delete", "profile_limits_latest_only")
                    .forEach { db.execSQL("DROP TRIGGER $it") }
                change(db)
                ClinicalProfileSchema.V3_ADDITIONS.filter { it.startsWith("CREATE TRIGGER ") }.forEach(db::execSQL)
            }
            SqliteClinicalProfileRepository(context, name).use {
                assertEquals(ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD), it.readRecord())
                assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
                    it.save(ProfileWrite(2, limited(step = entered("1")), ProfileOrigin.MANUAL, null), 3, writer))
            }
        }
        // A changed value no longer matches the stored fingerprint.
        tampered { it.execSQL("UPDATE profile_limits SET value = '5' WHERE version = 2 AND limit_code = 'max_bolus'") }
        // A schema 2 version without one of its limits.
        tampered { it.execSQL("DELETE FROM profile_limits WHERE version = 2 AND limit_code = 'round_step'") }
        // A schema 1 version may not hold limits: they are never backfilled.
        tampered { it.execSQL("INSERT INTO profile_limits(version, limit_code, value) VALUES (1, 'round_step', '0.5')") }
        // Unknown code and non-canonical text.
        tampered { it.execSQL("UPDATE profile_limits SET limit_code = 'max_iob' WHERE version = 2 AND limit_code = 'max_bolus'") }
        tampered { it.execSQL("UPDATE profile_limits SET value = '0.50' WHERE version = 2 AND limit_code = 'round_step'") }
    }
}
