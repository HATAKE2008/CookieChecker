package com.hatake.cookiechecker

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CookieChecker"

class CookieViewModel(app: Application) : AndroidViewModel(app) {

    private val service = CookieCheckerService()
    private val repository = CookieRepository(service, viewModelScope)

    private val liveFile = File(app.filesDir, "live_cookies.txt")

    private val _savedLive = MutableStateFlow<List<String>>(emptyList())
    val savedLive: StateFlow<List<String>> = _savedLive.asStateFlow()

    private val _ui = MutableStateFlow(CookieUiState())
    val ui: StateFlow<CookieUiState> = _ui.asStateFlow()

    init {
        // Load previously saved LIVE cookies
        viewModelScope.launch { _savedLive.value = readSavedLive() }
        // Mirror repository items into UI state
        viewModelScope.launch {
            repository.items.collect { items ->
                _ui.update { it.copy(items = items) }
            }
        }
        viewModelScope.launch {
            // Poll running/paused flags while work is active
            while (true) {
                kotlinx.coroutines.delay(300)
                _ui.update {
                    if (it.isRunning != repository.running ||
                        it.isPaused != repository.paused
                    ) it.copy(isRunning = repository.running, isPaused = repository.paused)
                    else it
                }
            }
        }
    }

    // ---- Input ----
    fun onInputChange(v: String) = _ui.update { it.copy(inputText = v, errorMessage = null) }
    fun onTargetChange(v: String) = _ui.update { it.copy(targetUrl = v) }
    fun onDelayChange(v: Long) = _ui.update { it.copy(delayMs = v.coerceIn(0L, 5000L)) }
    fun onConcurrencyChange(v: Int) = _ui.update { it.copy(concurrency = v.coerceIn(1, 8)) }
    fun onFilterChange(f: ResultFilter) = _ui.update { it.copy(filter = f) }
    fun consumeMessage() = _ui.update { it.copy(errorMessage = null, exportedMessage = null) }

    fun loadFromInput() {
        val cookies = FileParser.extractCookiesFromText(_ui.value.inputText)
        if (cookies.isEmpty()) {
            _ui.update { it.copy(errorMessage = "No valid cookies found. Need c_user + xs/datr.") }
            return
        }
        repository.setCookies(cookies)
        _ui.update { it.copy(errorMessage = null) }
    }

    fun importFile(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val res = FileParser.parseUri(resolver, uri)
            res.onSuccess { cookies ->
                if (cookies.isEmpty()) {
                    _ui.update { it.copy(errorMessage = "No valid cookies found in file.") }
                } else {
                    repository.setCookies(cookies)
                    _ui.update {
                        it.copy(
                            errorMessage = null,
                            exportedMessage = "Imported ${cookies.size} cookies"
                        )
                    }
                }
            }.onFailure { e ->
                _ui.update { it.copy(errorMessage = "Import failed: ${e.message}") }
            }
        }
    }

    // ---- Actions ----
    fun start() {
        val repoCount = repository.items.value.size
        Log.d(TAG, "STAGE1 start-clicked repoCount=$repoCount inputLen=${_ui.value.inputText.length} running=${repository.running}")
        // NOTE: read repository.items.value directly (synchronous).
        // _ui.value.items is only an async mirror and is still empty
        // right after parsing, which used to abort every first Start tap.
        if (repository.items.value.isEmpty()) {
            val parsed = FileParser.extractCookiesFromText(_ui.value.inputText)
            Log.d(TAG, "STAGE2 parsed-from-input count=${parsed.size}")
            if (parsed.isEmpty()) {
                Log.d(TAG, "STAGE2 abort: no valid cookies found")
                _ui.update { it.copy(errorMessage = "Paste cookies or import a file first.") }
                return
            }
            repository.setCookies(parsed)
        }
        Log.d(TAG, "STAGE2 items-received count=${repository.items.value.size}")
        if (repository.running) {
            Log.d(TAG, "STAGE2 abort: checker already running")
            return // already checking
        }
        _ui.update { it.copy(isRunning = true, isPaused = false, errorMessage = null) }
        repository.start(
            delayMs = _ui.value.delayMs,
            concurrency = _ui.value.concurrency,
            targetUrl = _ui.value.targetUrl.ifBlank { CookieCheckerService.DEFAULT_TARGET }
        )
    }

    fun pause() {
        repository.pause()
        _ui.update { it.copy(isPaused = true) }
    }

    fun resume() {
        repository.resume()
        _ui.update { it.copy(isPaused = false) }
    }

    fun cancel() {
        repository.cancel()
        _ui.update { it.copy(isRunning = false, isPaused = false) }
    }

    fun clear() {
        repository.clear()
        _ui.update { it.copy(inputText = "", isRunning = false, isPaused = false, errorMessage = null, exportedMessage = null) }
    }

    fun liveCookiesText(): String =
        _ui.value.items.filter { it.status == CookieStatus.LIVE }.joinToString("\n") { it.raw }

    fun exportCsvText(): String = buildString {
        appendLine("id,status,detail,latency_ms,cookie")
        _ui.value.items.forEach {
            val safe = "\"" + it.raw.replace("\"", "\"\"") + "\""
            appendLine("${it.id},${it.status},${it.detail},${it.latencyMs},$safe")
        }
    }

    fun markExported(path: String) {
        _ui.update { it.copy(exportedMessage = "Exported: $path") }
    }

    // ---- WebView login-check engine support ----
    fun webCheckResult(id: Int, status: CookieStatus, detail: String, latencyMs: Long) {
        repository.setResult(id, status, detail, latencyMs)
    }

    fun queueForWebCheck(): List<CookieItem> = repository.items.value

    /** Make sure pasted text is parsed into the queue. False = nothing to check. */
    fun ensureQueueFromInput(): Boolean {
        if (repository.items.value.isNotEmpty()) return true
        val parsed = FileParser.extractCookiesFromText(_ui.value.inputText)
        if (parsed.isEmpty()) return false
        repository.setCookies(parsed)
        return true
    }

    // ---- Saved LIVE cookies (persisted to live_cookies.txt) ----
    private suspend fun readSavedLive(): List<String> = withContext(Dispatchers.IO) {
        try {
            if (liveFile.exists()) {
                liveFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "read saved live failed: ${e.message}")
            emptyList()
        }
    }

    fun saveLiveCookie(raw: String) {
        val cookie = raw.trim()
        if (cookie.isEmpty() || _savedLive.value.contains(cookie)) return
        _savedLive.update { it + cookie }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                liveFile.appendText(cookie + "\n")
                Log.d(TAG, "WEBCHECK saved live cookie (${_savedLive.value.size} total)")
            } catch (e: Exception) {
                Log.e(TAG, "save live failed: ${e.message}")
            }
        }
    }

    fun savedLiveText(): String = _savedLive.value.joinToString("\n")
}
