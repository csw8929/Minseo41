package com.minseo41.subfeed

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.minseo41.subfeed.data.db.VideoDao
import com.minseo41.subfeed.data.newpipe.SubFeedDownloader
import com.minseo41.subfeed.data.refresh.RefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
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
        // NewPipeExtractor 초기화 — Downloader 등록.
        // 스트림 추출은 v0.26.3+ visionOS 클라이언트(PoToken 불요)에 의존하므로 PoTokenProvider 미주입.
        // Localization=ko/KR 로 자막/제목 기본값 한국어.
        NewPipe.init(
            SubFeedDownloader,
            Localization("ko", "KR"),
            ContentCountry("KR"),
        )

        refreshScheduler.schedulePeriodic(replaceExisting = false)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (videoDao.count() == 0) {
                refreshScheduler.triggerNow()
            }
        }
    }
}
