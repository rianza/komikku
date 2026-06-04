package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    private val resolveLocks = mutableMapOf<String, Any>()
    private val lastSolveTime = mutableMapOf<String, Long>()

    private fun getLock(host: String): Any {
        synchronized(resolveLocks) {
            return resolveLocks.getOrPut(host) { Any() }
        }
    }

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        if (response.header("cf-ray") != null || response.header("Server") in SERVER_CHECK) {
            if (response.code in ERROR_CODES) {
                Log.i("WebViewCF", "DETECT code=${response.code} server=${response.header("Server")} url=${response.request.url}")
                return true
            }

            // KMK -->
            // Check for Turnstile/BotManagement even on 200 OK
            if (response.code == 200 && response.header("cf-mitigated") == "challenge") {
                Log.i("WebViewCF", "DETECT cf-mitigated=challenge url=${response.request.url}")
                return true
            }

            val body = response.peekBody(BODY_PEEK_SIZE)
            if (body.contentType()?.run { type == "text" && subtype == "html" } == true) {
                val bodyString = body.string()
                if (bodyString.contains("_cf_chl_opt") || bodyString.contains("/cdn-cgi/challenge-platform/")) {
                    Log.i("WebViewCF", "DETECT challenge markers in body url=${response.request.url}")
                    return true
                }
            }
            // KMK <--
        }

        return false
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            val oldCookies = cookieManager.get(request.url)
                .filter { it.name in COOKIE_NAMES }
                .associate { it.name to it.value }
            resolveWithWebView(request, oldCookies)

            // KMK -->
            val host = request.url.host
            val isAjax = request.header("X-Requested-With") == "XMLHttpRequest" ||
                request.url.encodedPath.contains("admin-ajax.php")

            val newRequest = request.newBuilder()
                .apply {
                    val ua = request.header("User-Agent") ?: defaultUserAgentProvider()
                    header("User-Agent", ua)

                    // Add browser-like headers if they are likely missing
                    if (request.header("Accept").isNullOrBlank()) {
                        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    }
                    if (request.header("Accept-Language").isNullOrBlank()) {
                        header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                    }

                    // Cloudflare Bot Management specific headers
                    if (isAjax) {
                        header("Sec-Fetch-Dest", "empty")
                        header("Sec-Fetch-Mode", "cors")
                        header("Sec-Fetch-Site", "same-origin")
                    } else {
                        header("Sec-Fetch-Dest", "document")
                        header("Sec-Fetch-Mode", "navigate")
                        header("Sec-Fetch-Site", "none")
                        header("Sec-Fetch-User", "?1")
                    }

                    header("Sec-Ch-Ua-Mobile", "?1")
                    header("Sec-Ch-Ua-Platform", "\"Android\"")
                    header("Upgrade-Insecure-Requests", "1")

                    if (request.method == "POST" && request.header("Origin").isNullOrBlank()) {
                        header("Origin", "https://$host")
                    }

                    if (request.header("Referer").isNullOrBlank()) {
                        header("Referer", "https://$host/")
                    }

                    // Remove X-Requested-With if it's the app's package name
                    val xrw = request.header("X-Requested-With")
                    if (xrw != null && xrw != "XMLHttpRequest") {
                        removeHeader("X-Requested-With")
                    }
                }
                .build()

            Log.d("WebViewCF", "RETRY request: ${newRequest.url} headers=${newRequest.headers.size}")
            return chain.proceed(newRequest)
            // KMK <--
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookies: Map<String, String?>) {
        val host = originalRequest.url.host
        synchronized(getLock(host)) {
            // KMK -->
            val lastSolve = lastSolveTime[host] ?: 0L
            if (System.currentTimeMillis() - lastSolve < 5000) {
                Log.i("WebViewCF", "RATE LIMIT: solve terakhir ${System.currentTimeMillis() - lastSolve}ms lalu, skip")
                return
            }

            // Re-check cookies after acquiring lock
            if (isCloudFlareBypassed(originalRequest.url.toString(), oldCookies)) {
                Log.i("WebViewCF", "LOCK waited host=$host — cookies already updated")
                return
            }

            // Clear cf_clearance to ensure we get a fresh one
            cookieManager.remove(originalRequest.url, listOf("cf_clearance"), 0)
            CookieManager.getInstance().flush()
            // KMK <--

            // We need to lock this thread until the WebView finds the challenge solution url, because
            // OkHttp doesn't support asynchronous interceptors.
            val latch = CountDownLatch(1)

            var webview: WebView? = null

            var challengeFound = false
            var cloudflareBypassed = false
            var isWebViewOutdated = false

            // Use root URL for non-GET or AJAX requests to increase solve success rate
            val resolveUrl = if (originalRequest.method == "GET" && !originalRequest.url.encodedPath.contains("admin-ajax.php")) {
                originalRequest.url.toString()
            } else {
                originalRequest.url.newBuilder()
                    .encodedPath("/")
                    .query(null)
                    .build()
                    .toString()
            }

            val headers = parseHeaders(originalRequest.headers)

            executor.execute {
                webview = createWebView(originalRequest)

                webview?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (isCloudFlareBypassed(url, oldCookies)) {
                            cloudflareBypassed = true
                            Log.i("WebViewCF", "SOLVE success host=$host via clearance cookie")
                            CookieManager.getInstance().flush()
                            lastSolveTime[host] = System.currentTimeMillis()
                            latch.countDown()
                        }

                        // Silent bypass for BotManagement
                        if (!challengeFound && !cloudflareBypassed && url.toHttpUrl().host == host) {
                            val title = view.title.orEmpty()
                            if (isTitleSuccess(title)) {
                                Log.i("WebViewCF", "SOLVE success host=$host via title shift: $title")
                                cloudflareBypassed = true
                                CookieManager.getInstance().flush()
                                lastSolveTime[host] = System.currentTimeMillis()
                                latch.countDown()
                            }
                        }

                        if (url == resolveUrl && !challengeFound && !cloudflareBypassed) {
                            if (isTitleSuccess(view.title.orEmpty())) {
                                Log.i("WebViewCF", "SOLVE success host=$host via title on finish: ${view.title}")
                                cloudflareBypassed = true
                                CookieManager.getInstance().flush()
                                lastSolveTime[host] = System.currentTimeMillis()
                            } else {
                                Log.i("WebViewCF", "ABORT: challenge not found on $url (title: ${view.title})")
                            }
                            latch.countDown()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            val statusCode = errorResponse?.statusCode ?: -1
                            if (statusCode in ERROR_CODES) {
                                // Found the Cloudflare challenge page.
                                Log.i("WebViewCF", "INTERCEPT triggered url=${request.url} code=$statusCode")
                                challengeFound = true
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.e("WebViewCF", "WV error: ${error?.errorCode} ${error?.description} url=${request.url}")
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString().orEmpty()
                        if (requestUrl.contains("chk_jschl") || requestUrl.contains("challenge-platform") || requestUrl.contains("turnstile")) {
                            Log.i("WebViewCF", "CHALLENGE load: $requestUrl")
                            challengeFound = true
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webview?.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (!cloudflareBypassed && isTitleSuccess(title.orEmpty()) && view?.url?.toHttpUrl()?.host == host) {
                            Log.i("WebViewCF", "SOLVE success host=$host via title shift (Chrome): $title")
                            cloudflareBypassed = true
                            CookieManager.getInstance().flush()
                            lastSolveTime[host] = System.currentTimeMillis()
                            latch.countDown()
                        }
                    }
                }

                Log.i("WebViewCF", "SOLVE start host=$host method=${originalRequest.method} url=$resolveUrl")
                webview?.loadUrl(resolveUrl, headers)
            }

            latch.await(45, TimeUnit.SECONDS)

            executor.execute {
                if (!cloudflareBypassed) {
                    isWebViewOutdated = webview?.isOutdated() == true
                }

                webview?.run {
                    stopLoading()
                    destroy()
                }
            }

            // Throw exception if we failed to bypass Cloudflare
            if (!cloudflareBypassed) {
                // Prompt user to update WebView if it seems too outdated
                if (isWebViewOutdated) {
                    context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
                }

                Log.e("WebViewCF", "SOLVE failed host=$host")
                throw CloudflareBypassException()
            }

            // Small delay to allow cookies to settle in the client's jar
            Thread.sleep(1000)
        }
    }

    private fun isCloudFlareBypassed(url: String, oldCookies: Map<String, String?>): Boolean {
        val currentCookies = cookieManager.get(url.toHttpUrl()).associate { it.name to it.value }
        return COOKIE_NAMES.any { name ->
            val current = currentCookies[name]
            val old = oldCookies[name]
            current != null && current != old
        }
    }

    private fun isTitleSuccess(title: String): Boolean {
        return title.isNotBlank() && !isTitleChallenge(title)
    }

    private fun isTitleChallenge(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("just a moment") ||
            t.contains("ditunggu sebentar") ||
            t.contains("please wait") ||
            t.contains("attention required") ||
            t.contains("cloudflare") ||
            t.contains("checking your browser") ||
            t.contains("menunggu") ||
            t.contains("ditunggu") ||
            t.contains("tunggu") ||
            t.contains("verifying") ||
            t.contains("checking") ||
            t.contains("security check")
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        private val COOKIE_NAMES = listOf("cf_clearance", "cf_bm", "_cfuvid")
        private const val BODY_PEEK_SIZE = 1024L * 10
    }
}

private class CloudflareBypassException : Exception()
