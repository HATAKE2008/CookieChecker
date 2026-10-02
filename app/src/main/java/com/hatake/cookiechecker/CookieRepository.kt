package com.hatake.cookiechecker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "CookieChecker"

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

    @Volatile private var _running: Boolean = false
    val running: Boolean get() = _running

    fun setCookies(raw: List<String>) {
        cancel()
        _items.value = raw.mapIndexed { i, c -> CookieItem(id = i + 1, raw = c) }
    }

    fun clear() {
        cancel()
        _items.value = emptyList()
    }

    fun start(delayMs: Long, concurrency: Int, targetUrl: String, onItem: (CookieItem) -> Unit = {}) {
        if (_running) {
            Log.d(TAG, "STAGE3 worker NOT started (already running)")
            return
        }
        val snapshot = _items.value
        if (snapshot.isEmpty()) {
            Log.d(TAG, "STAGE3 worker NOT started (snapshot empty)")
            return
        }
        paused = false
        // Reset previous results so a re-run restarts the progress cleanly
        _items.value = snapshot.map { it.copy(status = CookieStatus.PENDING, detail = "", latencyMs = 0L) }
        val permits = concurrency.coerceIn(1, 8)
        val safeDelay = delayMs.coerceIn(0L, 5000L)
        val url = targetUrl.ifBlank { CookieCheckerService.DEFAULT_TARGET }
        _running = true
        Log.d(TAG, "STAGE3 worker-started total=${snapshot.size} permits=$permits delayMs=$safeDelay target=$url")
        job = scope.launch {
            try {
                supervisorScope {
                    val semaphore = Semaphore(permits)
                    val jobs = snapshot.map { item ->
                        launch {
                            try {
                                semaphore.withPermit {
                                    // Cooperative pause
                                    while (paused) delay(200)
                                    if (!_running) return@withPermit
                                    Log.d(TAG, "STAGE4 item #${item.id} begins checking")
                                    update(item.id) { it.copy(status = CookieStatus.CHECKING) }
                                    val out = service.check(item.raw, url, label = "#${item.id}")
                                    update(item.id) {
                                        it.copy(status = out.status, detail = out.detail, latencyMs = out.latencyMs)
                                    }
                                    Log.d(TAG, "STAGE6 item #${item.id} Pending -> ${out.status} (${out.detail} ${out.latencyMs}ms)")
                                    _items.value.firstOrNull { it.id == item.id }?.let(onItem)
                                    delay(safeDelay)
                                }
                            } catch (t: Throwable) {
                                // One bad item must never kill the other 24
                                if (t is CancellationException) throw t
                                Log.e(TAG, "STAGE6 item #${item.id} worker-crashed: ${t.message}", t)
                                update(item.id) { it.copy(status = CookieStatus.ERROR, detail = "worker: ${t.message}") }
                            }
                        }
                    }
                    jobs.forEach { it.join() }
                }
            } finally {
                _running = false
                job = null
                val done = _items.value
                val c = done.count { it.status == CookieStatus.LIVE || it.status == CookieStatus.DEAD || it.status == CookieStatus.ERROR }
                val l = done.count { it.status == CookieStatus.LIVE }
                val d = done.count { it.status == CookieStatus.DEAD }
                val e = done.count { it.status == CookieStatus.ERROR }
                Log.d(TAG, "STAGE7 run-finished checked=$c/${done.size} live=$l dead=$d error=$e")
            }
        }
    }

    fun pause() { paused = true; Log.d(TAG, "worker paused") }
    fun resume() { paused = false; Log.d(TAG, "worker resumed") }
    fun cancel() {
        Log.d(TAG, "worker cancel requested")
        paused = false
        _running = false
        job?.cancel()
        job = null
    }

    private fun update(id: Int, fn: (CookieItem) -> CookieItem) {
        _items.update { list -> list.map { if (it.id == id) fn(it) else it } }
    }
}
