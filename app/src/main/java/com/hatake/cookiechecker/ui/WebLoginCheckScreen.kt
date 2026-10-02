package com.hatake.cookiechecker.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hatake.cookiechecker.CookieInjector
import com.hatake.cookiechecker.CookieStatus
import com.hatake.cookiechecker.CookieViewModel
import com.hatake.cookiechecker.LoginVerdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "CookieChecker"
private const val MBASIC = "https://mbasic.facebook.com"
private const val VERDICT_TIMEOUT_MS = 25_000L
private const val BETWEEN_ACCOUNTS_MS = 1500L

/**
 * Sequential WebView login-checker:
 * cookie #1 -> clear -> inject -> load mbasic -> LIVE(green+save)/DEAD
 * -> next cookie -> ... until the queue is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginCheckScreen(vm: CookieViewModel, onClose: () -> Unit) {
    val state by vm.ui.collectAsState()
    val saved by vm.savedLive.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var goOn by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var currentText by remember { mutableStateOf("Preparing…") }
    var lastVerdict by remember { mutableStateOf("") }

    BackHandler(onBack = { goOn = false; onClose() })
    DisposableEffect(Unit) {
        onDispose { webView?.destroy(); webView = null }
    }

    // Main sequential loop — starts once the WebView exists
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        val queue = vm.queueForWebCheck()
        Log.d(TAG, "WEBCHECK run-started total=${queue.size}")
        var done = 0
        for (item in queue) {
            if (!goOn) break
            while (paused && goOn) delay(300)
            if (!goOn) break
            currentText = "Checking #${item.id} (${done + 1}/${queue.size})…"
            val t0 = SystemClock.elapsedRealtime()
            Log.d(TAG, "WEBCHECK item #${item.id} login-start")
            vm.webCheckResult(item.id, CookieStatus.CHECKING, "webview-login…", 0L)
            CookieInjector.clearAll()
            val pairs = CookieInjector.inject(item.raw)
            Log.d(TAG, "WEBCHECK item #${item.id} injected $pairs pairs, loading $MBASIC")
            wv.loadUrl(MBASIC)
            val verdict = awaitVerdict(wv, { goOn && !finished }, { paused })
            val latency = SystemClock.elapsedRealtime() - t0
            if (verdict == null) {
                Log.d(TAG, "WEBCHECK item #${item.id} stopped by user")
                vm.webCheckResult(item.id, CookieStatus.PENDING, "stopped", latency)
                break
            }
            if (verdict.status == CookieStatus.LIVE) vm.saveLiveCookie(item.raw)
            vm.webCheckResult(item.id, verdict.status, verdict.detail, latency)
            lastVerdict = "#${item.id} → ${verdict.status} (${verdict.detail})"
            Log.d(TAG, "WEBCHECK item #${item.id} verdict=${verdict.status} (${verdict.detail}) ${latency}ms")
            done++
            delay(BETWEEN_ACCOUNTS_MS)
        }
        finished = true
        val l = state.items.count { it.status == CookieStatus.LIVE }
        Log.d(TAG, "WEBCHECK run-finished checked=$done/${queue.size} live=$l saved=${saved.size}")
        currentText = "Done: $done/${queue.size} checked • $l live • ${saved.size} saved"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web login check") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(
                        onClick = { goOn = false; onClose() }
                    ) { Text("Close") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(currentText, style = MaterialTheme.typography.titleSmall)
            if (lastVerdict.isNotBlank()) {
                Text(lastVerdict, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Live: ${state.live} • Dead: ${state.dead} • Saved to file: ${saved.size}",
                style = MaterialTheme.typography.labelLarge
            )
            LinearProgressIndicator(
                progress = state.progress,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!finished && goOn) {
                    if (!paused) {
                        OutlinedButton(onClick = { paused = true }, modifier = Modifier.weight(1f)) {
                            Text("Pause")
                        }
                    } else {
                        Button(onClick = { paused = false }, modifier = Modifier.weight(1f)) {
                            Text("Resume")
                        }
                    }
                    OutlinedButton(onClick = { goOn = false }, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text("Back to results")
                    }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        webView = this
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.items, key = { it.id }) { CookieRow(it) }
            }
        }
    }
}

/** Poll page URL + HTML until conclusive or timeout. Null = aborted by user. */
private suspend fun awaitVerdict(
    wv: WebView,
    keepGoing: () -> Boolean,
    isPaused: () -> Boolean
): LoginVerdict.Verdict? {
    val t0 = SystemClock.elapsedRealtime()
    var url = ""
    var html = ""
    while (SystemClock.elapsedRealtime() - t0 < VERDICT_TIMEOUT_MS) {
        if (!keepGoing()) return null
        while (isPaused() && keepGoing()) delay(300)
        if (!keepGoing()) return null
        url = try {
            wv.url.orEmpty()
        } catch (_: Exception) {
            ""
        }
        html = wv.evalHtml()
        if (LoginVerdict.isConclusive(url, html)) {
            return LoginVerdict.decide(url, html)
        }
        delay(1000)
    }
    if (html.isBlank()) {
        return LoginVerdict.Verdict(CookieStatus.ERROR, "webview-timeout")
    }
    return LoginVerdict.decide(url, html)
}

private suspend fun WebView.evalHtml(): String {
    return try {
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(
                "(function(){try{return document.documentElement.outerHTML.slice(0,150000);}catch(e){return '';}})()"
            ) { v -> cont.resume(v ?: "") }
        }
    } catch (_: Exception) {
        ""
    }
}
