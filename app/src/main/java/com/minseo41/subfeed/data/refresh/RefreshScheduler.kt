package com.minseo41.subfeed.data.refresh

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val refreshPrefs: RefreshPrefs,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    fun schedulePeriodic(replaceExisting: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<RefreshFeedWorker>(
            refreshPrefs.intervalHours.toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        val policy = if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE
            else ExistingPeriodicWorkPolicy.KEEP
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, policy, request)
    }

    fun triggerNow() {
        val request = OneTimeWorkRequestBuilder<RefreshFeedWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_WORK_NAME = "subfeed_refresh_periodic"
        const val MANUAL_WORK_NAME = "subfeed_refresh_manual"
    }
}
