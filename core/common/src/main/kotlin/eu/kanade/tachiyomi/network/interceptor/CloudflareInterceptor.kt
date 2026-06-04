package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        if (response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK) {
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

    @SuppressLint("SetJavaScriptEnabled")
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

            webview?.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        // KMK -->
                        Log.i("WebViewCF", "SOLVE success host=${origRequestUrl.toHttpUrl().host} via clearance cookie")
                        CookieManager.getInstance().flush()
                        // KMK <--
                        latch.countDown()
                    }

                    // KMK -->
                    // Silent bypass for BotManagement
                    if (!challengeFound && !cloudflareBypassed && url.toHttpUrl().host == origRequestUrl.toHttpUrl().host) {
                        val title = view.title.orEmpty()
                        if (title.isNotBlank() && !title.contains("Just a moment") && !title.contains("Ditunggu sebentar")) {
                            Log.i("WebViewCF", "SOLVE success host=${origRequestUrl.toHttpUrl().host} via title shift: $title")
                            cloudflareBypassed = true
                            CookieManager.getInstance().flush()
                            latch.countDown()
                        }
                    }
                    // KMK <--

                    if (url == origRequestUrl && !challengeFound && !cloudflareBypassed) {
                        // The first request didn't return the challenge, abort.
                        Log.i("WebViewCF", "ABORT: challenge not found on $url")
                        latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.statusCode != null && errorResponse.statusCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            Log.i("WebViewCF", "INTERCEPT triggered url=${request.url} code=${errorResponse.statusCode}")
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }

                // KMK -->
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (request?.url?.toString()?.contains("chk_jschl") == true) {
                        challengeFound = true
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                // KMK <--
            }

            Log.i("WebViewCF", "SOLVE start host=${origRequestUrl.toHttpUrl().host} url=$origRequestUrl")
            webview?.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

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

            Log.e("WebViewCF", "SOLVE failed host=${origRequestUrl.toHttpUrl().host}")
            throw CloudflareBypassException()
        }
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        private val COOKIE_NAMES = listOf("cf_clearance")
        private const val BODY_PEEK_SIZE = 1024L * 10
    }
}

private class CloudflareBypassException : Exception()
