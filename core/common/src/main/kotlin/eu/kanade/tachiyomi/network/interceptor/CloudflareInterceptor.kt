package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    private val hostLocks = ConcurrentHashMap<String, Any>()
    private val hostLastSolve = ConcurrentHashMap<String, Long>()

    // UA WebView yang sebenarnya — diambil saat runtime dari Chrome yang ter-install
    private val webViewUserAgent: String by lazy {
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
    }

    override fun shouldIntercept(response: Response): Boolean {
        if (response.code !in ERROR_CODES) return false
        if (response.header("Server") !in SERVER_CHECK) return false

        val url = response.request.url
        val cfRay = response.header("cf-ray") ?: "none"

        if (response.request.method != "GET") {
            Log.i(TAG, "SKIP non-GET request url=$url method=${response.request.method}")
            return false
        }

        val cfMitigated = response.header("cf-mitigated")
        if (cfMitigated?.equals("challenge", ignoreCase = true) == true) {
            Log.i(TAG, "DETECT cf-mitigated=challenge url=$url cf-ray=$cfRay code=${response.code}")
            return true
        }

        val priorResponse = response.priorResponse
        if (priorResponse != null && priorResponse.code in listOf(301, 302, 307, 308)) {
            val location = priorResponse.header("Location")
            if (location?.contains("cdn-cgi/challenge-platform") == true ||
                location?.contains("cdn-cgi/") == true
            ) {
                Log.i(TAG, "DETECT redirect to CF challenge url=$url cf-ray=$cfRay")
                return true
            }
        }

        val body = try {
            response.peekBody(2 * 1024 * 1024).string()
        } catch (e: Exception) {
            Log.w(TAG, "DETECT peekBody failed: ${e.message}")
            return false
        }

        if (body.contains("_cf_chl_opt") ||
            body.contains("cf-please-wait") ||
            body.contains("challenge-error") ||
            body.contains("challenges.cloudflare.com") ||
            body.contains("cf-wrapper") ||
            body.contains("cf-challenge") ||
            body.contains("name=\"cf_chl_prog\"") ||
            body.contains("name=\"cf_chl_opt\"") ||
            (body.contains("data-sitekey") && body.contains("turnstile"))
        ) {
            Log.i(TAG, "DETECT CF challenge pattern in body url=$url cf-ray=$cfRay")
            return true
        }

        val doc = try {
            org.jsoup.Jsoup.parse(body, url.toString())
        } catch (e: Exception) {
            Log.w(TAG, "DETECT Jsoup.parse failed: ${e.message}")
            return false
        }

        val legacyMatch = doc.getElementById("challenge-error-title") != null ||
            doc.getElementById("challenge-error-text") != null ||
            doc.getElementById("challenge-form") != null ||
            doc.getElementById("challenge-stage") != null

        if (legacyMatch) Log.i(TAG, "DETECT legacy DOM challenge url=$url cf-ray=$cfRay")
        return legacyMatch
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        val host = request.url.host
        val lock = hostLocks.getOrPut(host) { Any() }

        val now = System.currentTimeMillis()
        val lastSolve = hostLastSolve[host] ?: 0L
        if (now - lastSolve < 30_000) {
            Log.i(TAG, "RATE LIMIT: solve terakhir ${now - lastSolve}ms lalu, skip")
            response.close()
            return chain.proceed(request)
        }

        return synchronized(lock) {
            val waitTime = System.currentTimeMillis() - now
            if (waitTime > 100) {
                Log.i(TAG, "LOCK waited ${waitTime}ms host=$host — cek cookie")
                val freshCookie = cookieManager.get(request.url)
                    .firstOrNull { it.name in CF_COOKIE_NAMES }
                if (freshCookie != null) {
                    Log.i(TAG, "LOCK CF cookie sudah ada (${freshCookie.name}), skip solve")
                    response.close()
                    return@synchronized chain.proceed(request)
                }
            }

            Log.i(TAG, "SOLVE start host=$host url=${request.url}")
            response.close()

            return@synchronized try {
                val result = resolveWithWebView(request)

                when (result) {
                    is SolveResult.CookieObtained -> {
                        // CF JS/Turnstile challenge → dapat cf_clearance → retry OkHttp
                        hostLastSolve[host] = System.currentTimeMillis()
                        Log.i(TAG, "SOLVE success (cookie) host=$host")
                        chain.proceed(request)
                    }
                    is SolveResult.HtmlFetched -> {
                        // CF BotManagement → tidak ada cookie → return HTML dari WebView
                        hostLastSolve[host] = System.currentTimeMillis()
                        Log.i(TAG, "SOLVE success (webview html) host=$host len=${result.html.length}")
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(result.html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                            .build()
                    }
                    is SolveResult.Failed -> {
                        Log.e(TAG, "SOLVE FAILED host=$host")
                        throw CloudflareBypassException()
                    }
                }
            } catch (e: CloudflareBypassException) {
                Log.e(TAG, "SOLVE FAILED host=$host (bypass exception)")
                throw IOException(
                    context.stringResource(MR.strings.information_cloudflare_bypass_failure),
                    e,
                )
            } catch (e: Exception) {
                Log.e(TAG, "SOLVE FAILED host=$host ${e.javaClass.simpleName}: ${e.message}")
                throw IOException(e)
            }
        }
    }

    private fun syncCookiesToOkHttp(pageUrl: String, targetUrl: HttpUrl) {
        try {
            val wvc = CookieManager.getInstance()
            val cookieString = wvc.getCookie(pageUrl)
                ?: wvc.getCookie("${targetUrl.scheme}://${targetUrl.host}")
                ?: return

            val cookies = cookieString.split(";")
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .mapNotNull { pair ->
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0) {
                        try {
                            Cookie.Builder()
                                .name(pair.substring(0, eqIdx).trim())
                                .value(pair.substring(eqIdx + 1).trim())
                                .domain(targetUrl.host)
                                .path("/")
                                .build()
                        } catch (_: Exception) { null }
                    } else null
                }

            if (cookies.isNotEmpty()) {
                cookieManager.saveFromResponse(targetUrl, cookies)
                Log.i(TAG, "WV synced ${cookies.size} cookies → OkHttp: ${cookies.map { it.name }}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WV cookie sync failed: ${e.message}")
        }
    }

    // =========================================================================
    // SolveResult: hasil dari resolveWithWebView
    // =========================================================================
    private sealed class SolveResult {
        /** CF JS/Turnstile challenge → dapat cf_clearance cookie */
        object CookieObtained : SolveResult()
        /** CF BotManagement → tidak ada cookie, ambil HTML langsung dari WebView */
        data class HtmlFetched(val html: String) : SolveResult()
        /** Gagal total */
        object Failed : SolveResult()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request): SolveResult {
        val latch = CountDownLatch(1)
        var webview: WebView? = null
        val challengeFound = AtomicBoolean(false)
        val solveResult = AtomicReference<SolveResult>(SolveResult.Failed)
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val httpUrl = origRequestUrl.toHttpUrl()
        val baseUrl = "${httpUrl.scheme}://${httpUrl.host}"

        // Hapus hanya CF cookies lama, bukan semua cookies
        try {
            val wvc = CookieManager.getInstance()
            CF_COOKIE_NAMES.forEach { name ->
                wvc.setCookie(baseUrl, "$name=; Max-Age=0; Path=/")
            }
            wvc.flush()
            cookieManager.remove(httpUrl, COOKIE_NAMES)
            Log.i(TAG, "WV cleared CF cookies for $baseUrl")
        } catch (e: Exception) {
            Log.w(TAG, "WV failed to clear CF cookies: ${e.message}")
        }

        val pollThread = Thread({
            try {
                var elapsed = 0L
                while (elapsed < TIMEOUT_MS && solveResult.get() == SolveResult.Failed) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    elapsed += POLL_INTERVAL_MS

                    val cfCookieOkHttp = cookieManager.get(httpUrl)
                        .firstOrNull { it.name in CF_COOKIE_NAMES }
                    if (cfCookieOkHttp != null) {
                        Log.i(TAG, "WV POLL ${cfCookieOkHttp.name} found (OkHttp) setelah ${elapsed}ms")
                        solveResult.set(SolveResult.CookieObtained)
                        latch.countDown()
                        return@Thread
                    }

                    try {
                        val wvc = CookieManager.getInstance()
                        val cookies = wvc.getCookie(baseUrl).orEmpty()
                        if (cookies.contains("cf_clearance=") || cookies.contains("__cf_bm=")) {
                            val found = if (cookies.contains("cf_clearance=")) "cf_clearance" else "__cf_bm"
                            Log.i(TAG, "WV POLL $found found (WebView) setelah ${elapsed}ms")
                            syncCookiesToOkHttp(baseUrl, httpUrl)
                            solveResult.set(SolveResult.CookieObtained)
                            latch.countDown()
                            return@Thread
                        }
                    } catch (_: Exception) {}
                }
                Log.w(TAG, "WV POLL selesai tanpa CF cookie setelah ${elapsed}ms")
            } catch (_: InterruptedException) {
                Log.d(TAG, "WV POLL thread interrupted (normal)")
            }
        }, "cf-poll-${httpUrl.host}")
        pollThread.isDaemon = true
        pollThread.start()

        executor.execute {
            val wv = createWebView(originalRequest)
            webview = wv

            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.settings.loadsImagesAutomatically = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false

            // FIX: Pastikan WebView pakai UA Chrome asli, bukan UA statis dari preferences
            try {
                val realUA = WebSettings.getDefaultUserAgent(context)
                wv.settings.userAgentString = realUA
                Log.d(TAG, "WV UA set to: ${realUA.take(80)}")
            } catch (_: Exception) {}

            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            val dm = context.resources.displayMetrics
            val w = dm.widthPixels.coerceAtLeast(1080)
            val h = dm.heightPixels.coerceAtLeast(1920)
            wv.layout(0, 0, w, h)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            wv.onResume()
            wv.resumeTimers()
            wv.onWindowFocusChanged(true)
            wv.dispatchWindowVisibilityChanged(View.VISIBLE)

            Log.i(TAG, "WV loadUrl=$origRequestUrl")

            wv.webViewClient = object : WebViewClient() {
                // ====================================================================
                // FIX INTI: Track total error count sepanjang sesi WebView,
                // TIDAK di-reset saat redirect internal CF (/komik → /komik/).
                // Ini fix untuk bug false-positive "CF BotManagement mode detected".
                // ====================================================================
                private var pageCount = 0
                private var mainFrameErrorCount = 0

                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    Log.d(TAG, "WV shouldOverrideUrlLoading url=$url")
                    return false
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    pageCount++
                    // TIDAK reset mainFrameErrorCount — track sepanjang sesi
                    Log.i(TAG, "WV onPageStarted #$pageCount url=$url errorCount=$mainFrameErrorCount")
                    view.evaluateJavascript(JS_NAVIGATOR_OVERRIDE, null)
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.i(TAG, "WV onPageFinished #$pageCount url=$url errorCount=$mainFrameErrorCount")

                    if (url.contains("cdn-cgi/")) {
                        Log.d(TAG, "WV skip cdn-cgi URL")
                        return
                    }

                    try {
                        val wvc = CookieManager.getInstance()
                        val cookiesFromUrl = wvc.getCookie(url).orEmpty()
                        val cookiesFromBase = wvc.getCookie(baseUrl).orEmpty()

                        // Prioritas 1: cf_clearance → JS/Turnstile challenge solved
                        if (cookiesFromUrl.contains("cf_clearance=") ||
                            cookiesFromBase.contains("cf_clearance=")
                        ) {
                            Log.i(TAG, "WV cf_clearance FOUND → cookie mode")
                            syncCookiesToOkHttp(url, httpUrl)
                            syncCookiesToOkHttp(baseUrl, httpUrl)
                            solveResult.set(SolveResult.CookieObtained)
                            latch.countDown()
                            return
                        }

                        // Prioritas 2: __cf_bm setelah error → sync cookie
                        if (mainFrameErrorCount > 0) {
                            val hasBm = cookiesFromUrl.contains("__cf_bm=") ||
                                cookiesFromBase.contains("__cf_bm=")
                            if (hasBm) {
                                Log.i(TAG, "WV __cf_bm found after error → cookie mode")
                                syncCookiesToOkHttp(url, httpUrl)
                                syncCookiesToOkHttp(baseUrl, httpUrl)
                                solveResult.set(SolveResult.CookieObtained)
                                latch.countDown()
                                return
                            }

                            // ============================================================
                            // FIX UTAMA — CF BotManagement tanpa cookie:
                            //
                            // Situasi: mainFrameErrorCount > 0 (ada 403 di /komik)
                            // tapi WebView berhasil load /komik/ tanpa CF cookie.
                            //
                            // Artinya CF memblock OkHttp via TLS fingerprint,
                            // tapi WebView (Chrome fingerprint) diizinkan.
                            // Cookie sync tidak akan membantu OkHttp.
                            //
                            // Solusi: Ambil HTML langsung dari WebView via JavaScript,
                            // return sebagai Response ke Tachiyomi.
                            // ============================================================
                            Log.i(
                                TAG,
                                "WV CF BotManagement detected (errorCount=$mainFrameErrorCount, no cookie) " +
                                    "→ fetching HTML from WebView directly",
                            )
                            view.evaluateJavascript(
                                "(function(){ return document.documentElement.outerHTML; })()",
                            ) { rawHtml ->
                                val html = rawHtml
                                    ?.takeIf { it != "null" && it.length > 100 }
                                    ?.removeSurrounding("\"")
                                    ?.replace("\\n", "\n")
                                    ?.replace("\\t", "\t")
                                    ?.replace("\\\"", "\"")
                                    ?.replace("\\'", "'")
                                    ?.replace("\\\\", "\\")

                                if (html != null) {
                                    Log.i(TAG, "WV HTML fetched len=${html.length} → webview mode")
                                    solveResult.set(SolveResult.HtmlFetched(html))
                                } else {
                                    Log.w(TAG, "WV HTML fetch failed (null/empty result)")
                                    solveResult.set(SolveResult.Failed)
                                }
                                latch.countDown()
                            }
                            return
                        }

                        // Tidak ada error sama sekali → CF tidak aktif / sudah bypass
                        // Sync semua cookies dan retry normal dengan OkHttp
                        Log.i(TAG, "WV page loaded with no errors → sync cookies, retry OkHttp")
                        syncCookiesToOkHttp(url, httpUrl)
                        syncCookiesToOkHttp(baseUrl, httpUrl)
                        solveResult.set(SolveResult.CookieObtained)
                        latch.countDown()

                    } catch (e: Exception) {
                        Log.w(TAG, "WV error in onPageFinished: ${e.message}")
                    }

                    if (pageCount >= 10) {
                        Log.w(TAG, "WV terlalu banyak page load ($pageCount), abort")
                        if (solveResult.get() == SolveResult.Failed) latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val code = errorResponse?.statusCode
                    if (request?.isForMainFrame == true) {
                        Log.i(TAG, "WV mainFrame HTTP error code=$code url=${request.url}")
                        if (code in ERROR_CODES) {
                            mainFrameErrorCount++  // Increment, TIDAK pernah di-reset
                            challengeFound.set(true)
                            view?.evaluateJavascript(JS_NAVIGATOR_OVERRIDE, null)
                            view?.evaluateJavascript(JS_VISIBILITY_OVERRIDE, null)
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "WV mainFrame error code=${error?.errorCode} url=${request.url}")
                    }
                }
            }

            wv.loadUrl(origRequestUrl, emptyMap())
        }

        val solved = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pollThread.interrupt()

        if (!solved) {
            Log.w(TAG, "WV latch TIMEOUT ${TIMEOUT_SECONDS}s challengeFound=${challengeFound.get()}")
        }

        executor.execute {
            if (solveResult.get() == SolveResult.Failed) {
                isWebViewOutdated = webview?.isOutdated() == true
                if (isWebViewOutdated) Log.w(TAG, "WV kemungkinan outdated")
            }
            webview?.run {
                stopLoading()
                pauseTimers()
                destroy()
                Log.d(TAG, "WV destroyed")
            }
        }

        if (solveResult.get() == SolveResult.Failed) {
            if (isWebViewOutdated) {
                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
            }
            throw CloudflareBypassException()
        }

        return solveResult.get()
    }

    companion object {
        private const val TAG = "WebViewCF"
        private const val TIMEOUT_SECONDS = 60L
        private const val TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 500L

        private val CF_COOKIE_NAMES = setOf("cf_clearance", "__cf_bm", "_cfuvid")

        private const val JS_NAVIGATOR_OVERRIDE = """
            (function(){
              try {
                Object.defineProperty(navigator, 'webdriver', {
                  get: function(){ return false; }, configurable: true
                });
                if (!navigator.plugins || navigator.plugins.length === 0) {
                  Object.defineProperty(navigator, 'plugins', {
                    get: function(){
                      return [
                        {name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format'},
                        {name: 'Native Client', filename: 'internal-nacl-plugin', description: ''}
                      ];
                    }, configurable: true
                  });
                }
                if (!navigator.languages || navigator.languages.length === 0) {
                  Object.defineProperty(navigator, 'languages', {
                    get: function(){ return ['id-ID', 'id', 'en-US', 'en']; }, configurable: true
                  });
                }
                Object.defineProperty(navigator, 'hardwareConcurrency', {
                  get: function(){ return 8; }, configurable: true
                });
                Object.defineProperty(navigator, 'deviceMemory', {
                  get: function(){ return 8; }, configurable: true
                });
              } catch(e) {}
            })();
        """

        private const val JS_VISIBILITY_OVERRIDE = """
            (function(){
              try {
                Object.defineProperty(document, 'visibilityState', {
                  get: function(){ return 'visible'; }, configurable: true
                });
                Object.defineProperty(document, 'hidden', {
                  get: function(){ return false; }, configurable: true
                });
                Document.prototype.hasFocus = function(){ return true; };
                document.dispatchEvent(new Event('visibilitychange'));
              } catch(e) {}
            })();
        """

        private const val JS_AUTO_CLICK_TURNSTILE = """
            (function(){
              if (window.__cfClickDone) return 'already';
              var tries = 0;
              var iv = setInterval(function(){
                tries++;
                var frames = document.querySelectorAll('iframe[src*="challenges.cloudflare.com"]');
                for (var i = 0; i < frames.length; i++) {
                  try {
                    frames[i].contentWindow.postMessage(
                      JSON.stringify({event: 'click', x: 0, y: 0}),
                      'https://challenges.cloudflare.com'
                    );
                  } catch(e) {}
                  var r = frames[i].getBoundingClientRect();
                  if (r.width > 0 && r.height > 0) {
                    frames[i].dispatchEvent(new MouseEvent('click', {
                      bubbles: true, cancelable: true, view: window,
                      clientX: r.left + r.width/2, clientY: r.top + r.height/2
                    }));
                  }
                }
                var btn = document.querySelector(
                  '#challenge-stage input[type="submit"],' +
                  '#challenge-form button,' +
                  '.cf-turnstile-wrapper button,' +
                  'div[style*="challenge"] button'
                );
                if (btn && btn.offsetParent !== null) {
                  btn.click(); window.__cfClickDone = true; clearInterval(iv);
                }
                if (tries > 200) clearInterval(iv);
              }, 300);
              return 'started';
            })();
        """
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance", "__cf_bm", "_cfuvid")
private class CloudflareBypassException : Exception()
