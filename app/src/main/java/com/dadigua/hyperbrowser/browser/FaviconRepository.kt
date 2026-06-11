package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

        val candidates = findIconCandidates(pageUrl).ifEmpty { listOf(defaultFaviconUrl(pageUri)) }
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

    suspend fun clearCache(): Int = withContext(Dispatchers.IO) {
        if (!iconDir.exists()) return@withContext 0
        iconDir.listFiles()
            ?.count { file -> file.isFile && runCatching { file.delete() }.getOrDefault(false) }
            ?: 0
    }

    private fun iconFileDataUrl(file: File): String? {
        if (!file.exists() || file.length() <= 0 || file.length() > 1_500_000) return null
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }

    private fun findIconCandidates(pageUrl: String): List<String> {
        val body = downloadText(pageUrl, 300_000) ?: return emptyList()
        val links = linkTagRegex.findAll(body).map { it.value }.toList()
        val iconLinks = links.filter { tag ->
            val rel = attrValue(tag, "rel")?.lowercase().orEmpty()
            rel.contains("icon")
        }
        return iconLinks
            .sortedByDescending { iconScore(it) }
            .mapNotNull { attrValue(it, "href") }
            .mapNotNull { resolveUrl(pageUrl, it) }
    }

    private fun iconScore(tag: String): Int {
        val rel = attrValue(tag, "rel")?.lowercase().orEmpty()
        val sizes = attrValue(tag, "sizes").orEmpty()
        val maxSize = sizeRegex.findAll(sizes).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return maxSize + if (rel.contains("apple-touch-icon")) 512 else 0
    }

    private fun downloadText(url: String, maxBytes: Long): String? {
        val bytes = downloadBytes(url, maxBytes) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun downloadBytes(url: String, maxBytes: Long): ByteArray? {
        if (!isHttpUrl(url)) return null
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 HyperBrowser/0.1")
                .build()
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

    private fun isHttpUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun resolveUrl(baseUrl: String, href: String): String? =
        runCatching {
            val base = java.net.URI(baseUrl)
            base.resolve(href.trim()).toString()
        }.getOrNull()?.takeIf(::isHttpUrl)

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

    private companion object {
        val linkTagRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""(\d+)x\d+""", RegexOption.IGNORE_CASE)
    }
}
