package com.freedar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

data class Place(
    val id: Long, val name: String, val region: String, val regionCode: String,
    val country: String, val countryCode: String, val position: LocalPosition,
)

/** Offline-only gazetteer. Construct/query/close on a worker thread, never the UI thread. */
class PlaceSearch(context: Context) : Closeable {
    companion object { const val RESULT_LIMIT = 50 }

    private val database: SQLiteDatabase

    init {
        // Immutable app data, deliberately outside both imagery caches and Android backup.
        val directory = File(context.noBackupFilesDir, "place-search").apply { mkdirs() }
        val file = File(directory, "places-v1.sqlite")
        synchronized(PlaceSearch::class.java) {
            if (!file.exists()) {
                val temporary = File(directory, "places-v1.tmp")
                try {
                    GZIPInputStream(context.assets.open("places-v1.sqlite.gzip"), 65536).use { input ->
                        temporary.outputStream().use { input.copyTo(it) }
                    }
                    check(temporary.renameTo(file)) { "Cannot install place database" }
                } finally { temporary.delete() }
            }
        }
        database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun search(query: String): List<Place> {
        val key = normalize(query.take(200))
        if (key.isEmpty()) return emptyList()
        // Binary range uses the names primary-key index, including non-Latin scripts.
        // Exact aliases rank first; population then stable ID break ties. One result per place.
        return database.rawQuery("""
            SELECT p.id, p.name, p.region, p.region_code, p.country, p.country_code,
                   p.latitude, p.longitude
            FROM (SELECT place, MAX(name = ?) AS exact FROM names
                  WHERE name >= ? AND name < ? GROUP BY place) n
            JOIN places p ON p.id = n.place
            ORDER BY n.exact DESC, p.population DESC, p.id
            LIMIT $RESULT_LIMIT
        """.trimIndent(), arrayOf(key, key, key + "\uFFFF")).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Place(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5),
                    LocalPosition(cursor.getDouble(6), cursor.getDouble(7))))
            }
        }
    }

    override fun close() = database.close()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
