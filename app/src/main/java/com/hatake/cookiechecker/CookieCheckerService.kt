package com.hatake.cookiechecker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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

    suspend fun check(cookie: String, targetUrl: String): CheckOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(targetUrl.ifBlank { DEFAULT_TARGET })
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", cookie)
                    .get()
                    .build()

                client.newCall(request).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    val location = resp.header("Location").orEmpty()
                    val code = resp.code

                    // --- Redirect-based verdicts (cheap, no body read) ---
                    if (location.contains("/login", ignoreCase = true) ||
                        location.contains("checkpoint", ignoreCase = true)
                    ) {
                        return@withContext CheckOutcome(CookieStatus.DEAD, "redirect->$location", latency)
                    }
                    if (location.contains("home.php", ignoreCase = true)) {
                        return@withContext CheckOutcome(CookieStatus.LIVE, "redirect->$location", latency)
                    }
                    // 3xx without a login location but pointing into home = live
                    if (code in 300..399 && location.isNotBlank()) {
                        return@withContext CheckOutcome(CookieStatus.DEAD, "redirect->$location", latency)
                    }

                    // --- Body-based verdicts ---
                    val body = try {
                        resp.body?.string().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    classifyBody(body, latency)
                }
            } catch (e: SocketTimeoutException) {
                CheckOutcome(CookieStatus.ERROR, "timeout: ${e.message}", System.currentTimeMillis() - start)
            } catch (e: SSLException) {
                CheckOutcome(CookieStatus.ERROR, "ssl: ${e.message}", System.currentTimeMillis() - start)
            } catch (e: java.io.IOException) {
                CheckOutcome(CookieStatus.ERROR, "network: ${e.message}", System.currentTimeMillis() - start)
            } catch (e: Exception) {
                CheckOutcome(CookieStatus.ERROR, "error: ${e.message}", System.currentTimeMillis() - start)
            }
        }

    private fun classifyBody(body: String, latency: Long): CheckOutcome {
        if (body.isBlank()) return CheckOutcome(CookieStatus.ERROR, "empty-body", latency)
        val lower = body.lowercase()
        // Dead markers first (login page)
        if (lower.contains("mbasic_inline_login_button") ||
            lower.contains("name=\"login\"") ||
            lower.contains("name='login'") ||
            (lower.contains("/login") && lower.contains("password"))
        ) {
            return CheckOutcome(CookieStatus.DEAD, "login-page", latency)
        }
        // Live markers
        if (lower.contains("mbasic_logout_button") ||
            lower.contains("mbasic_logout") ||
            lower.contains("home.php") ||
            lower.contains("name=\"logout\"") ||
            lower.contains("composer") && lower.contains("what's on your mind")
        ) {
            return CheckOutcome(CookieStatus.LIVE, "authenticated", latency)
        }
        // Heuristic fallback: checkpoint wording = dead
        if (lower.contains("checkpoint")) {
            return CheckOutcome(CookieStatus.DEAD, "checkpoint", latency)
        }
        return CheckOutcome(CookieStatus.DEAD, "no-auth-markers", latency)
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
