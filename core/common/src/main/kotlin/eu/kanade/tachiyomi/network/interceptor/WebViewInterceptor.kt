package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.DelicateCoroutinesApi
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    private val initWebView by lazy {
        if (DeviceUtil.isMiui || (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && DeviceUtil.isSamsung)) {
            return@lazy
        }
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
        }
    }

    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    @OptIn(DelicateCoroutinesApi::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!shouldIntercept(response)) {
            return response
        }

        Log.i(
            TAG,
            "INTERCEPT triggered url=${request.url} code=${response.code} " +
                "server=${response.header("Server")}",
        )

        if (!WebViewUtil.supportsWebView(context)) {
            Log.w(TAG, "INTERCEPT WebView not supported on this device, abort")
            launchUI {
                context.toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            }
            return response
        }

        initWebView
        Log.i(TAG, "INTERCEPT delegating to child (${this.javaClass.simpleName})")
        return intercept(chain, request, response)
    }

    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            .filter { (name, value) ->
                isRequestHeaderSafe(name, value)
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    fun CountDownLatch.awaitFor30Seconds() {
        await(30, TimeUnit.SECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            setDefaultSettings()
            // FIX: Selalu gunakan UA dari WebSettings (Chrome asli device),
            // bukan UA statis dari preferences.
            // Ini penting agar WebView dan OkHttp pakai UA yang sama,
            // dan CF tidak bisa membedakan keduanya dari UA string.
            val webViewUA = try {
                WebSettings.getDefaultUserAgent(context)
            } catch (_: Exception) {
                request.header("User-Agent") ?: defaultUserAgentProvider()
            }
            settings.userAgentString = webViewUA
        }
    }

    /**
     * Fetch halaman HTML menggunakan WebView dan return hasilnya sebagai String.
     *
     * Digunakan untuk CF BotManagement mode di mana OkHttp selalu diblock
     * berdasarkan TLS fingerprint, tapi WebView (Chrome) diizinkan.
     * Dalam kasus ini, cookie tidak membantu — kita perlu fetch konten
     * langsung via WebView.
     */
    fun fetchHtmlWithWebView(
        context: Context,
        url: String,
        userAgent: String,
        executor: java.util.concurrent.Executor,
    ): String? {
        val latch = CountDownLatch(1)
        var htmlResult: String? = null

        executor.execute {
            val wv = WebView(context).apply {
                setDefaultSettings()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = userAgent
            }

            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String) {
                    // Ambil HTML via JavaScript
                    view.evaluateJavascript(
                        "(function(){ return document.documentElement.outerHTML; })()",
                    ) { html ->
                        if (html != null && html != "null") {
                            // JavaScript string result dibungkus quotes dan di-escape
                            htmlResult = html
                                .removeSurrounding("\"")
                                .replace("\\n", "\n")
                                .replace("\\t", "\t")
                                .replace("\\\"", "\"")
                                .replace("\\'", "'")
                                .replace("\\\\", "\\")
                            Log.i(TAG, "WV fetchHtml success url=$pageUrl len=${htmlResult?.length}")
                        }
                        latch.countDown()
                        view.stopLoading()
                        view.destroy()
                    }
                }

                override fun onReceivedHttpError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "WV fetchHtml HTTP error ${errorResponse?.statusCode} url=${request.url}")
                        // Tetap tunggu onPageFinished untuk mencoba ambil konten error page
                    }
                }
            }

            wv.loadUrl(url)
        }

        latch.await(30, TimeUnit.SECONDS)
        return htmlResult
    }

    companion object {
        private const val TAG = "WebViewCF"
    }
}

// Based on [IsRequestHeaderSafe] in
// https://source.chromium.org/chromium/chromium/src/+/main:services/network/public/cpp/header_util.cc
private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)
    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}

private val unsafeHeaderNames = listOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2",
    "keep-alive", "transfer-encoding", "set-cookie",
)
