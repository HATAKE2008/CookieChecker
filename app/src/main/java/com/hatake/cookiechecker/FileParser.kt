package com.hatake.cookiechecker

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Extracts cookie strings from messy input: pasted text, .txt/.csv cells,
 * or .xlsx sheets (parsed WITHOUT Apache POI to keep the APK small —
 * XLSX is just a ZIP of XML files, parsed with stdlib ZipInputStream).
 *
 * A "valid" cookie must contain at least `c_user` + (`xs` or `datr`).
 */
object FileParser {

    private val C_USER = Regex("c_user\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("[A-Za-z0-9_\\-]+\\s*=\\s*[^;\\r\\n]+")

    /** Extract cookie candidates from any free-form text. */
    fun extractCookiesFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // Split on newlines first; each line may still hold a cookie buried in noise.
        val out = linkedSetOf<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim().trim(',', '"', '\'', '|', '\t')
            if (line.isEmpty()) continue
            // Direct hit: whole line is a cookie
            if (isCookie(line)) {
                out += normalize(line)
                continue
            }
            // Buried hit: find longest token-run containing c_user
            extractBuried(line)?.let { out += it }
        }
        return out.toList()
    }

    private fun isCookie(s: String): Boolean {
        if (s.length < 20 || s.length > 8000) return false
        if (!s.contains("c_user", ignoreCase = true)) return false
        val lower = s.lowercase()
        if (!lower.contains("xs=") && !lower.contains("datr=")) return false
        return C_USER.containsMatchIn(s)
    }

    /** Try to carve a cookie substring out of a noisy line (JSON, CSV row, log line...). */
    private fun extractBuried(line: String): String? {
        val idx = line.indexOf("c_user", ignoreCase = true)
        if (idx == -1) return null
        // Expand left/right to token boundaries
        var start = idx
        while (start > 0 && line[start - 1] != '\n' && line[start - 1] != '"' && line[start - 1] != '\'') start--
        var end = idx
        while (end < line.length && line[end] != '\n' && line[end] != '"' && line[end] != '\'') end++
        var candidate = line.substring(start, end).trim().trimEnd(',', ';')
        // Keep only k=v pairs joined by ';'
        val tokens = TOKEN.findAll(candidate).map { it.value.trim() }.toList()
        if (tokens.size < 2) return null
        candidate = tokens.joinToString("; ")
        return if (isCookie(candidate)) normalize(candidate) else null
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s*;\\s*"), "; ").trimEnd(';', ' ')

    /** Read a SAF [Uri] (.txt/.csv/.xlsx) and return parsed cookies. Never throws. */
    suspend fun parseUri(
        resolver: ContentResolver,
        uri: Uri
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val name = displayName(resolver, uri).lowercase()
            resolver.openInputStream(uri)?.use { ins ->
                val cookies = when {
                    name.endsWith(".xlsx") -> parseXlsx(ins)
                    else -> parseText(ins)
                }
                Result.success(cookies)
            } ?: Result.success(emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseText(ins: java.io.InputStream): List<String> {
        BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { br ->
            return extractCookiesFromText(br.readText())
        }
    }

    /**
     * Minimal XLSX reader: unzips sharedStrings.xml + sheet1.xml and joins
     * inline/shared strings per row. Handles the common single-sheet export.
     */
    private fun parseXlsx(ins: java.io.InputStream): List<String> {
        val files = mutableMapOf<String, String>()
        ZipInputStream(ins).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName.endsWith("sharedStrings.xml") || entryName.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    files[entryName] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (files.isEmpty()) return emptyList()

        // Shared strings table
        val shared = mutableListOf<String>()
        files.entries.firstOrNull { it.key.endsWith("sharedStrings.xml") }?.value?.let { xml ->
            val tRe = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
            tRe.findAll(xml).forEach { m ->
                shared += unescapeXml(m.groupValues[1])
            }
        }

        // Sheet rows: collect <row>...</row>, resolve <v> indices + <t> inline
        val sheetXml = files.entries.firstOrNull { it.key.contains("worksheets/sheet") }?.value
            ?: return extractCookiesFromText(shared.joinToString("\n"))
        val rowRe = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRe = Regex("<c[^>]*?(t=\"([^\"]+)\")?[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val vRe = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val tRe = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)

        val rows = mutableListOf<String>()
        for (row in rowRe.findAll(sheetXml)) {
            val cells = mutableListOf<String>()
            for (cell in cellRe.findAll(row.groupValues[1])) {
                val type = cell.groupValues[2]
                val inner = cell.groupValues[3]
                val value = when (type) {
                    "s" -> vRe.find(inner)?.groupValues?.get(1)?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr", "str" -> tRe.find(inner)?.groupValues?.get(1)?.let(::unescapeXml).orEmpty()
                    else -> vRe.find(inner)?.groupValues?.get(1).orEmpty()
                        .let { num -> tRe.find(inner)?.groupValues?.get(1)?.let(::unescapeXml) ?: num }
                }
                cells += value
            }
            if (cells.any { it.isNotBlank() }) rows += cells.joinToString(" | ")
        }
        // Fallback: also scan shared strings directly
        rows += shared
        return extractCookiesFromText(rows.joinToString("\n"))
    }

    private fun unescapeXml(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) return c.getString(idx) ?: "import"
            }
        }
        return uri.lastPathSegment ?: "import"
    }
}
