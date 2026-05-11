package com.minseo41.subfeed.data.refresh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshLogEntry(
    val timestampMs: Long,
    val channelCount: Int,
    val successCount: Int,
)

@Singleton
class RefreshPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
        set(value) {
            prefs.edit().putInt(KEY_INTERVAL_HOURS, value).apply()
        }

    fun saveLog(entry: RefreshLogEntry) {
        val existing = getLogs().toMutableList()
        existing.add(0, entry)
        val arr = JSONArray()
        existing.take(MAX_LOG_ENTRIES).forEach { e ->
            arr.put(JSONObject().apply {
                put("ts", e.timestampMs)
                put("ch", e.channelCount)
                put("ok", e.successCount)
            })
        }
        prefs.edit().putString(KEY_REFRESH_LOGS, arr.toString()).apply()
    }

    fun getLogs(): List<RefreshLogEntry> {
        val str = prefs.getString(KEY_REFRESH_LOGS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(str)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RefreshLogEntry(obj.getLong("ts"), obj.getInt("ch"), obj.getInt("ok"))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val NAME = "refresh"
        const val KEY_INTERVAL_HOURS = "intervalHours"
        const val DEFAULT_INTERVAL_HOURS = 6
        val INTERVAL_OPTIONS: List<Int> = listOf(0, 1, 3, 6, 12)
        private const val KEY_REFRESH_LOGS = "refreshLogs"
        private const val MAX_LOG_ENTRIES = 20
    }
}
