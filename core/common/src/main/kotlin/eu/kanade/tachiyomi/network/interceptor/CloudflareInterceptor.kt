package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
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

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        val isChallenge = response.header("cf-mitigated") == "challenge"
        val isCloudflare = response.header("Server")?.contains("cloudflare", ignoreCase = true) == true
        val isError = response.code in ERROR_CODES

        if (isChallenge) {
            logcat(LogPriority.INFO) { "Cloudflare challenge detected via header" }
            return true
        }

        if (isCloudflare) {
            if (isError) {
                logcat(LogPriority.INFO) { "Cloudflare challenge detected via status code ${response.code}" }
                return true
            }

            // Sometimes it returns 200 but it's a challenge page
            if (response.code == 200) {
                val body = response.peekBody(1024).string()
                val hasSignature = body.contains("challenges.cloudflare.com") ||
                    body.contains("_cf_chl_opt") ||
                    body.contains("cf-challenge") ||
                    body.contains("Just a moment...")
                if (hasSignature) {
                    logcat(LogPriority.INFO) { "Cloudflare challenge detected via body signature" }
                    return true
                }
            }
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
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }
            resolveWithWebView(request, oldCookie)

            return chain.proceed(request)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            webview = createWebView(originalRequest)

            webview.webViewClient = object : WebViewClient() {
                private var lastError: Int? = null

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    logcat(LogPriority.DEBUG) { "WebView started loading: $url" }
                    lastError = null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    logcat(LogPriority.DEBUG) { "WebView finished loading: $url" }
                    checkSuccess(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        logcat(LogPriority.ERROR) { "WebView error: ${error?.errorCode} - ${error?.description}" }
                        lastError = error?.errorCode
                        if (error?.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                            error?.errorCode == WebViewClient.ERROR_CONNECT ||
                            error?.errorCode == WebViewClient.ERROR_TIMEOUT
                        ) {
                            latch.countDown()
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        logcat(LogPriority.ERROR) { "WebView HTTP error: ${errorResponse?.statusCode}" }
                        if (errorResponse?.statusCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            lastError = errorResponse?.statusCode
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }

                private fun checkSuccess(view: WebView) {
                    val url = view.url ?: ""
                    val title = view.title ?: ""
                    logcat(LogPriority.DEBUG) { "Checking success: $url (Title: $title)" }

                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .any { it.name == "cf_clearance" && it.value != oldCookie?.value }
                    }

                    if (isCloudFlareBypassed()) {
                        logcat(LogPriority.INFO) { "Cloudflare bypassed: cf_clearance cookie found/updated" }
                        cloudflareBypassed = true
                        latch.countDown()
                        return
                    }

                    if (lastError == null && url.contains(origRequestUrl.toHttpUrl().host)) {
                        if (title.isNotBlank() &&
                            !title.contains("Just a moment", ignoreCase = true) &&
                            !title.contains("Cloudflare", ignoreCase = true) &&
                            !title.contains("Attention Required", ignoreCase = true) &&
                            !title.contains("Ditunggu sebentar", ignoreCase = true)
                        ) {
                            logcat(LogPriority.INFO) { "Cloudflare bypassed: site title detected" }
                            cloudflareBypassed = true
                            latch.countDown()
                        }
                    }
                }
            }

            webview.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    logcat(LogPriority.DEBUG) { "WebView title: $title" }
                    view?.let {
                        val url = it.url ?: return
                        if (url.contains(origRequestUrl.toHttpUrl().host)) {
                            if (title != null && title.isNotBlank() &&
                                !title.contains("Just a moment", ignoreCase = true) &&
                                !title.contains("Cloudflare", ignoreCase = true) &&
                                !title.contains("Attention Required", ignoreCase = true) &&
                                !title.contains("Ditunggu sebentar", ignoreCase = true)
                            ) {
                                logcat(LogPriority.INFO) { "Cloudflare bypassed: site title detected (real-time)" }
                                cloudflareBypassed = true
                                latch.countDown()
                            }
                        }
                    }
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        latch.await(45, java.util.concurrent.TimeUnit.SECONDS)

        if (!cloudflareBypassed) {
            if (cookieManager.get(origRequestUrl.toHttpUrl()).any { it.name == "cf_clearance" }) {
                cloudflareBypassed = true
            }
        }

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

            throw CloudflareBypassException()
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
