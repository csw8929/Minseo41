package com.minseo41.subfeed

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.minseo41.subfeed.data.db.VideoDao
import com.minseo41.subfeed.data.refresh.RefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SubFeedApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var refreshScheduler: RefreshScheduler
    @Inject lateinit var videoDao: VideoDao

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        refreshScheduler.schedulePeriodic(replaceExisting = false)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (videoDao.count() == 0) {
                refreshScheduler.triggerNow()
            }
        }
    }
}
