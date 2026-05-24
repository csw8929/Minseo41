package com.minseo41.subfeed.data.potoken

class PoTokenException(message: String) : Exception(message)

// system WebView 구현이 깨졌을 때 (예: 너무 오래된 WebView 버전이라 ES 신문법 미지원)
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BadWebViewException(error) else PoTokenException(error)
