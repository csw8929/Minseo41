package com.minseo41.subfeed.data.refresh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

    companion object {
        const val NAME = "refresh"
        const val KEY_INTERVAL_HOURS = "intervalHours"
        const val DEFAULT_INTERVAL_HOURS = 6
        val INTERVAL_OPTIONS: List<Int> = listOf(1, 3, 6, 12)
    }
}
