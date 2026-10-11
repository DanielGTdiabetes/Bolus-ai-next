package org.bolusai.next.glucose.dexcom.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationPolicy
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.StoredMessage
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.dexcomg7.StoredReading
import org.bolusai.dexcomg7.TransmitterFingerprint
import org.bolusai.dexcomg7.TransmitterHasher
import java.io.Closeable

/**
 * Device adapter of the Dexcom observation log (ADR 0020). Own database file, append-only by triggers, schema verified
 * by definition on every opening. The policy decides each append inside one exclusive transaction and the result is
 * re-read before commit. Every read decodes strictly and fails closed. No row is ever updated or deleted.
 */
internal class SqliteDexcomObservationStore(
    context: Context,
    name: String? = DATABASE_NAME,
    private val ids: ObservationIds,
    private val hasher: TransmitterHasher,
) : DexcomObservationStore, Closeable {
    private class CorruptRow : RuntimeException("dexcom_observation.corrupt_storage")

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, DexcomObservationSchema.V1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException(ObservationFailure.CORRUPT_STORAGE.code) }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','trigger','index','view') AND " +
                "name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use {
                if (it.moveToFirst()) throw DexcomObservationSchema.Mismatch("foreign objects")
            }
            DexcomObservationSchema.SCHEMA_V1.forEach(db::execSQL)
            DexcomObservationSchema.verify(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw DexcomObservationSchema.Mismatch("upgrade $oldVersion")
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw DexcomObservationSchema.Mismatch("downgrade $oldVersion")
        }

        override fun onOpen(db: SQLiteDatabase) { DexcomObservationSchema.verify(db) }
    }

    override fun record(input: ObservationInput): ObservationWrite = try {
        val db = helper.writableDatabase
        var result: ObservationWrite = ObservationWrite.Failed(ObservationFailure.WRITE_FAILED)
        db.transaction {
            val plan = ObservationPolicy.plan(input, ids, hasher) { transmitter, timestamp ->
                readReadings(db, "transmitter_sha256 = ? AND producer_timestamp = ?",
                    arrayOf(transmitter.sha256Hex, timestamp.toString()))
            }
            insertMessage(db, plan.message)
            plan.readings.forEach { insertReading(db, it) }
            // What is committed is exactly what the policy decided.
            check(readMessages(db, "message_id = ?", arrayOf(plan.message.messageId)) == listOf(plan.message))
            check(readReadings(db, "message_id = ?", arrayOf(plan.message.messageId)) == plan.readings)
            result = ObservationWrite.Recorded(plan.message, plan.readings)
        }
        // Including commit failure: Recorded is visible only after the transaction returned.
        result
    } catch (failure: RuntimeException) {
        ObservationWrite.Failed(reason(failure, ObservationFailure.WRITE_FAILED))
    }

    override fun read(): ObservationRead = try {
        val db = helper.readableDatabase
        var read: ObservationRead = ObservationRead.Failed(ObservationFailure.READ_FAILED)
        db.transaction { read = ObservationRead.Loaded(readMessages(db, null, null), readReadings(db, null, null)) }
        read
    } catch (failure: RuntimeException) {
        ObservationRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    /**
     * Aggregates in SQL inside one read transaction (ADR 0021, F5): the history is never loaded. Only the last message
     * is decoded, strictly. Unknown stored rejection codes are corruption, as in [read].
     */
    override fun summary(): ObservationSummaryRead = try {
        val db = helper.readableDatabase
        var result: ObservationSummaryRead = ObservationSummaryRead.Failed(ObservationFailure.READ_FAILED)
        db.transaction {
            val totals = db.rawQuery("SELECT COUNT(*), TOTAL(outcome = 'observed'), TOTAL(outcome = 'payload_unreadable'), " +
                "TOTAL(duplicate_readings), TOTAL(collapsed_in_message) FROM dexcom_messages", null).use { c ->
                check(c.moveToFirst())
                List(5) { c.getLong(it) }
            }
            val rejected = db.rawQuery("SELECT rejection_code, COUNT(*) FROM dexcom_messages " +
                "WHERE rejection_code IS NOT NULL GROUP BY rejection_code ORDER BY rejection_code", null).use { c ->
                buildMap {
                    while (c.moveToNext()) {
                        val code = c.getString(0)
                        if (DexcomG7RejectionCode.entries.none { it.code == code }) throw CorruptRow()
                        put(code, c.getLong(1).toCount())
                    }
                }
            }
            val readings = db.rawQuery("SELECT COUNT(*), TOTAL(conflict_of IS NOT NULL) FROM dexcom_readings", null).use { c ->
                check(c.moveToFirst())
                List(2) { c.getLong(it) }
            }
            val last = db.query("dexcom_messages", null, null, null, null, null, "seq DESC", "1").use { c ->
                if (c.moveToFirst()) decodeMessage(c) else null
            }
            result = ObservationSummaryRead.Loaded(ObservationSummary(
                messages = totals[0].toCount(),
                observed = totals[1].toCount(),
                unreadable = totals[2].toCount(),
                rejectedByCode = rejected,
                storedReadings = readings[0].toCount(),
                duplicates = totals[3].toCount(),
                collapsedInMessage = totals[4].toCount(),
                conflicts = readings[1].toCount(),
                lastReceivedAtEpochMs = last?.receivedAtEpochMs,
                lastSender = last?.sender,
            ))
        }
        result
    } catch (failure: RuntimeException) {
        ObservationSummaryRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    private fun Long.toCount(): Int = if (this < 0 || this > Int.MAX_VALUE) throw CorruptRow() else toInt()

    override fun close() = helper.close()

    private fun reason(failure: RuntimeException, fallback: ObservationFailure): ObservationFailure = when (failure) {
        is DexcomObservationSchema.Mismatch -> ObservationFailure.UNSUPPORTED_SCHEMA
        is CorruptRow, is SQLiteDatabaseCorruptException -> ObservationFailure.CORRUPT_STORAGE
        else -> fallback
    }

    private fun insertMessage(db: SQLiteDatabase, message: StoredMessage) {
        val values = ContentValues().apply {
            put("message_id", message.messageId)
            put("received_at_ms", message.receivedAtEpochMs)
            put("sender_package", message.sender.packageName)
            put("sender_version_code", message.sender.versionCode)
            put("sender_signers_sha256", message.sender.signerCertificateSha256.joinToString(","))
            put("outcome", message.outcome.code)
            put("contract", message.contract)
            put("rejection_code", message.rejection?.code?.code)
            put("rejection_field", message.rejection?.field)
            put("rejection_index", message.rejection?.index)
            put("new_readings", message.newReadings)
            put("duplicate_readings", message.duplicateReadings)
            put("conflicting_readings", message.conflictingReadings)
            put("collapsed_in_message", message.collapsedInMessage)
            put("unknown_top_level", message.unknownTopLevelFields.joinToString(","))
            put("unknown_reading", message.unknownReadingFields.joinToString(","))
            put("unreportable_unknown", message.unreportableUnknownFieldCount)
        }
        db.insertOrThrow("dexcom_messages", null, values)
    }

    private fun insertReading(db: SQLiteDatabase, reading: StoredReading) {
        val values = ContentValues().apply {
            put("observation_id", reading.observationId)
            put("message_id", reading.messageId)
            put("idx", reading.index)
            put("transmitter_sha256", reading.transmitter.sha256Hex)
            put("producer_timestamp", reading.producerTimestamp)
            put("producer_value", reading.producerGlucoseValue)
            put("producer_trend", reading.producerTrendArrow)
            put("egv_tx_time", reading.egvTxTime)
            put("conflict_of", reading.conflictOf)
            put("status", reading.status.code.removePrefix("dexcom_g7.reading."))
        }
        db.insertOrThrow("dexcom_readings", null, values)
    }

    private fun readMessages(db: SQLiteDatabase, where: String?, args: Array<String>?): List<StoredMessage> =
        db.query("dexcom_messages", null, where, args, null, null, "seq").use { cursor ->
            buildList { while (cursor.moveToNext()) add(decodeMessage(cursor)) }
        }

    private fun readReadings(db: SQLiteDatabase, where: String?, args: Array<String>?): List<StoredReading> =
        db.query("dexcom_readings", null, where, args, null, null, "seq").use { cursor ->
            buildList { while (cursor.moveToNext()) add(decodeReading(cursor)) }
        }

    private fun decodeMessage(c: Cursor): StoredMessage = strict {
        val outcome = StoredMessageOutcome.entries.single { it.code == c.text("outcome") }
        val rejection = c.textOrNull("rejection_code")?.let { code ->
            DexcomG7PayloadRejection(
                DexcomG7RejectionCode.entries.single { it.code == code },
                c.textOrNull("rejection_field"),
                c.intOrNull("rejection_index"),
            )
        }
        StoredMessage(
            messageId = c.text("message_id"),
            receivedAtEpochMs = c.long("received_at_ms"),
            sender = AuthenticatedSender(c.text("sender_package"), c.long("sender_version_code"),
                c.text("sender_signers_sha256").split(",").toSet().also {
                    if (it.joinToString(",") != c.text("sender_signers_sha256")) throw CorruptRow()
                }),
            outcome = outcome,
            rejection = rejection,
            newReadings = c.int("new_readings"),
            duplicateReadings = c.int("duplicate_readings"),
            conflictingReadings = c.int("conflicting_readings"),
            collapsedInMessage = c.int("collapsed_in_message"),
            unknownTopLevelFields = names(c.text("unknown_top_level")),
            unknownReadingFields = names(c.text("unknown_reading")),
            unreportableUnknownFieldCount = c.int("unreportable_unknown"),
        ).also { if (it.contract != c.text("contract")) throw CorruptRow() }
    }

    private fun decodeReading(c: Cursor): StoredReading = strict {
        StoredReading(
            observationId = c.text("observation_id"),
            messageId = c.text("message_id"),
            index = c.int("idx"),
            transmitter = TransmitterFingerprint(c.text("transmitter_sha256")),
            producerTimestamp = c.long("producer_timestamp"),
            producerGlucoseValue = c.int("producer_value"),
            producerTrendArrow = c.text("producer_trend"),
            egvTxTime = c.long("egv_tx_time"),
            conflictOf = c.textOrNull("conflict_of"),
        ).also { if (c.text("status") != "not_approved_for_use") throw CorruptRow() }
    }

    private fun names(stored: String): List<String> = if (stored.isEmpty()) emptyList() else stored.split(",")

    /** Any decoding or validation failure of a stored row is corruption, never a default. */
    private inline fun <T> strict(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw CorruptRow()
    } catch (_: NoSuchElementException) {
        throw CorruptRow()
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.text(name: String): String {
        val i = column(name)
        if (getType(i) != Cursor.FIELD_TYPE_STRING) throw CorruptRow()
        return getString(i)
    }

    private fun Cursor.textOrNull(name: String): String? = if (isNull(column(name))) null else text(name)

    private fun Cursor.long(name: String): Long {
        val i = column(name)
        if (getType(i) != Cursor.FIELD_TYPE_INTEGER) throw CorruptRow()
        return getLong(i)
    }

    private fun Cursor.int(name: String): Int = long(name).let {
        if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw CorruptRow() else it.toInt()
    }

    private fun Cursor.intOrNull(name: String): Int? = if (isNull(column(name))) null else int(name)

    companion object {
        const val DATABASE_NAME = "dexcom-observations.db"
    }
}
