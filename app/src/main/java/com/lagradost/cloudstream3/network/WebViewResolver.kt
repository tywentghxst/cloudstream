package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * When used as Interceptor additionalUrls cannot be returned, use WebViewResolver(...).resolveUsingWebView(...)
 * @param interceptUrl will stop the WebView when reaching this url.
 * @param additionalUrls this will make resolveUsingWebView also return all other requests matching the list of Regex.
 * */
class WebViewResolver(val interceptUrl: Regex, val additionalUrls: List<Regex> = emptyList()) :
    Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixedRequest = resolveUsingWebView(request).first
            return@runBlocking chain.proceed(fixedRequest ?: request)
        }
    }

    /**
     * @param requestCallBack asynchronously return matched requests by either interceptUrl or additionalUrls.
     * @return the final request (by interceptUrl) and all the collected urls (by additionalUrls).
     * */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Unit = {}
    ): Pair<Request?, List<Request>> {
        val url = request.url.toString()
        val headers = request.headers
        println("Initial web-view request: $url")
        var webView: WebView? = null

        fun destroyWebView() {
            main {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                println("Destroyed webview")
            }
        }

        var fixedRequest: Request? = null
        val extraRequestList = mutableListOf<Request>()

        main {
            // Useful for debugging
//            WebView.setWebContentsDebuggingEnabled(true)
            webView = WebView(
                AcraApplication.context
                    ?: throw RuntimeException("No base context in WebViewResolver")
            ).apply {
                // Bare minimum to bypass captcha
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = USER_AGENT
                // Blocks unnecessary images, remove if captcha fucks.
                settings.blockNetworkImage = true
            }

            webView?.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val webViewUrl = request.url.toString()
//                    println("Loading WebView URL: $webViewUrl")

                    if (interceptUrl.containsMatchIn(webViewUrl)) {
                        fixedRequest = request.toRequest().also(requestCallBack)
                        println("Web-view request finished: $webViewUrl")
                        destroyWebView()
                        return null
                    }

                    if (additionalUrls.any { it.containsMatchIn(webViewUrl) }) {
                        extraRequestList.add(request.toRequest().also(requestCallBack))
                    }

                    // Suppress image requests as we don't display them anywhere
                    // Less data, low chance of causing issues.
                    // blockNetworkImage also does this job but i will keep it for the future.
                    val blacklistedFiles = listOf(".jpg", ".png", ".webp", ".jpeg", ".webm", ".mp4")

                    /** NOTE!  request.requestHeaders is not perfect!
                     *  They don't contain all the headers the browser actually gives.
                     *  Overriding with okhttp might fuck up otherwise working requests,
                     *  e.g the recaptcha request.
                     * **/
                    return try {
                        when {
                            blacklistedFiles.any { URI(webViewUrl).path.endsWith(it) } || webViewUrl.endsWith(
                                "/favicon.ico"
                            ) -> WebResourceResponse(
                                "image/png",
                                null,
                                null
                            )

                            webViewUrl.contains("recaptcha") -> super.shouldInterceptRequest(
                                view,
                                request
                            )

                            request.method == "GET" -> app.get(
                                webViewUrl,
                                headers = request.requestHeaders
                            ).response.toWebResourceResponse()

                            request.method == "POST" -> app.post(
                                webViewUrl,
                                headers = request.requestHeaders
                            ).response.toWebResourceResponse()
                            else -> return super.shouldInterceptRequest(view, request)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed() // Ignore ssl issues
                }
            }
            webView?.loadUrl(url, headers.toMap())
        }

        var loop = 0
        // Timeouts after this amount, 60s
        val totalTime = 60000L

        val delayTime = 100L

        // A bit sloppy, but couldn't find a better way
        while (loop < totalTime / delayTime) {
            if (fixedRequest != null) return fixedRequest to extraRequestList
            delay(delayTime)
            loop += 1
        }

        println("Web-view timeout after ${totalTime / 1000}s")
        destroyWebView()
        return null to extraRequestList
    }

    fun WebResourceRequest.toRequest(): Request {
        val webViewUrl = this.url.toString()

        return when (this.method) {
            "POST" -> postRequestCreator(
                webViewUrl,
                this.requestHeaders,
                null,
                emptyMap(),
                emptyMap(),
                emptyMap(),
                10,
                TimeUnit.MINUTES
            )
//            "GET",
            else -> getRequestCreator(
                webViewUrl,
                this.requestHeaders,
                null,
                emptyMap(),
                emptyMap(),
                10,
                TimeUnit.MINUTES
            )
        }
    }

    fun Response.toWebResourceResponse(): WebResourceResponse {
        val contentTypeValue = this.header("Content-Type")
        // 1. contentType. 2. charset
        val typeRegex = Regex("""(.*);(?:.*charset=(.*)(?:|;)|)""")
        return if (contentTypeValue != null) {
            val found = typeRegex.find(contentTypeValue)
            val contentType = found?.groupValues?.getOrNull(1)?.ifBlank { null } ?: contentTypeValue
            val charset = found?.groupValues?.getOrNull(2)?.ifBlank { null }
            WebResourceResponse(contentType, charset, this.body?.byteStream())
        } else {
            WebResourceResponse("application/octet-stream", null, this.body?.byteStream())
        }
    }
}