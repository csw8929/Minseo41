package com.minseo41.subfeed.data

import com.minseo41.subfeed.data.newpipe.NewPipeStreamFetcher
import com.minseo41.subfeed.data.rss.YoutubeRssParser
import com.minseo41.subfeed.model.VideoItem
import javax.inject.Inject
import javax.inject.Singleton

// VideoExtractor 구현체 — thin orchestrator.
// - 채널 피드: YoutubeRssParser (NewPipe 무관, RSS 직접 파싱)
// - 스트림 추출: NewPipeStreamFetcher (NewPipe Extractor 위임)
//
// NewPipe 의존 코드는 data/newpipe/ 패키지에 격리되어 있다. 업데이트 시 그 폴더만 확인하면 됨.
// API contract 는 data/newpipe/README.md 참고.
@Singleton
class NewPipeVideoExtractor @Inject constructor(
    private val rssParser: YoutubeRssParser,
    private val streamFetcher: NewPipeStreamFetcher,
) : VideoExtractor {

    override suspend fun getChannelFeed(channelUrl: String): List<VideoItem> =
        rssParser.fetch(channelUrl)

    override suspend fun getStreamInfo(videoId: String): StreamInfo =
        streamFetcher.fetch(videoId)
}
