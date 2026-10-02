package com.hatake.cookiechecker.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "CookieChecker"
private const val MBASIC = "https://mbasic.facebook.com"

/**
 * In-app browser that mirrors the bookmarklet flow:
 * set each cookie pair (Domain=.facebook.com, Path=/) then load mbasic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CookieWebViewScreen(cookie: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account browser") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } }
            )
        }
    ) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    injectBookmarkletCookie(cookie)
                    loadUrl(MBASIC)
                }
            }
        )
    }
}

/** Same parsing as the bookmarklet: pipe-split picks the c_user segment, then k=v pairs. */
private fun injectBookmarkletCookie(raw: String) {
    val count = com.hatake.cookiechecker.CookieInjector.inject(raw)
    Log.d(TAG, "WEBVIEW injected $count pairs, loading $MBASIC")
}
