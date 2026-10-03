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

/**
 * ADR 0014 on SQLite: schema v2, migration v1 -> v2 verified by definition, prior-copy tests and append-only,
 * idempotent confirmation events. Synthetic, isolated database files only; the app's clinical-profile.db is never
 * opened, nothing touches the network and calculation stays blocked in every state.
 */
@RunWith(AndroidJUnit4::class)
class ClinicalProfileConfirmationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
    private val mmol: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MMOL_L)
    private val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))
    private val writer = "test/synthetic"

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun content(unit: Setting<GlucoseUnit> = mgdl, ratio: ProfileValue = entered("10"),
                        sensitivity: ProfileValue = entered("40"), target: ProfileValue = entered("110")) =
        ProfileContent(1, unit, madrid, listOf(
            ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ratio),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, target)))

    private val split = ProfileContent(1, mgdl, madrid, listOf(
        ParameterSchedule(ProfileParameter.CARB_RATIO, listOf(TimeSegment(0, 360, entered("8")),
            TimeSegment(360, 720, entered("8")), TimeSegment(720, 1440, ProfileValue.NotConfigured))),
        ParameterSchedule(ProfileParameter.INSULIN_SENSITIVITY, listOf(TimeSegment(0, 1320, entered("0")),
            TimeSegment(1320, 1440, entered("45.5")))),
        ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110"))))

    /** v1: 0 and "not configured", multiple segments, a unit change and an exact restoration. */
    private val history = listOf(
        ProfileWrite(0, content(ratio = entered("0"), sensitivity = ProfileValue.NotConfigured), ProfileOrigin.MANUAL, null),
        ProfileWrite(1, split, ProfileOrigin.MANUAL, null),
        ProfileWrite(2, split.withGlucoseUnit(mmol), ProfileOrigin.MANUAL, null),
        ProfileWrite(3, content(ratio = entered("0"), sensitivity = ProfileValue.NotConfigured), ProfileOrigin.RESTORED, 1),
        ProfileWrite(4, content(), ProfileOrigin.MANUAL, null),
    )

    private fun withDatabase(run: (String) -> Unit) {
        val name = "synthetic-profile-${UUID.randomUUID()}.db"
        try { run(name) } finally { context.deleteDatabase(name) }
    }

    private fun path(name: String) = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }

    private fun raw(name: String, run: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(path(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(run)

    private fun userVersion(name: String): Int =
        SQLiteDatabase.openDatabase(path(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    /** Builds a v1 file as released builds wrote it: frozen v1 statements, user_version 1 and policy-approved rows. */
    private fun v1File(name: String, writes: List<ProfileWrite> = emptyList(),
                       statements: List<String> = ClinicalProfileSchema.SCHEMA_V1): List<ProfileVersion> {
        val versions = mutableListOf<ProfileVersion>()
        SQLiteDatabase.openOrCreateDatabase(path(name), null).use { db ->
            statements.forEach(db::execSQL)
            writes.forEachIndexed { index, write ->
                val decision = ProfileWritePolicy.evaluate(write, 1_000L + index, "android/0.1.0-dev(1)",
                    versions.lastOrNull(), null, write.restoredFrom?.let { n -> versions.first { it.version == n } })
                val version = (decision as ProfileWriteDecision.Insert).version
                ClinicalProfileRows.insertVersion(db, version)
                versions.add(version)
            }
            db.version = 1
        }
        return versions
    }

    private fun schema(name: String): List<String> = buildList {
        raw(name) { db ->
            db.rawQuery("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", null).use { c ->
                while (c.moveToNext()) add((0 until 4).joinToString("|") { if (c.isNull(it)) "NULL" else c.getString(it) })
            }
        }
    }

    private fun rows(name: String): List<String> = buildList {
        raw(name) { db ->
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'profile_%' ORDER BY name",
                null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            tables.forEach { table ->
                db.rawQuery("SELECT * FROM $table ORDER BY 1, 2, 3", null).use { c ->
                    while (c.moveToNext()) add(table + ":" + (0 until c.columnCount).joinToString("|") {
                        if (c.isNull(it)) "NULL" else "${c.getType(it)}:${c.getString(it)}"
                    })
                }
            }
        }
    }

    private fun SQLiteDatabase.withoutTriggers(statements: List<String>, vararg triggers: String, change: (SQLiteDatabase) -> Unit) {
        triggers.forEach { execSQL("DROP TRIGGER $it") }
        change(this)
        triggers.forEach { trigger -> execSQL(statements.single { it.startsWith("CREATE TRIGGER $trigger ") }) }
    }

    private fun profiles(repository: ClinicalProfileRepository, zones: TimeZoneRules = AndroidTimeZoneRules) =
        ClinicalProfiles(repository, ProfileClock { 7_000 }, writer, zones)

    private fun SqliteClinicalProfileRepository.record(): ProfileRecord = (readRecord() as ProfileRecordRead.Loaded).record
    private fun ClinicalProfiles.evaluated() = readState() as ProfileGateState.Evaluated
    private fun ClinicalProfiles.recorded(request: ConfirmationRequest) = (record(request) as ConfirmationOutcome.Recorded).also {
        assertFalse(it.allowsCalculation || it.allowsTreatment || it.state.allowsCalculation || it.state.allowsTreatment)
    }

    @Test fun cleanCreationEqualsMigratingAnEmptyV1() = withDatabase { created ->
        withDatabase { migrated ->
            SqliteClinicalProfileRepository(context, created).use { assertEquals(VersionlessEmpty, it.readRecord().shape()) }
            v1File(migrated)
            SqliteClinicalProfileRepository(context, migrated).use { assertEquals(VersionlessEmpty, it.readRecord().shape()) }
            assertEquals(schema(created), schema(migrated))
            assertEquals(2, userVersion(created))
            assertEquals(2, userVersion(migrated))
            listOf(created, migrated).forEach { name -> raw(name) { ClinicalProfileSchema.verify(it, ClinicalProfileSchema.V2) } }
            // The stored definitions are byte-for-byte the frozen v2 text.
            val sql = schema(created).map { it.substringAfterLast("|") }
            ClinicalProfileSchema.SCHEMA_V2.forEach { assertTrue(it, it in sql) }
        }
    }

    @Test fun migrationKeepsEveryRowFingerprintAndProvenanceAndCreatesNoConfirmation() = withDatabase { name ->
        val versions = v1File(name, history)
        val before = rows(name)
        assertEquals(5, versions.size)
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val record = repository.record()
            assertEquals(versions.reversed(), (record.history as ProfileHistory.Loaded).versions)
            assertEquals(emptyList<ConfirmationEvent>(), record.events)
            assertEquals(ProfileRead.Loaded(versions), repository.readVersions())
            val state = profiles(repository).evaluated()
            assertEquals(VersionConfirmation.Unconfirmed, state.confirmation)
            assertTrue(state.canConfirm)
        }
        assertEquals(before, rows(name))
        assertEquals(2, userVersion(name))
        // Fingerprints are unchanged: re-deriving them from the stored content gives the stored values.
        versions.forEach { assertEquals(ProfileCodec.sha256(it.content), it.contentSha256) }
    }

    @Test fun priorCopyOfAValidV1StaysReadableByTheFrozenV1Reader() = withDatabase { name ->
        val copy = "synthetic-profile-copy-${UUID.randomUUID()}.db"
        try {
            val versions = v1File(name, history)
            path(name).copyTo(path(copy))
            SqliteClinicalProfileRepository(context, name).use { assertEquals(ProfileRead.Loaded(versions), it.readVersions()) }
            assertEquals(2, userVersion(name))
            assertEquals(ProfileRead.Loaded(versions), FrozenV1ProfileReader.read(path(copy).path))
            assertEquals(1, userVersion(copy))
            // The migrated original is not a v1 file any more and the frozen v1 reader refuses it.
            assertEquals(ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), FrozenV1ProfileReader.read(path(name).path))
        } finally {
            context.deleteDatabase(copy)
        }
    }

    @Test fun corruptV1IsNeitherMigratedNorRepairedAndItsCopyStaysRejected() {
        fun corrupt(change: (SQLiteDatabase) -> Unit) = withDatabase { name ->
            val copy = "synthetic-profile-copy-${UUID.randomUUID()}.db"
            try {
                v1File(name, history)
                raw(name) { db ->
                    db.withoutTriggers(ClinicalProfileSchema.SCHEMA_V1, "profile_versions_immutable_update",
                        "profile_segments_immutable_update", change = change)
                }
                val expected = FrozenV1ProfileReader.read(path(name).path)
                assertEquals(ProfileRead.Failed(ProfileFailure.INVALID_RECORD), expected)
                val before = rows(name)
                val schemaBefore = schema(name)
                path(name).copyTo(path(copy))
                repeat(2) {
                    // Every start retries from the same state and fails with the same stable reason.
                    SqliteClinicalProfileRepository(context, name).use {
                        assertEquals(ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD), it.readRecord())
                        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD), it.save(
                            ProfileWrite(5, content(ratio = entered("12")), ProfileOrigin.MANUAL, null), 1, writer))
                    }
                }
                assertEquals(1, userVersion(name))
                assertEquals(before, rows(name))
                assertEquals(schemaBefore, schema(name))
                assertEquals(expected, FrozenV1ProfileReader.read(path(name).path))
                assertEquals(expected, FrozenV1ProfileReader.read(path(copy).path))
            } finally {
                context.deleteDatabase(copy)
            }
        }
        corrupt { it.execSQL("UPDATE profile_segments SET value = '9' WHERE version = 2 AND parameter = 'carb_ratio' AND start_minute = 0") }
        corrupt { it.execSQL("UPDATE profile_segments SET value = '10.0' WHERE version = 5 AND parameter = 'carb_ratio'") }
        corrupt { it.execSQL("UPDATE profile_versions SET content_sha256 = '${"0".repeat(64)}' WHERE version = 3") }
        corrupt { it.execSQL("UPDATE profile_versions SET glucose_unit = 'mmol/L' WHERE version = 2") }
    }

    @Test fun alteredV1DefinitionsAreUnsupportedBeforeReadingRowsAndStayUntouched() {
        fun altered(statements: List<String> = ClinicalProfileSchema.SCHEMA_V1, change: (SQLiteDatabase) -> Unit = {}) =
            withDatabase { name ->
                v1File(name, history.take(2), statements)
                raw(name, change)
                val before = rows(name)
                val schemaBefore = schema(name)
                SqliteClinicalProfileRepository(context, name).use {
                    assertEquals(ProfileRecordRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), it.readRecord())
                    assertEquals(ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), it.readVersions())
                }
                assertEquals(1, userVersion(name))
                assertEquals(before, rows(name))
                assertEquals(schemaBefore, schema(name))
            }
        val v1 = ClinicalProfileSchema.SCHEMA_V1
        // A permissive trigger under the same name keeps the inventory but not the definition.
        altered {
            it.execSQL("DROP TRIGGER profile_versions_immutable_update")
            it.execSQL("CREATE TRIGGER profile_versions_immutable_update BEFORE UPDATE ON profile_versions BEGIN SELECT 1; END")
        }
        // A different CHECK, an extra column and a table without its composite key (no automatic index).
        altered(v1.map { it.replace("value NOT GLOB '*[^0-9.]*'", "1 = 1") })
        altered(v1.map { it.replace("writer TEXT NOT NULL CHECK(length(writer) BETWEEN 1 AND 128),",
            "writer TEXT NOT NULL CHECK(length(writer) BETWEEN 1 AND 128),\n    note TEXT,") })
        altered(v1.map { it.replace(",\n    PRIMARY KEY(version, parameter, start_minute)", "") })
        // Additional objects.
        altered { it.execSQL("CREATE TABLE foreign_table(value TEXT)") }
        altered { it.execSQL("CREATE INDEX extra_index ON profile_segments(value)") }
        altered { it.execSQL("CREATE VIEW extra_view AS SELECT version FROM profile_versions") }
        // A trigger missing from the inventory.
        altered { it.execSQL("DROP TRIGGER profile_segments_latest_only") }
        // A v1 file that already holds v2 objects is not a v1 file.
        altered { db -> ClinicalProfileSchema.V2_ADDITIONS.forEach(db::execSQL) }
    }

    @Test fun alteredV2DefinitionsAreRejectedOnOpenAndFutureVersionsAreUnsupported() {
        fun altered(change: (SQLiteDatabase) -> Unit) = withDatabase { name ->
            SqliteClinicalProfileRepository(context, name).use { it.readRecord() }
            raw(name, change)
            val schemaBefore = schema(name)
            SqliteClinicalProfileRepository(context, name).use {
                assertEquals(ProfileRecordRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA), it.readRecord())
                assertTrue(it.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer) ==
                    ProfileSave.Failed(ProfileFailure.UNSUPPORTED_SCHEMA))
            }
            assertEquals(schemaBefore, schema(name))
        }
        altered {
            it.execSQL("DROP TRIGGER profile_confirmation_single_active")
            it.execSQL("CREATE TRIGGER profile_confirmation_single_active BEFORE INSERT ON profile_confirmation_events BEGIN SELECT 1; END")
        }
        altered { it.execSQL("DROP INDEX profile_confirmation_one_revocation") }
        altered { it.execSQL("CREATE INDEX extra_index ON profile_confirmation_events(kind)") }
        altered { it.version = 3 }
        altered { it.version = 1 }
    }

    @Test fun confirmRevokeAndReconfirmPersistAcrossRestartWithoutTouchingVersions() = withDatabase { name ->
        val request = SqliteClinicalProfileRepository(context, name).use { repository ->
            val profiles = profiles(repository)
            repository.save(ProfileWrite(0, content(ratio = entered("0")), ProfileOrigin.MANUAL, null), 1, writer)
            val versionRows = rows(name)
            val request = profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next())
            val confirmed = profiles.recorded(request)
            assertFalse(confirmed.replayed)
            assertEquals(ConfirmationEvent(1, request.operationId, ConfirmationEventKind.CONFIRM, 1, 1,
                request.contentSha256, null, 0, 7_000, writer), confirmed.event)
            assertEquals(listOf("profile.not_approved_for_calculation"), confirmed.state.detailCodes)
            assertEquals(versionRows, rows(name).filterNot { it.startsWith("profile_confirmation_events:") })
            request
        }
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val profiles = profiles(repository)
            val restarted = profiles.evaluated()
            assertTrue(restarted.confirmation is VersionConfirmation.Active)
            val revoked = profiles.recorded(profiles.revokeRequest(restarted, AndroidOperationIds.next())!!)
            assertTrue((revoked.state as ProfileGateState.Evaluated).confirmation is VersionConfirmation.Revoked)
            // Retrying the original confirmation returns history and never reactivates it.
            val retried = profiles.recorded(request)
            assertTrue(retried.replayed)
            assertFalse(retried.confirmationActiveNow)
            assertEquals(2, repository.record().events.size)
            val again = profiles.recorded(profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next()))
            assertEquals(3L, again.event.seq)
        }
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val events = repository.record().events
            assertEquals(listOf(ConfirmationEventKind.CONFIRM, ConfirmationEventKind.REVOKE, ConfirmationEventKind.CONFIRM),
                events.map { it.kind })
            assertEquals(listOf(0L, 1L, 2L), events.map { it.observedEventSeq })
            assertTrue(events.all { OperationId.isWellFormed(it.operationId.value) })
            assertEquals(VersionConfirmation.Active(events[2]), repository.record().confirmationOf(1))
        }
    }

    @Test fun newVersionsNeverInheritAndStaleOrIncompleteRequestsWriteNothing() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val profiles = profiles(repository)
            repository.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer)
            val reviewing = profiles.evaluated()
            profiles.recorded(profiles.confirmRequest(reviewing, AndroidOperationIds.next()))
            repository.save(ProfileWrite(1, content(ratio = entered("11")), ProfileOrigin.MANUAL, null), 2, writer)
            val before = rows(name)
            assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.CONFIRMATION_STALE_VERSION),
                profiles.record(ConfirmationRequest.Confirm(AndroidOperationIds.next(), 1, reviewing.latest.contentSha256, 1)))
            // Restoring the confirmed content creates version 3 with the same fingerprint, unconfirmed.
            repository.save(ProfileWrite(2, content(), ProfileOrigin.RESTORED, 1), 3, writer)
            val restored = profiles.evaluated()
            assertEquals(VersionConfirmation.Unconfirmed, restored.confirmation)
            assertEquals(listOf("profile.confirmation.missing", "profile.confirmation.superseded"), restored.detailCodes)
            // A unit change from a declared unit is incomplete by construction.
            repository.save(ProfileWrite(3, content().withGlucoseUnit(mmol), ProfileOrigin.MANUAL, null), 4, writer)
            val changed = profiles.evaluated()
            assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.CONFIRMATION_INCOMPLETE),
                profiles.record(profiles.confirmRequest(changed, AndroidOperationIds.next())))
            assertEquals(before.filter { it.startsWith("profile_confirmation_events:") },
                rows(name).filter { it.startsWith("profile_confirmation_events:") })
        }
    }

    @Test fun revocationWorksWhenTheZoneIsNoLongerRecognized() = withDatabase { name ->
        val known = mutableSetOf("Europe/Madrid")
        val zones = TimeZoneRules { it in known }
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val profiles = profiles(repository, zones)
            repository.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer)
            profiles.recorded(profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next()))
            known.clear()
            val e11 = profiles.evaluated()
            assertEquals(listOf("profile.gate.time_zone_unrecognized"), e11.detailCodes)
            val revoked = profiles.recorded(profiles.revokeRequest(e11, AndroidOperationIds.next())!!)
            val e10 = revoked.state as ProfileGateState.Evaluated
            assertFalse(e10.canConfirm)
            assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.CONFIRMATION_INCOMPLETE),
                profiles.record(profiles.confirmRequest(e10, AndroidOperationIds.next())))
            assertEquals(2, repository.record().events.size)
        }
    }

    @Test fun triggersKeepEventsAppendOnlyAndEnforceInsertRules() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val profiles = profiles(repository)
            repository.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer)
            profiles.recorded(profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next()))
            repository.save(ProfileWrite(1, content(ratio = entered("11")), ProfileOrigin.MANUAL, null), 2, writer)
            profiles.recorded(profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next()))
            profiles.recorded(profiles.revokeRequest(profiles.evaluated(), AndroidOperationIds.next())!!)
        }
        val before = rows(name)
        val v1 = ProfileCodec.sha256(content())
        val v2 = ProfileCodec.sha256(content(ratio = entered("11")))
        fun op() = UUID.randomUUID().toString()
        raw(name) { db ->
            db.setForeignKeyConstraintsEnabled(true)
            listOf(
                "UPDATE profile_confirmation_events SET writer = 'x' WHERE seq = 1",
                "DELETE FROM profile_confirmation_events WHERE seq = 3",
                // Not contiguous, or observed state other than seq - 1.
                "INSERT INTO profile_confirmation_events VALUES (5, '${op()}', 'confirm', 1, 2, '$v2', NULL, 4, 1, 'w')",
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'confirm', 1, 2, '$v2', NULL, 2, 1, 'w')",
                // Not the latest version, or not its exact fingerprint.
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'confirm', 1, 1, '$v1', NULL, 3, 1, 'w')",
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'confirm', 1, 2, '$v1', NULL, 3, 1, 'w')",
                // Revoking a revocation, an already revoked confirmation, or a confirmation of another version.
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'revoke', 1, NULL, NULL, 3, 3, 1, 'w')",
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'revoke', 1, NULL, NULL, 2, 3, 1, 'w')",
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'revoke', 1, NULL, NULL, 1, 3, 1, 'w')",
                // Unknown kind, malformed shape, repeated operation identity.
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'approve', 1, 2, '$v2', NULL, 3, 1, 'w')",
                "INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'confirm', 1, 2, '$v2', 1, 3, 1, 'w')",
                "INSERT INTO profile_confirmation_events VALUES (4, 'short', 'confirm', 1, 2, '$v2', NULL, 3, 1, 'w')",
            ).forEach { sql -> assertThrows(sql, android.database.SQLException::class.java) { db.execSQL(sql) } }
            val firstOperation = db.rawQuery("SELECT operation_id FROM profile_confirmation_events WHERE seq = 1", null).use {
                it.moveToFirst(); it.getString(0)
            }
            assertThrows(android.database.SQLException::class.java) {
                db.execSQL("INSERT INTO profile_confirmation_events VALUES (4, '$firstOperation', 'confirm', 1, 2, '$v2', NULL, 3, 1, 'w')")
            }
            // A second active confirmation of the same version is refused even with a valid shape.
            db.execSQL("INSERT INTO profile_confirmation_events VALUES (4, '${op()}', 'confirm', 1, 2, '$v2', NULL, 3, 1, 'w')")
            assertThrows(android.database.SQLException::class.java) {
                db.execSQL("INSERT INTO profile_confirmation_events VALUES (5, '${op()}', 'confirm', 1, 2, '$v2', NULL, 4, 1, 'w')")
            }
        }
        assertEquals(4, rows(name).count { it.startsWith("profile_confirmation_events:") })
        assertEquals(before, rows(name).filterNot { it.startsWith("profile_confirmation_events:1:4|") })
    }

    @Test fun twoConnectionsNeverRecordTwoActiveConfirmations() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use {
            it.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer)
        }
        val first = SqliteClinicalProfileRepository(context, name)
        val second = SqliteClinicalProfileRepository(context, name)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val a = profiles(first)
            val b = profiles(second)
            val requestA = a.confirmRequest(a.evaluated(), AndroidOperationIds.next())
            val requestB = b.confirmRequest(b.evaluated(), AndroidOperationIds.next())
            val futureA = pool.submit<ConfirmationOutcome> { start.await(); a.record(requestA) }
            val futureB = pool.submit<ConfirmationOutcome> { start.await(); b.record(requestB) }
            start.countDown()
            val results = listOf(futureA.get(10, TimeUnit.SECONDS), futureB.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it is ConfirmationOutcome.Recorded })
            val failed = results.filterIsInstance<ConfirmationOutcome.Rejected>().single()
            assertTrue(failed.reason.code, failed.reason in setOf(ProfileFailure.CONFIRMATION_STATE_CHANGED,
                ProfileFailure.CONFIRMATION_ALREADY_ACTIVE, ProfileFailure.SAVE_FAILED))
            assertEquals(1, first.record().events.size)

            // Confirmation against a concurrent revocation: the one that comes later never acts on an unseen state.
            val seenByB = b.evaluated()
            a.recorded(a.revokeRequest(a.evaluated(), AndroidOperationIds.next())!!)
            assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.CONFIRMATION_STATE_CHANGED),
                b.record(ConfirmationRequest.Confirm(AndroidOperationIds.next(), 1, seenByB.latest.contentSha256,
                    seenByB.record.lastEventSeq)))
            // A version saved by the other connection between reading and confirming.
            val reviewing = a.evaluated()
            second.save(ProfileWrite(1, content(ratio = entered("12")), ProfileOrigin.MANUAL, null), 2, writer)
            assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.CONFIRMATION_STALE_VERSION),
                a.record(a.confirmRequest(reviewing, AndroidOperationIds.next())))
            // Lost response: the other connection retries the same intention and gets the committed event.
            val pending = b.confirmRequest(b.evaluated(), AndroidOperationIds.next())
            val committed = a.recorded(pending)
            val retried = b.recorded(pending)
            assertTrue(retried.replayed)
            assertEquals(committed.event, retried.event)
            assertEquals(committed.state, retried.state)
            assertEquals(PendingResolution.Recorded(committed.event, b.readState()), b.resolvePending(pending))
            assertEquals(3, second.record().events.size)
        } finally {
            pool.shutdownNow(); first.close(); second.close()
        }
    }

    @Test fun failedWriteRollsBackAndTheSameIntentionSucceedsAfterwards() = withDatabase { name ->
        SqliteClinicalProfileRepository(context, name).use { repository ->
            val profiles = profiles(repository)
            repository.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer)
            val request = profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next())
            // Injected inside the open connection's transaction, after the policy approved the insert.
            raw(name) {
                it.execSQL("CREATE TRIGGER synthetic_failure AFTER INSERT ON profile_confirmation_events " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            }
            assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.SAVE_FAILED), profiles.record(request))
            assertEquals(emptyList<ConfirmationEvent>(), repository.record().events)
            raw(name) { it.execSQL("DROP TRIGGER synthetic_failure") }
            val recorded = profiles.recorded(request)
            assertFalse(recorded.replayed)
            assertEquals(1L, recorded.event.seq)
        }
    }

    @Test fun retriesAndSavesFailClosedOverATamperedEventHistory() {
        fun tampered(change: (SQLiteDatabase) -> Unit, expected: ProfileFailure) = withDatabase { name ->
            val request = SqliteClinicalProfileRepository(context, name).use { repository ->
                val profiles = profiles(repository)
                repository.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, writer)
                profiles.confirmRequest(profiles.evaluated(), AndroidOperationIds.next()).also { profiles.recorded(it) }
            }
            raw(name) { db ->
                db.withoutTriggers(ClinicalProfileSchema.SCHEMA_V2, "profile_confirmation_immutable_update",
                    "profile_confirmation_immutable_delete", change = change)
            }
            val before = rows(name)
            SqliteClinicalProfileRepository(context, name).use { repository ->
                val profiles = profiles(repository)
                assertEquals(ConfirmationOutcome.Rejected(expected), profiles.record(request))
                assertEquals(ProfileGateState.Unreadable(expected), profiles.readState())
                assertEquals(PendingResolution.Undetermined(ProfileGateState.Unreadable(expected)), profiles.resolvePending(request))
                assertEquals(ProfileRead.Failed(expected), repository.readVersions())
                assertEquals(ProfileSave.Failed(expected),
                    repository.save(ProfileWrite(1, content(ratio = entered("11")), ProfileOrigin.MANUAL, null), 2, writer))
            }
            assertEquals(before, rows(name))
        }
        tampered({ it.execSQL("UPDATE profile_confirmation_events SET content_sha256 = '${"0".repeat(64)}'") },
            ProfileFailure.INVALID_RECORD)
        tampered({ it.execSQL("UPDATE profile_confirmation_events SET operation_id = '${"z".repeat(36)}'") },
            ProfileFailure.INVALID_RECORD)
        tampered({ it.execSQL("UPDATE profile_confirmation_events SET seq = 2, observed_event_seq = 1") },
            ProfileFailure.INVALID_RECORD)
        tampered({ it.execSQL("UPDATE profile_confirmation_events SET recorded_at_ms = 'ayer'") }, ProfileFailure.INVALID_RECORD)
        tampered({ it.execSQL("UPDATE profile_confirmation_events SET contract_version = 2") }, ProfileFailure.UNSUPPORTED_SCHEMA)
    }

    @Test fun mealDatabaseIsUntouchedByCreationAndMigration() = withDatabase { name ->
        val mealName = "synthetic-meals-${UUID.randomUUID()}.db"
        try {
            val meal = SqliteMealRepository(context, mealName).use {
                val saved = (it.save(MealRecord("meal", 0, MealKind.DISH, MealContent(carbs = draftField("0")))) as MealSave.Saved).record
                it.select(saved); saved
            }
            val mealSchema = schema(mealName)
            val mealRows = buildList {
                raw(mealName) { db ->
                    db.rawQuery("SELECT * FROM meal_revisions ORDER BY 1, 2", null).use { c ->
                        while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "NULL" })
                    }
                }
            }
            v1File(name, history)
            SqliteClinicalProfileRepository(context, name).use { assertTrue(it.readRecord() is ProfileRecordRead.Loaded) }
            assertEquals(3, userVersion(mealName))
            assertEquals(mealSchema, schema(mealName))
            SqliteMealRepository(context, mealName).use {
                assertEquals(MealRead.Loaded(listOf(meal)), it.readRevisions("meal"))
                assertEquals(MealSelection.Reviewed(meal, meal), it.readSelection())
            }
            assertEquals(mealRows, buildList {
                raw(mealName) { db ->
                    db.rawQuery("SELECT * FROM meal_revisions ORDER BY 1, 2", null).use { c ->
                        while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "NULL" })
                    }
                }
            })
        } finally {
            context.deleteDatabase(mealName)
        }
    }

    private object VersionlessEmpty

    /** Shape of a successful read of an empty file: no version and no event. */
    private fun ProfileRecordRead.shape(): Any = when (this) {
        is ProfileRecordRead.Loaded -> if (record.history == ProfileHistory.Missing && record.events.isEmpty()) VersionlessEmpty else this
        is ProfileRecordRead.Failed -> this
    }
}

/**
 * The read contract of the released v1 adapter, applied read-only to a closed file for the prior-copy tests of ADR 0014
 * section 8.6: user_version 1, v1 definitions verified, every row decoded and the whole chain validated.
 */
internal object FrozenV1ProfileReader {
    fun read(path: String): ProfileRead {
        return try {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                if (db.version != ClinicalProfileSchema.V1) return ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA)
                ClinicalProfileSchema.verify(db, ClinicalProfileSchema.V1)
                val versions = ClinicalProfileRows.readVersions(db)
                if (versions.isNotEmpty()) ProfileHistory.Loaded(versions.sortedByDescending { it.version })
                ProfileRead.Loaded(versions)
            }
        } catch (_: ClinicalProfileSchema.Mismatch) {
            ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA)
        } catch (_: UnsupportedProfileSchema) {
            ProfileRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA)
        } catch (_: IllegalArgumentException) {
            ProfileRead.Failed(ProfileFailure.INVALID_RECORD)
        } catch (_: ArithmeticException) {
            ProfileRead.Failed(ProfileFailure.INVALID_RECORD)
        }
    }
}
