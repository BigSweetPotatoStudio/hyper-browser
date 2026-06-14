package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class FaviconRepository(context: Context) {
    private val iconDir = File(context.filesDir, "favicons")
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolveIconPath(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val pageUri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return@withContext null
        val scheme = pageUri.scheme ?: return@withContext null
        if (scheme != "http" && scheme != "https") return@withContext null
        iconDir.mkdirs()

        val targetFile = iconFile(pageUri)
        if (targetFile.exists() && targetFile.length() > 0) return@withContext targetFile.absolutePath
        cachedIconPath(pageUrl)?.let { return@withContext it }

        val candidates = (findIconCandidates(pageUrl) + defaultFaviconUrl(pageUri)).distinct()
        for (candidate in candidates.distinct()) {
            val bytes = downloadBytes(candidate, 1_500_000) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            targetFile.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            return@withContext targetFile.absolutePath
        }
        null
    }

    fun iconDataUrl(iconPath: String?): String? {
        if (iconPath.isNullOrBlank()) return null
        return iconFileDataUrl(File(iconPath))
    }

    fun iconDataUrl(iconPath: String?, pageUrl: String): String? =
        iconDataUrl(iconPath) ?: cachedIconPath(pageUrl)?.let { iconDataUrl(it) }

    fun saveIconDataUrl(pageUrl: String, iconDataUrl: String?): String? {
        if (iconDataUrl.isNullOrBlank()) return null
        val pageUri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return null
        val scheme = pageUri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val commaIndex = iconDataUrl.indexOf(',')
        if (!iconDataUrl.startsWith("data:image/", ignoreCase = true) || commaIndex <= 0) return null
        val encoded = iconDataUrl.substring(commaIndex + 1)
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > 1_500_000) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        iconDir.mkdirs()
        val targetFile = iconFile(pageUri)
        val saved = runCatching {
            targetFile.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }.getOrDefault(false)
        return targetFile.takeIf { saved && it.exists() && it.length() > 0 }?.absolutePath
    }

    fun existingIconPath(iconPath: String?): String? {
        if (iconPath.isNullOrBlank()) return null
        return File(iconPath)
            .takeIf { it.exists() && it.length() > 0 && it.length() <= 1_500_000 }
            ?.absolutePath
    }

    fun cachedIconPath(pageUrl: String): String? {
        val pageUri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return null
        val scheme = pageUri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val exact = iconFile(pageUri)
        if (exact.exists() && exact.length() > 0) return exact.absolutePath
        return alternateWwwUri(pageUri)
            ?.let { iconFile(it) }
            ?.takeIf { it.exists() && it.length() > 0 }
            ?.absolutePath
    }

    private fun iconFileDataUrl(file: File): String? {
        if (!file.exists() || file.length() <= 0 || file.length() > 1_500_000) return null
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }

    private fun findIconCandidates(pageUrl: String): List<String> {
        val body = downloadText(pageUrl, 300_000) ?: return emptyList()
        val links = linkTagRegex.findAll(body).map { it.value }.toList()
        val manifestCandidates = links
            .filter { tag -> attrValue(tag, "rel")?.lowercase().orEmpty().contains("manifest") }
            .mapNotNull { attrValue(it, "href") }
            .mapNotNull { resolveUrl(pageUrl, it) }
            .filter { isNetworkUrl(it) }
            .flatMap { findManifestIconCandidates(it) }
        val htmlCandidates = links.filter { tag ->
            val rel = attrValue(tag, "rel")?.lowercase().orEmpty()
            rel.contains("icon")
        }
            .mapNotNull { tag ->
                attrValue(tag, "href")
                    ?.let { resolveUrl(pageUrl, it) }
                    ?.takeIf { isNetworkUrl(it) }
                    ?.let { IconCandidate(it, htmlIconScore(tag)) }
            }
        return (manifestCandidates + htmlCandidates)
            .sortedByDescending { it.score }
            .map { it.url }
    }

    private fun findManifestIconCandidates(manifestUrl: String): List<IconCandidate> {
        val body = downloadText(manifestUrl, 300_000) ?: return emptyList()
        val icons = runCatching { JSONObject(body).optJSONArray("icons") }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until icons.length()) {
                val icon = icons.optJSONObject(index) ?: continue
                val src = icon.optString("src").takeIf { it.isNotBlank() } ?: continue
                val url = resolveUrl(manifestUrl, src)?.takeIf { isNetworkUrl(it) } ?: continue
                add(IconCandidate(url, manifestIconScore(icon)))
            }
        }
    }

    private fun htmlIconScore(tag: String): Int {
        val rel = attrValue(tag, "rel")?.lowercase().orEmpty()
        val sizes = attrValue(tag, "sizes").orEmpty()
        val maxSize = sizeRegex.findAll(sizes).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return maxSize + if (rel.contains("apple-touch-icon")) 512 else 0
    }

    private fun manifestIconScore(icon: JSONObject): Int {
        val sizes = icon.optString("sizes")
        val maxSize = sizeRegex.findAll(sizes).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull() ?: 0
        val type = icon.optString("type").lowercase()
        val purpose = icon.optString("purpose").lowercase()
        val typeScore = if (type.contains("png")) 256 else 0
        val purposePenalty = if (purpose.contains("maskable")) 8 else 0
        return 1024 + maxSize + typeScore - purposePenalty
    }

    private fun downloadText(url: String, maxBytes: Long): String? {
        val bytes = downloadBytes(url, maxBytes) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun downloadBytes(url: String, maxBytes: Long): ByteArray? {
        if (!isNetworkUrl(url)) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", FAVICON_USER_AGENT)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > maxBytes) return null
                val bytes = body.bytes()
                if (bytes.size > maxBytes) return null
                bytes
            }
        }.getOrNull()
    }

    private fun resolveUrl(baseUrl: String, href: String): String? =
        runCatching {
            val base = java.net.URI(baseUrl)
            base.resolve(href.trim()).toString()
        }.getOrNull()

    private fun isNetworkUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull() ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun defaultFaviconUrl(uri: Uri): String =
        uri.buildUpon().encodedPath("/favicon.ico").encodedQuery(null).fragment(null).build().toString()

    private fun iconCacheKey(uri: Uri): String {
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host.orEmpty().lowercase()}$port"
    }

    private fun iconFile(uri: Uri): File = File(iconDir, "${sha256(iconCacheKey(uri))}.png")

    private fun alternateWwwUri(uri: Uri): Uri? {
        val host = uri.host?.lowercase() ?: return null
        val alternateHost = if (host.startsWith("www.")) {
            host.removePrefix("www.")
        } else {
            "www.$host"
        }
        return uri.buildUpon().encodedAuthority(
            if (uri.port > 0) "$alternateHost:${uri.port}" else alternateHost
        ).build()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun attrValue(tag: String, name: String): String? {
        val regex = Regex("""\b${Regex.escape(name)}\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        return regex.find(tag)?.groupValues?.getOrNull(2)
    }

    private data class IconCandidate(
        val url: String,
        val score: Int
    )

    private companion object {
        const val FAVICON_USER_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"
        val linkTagRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""(\d+)x\d+""", RegexOption.IGNORE_CASE)
    }
}
