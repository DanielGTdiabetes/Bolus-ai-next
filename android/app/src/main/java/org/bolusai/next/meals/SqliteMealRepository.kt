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
internal class SqliteMealRepository(context: Context, name: String? = "meal-drafts.db") : MealRepository, Closeable {
    private class UnsupportedSchema : RuntimeException()
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("meal.storage.corrupt") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
    }

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
