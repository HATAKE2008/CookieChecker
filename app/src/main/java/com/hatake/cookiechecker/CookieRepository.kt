package com.hatake.cookiechecker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Orchestrates bulk checking with bounded concurrency + pause/resume/cancel.
 * UI owns display state; the repository owns the worker job.
 */
class CookieRepository(
    private val service: CookieCheckerService = CookieCheckerService(),
    private val scope: CoroutineScope
) {
    private val _items = MutableStateFlow<List<CookieItem>>(emptyList())
    val items: StateFlow<List<CookieItem>> = _items.asStateFlow()

    private var job: Job? = null

    @Volatile var paused: Boolean = false
        private set

    val running: Boolean get() = job?.isActive == true

    fun setCookies(raw: List<String>) {
        cancel()
        _items.value = raw.mapIndexed { i, c -> CookieItem(id = i + 1, raw = c) }
    }

    fun clear() {
        cancel()
        _items.value = emptyList()
    }

    fun start(delayMs: Long, concurrency: Int, targetUrl: String, onItem: (CookieItem) -> Unit = {}) {
        if (running) return
        val snapshot = _items.value
        if (snapshot.isEmpty()) return
        paused = false
        // Reset previous results so a re-run restarts the progress cleanly
        _items.value = snapshot.map { it.copy(status = CookieStatus.PENDING, detail = "", latencyMs = 0L) }
        val permits = concurrency.coerceIn(1, 8)
        job = scope.launch {
            val semaphore = Semaphore(permits)
            val jobs = snapshot.map { item ->
                launch {
                    semaphore.withPermit {
                        // Cooperative pause
                        while (paused) delay(200)
                        if (!running) return@withPermit
                        update(item.id) { it.copy(status = CookieStatus.CHECKING) }
                        val out = service.check(item.raw, targetUrl)
                        update(item.id) {
                            it.copy(status = out.status, detail = out.detail, latencyMs = out.latencyMs)
                        }
                        onItem(_items.value.first { it.id == item.id })
                        delay(delayMs.coerceIn(0L, 5000L))
                    }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun cancel() {
        paused = false
        job?.cancel()
        job = null
    }

    private fun update(id: Int, fn: (CookieItem) -> CookieItem) {
        _items.update { list -> list.map { if (it.id == id) fn(it) else it } }
    }
}
