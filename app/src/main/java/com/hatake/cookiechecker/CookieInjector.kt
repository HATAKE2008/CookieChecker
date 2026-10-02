package com.hatake.cookiechecker

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "CookieChecker"

/**
 * Bookmarklet-equivalent cookie injection for WebViews:
 * pipe-split picks the c_user segment, then each k=v pair is set with
 * Domain=.facebook.com + Path=/.
 */
object CookieInjector {

    fun pickSegment(raw: String): String {
        if (!raw.contains('|')) return raw
        for (part in raw.split('|')) {
            if (part.contains("c_user=")) return part
        }
        return raw
    }

    /** Returns number of pairs injected. Awaits every setCookie before returning. */
    suspend fun inject(raw: String): Int {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        var count = 0
        for (pair in CookieTools.pairs(pickSegment(raw))) {
            if (awaitSet(SETTING_URL, "$pair; Path=/; Domain=.facebook.com")) count++
        }
        cm.flush()
        return count
    }

    private const val SETTING_URL = "https://mbasic.facebook.com"

    private suspend fun awaitSet(url: String, value: String): Boolean {
        return try {
            suspendCancellableCoroutine { cont ->
                try {
                    CookieManager.getInstance().setCookie(url, value) { ok ->
                        cont.resume(ok)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "setCookie failed: ${t.message}")
                    cont.resume(false)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Clear all cookies, suspending until done. */
    suspend fun clearAll() {
        suspendCancellableCoroutine { cont ->
            try {
                CookieManager.getInstance().removeAllCookies { cont.resume(Unit) }
            } catch (t: Throwable) {
                Log.e(TAG, "cookie clear failed: ${t.message}")
                cont.resume(Unit)
            }
        }
    }
}
