package com.hatake.cookiechecker

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CookieViewModel : ViewModel() {

    private val service = CookieCheckerService()
    private val repository = CookieRepository(service, viewModelScope)

    private val _ui = MutableStateFlow(CookieUiState())
    val ui: StateFlow<CookieUiState> = _ui.asStateFlow()

    init {
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
        if (_ui.value.items.isEmpty()) loadFromInput()
        if (_ui.value.items.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Paste cookies or import a file first.") }
            return
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
}
