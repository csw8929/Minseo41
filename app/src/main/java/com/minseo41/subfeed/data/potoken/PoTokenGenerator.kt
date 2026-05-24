package com.minseo41.subfeed.data.potoken

import java.io.Closeable

// PoToken 생성기 추상화. WebView 외에 향후 다른 구현 가능성을 위해 인터페이스로 둠.
interface PoTokenGenerator : Closeable {
    // 주어진 identifier(visitorData 혹은 videoId)로 PoToken 발급. 여러 번 호출 가능.
    suspend fun generatePoToken(identifier: String): String

    // integrityToken 만료 여부. true 면 새 generator 인스턴스를 만들어야 함.
    fun isExpired(): Boolean
}
