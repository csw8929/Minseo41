package com.minseo41.subfeed.data.potoken

// WEB 클라이언트 PoToken 결과 — InnerTube /player 요청과 stream URL 양쪽에 필요.
data class PoTokenResult(
    val visitorData: String,
    val playerRequestPoToken: String,  // InnerTube /player body의 serviceIntegrityDimensions.poToken
    val streamingDataPoToken: String,  // googlevideo.com stream URL 의 &pot= 파라미터
)
