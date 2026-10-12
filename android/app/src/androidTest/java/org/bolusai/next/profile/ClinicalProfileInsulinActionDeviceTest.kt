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
 * ADR 0027 on SQLite: `clinical-profile.db` v4 stores the insulin curve of content schema 3; DIA and peak use
 * `profile_limits`. Migrations from v1, v2 and v3 keep every row, fingerprint and confirmation and fill in no curve,
 * DIA or peak. Synthetic isolated files only.
 */
@RunWith(AndroidJUnit4::class)
class ClinicalProfileInsulinActionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
    private val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))
    private val writer = "test/synthetic"

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun schemaOne(ratio: String = "10") = ProfileContent(1, mgdl, madrid, listOf(
        ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, entered(ratio)),
        ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, entered("40")),
        ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110"))))
    private fun schemaTwo(ratio: String = "10") = schemaOne(ratio).withLimit(ProfileLimit.ROUND_STEP, entered("0.5"))
    private fun action(curve: Setting<InsulinCurveCode> = Setting.Declared(InsulinCurveCode.FIASP),
                       dia: ProfileValue = entered("5"), peak: ProfileValue = ProfileValue.NotConfigured) =
        schemaTwo().withInsulinCurve(curve).withLimit(ProfileLimit.INSULIN_DIA, dia).withLimit(ProfileLimit.INSULIN_PEAK, peak)

    private fun withDatabase(run: (String) -> Unit) {
        val name = "synthetic-profile-action-${UUID.randomUUID()}.db"
        try { run(name) } finally { context.deleteDatabase(name) }
    }

    private fun path(name: String) = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }

    private fun raw(name: String, run: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(path(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(run)

    private fun userVersion(name: String): Int =
        SQLiteDatabase.openDatabase(path(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    private fun curveRows(name: String): List<String> = buildList {
        raw(name) { db ->
            db.rawQuery("SELECT version, curve FROM profile_insulin_curve ORDER BY version", null).use { c ->
                while (c.moveToNext()) add("${c.getLong(0)}:${if (c.isNull(1)) "NULL" else c.getString(1)}")
            }
        }
    }

    private fun actionLimitRows(name: String): List<String> = buildList {
        raw(name) { db ->
            db.rawQuery("SELECT version, limit_code, value FROM profile_limits WHERE limit_code LIKE 'insulin_%' " +
                "ORDER BY version, limit_code", null).use { c ->
                while (c.moveToNext()) add("${c.getLong(0)}:${c.getString(1)}:${if (c.isNull(2)) "NULL" else c.getString(2)}")
            }
        }
    }

    /**
     * A file as an older release wrote it: frozen statements, its user_version and policy-approved rows of the content
     * schemas that release knew. Rows are written with the current codec, which writes no curve row for schemas 1 and 2.
     */
    private fun olderFile(name: String, statements: List<String>, userVersion: Int, bodies: List<ProfileContent>): List<ProfileVersion> {
        val versions = mutableListOf<ProfileVersion>()
        SQLiteDatabase.openOrCreateDatabase(path(name), null).use { db ->
            statements.forEach(db::execSQL)
            bodies.forEachIndexed { index, body ->
                val decision = ProfileWritePolicy.evaluate(ProfileWrite(index.toLong(), body, ProfileOrigin.MANUAL, null),
                    1_000L + index, "android/0.1.0-dev(1)", versions.lastOrNull(), null, null)
                val version = (decision as ProfileWriteDecision.Insert).version
                ClinicalProfileRows.insertVersion(db, version)
                versions.add(version)
            }
            if (userVersion >= ClinicalProfileSchema.V2) {
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

    @Test fun migrationsFromV1V2AndV3KeepRowsAndConfirmationAndFillInNoAction() {
        listOf(
            Triple(ClinicalProfileSchema.SCHEMA_V1, ClinicalProfileSchema.V1, listOf(schemaOne(), schemaOne("12"))),
            Triple(ClinicalProfileSchema.SCHEMA_V2, ClinicalProfileSchema.V2, listOf(schemaOne(), schemaOne("12"))),
            Triple(ClinicalProfileSchema.SCHEMA_V3, ClinicalProfileSchema.V3, listOf(schemaOne(), schemaTwo("12"))),
        ).forEach { (statements, version, bodies) ->
            withDatabase { name ->
                val versions = olderFile(name, statements, version, bodies)
                SqliteClinicalProfileRepository(context, name).use { repository ->
                    assertEquals(ProfileRead.Loaded(versions), repository.readVersions())
                    val state = ProfileGateState.of(repository.readRecord(), AndroidTimeZoneRules) as ProfileGateState.Evaluated
                    assertEquals(version >= ClinicalProfileSchema.V2, state.confirmation is VersionConfirmation.Active)
                    assertNull(state.latest.content.insulinCurve)
                    assertNull(state.latest.content.limit(ProfileLimit.INSULIN_DIA))
                    assertNull(state.latest.content.limit(ProfileLimit.INSULIN_PEAK))
                }
                assertEquals(ClinicalProfileSchema.V4, userVersion(name))
                assertEquals(emptyList<String>(), curveRows(name))
                assertEquals(emptyList<String>(), actionLimitRows(name))
                raw(name) { ClinicalProfileSchema.verify(it, ClinicalProfileSchema.V4) }
            }
        }
    }

    @Test fun actionPersistsExactlyAcrossRestartAndIsCarriedByLaterEdits() = withDatabase { name ->
        val saved = SqliteClinicalProfileRepository(context, name).use { repository ->
            (repository.save(ProfileWrite(0, schemaTwo(), ProfileOrigin.MANUAL, null), 1, writer) as ProfileSave.Saved)
            (repository.save(ProfileWrite(1, action(), ProfileOrigin.MANUAL, null), 2, writer) as ProfileSave.Saved).version
        }
        assertEquals(listOf("2:fiasp"), curveRows(name))
        assertEquals(listOf("2:insulin_dia:5", "2:insulin_peak:NULL"), actionLimitRows(name))
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val read = (repository.readVersions() as ProfileRead.Loaded).versions.sortedBy { it.version }
            assertEquals(saved, read.last())
            assertNull(read.first().content.insulinCurve)
            val next = (read.last().content.edited(SegmentOperation.SetValue(ProfileParameter.CARB_RATIO,
                SegmentRef(0, ProfileCatalog.MINUTES_PER_DAY), entered("11"))) as ProfileEdit.Changed).content
            val v3 = (repository.save(ProfileWrite(2, next, ProfileOrigin.MANUAL, null), 3, writer) as ProfileSave.Saved).version
            assertEquals(saved.content.insulinCurve, v3.content.insulinCurve)
            assertEquals(saved.content.limits, v3.content.limits)
            assertEquals(ProfileSave.Failed(ProfileFailure.SCHEMA_DOWNGRADE),
                repository.save(ProfileWrite(3, schemaTwo("13"), ProfileOrigin.MANUAL, null), 4, writer))
            // Not configured is stored as such and is distinct from a declared curve.
            val cleared = v3.content.withInsulinCurve(Setting.NotConfigured)
            val v4 = (repository.save(ProfileWrite(3, cleared, ProfileOrigin.MANUAL, null), 5, writer) as ProfileSave.Saved).version
            assertEquals(Setting.NotConfigured, v4.content.insulinCurve)
        }
        assertEquals(listOf("2:fiasp", "3:fiasp", "4:NULL"), curveRows(name))
    }

    @Test fun curveRowsAreAppendOnlyAndOnlyForTheLatestVersion() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use {
            it.save(ProfileWrite(0, action(), ProfileOrigin.MANUAL, null), 1, writer)
            it.save(ProfileWrite(1, action(curve = Setting.Declared(InsulinCurveCode.WALSH), peak = entered("75")),
                ProfileOrigin.MANUAL, null), 2, writer)
        }
        raw(name) { db ->
            listOf(
                "UPDATE profile_insulin_curve SET curve = 'linear' WHERE version = 2",
                "DELETE FROM profile_insulin_curve WHERE version = 1",
                "INSERT INTO profile_insulin_curve(version, curve) VALUES (1, 'walsh')",
                "INSERT INTO profile_insulin_curve(version, curve) VALUES (2, 'walsh')",
            ).forEach { sql -> assertThrows(SQLiteConstraintException::class.java) { db.execSQL(sql) } }
        }
        assertEquals(listOf("1:fiasp", "2:walsh"), curveRows(name))
    }

    @Test fun tamperedCurveRowsFailClosedOnRead() {
        fun tampered(change: (SQLiteDatabase) -> Unit) = withDatabase { name ->
            SqliteClinicalProfileRepository(context, name).use {
                it.save(ProfileWrite(0, schemaTwo(), ProfileOrigin.MANUAL, null), 1, writer)
                it.save(ProfileWrite(1, action(), ProfileOrigin.MANUAL, null), 2, writer)
            }
            raw(name) { db ->
                (ClinicalProfileSchema.V3_ADDITIONS + ClinicalProfileSchema.V4_ADDITIONS)
                    .filter { it.startsWith("CREATE TRIGGER ") }
                    .forEach { db.execSQL("DROP TRIGGER ${it.removePrefix("CREATE TRIGGER ").substringBefore(' ')}") }
                change(db)
                (ClinicalProfileSchema.V3_ADDITIONS + ClinicalProfileSchema.V4_ADDITIONS)
                    .filter { it.startsWith("CREATE TRIGGER ") }.forEach(db::execSQL)
            }
            SqliteClinicalProfileRepository(context, name).use {
                assertEquals(ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD), it.readRecord())
                assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
                    it.save(ProfileWrite(2, action(dia = entered("4")), ProfileOrigin.MANUAL, null), 3, writer))
            }
        }
        // A changed curve or DIA no longer matches the stored fingerprint.
        tampered { it.execSQL("UPDATE profile_insulin_curve SET curve = 'walsh' WHERE version = 2") }
        tampered { it.execSQL("UPDATE profile_limits SET value = '4' WHERE version = 2 AND limit_code = 'insulin_dia'") }
        // A schema 3 version without its curve row or without one of its action fields.
        tampered { it.execSQL("DELETE FROM profile_insulin_curve WHERE version = 2") }
        tampered { it.execSQL("DELETE FROM profile_limits WHERE version = 2 AND limit_code = 'insulin_peak'") }
        // A schema 2 version may not hold a curve: it is never backfilled.
        tampered { it.execSQL("INSERT INTO profile_insulin_curve(version, curve) VALUES (1, 'fiasp')") }
        // Unknown curve and a fractional peak.
        tampered { it.execSQL("UPDATE profile_insulin_curve SET curve = 'exponential' WHERE version = 2") }
        tampered { it.execSQL("UPDATE profile_limits SET value = '75.5' WHERE version = 2 AND limit_code = 'insulin_peak'") }
    }
}
