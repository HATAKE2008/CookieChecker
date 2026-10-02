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

    /** Returns number of pairs injected. */
    fun inject(raw: String): Int {
        val cookieStr = pickSegment(raw)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookies(null)
        var count = 0
        for (item in cookieStr.split(';')) {
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            val k = item.substring(0, eq).trim()
            val v = item.substring(eq + 1).trim()
            if (k.isEmpty()) continue
            cm.setCookie("https://.facebook.com", "$k=$v; Path=/; Domain=.facebook.com")
            count++
        }
        cm.flush()
        return count
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
