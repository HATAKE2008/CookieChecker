package com.hatake.cookiechecker

/** Validation outcome for a single cookie string. */
enum class CookieStatus {
    PENDING,
    CHECKING,
    LIVE,
    DEAD,
    ERROR
}

/** One cookie entry in the checking queue. */
data class CookieItem(
    val id: Int,
    val raw: String,
    val status: CookieStatus = CookieStatus.PENDING,
    val detail: String = "",
    val latencyMs: Long = 0L
)

/** Result list filter. */
enum class ResultFilter {
    ALL, LIVE, DEAD
}
