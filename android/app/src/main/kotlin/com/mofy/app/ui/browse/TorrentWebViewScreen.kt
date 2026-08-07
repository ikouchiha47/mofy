package com.mofy.app.ui.browse

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mofy.app.data.sites.TorrentSite

/**
 * Phase 02: WebView browsing with ad-overlay blocking and silent title
 * auto-detection. See docs/phases/02-torrent-site-browsing.md and ADR 0003.
 *
 * Ad overlays are blocked by refusing to create new windows/tabs at all
 * (onCreateWindow -> false) - the overlay's own JS never gets a WebView to
 * render into, rather than being hidden after the fact with CSS.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TorrentWebViewScreen(
    contentPadding: PaddingValues,
    site: TorrentSite,
    sessionViewModel: BrowseSessionViewModel,
    onMagnetCaptured: (magnetUri: String) -> Unit,
) {
    val extractedTitle by sessionViewModel.extractedTitle.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Strip the "; wv)" token Android's WebView adds to its
                    // default UA - it marks the request as coming from an
                    // embedded WebView rather than a real browser, and sites
                    // behind Cloudflare (e.g. EZTV) hard-reset the connection
                    // when they see it, even though the same site loads fine
                    // in Chrome itself.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    // Sites without proper mobile CSS (e.g. EZTV) render at
                    // WebView's default ~980px virtual viewport otherwise,
                    // forcing pan in both directions - this is the same
                    // "shrink desktop layout to fit" trick Chrome mobile
                    // does automatically for non-responsive pages.
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // Ad overlays rely on window.open()/target=_blank popups -
                    // refusing to support multiple windows and never creating
                    // one blocks them at the source.
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = false
                    }

                    // Re-extract fresh on every magnet tap rather than relying on
                    // onPageFinished's result: ad-heavy mirror sites keep making
                    // background requests that delay "page finished" well past
                    // when the title is actually visible and tappable to a human
                    // - so the onPageFinished-triggered extraction can still be
                    // pending (or based on a prior interstitial page) at tap time.
                    fun extractTitle(webView: WebView, onResult: (String) -> Unit) {
                        val selector = site.titleSelector ?: return
                        val js = "document.querySelector(${jsStringLiteral(selector)})?.textContent || ''"
                        webView.evaluateJavascript(js) { result ->
                            val title = result?.trim('"')?.trim()
                            if (!title.isNullOrBlank()) onResult(title)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (request.url.scheme == "magnet") {
                                val url = request.url.toString()
                                extractTitle(view) { sessionViewModel.onTitleExtracted(it) }
                                sessionViewModel.onMagnetTapped(url)
                                onMagnetCaptured(url)
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            extractTitle(view) { sessionViewModel.onTitleExtracted(it) }
                        }
                    }

                    // Restore prior in-page navigation history if we have it
                    // (e.g. returning from Confirm Match) instead of always
                    // reloading the site's base URL from scratch.
                    val savedState = sessionViewModel.webViewState
                    if (savedState != null) {
                        restoreState(savedState)
                    } else {
                        val pendingUrl = sessionViewModel.consumePendingLoadUrl()
                        if (pendingUrl != null) {
                            // GET only - see SiteSearchConfig for why POST is parked.
                            val headers = site.searchConfig?.headers?.takeIf { it.isNotEmpty() }
                            if (headers != null) loadUrl(pendingUrl, headers) else loadUrl(pendingUrl)
                        } else {
                            loadUrl(site.baseUrl)
                        }
                    }
                }
            },
            onRelease = { webView ->
                sessionViewModel.webViewState = android.os.Bundle().also { webView.saveState(it) }
            },
        )

        // Silent, non-blocking auto-detect toast - browsing continues underneath.
        extractedTitle?.let { title ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50)),
                )
                Text("Detected: $title", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun jsStringLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}
