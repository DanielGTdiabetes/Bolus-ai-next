package org.bolusai.next.meals

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.bolusai.meals.*
import java.io.Closeable

/** Device adapter for the shared draft port. Never deletes or replaces an existing revision. */
internal class SqliteMealRepository(context: Context, name: String? = "meal-drafts.db") :
    MealRepository, MealSelectionRepository, MealHistoryRepository, Closeable {
    private class UnsupportedSchema : RuntimeException()
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 2,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("meal.storage.corrupt") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use {
                if (it.moveToFirst()) throw UnsupportedSchema()
            }
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
            createSelectionTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) throw UnsupportedSchema()
            // Validate the known v1 shape before adding review state; no revisions are rewritten.
            db.rawQuery("SELECT id, revision, schema_version, kind, basis, name, carbs, fat, protein, fiber, notes, source_id, source_revision FROM meal_revisions", null).use {
                while (it.moveToNext()) decode(it)
            }
            createSelectionTable(db)
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
    }

    private fun createSelectionTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE meal_selection (
                slot INTEGER PRIMARY KEY CHECK(slot = 1),
                id TEXT NOT NULL, revision INTEGER NOT NULL,
                FOREIGN KEY(id, revision) REFERENCES meal_revisions(id, revision)
            )
        """.trimIndent())
    }

    override fun readSelection(): MealSelection = try {
        val db = helper.readableDatabase
        var result: MealSelection = MealSelection.Missing
        db.transaction {
            db.rawQuery("SELECT id, revision FROM meal_selection WHERE slot = 1", null).use { selection ->
                if (selection.moveToFirst()) {
                    val id = selection.getString(0)
                    val snapshot = db.rawQuery("SELECT * FROM meal_revisions WHERE id = ? AND revision = ?",
                        arrayOf(id, selection.getLong(1).toString())).use {
                        require(it.moveToFirst()); decode(it)
                    }
                    result = MealSelection.Reviewed(snapshot, requireNotNull(latest(db, id)))
                }
            }
        }
        result
    } catch (failure: RuntimeException) {
        MealSelection.Failed(reason(failure, MealFailure.READ_FAILED))
    }

    override fun select(record: MealRecord): MealSelection = try {
        val db = helper.writableDatabase
        var result: MealSelection = MealSelection.Failed(MealFailure.SAVE_FAILED)
        db.transaction {
            val current = latest(db, record.id)
            result = when {
                record.revision <= 0 -> MealSelection.Failed(MealFailure.INVALID_RECORD)
                current != record -> MealSelection.Failed(MealFailure.CONFLICT)
                else -> {
                    val values = ContentValues().apply {
                        put("slot", 1); put("id", record.id); put("revision", record.revision)
                    }
                    if (db.update("meal_selection", values, "slot = 1", null) == 0) {
                        db.insertOrThrow("meal_selection", null, values)
                    }
                    MealSelection.Reviewed(record, current)
                }
            }
        }
        result
    } catch (failure: RuntimeException) {
        MealSelection.Failed(reason(failure, MealFailure.SAVE_FAILED))
    }

    override fun clearSelection(): MealSelection = try {
        val db = helper.writableDatabase
        db.transaction {
            db.delete("meal_selection", "slot = 1", null)
        }
        // Missing is reported only after commit; meal revisions are never touched.
        MealSelection.Missing
    } catch (failure: RuntimeException) {
        MealSelection.Failed(reason(failure, MealFailure.SAVE_FAILED))
    }

    private fun latest(db: SQLiteDatabase, id: String): MealRecord? =
        db.rawQuery("SELECT * FROM meal_revisions WHERE id = ? ORDER BY revision DESC LIMIT 1",
            arrayOf(id)).use { if (it.moveToFirst()) decode(it) else null }

    override fun read(kind: MealKind): MealRead = try {
        val records = helper.readableDatabase.rawQuery("""
            SELECT * FROM meal_revisions m WHERE kind = ? AND revision =
                (SELECT MAX(revision) FROM meal_revisions WHERE id = m.id) ORDER BY id
        """.trimIndent(), arrayOf(kind.name)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor)) }
        }
        MealRead.Loaded(records)
    } catch (failure: RuntimeException) {
        MealRead.Failed(reason(failure, MealFailure.READ_FAILED))
    }

    override fun readRevisions(id: String): MealRead = try {
        // One statement is one consistent SQLite read; rows are decoded and never rewritten.
        val records = helper.readableDatabase.rawQuery(
            "SELECT * FROM meal_revisions WHERE id = ? ORDER BY revision", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor)) }
        }
        MealRead.Loaded(records)
    } catch (failure: RuntimeException) {
        MealRead.Failed(reason(failure, MealFailure.READ_FAILED))
    }

    override fun save(editor: MealRecord): MealSave = try {
        val db = helper.writableDatabase
        var result: MealSave = MealSave.Failed(MealFailure.SAVE_FAILED)
        db.transaction {
            val previous = db.rawQuery("SELECT * FROM meal_revisions WHERE id = ? ORDER BY revision DESC LIMIT 1",
                arrayOf(editor.id)).use { if (it.moveToFirst()) decode(it) else null }
            val next = editor.copy(revision = Math.addExact(editor.revision, 1))
            result = when {
                previous == next -> MealSave.Saved(previous)
                (previous?.revision ?: 0) != editor.revision -> MealSave.Failed(MealFailure.CONFLICT)
                previous != null && (previous.kind != editor.kind || previous.copiedFrom != editor.copiedFrom) ->
                    MealSave.Failed(MealFailure.INVALID_RECORD)
                else -> {
                    db.insertOrThrow("meal_revisions", null, encode(next))
                    MealSave.Saved(next)
                }
            }
        }
        // Including commit failure: the caller sees Saved only after transaction returns.
        result
    } catch (failure: RuntimeException) {
        MealSave.Failed(reason(failure, MealFailure.SAVE_FAILED))
    }

    private fun reason(failure: RuntimeException, fallback: MealFailure): MealFailure = when (failure) {
        is UnsupportedSchema -> MealFailure.UNSUPPORTED_SCHEMA
        is SQLiteDatabaseCorruptException -> MealFailure.CORRUPT_STORAGE
        is IllegalArgumentException, is ArithmeticException -> MealFailure.INVALID_RECORD
        is SQLiteException -> fallback
        else -> fallback
    }

    private fun encode(record: MealRecord) = ContentValues().apply {
        put("id", record.id)
        put("revision", record.revision)
        put("schema_version", record.schemaVersion)
        put("kind", record.kind.name)
        put("basis", record.content.basis.name)
        fieldNames.zip(record.content.fields).forEach { (key, field) ->
            when (field) {
                DraftField.Missing -> putNull(key)
                is DraftField.Entered -> put(key, field.text)
            }
        }
        record.copiedFrom?.let { put("source_id", it.id); put("source_revision", it.revision) }
    }

    private fun decode(cursor: Cursor): MealRecord {
        fun value(key: String): String? = cursor.getColumnIndexOrThrow(key).let {
            if (cursor.isNull(it)) null else cursor.getString(it)
        }
        fun field(key: String): DraftField = value(key)?.let { DraftField.Entered(it) } ?: DraftField.Missing
        val sourceId = value("source_id")
        val sourceRevision = value("source_revision")
        require((sourceId == null) == (sourceRevision == null))
        val revision = value("revision")!!.toLong()
        require(revision > 0)
        if (value("schema_version") != "1") throw UnsupportedSchema()
        return MealRecord(value("id")!!, revision, MealKind.valueOf(value("kind")!!),
            MealContent(field("name"), field("carbs"), field("fat"), field("protein"), field("fiber"),
                field("notes"), NutritionBasis.valueOf(value("basis")!!)),
            sourceId?.let { DishReference(it, sourceRevision!!.toLong()) })
    }

    override fun close() = helper.close()

    private companion object {
        val fieldNames = listOf("name", "carbs", "fat", "protein", "fiber", "notes")
    }
}
