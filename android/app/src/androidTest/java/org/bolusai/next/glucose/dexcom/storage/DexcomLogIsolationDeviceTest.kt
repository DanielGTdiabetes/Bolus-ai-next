package org.bolusai.next.glucose.dexcom.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Regressions of the 2026-10-11 review of ADR 0021. The Diagnóstico reader never creates, initialises or rewrites a log
 * that is not a v1 log, and during instrumentation nothing may open or inspect the app's own dexcom-observations.db.
 * Every case runs on an isolated context whose database directory is a fresh folder in the test cache: the "real" log
 * here is that folder's dexcom-observations.db, never the file of the installed app.
 */
@RunWith(AndroidJUnit4::class)
class DexcomLogIsolationDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(target.cacheDir, "dexcom-guard-${UUID.randomUUID()}").apply { check(mkdirs()) }

    /** Same context for the guard and for SQLite: its application context is itself and its databases live in [folder]. */
    private val isolated = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(folder, name)
    }
    private val realHere = File(folder, SqliteDexcomObservationStore.DATABASE_NAME)
    private val name = "synthetic-dexcom-log.db"
    private val synthetic = File(folder, name)
    private var n = 0
    private val ids = ObservationIds { "id-${++n}" }
    private val sender = AuthenticatedSender("example.sender", 42, setOf("a".repeat(64)))

    @After fun clear() { folder.deleteRecursively() }

    private fun read() = readDexcomObservationLog(isolated, name)

    private fun writer() = SqliteDexcomObservationStore(isolated, name, ids, AndroidTransmitterHasher)

    /** The rejected file is neither deleted nor rewritten: it keeps exactly its bytes. */
    private fun assertUntouched(before: ByteArray) {
        assertTrue(synthetic.exists())
        assertArrayEquals(before, synthetic.readBytes())
    }

    @Test fun theRunnerActivatesTheGuardBeforeAnyTest() {
        assertTrue(DexcomStorageGuard.active)
    }

    @Test fun theRealLogIsRejectedBeforeItIsOpenedOrLookedAt() {
        val content = "not even read".toByteArray()
        realHere.writeBytes(content)
        for (reader in listOf<() -> Any>(
            { readDexcomObservationLog(isolated) },
            { readDexcomObservationLog(isolated, realHere.absolutePath) },
            { SqliteDexcomObservationStore(isolated, SqliteDexcomObservationStore.DATABASE_NAME, ids, AndroidTransmitterHasher) },
            { SqliteDexcomObservationStore(isolated, "${folder.absolutePath}/./${SqliteDexcomObservationStore.DATABASE_NAME}", ids,
                AndroidTransmitterHasher) },
        )) {
            val failure = assertThrows(IllegalStateException::class.java) { reader() }
            assertEquals(DexcomStorageGuard.REAL_DATABASE_DENIED, failure.message)
        }
        assertArrayEquals(content, realHere.readBytes())
        assertEquals(listOf(realHere.name), folder.list().orEmpty().toList())
    }

    @Test fun aMissingLogReadsAsEmptyWithoutBeingCreated() {
        assertEquals(ObservationSummaryRead.Loaded(ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, 0, null, null)), read())
        assertEquals(emptyList<String>(), folder.list().orEmpty().toList())
    }

    @Test fun aZeroByteFileIsRejectedAndNotInitialised() {
        check(synthetic.createNewFile())
        assertEquals(ObservationSummaryRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), read())
        assertUntouched(ByteArray(0))
    }

    @Test fun anEmptyVersionZeroDatabaseIsRejectedAndNotInitialised() {
        // What a writer leaves when it dies during its first onCreate: android_metadata only, user_version 0.
        SQLiteDatabase.openOrCreateDatabase(synthetic, null).use { assertEquals(0, it.version) }
        val before = synthetic.readBytes()
        assertEquals(ObservationSummaryRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), read())
        assertUntouched(before)
        SQLiteDatabase.openDatabase(synthetic.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(0, db.version)
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'dexcom_%'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun anUnsupportedSchemaOrVersionIsRejectedWithoutTouchingTheFile() {
        writer().use { it.record(ObservationInput.PayloadUnreadable(1, sender)) }
        SQLiteDatabase.openDatabase(synthetic.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("CREATE TABLE synthetic_extra (x INTEGER)") }
        val altered = synthetic.readBytes()
        assertEquals(ObservationSummaryRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), read())
        assertUntouched(altered)

        synthetic.delete()
        writer().use { it.record(ObservationInput.PayloadUnreadable(1, sender)) }
        SQLiteDatabase.openDatabase(synthetic.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        val newer = synthetic.readBytes()
        assertEquals(ObservationSummaryRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), read())
        assertUntouched(newer)
    }

    @Test fun aFileThatIsNotSqliteIsCorruptionAndIsKept() {
        // The platform default error handler would delete it; the log never uses that handler.
        val garbage = ByteArray(4096) { (it * 31).toByte() }
        synthetic.writeBytes(garbage)
        assertEquals(ObservationSummaryRead.Failed(ObservationFailure.CORRUPT_STORAGE), read())
        assertUntouched(garbage)
    }

    @Test fun aValidLogIsCountedLikeTheReference() {
        writer().use { s ->
            s.record(ObservationInput.PayloadUnreadable(1, sender))
            s.record(ObservationInput.PayloadUnreadable(2, sender))
        }
        val reference = writer().use { ObservationSummary.of(it.read() as ObservationRead.Loaded) }
        assertEquals(ObservationSummaryRead.Loaded(reference), read())
        assertEquals(2, reference.messages)
        assertFalse(realHere.exists())
    }
}
