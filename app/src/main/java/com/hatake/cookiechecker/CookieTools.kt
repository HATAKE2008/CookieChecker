package com.hatake.cookiechecker

/** Cookie string helpers shared by both check engines. */
object CookieTools {

    private val C_USER_VALUE =
        Regex("c_user\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)

    // Attributes that must never be sent back in a Cookie header / injection
    private val SKIP_ATTRS = setOf(
        "expires", "max-age", "maxage", "path", "domain", "samesite",
        "secure", "httponly", "priority", "partitioned", "sameparty"
    )

    /** Extract the c_user id (requires 7+ digits to avoid false positives). */
    fun extractUserId(raw: String): String {
        val v = C_USER_VALUE.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (v.length < 7) return ""
        if (!v.all { it.isDigit() }) return ""
        return v
    }

    /** Split into clean `name=value` pairs, dropping cookie attributes. */
    fun pairs(raw: String): List<String> {
        val out = mutableListOf<String>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            val name = (if (eq > 0) part.substring(0, eq) else part).trim()
            if (name.isEmpty()) continue
            if (SKIP_ATTRS.contains(name.lowercase())) continue
            if (eq > 0) {
                val value = part.substring(eq + 1).trim()
                if (value.isEmpty()) continue
                out += "$name=$value"
            }
        }
        return out
    }

    /** Safe value for the HTTP `Cookie` request header. */
    fun sanitizeHeader(raw: String): String = pairs(raw).joinToString("; ")
}
