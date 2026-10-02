package com.hatake.cookiechecker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "CookieChecker"

/**
 * Validation engine. Sends each cookie to mbasic endpoint with a mobile
 * User-Agent and classifies the response as LIVE / DEAD / ERROR.
 *
 * Redirect following is DISABLED so we can inspect `Location` headers
 * (`/login`, `/checkpoint` = DEAD, `home.php` = LIVE).
 */
class CookieCheckerService(
    client: OkHttpClient? = null
) {
    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(cookie: String, targetUrl: String, label: String = ""): CheckOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val url = targetUrl.ifBlank { DEFAULT_TARGET }
            val header = CookieTools.sanitizeHeader(cookie)
            val userId = CookieTools.extractUserId(cookie)
            Log.d(TAG, "STAGE5 $label request started target=$url pairs=${header.split(';').size} userId=${userId.takeLast(4).padStart(userId.length, '*')}")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", header)
                    .get()
                    .build()

                client.newCall(request).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    val location = resp.header("Location").orEmpty()
                    val code = resp.code
                    Log.d(TAG, "STAGE5 $label response code=$code location=$location")
                    val body = try {
                        resp.body?.string().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    Log.d(TAG, "STAGE5 $label body len=${body.length} snippet=${body.take(220).replace(Regex("\\s+"), " ")}")
                    val outcome = decide(code, location, body, latency, userId)
                    Log.d(TAG, "STAGE5 $label verdict=${outcome.status} (${outcome.detail}) latency=${outcome.latencyMs}ms")
                    return@withContext outcome
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // pause/cancel must propagate
                val msg = when (t) {
                    is SocketTimeoutException -> "timeout: ${t.message}"
                    is SSLException -> "ssl: ${t.message}"
                    is java.io.IOException -> "network: ${t.message}"
                    else -> "error: ${t.message}"
                }
                Log.e(TAG, "STAGE5 $label request failed: $msg")
                CheckOutcome(CookieStatus.ERROR, msg, System.currentTimeMillis() - start)
            }
        }

    private fun decide(code: Int, location: String, body: String, latency: Long, userId: String = ""): CheckOutcome {
        // --- Redirect-based verdicts (cheap, no body read) ---
        if (location.contains("/login", ignoreCase = true) ||
            location.contains("checkpoint", ignoreCase = true)
        ) {
            return CheckOutcome(CookieStatus.DEAD, "redirect->$location", latency)
        }
        if (location.contains("home.php", ignoreCase = true)) {
            return CheckOutcome(CookieStatus.LIVE, "redirect->$location", latency)
        }
        // 3xx elsewhere (not home/login) = not authenticated
        if (code in 300..399 && location.isNotBlank()) {
            return CheckOutcome(CookieStatus.DEAD, "redirect->$location", latency)
        }
        // --- Body-based verdicts ---
        return classifyBody(body, latency, userId)
    }

    private fun classifyBody(body: String, latency: Long, userId: String = ""): CheckOutcome {
        if (body.isBlank()) return CheckOutcome(CookieStatus.ERROR, "empty-body", latency)
        val lower = body.lowercase()
        // STRONG DEAD: password field exists (never on a logged-in home)
        if (lower.contains("type=\"password\"") || lower.contains("type='password'") ||
            lower.contains("name=\"pass\"") || lower.contains("name='pass'") ||
            lower.contains("id=\"login_form\"") || lower.contains("id='login_form'")
        ) {
            return CheckOutcome(CookieStatus.DEAD, "login-form", latency)
        }
        // Dead markers (login page)
        if (lower.contains("mbasic_inline_login_button") ||
            lower.contains("name=\"login\"") ||
            lower.contains("name='login'") ||
            (lower.contains("/login") && lower.contains("password"))
        ) {
            return CheckOutcome(CookieStatus.DEAD, "login-page", latency)
        }
        // STRONG LIVE: our own user id embedded in the page
        if (userId.isNotEmpty() && body.contains(userId)) {
            return CheckOutcome(CookieStatus.LIVE, "user-id-match", latency)
        }
        // Live markers
        if (lower.contains("mbasic_logout_button") ||
            lower.contains("mbasic_logout") ||
            lower.contains("home.php") ||
            lower.contains("name=\"logout\"") ||
            lower.contains("log out") ||
            lower.contains("composer") && lower.contains("what's on your mind")
        ) {
            return CheckOutcome(CookieStatus.LIVE, "authenticated", latency)
        }
        // Heuristic fallback: checkpoint wording = dead
        if (lower.contains("checkpoint")) {
            return CheckOutcome(CookieStatus.DEAD, "checkpoint", latency)
        }
        return CheckOutcome(CookieStatus.DEAD, "no-auth-markers len=${body.length}", latency)
    }

    data class CheckOutcome(
        val status: CookieStatus,
        val detail: String,
        val latencyMs: Long
    )

    companion object {
        const val DEFAULT_TARGET = "https://mbasic.facebook.com/"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    }
}
