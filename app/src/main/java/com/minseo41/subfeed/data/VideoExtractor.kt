package com.minseo41.subfeed.data

import com.minseo41.subfeed.model.VideoItem

// 추상화 인터페이스 — NewPipe Extractor 버전이 바뀌어도 이 파일은 변경 없음.
// 구현체는 NewPipeVideoExtractor.kt 하나만 교체하면 됨.
interface VideoExtractor {
    suspend fun getChannelFeed(channelUrl: String): List<VideoItem>
    suspend fun getStreamInfo(videoId: String): StreamInfo
}
