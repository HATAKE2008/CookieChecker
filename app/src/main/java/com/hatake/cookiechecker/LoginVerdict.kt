package com.hatake.cookiechecker

/**
 * Shared LIVE/DEAD verdict from a loaded page URL + HTML.
 * Used by the WebView login-checker (HTML comes from JS, so it may be
 * JSON-escaped — markers are chosen to survive that).
 */
object LoginVerdict {

    data class Verdict(val status: CookieStatus, val detail: String)

    fun decide(url: String, html: String): Verdict {
        val u = url.lowercase()
        val lower = html.lowercase()
        // URL verdicts are authoritative (unescaped, final after redirects)
        if (u.contains("/login") || u.contains("checkpoint")) {
            return Verdict(CookieStatus.DEAD, "login-page")
        }
        if (u.contains("home.php")) {
            return Verdict(CookieStatus.LIVE, "home.php")
        }
        // Authenticated markers
        if (lower.contains("mbasic_logout_button") ||
            lower.contains("mbasic_logout") ||
            lower.contains("home.php")
        ) {
            return Verdict(CookieStatus.LIVE, "authenticated")
        }
        // Login-page markers (\" escaped form included for JS-bridge HTML)
        if (lower.contains("mbasic_inline_login_button") ||
            (lower.contains("name=") && lower.contains("login")) ||
            (lower.contains("/login") && lower.contains("password"))
        ) {
            return Verdict(CookieStatus.DEAD, "login-page")
        }
        if (lower.contains("checkpoint")) {
            return Verdict(CookieStatus.DEAD, "checkpoint")
        }
        return Verdict(CookieStatus.DEAD, "no-auth-markers")
    }

    /** True when the page has settled enough to trust the verdict. */
    fun isConclusive(url: String, html: String): Boolean {
        val u = url.lowercase()
        val lower = html.lowercase()
        if (u.contains("/login") || u.contains("checkpoint") || u.contains("home.php")) return true
        if (lower.contains("mbasic_logout_button") || lower.contains("mbasic_logout")) return true
        if (lower.contains("mbasic_inline_login_button")) return true
        if (lower.contains("name=") && lower.contains("login")) return true
        return false
    }
}
