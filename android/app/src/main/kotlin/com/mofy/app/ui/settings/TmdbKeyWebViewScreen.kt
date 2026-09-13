package com.mofy.app.ui.settings

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Minimal domain-locked WebView for the TMDB API key onboarding flow.
 *
 * Separate from TorrentWebViewScreen on purpose: that one is built for torrent
 * sites (magnet capture, BrowseSessionViewModel, title-extraction JS against
 * site-specific selectors). TMDB's login/docs pages need none of that - just
 * load the page, let the user sign in and reach the API settings page to copy
 * the Read Access Token. Coupling the torrent-browse WebView lifecycle to a
 * one-off settings page would be the wrong trade.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TmdbKeyWebViewScreen(contentPadding: PaddingValues) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                // allowFileAccess stays default (false) - not needed for TMDB.

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val host = request.url.host ?: return true // block if no host
                        // Domain lock: allow themoviedb.org and its subdomains,
                        // block everything else (external links, other domains).
                        return !(host == "themoviedb.org" || host.endsWith(".themoviedb.org"))
                    }
                }
                loadUrl(TMDB_KEY_URL)
            }
        },
        // AndroidView's own disposal hook - destroy the WebView (and its
        // WebViewClient, which would otherwise retain the Activity) when the
        // screen leaves composition.
        onRelease = { webView -> webView.destroy() },
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    )
}

// The TMDB login page that redirects to the API authentication docs after
// sign-in; 'to=read_me' preserves the redirect target.
private const val TMDB_KEY_URL =
    "https://www.themoviedb.org/login?to=read_me&redirect=%2Freference%2Fauthentication"
