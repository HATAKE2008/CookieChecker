package com.hatake.cookiechecker

/**
 * Single source of truth for the UI. Emitted via StateFlow from [CookieViewModel].
 */
data class CookieUiState(
    val inputText: String = "",
    val targetUrl: String = CookieCheckerService.DEFAULT_TARGET,
    val delayMs: Long = 500L,
    val concurrency: Int = 4,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val items: List<CookieItem> = emptyList(),
    val filter: ResultFilter = ResultFilter.ALL,
    val errorMessage: String? = null,
    val exportedMessage: String? = null
) {
    val total: Int get() = items.size
    val checked: Int get() = items.count { it.status == CookieStatus.LIVE || it.status == CookieStatus.DEAD || it.status == CookieStatus.ERROR }
    val live: Int get() = items.count { it.status == CookieStatus.LIVE }
    val dead: Int get() = items.count { it.status == CookieStatus.DEAD }
    val errors: Int get() = items.count { it.status == CookieStatus.ERROR }
    val progress: Float get() = if (total == 0) 0f else checked.toFloat() / total.toFloat()

    val visibleItems: List<CookieItem>
        get() = when (filter) {
            ResultFilter.ALL -> items
            ResultFilter.LIVE -> items.filter { it.status == CookieStatus.LIVE }
            ResultFilter.DEAD -> items.filter { it.status == CookieStatus.DEAD || it.status == CookieStatus.ERROR }
        }
}
