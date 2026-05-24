package com.minseo41.subfeed.data.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.minseo41.subfeed.data.OkHttpDownloader
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// NewPipe 의 PoTokenWebView 를 Kotlin Coroutines 로 포팅한 버전.
// BotGuard JavaScript VM 을 WebView 에서 실행해 YouTube PoToken 을 발급한다.
//
// 사용 흐름:
//   val gen = PoTokenWebView.create(context)   // 1회 초기화 (BotGuard 다운로드 + integrityToken 발급)
//   val tok = gen.generatePoToken(identifier)  // identifier 마다 PoToken 발급 (반복 호출 가능)
//   gen.close()                                 // 만료 시 새로 만들어 교체
//
// 모든 메서드는 안전하게 코루틴에서 호출 가능 (내부적으로 main thread 로 dispatch).
class PoTokenWebView private constructor(
    context: Context,
    private val initDeferred: CompletableDeferred<PoTokenWebView>,
) : PoTokenGenerator {

    private val webView = WebView(context)
    private val poTokenDeferreds = mutableMapOf<String, CompletableDeferred<String>>()
    private lateinit var expirationInstant: Instant

    init {
        with(webView.settings) {
            @Suppress("SetJavaScriptEnabled") // BotGuard 실행에 필수
            javaScriptEnabled = true
            userAgentString = USER_AGENT
            blockNetworkLoads = true // BotGuard 응답은 OkHttp 로 받아서 evaluateJavascript 로 주입 — WebView 자체 네트워크 차단 OK
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    Log.e(TAG, "WebView console uncaught: $fmt")
                    val exception = BadWebViewException(fmt)
                    failInit(exception)
                    popAll().forEach { it.completeExceptionally(exception) }
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    // -------- 초기화 단계 --------

    private fun loadHtmlAndObtainBotguard(context: Context) {
        Log.d(TAG, "loadHtmlAndObtainBotguard()")
        val html = try {
            context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            failInit(t); return
        }
        webView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.replaceFirst(
                "</script>",
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Log.d(TAG, "downloadAndRunBotguard()")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            val parsed = try {
                parseChallengeData(responseBody)
            } catch (t: Throwable) {
                Log.e(TAG, "parseChallengeData failed — body head=${responseBody.take(300)}", t)
                failInit(PoTokenException("Create endpoint bad response: ${responseBody.take(200)}"))
                return@makeBotguardServiceRequest
            }
            webView.evaluateJavascript(
                """try {
                    data = $parsed
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null,
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, "JS init error: $error")
        failInit(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        Log.d(TAG, "botguardResponse received (len=${botguardResponse.length})")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { responseBody ->
            val (integrityToken, expirationSec) = try {
                parseIntegrityTokenData(responseBody)
            } catch (t: Throwable) {
                Log.e(TAG, "parseIntegrityTokenData failed — body head=${responseBody.take(300)}", t)
                failInit(PoTokenException("GenerateIT bad response: ${responseBody.take(200)}"))
                return@makeBotguardServiceRequest
            }
            // 10분 마진
            expirationInstant = Instant.now().plusSeconds(expirationSec - 600)
            webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                Log.d(TAG, "init done, expirationSec=$expirationSec")
                initDeferred.complete(this)
            }
        }
    }

    // -------- PoToken 발급 --------

    override suspend fun generatePoToken(identifier: String): String = suspendCoroutine { cont ->
        Log.d(TAG, "generatePoToken($identifier)")
        val deferred = CompletableDeferred<String>()
        synchronized(poTokenDeferreds) { poTokenDeferreds[identifier] = deferred }
        deferred.invokeOnCompletion { err ->
            if (err != null) cont.resumeWithException(err)
            else cont.resume(deferred.getCompleted())
        }
        runOnMainThread {
            val u8Identifier = stringToU8(identifier)
            webView.evaluateJavascript(
                """try {
                    identifier = "$identifier"
                    u8Identifier = $u8Identifier
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""",
                null,
            )
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.e(TAG, "obtainPoToken error id=$identifier: $error")
        pop(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            pop(identifier)?.completeExceptionally(t); return
        }
        Log.d(TAG, "poToken minted id=$identifier (len=${poToken.length})")
        pop(identifier)?.complete(poToken)
    }

    override fun isExpired(): Boolean =
        ::expirationInstant.isInitialized && Instant.now().isAfter(expirationInstant)

    // -------- Deferred 관리 --------

    private fun pop(identifier: String): CompletableDeferred<String>? =
        synchronized(poTokenDeferreds) { poTokenDeferreds.remove(identifier) }

    private fun popAll(): List<CompletableDeferred<String>> =
        synchronized(poTokenDeferreds) { poTokenDeferreds.values.toList().also { poTokenDeferreds.clear() } }

    // -------- 유틸 --------

    // Create / GenerateIT 엔드포인트 호출. WebView 의 blockNetworkLoads=true 이므로 OkHttp 로 우회.
    // Content-Type 은 반드시 application/json+protobuf 여야 함 (gRPC-Web JSON). application/json 이면 400.
    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        onSuccess: (String) -> Unit,
    ) {
        Thread {
            try {
                val body = OkHttpDownloader.post(
                    url = url,
                    body = data,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                        "x-goog-api-key" to GOOGLE_API_KEY,
                        "x-user-agent" to "grpc-web-javascript/0.1",
                    ),
                    contentType = "application/json+protobuf",
                )
                runOnMainThread {
                    try {
                        onSuccess(body)
                    } catch (t: Throwable) {
                        Log.e(TAG, "onSuccess threw — body head=${body.take(300)}", t)
                        failInit(t)
                    }
                }
            } catch (t: Throwable) {
                runOnMainThread { failInit(t) }
            }
        }.start()
    }

    private fun failInit(error: Throwable) {
        runOnMainThread {
            closeInternal()
            if (!initDeferred.isCompleted) initDeferred.completeExceptionally(error)
        }
    }

    override fun close() {
        runOnMainThread { closeInternal() }
    }

    private fun closeInternal() {
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private val TAG = PoTokenWebView::class.simpleName
        // BotGuard 가 사용하는 공개 API 키 (요청에서 추출)
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val JS_INTERFACE = "PoTokenWebView"

        // WebView 인스턴스 생성 + 초기화 완료 await. main thread 에서 WebView 생성 필수.
        suspend fun create(context: Context): PoTokenWebView = withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<PoTokenWebView>()
            val instance = PoTokenWebView(context, deferred)
            instance.loadHtmlAndObtainBotguard(context)
            deferred.await()
        }

        private fun runOnMainThread(block: () -> Unit) {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
}
