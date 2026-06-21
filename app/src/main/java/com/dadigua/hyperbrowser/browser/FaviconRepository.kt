package com.dadigua.hyperbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import com.dadigua.hyperbrowser.webapp.WebAppIconPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class FaviconRepository(private val context: Context) {
    private val iconDir = File(context.filesDir, "favicons")
    private val customIconDir = File(context.filesDir, "webapp-icons")
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
        val bitmap = decodeIconDataUrl(iconDataUrl) ?: return null
        iconDir.mkdirs()
        val targetFile = iconFile(pageUri)
        val saved = runCatching {
            targetFile.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }.getOrDefault(false)
        return targetFile.takeIf { saved && it.exists() && it.length() > 0 }?.absolutePath
    }

    fun saveCustomIconDataUrl(key: String, iconDataUrl: String?): String? {
        val bitmap = decodeIconDataUrl(iconDataUrl) ?: return null
        return saveCustomIconBitmap(key, bitmap)
    }

    suspend fun saveCustomIconFromUri(key: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = readContentBytes(uri, MAX_CUSTOM_ICON_INPUT_BYTES) ?: return@withContext null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        saveCustomIconBitmap(key, bitmap)
    }

    fun saveCustomIconPreset(key: String, preset: WebAppIconPreset): String? {
        val bitmap = renderPresetIcon(preset, CUSTOM_ICON_SIZE)
        return saveCustomIconBitmap(key, bitmap)
    }

    fun isCustomIconPath(iconPath: String?): Boolean {
        if (iconPath.isNullOrBlank()) return false
        val file = File(iconPath)
        return runCatching {
            val dirPath = customIconDir.canonicalPath
            val filePath = file.canonicalPath
            filePath == dirPath || filePath.startsWith("$dirPath${File.separator}")
        }.getOrDefault(false)
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

    private fun decodeIconDataUrl(iconDataUrl: String?): Bitmap? {
        if (iconDataUrl.isNullOrBlank()) return null
        val commaIndex = iconDataUrl.indexOf(',')
        if (!iconDataUrl.startsWith("data:image/", ignoreCase = true) || commaIndex <= 0) return null
        val encoded = iconDataUrl.substring(commaIndex + 1)
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > 1_500_000) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun saveCustomIconBitmap(key: String, bitmap: Bitmap): String? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        customIconDir.mkdirs()
        val normalized = normalizeIconBitmap(bitmap, CUSTOM_ICON_SIZE)
        val targetFile = File(customIconDir, "${sha256("$key:${System.currentTimeMillis()}")}.png")
        val saved = runCatching {
            targetFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.getOrDefault(false)
        return targetFile.takeIf { saved && it.exists() && it.length() > 0 && it.length() <= 1_500_000 }?.absolutePath
    }

    private fun normalizeIconBitmap(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val cropSize = minOf(source.width, source.height)
        val left = (source.width - cropSize) / 2
        val top = (source.height - cropSize) / 2
        val sourceRect = Rect(left, top, left + cropSize, top + cropSize)
        val targetRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, sourceRect, targetRect, paint)
        return output
    }

    private fun renderPresetIcon(preset: WebAppIconPreset, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = preset.backgroundColor
        }
        val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(bounds, size * 0.22f, size * 0.22f, backgroundPaint)

        val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = preset.foregroundColor
            textAlign = Paint.Align.CENTER
            textSize = size * 0.52f
            typeface = Typeface.DEFAULT_BOLD
        }
        val metrics = symbolPaint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(preset.symbol, size / 2f, baseline, symbolPaint)
        return output
    }

    private fun readContentBytes(uri: Uri, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
        } ?: return null
        return output.toByteArray().takeIf { it.isNotEmpty() }
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
        const val CUSTOM_ICON_SIZE = 512
        const val MAX_CUSTOM_ICON_INPUT_BYTES = 8 * 1024 * 1024
        const val FAVICON_USER_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"
        val linkTagRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""(\d+)x\d+""", RegexOption.IGNORE_CASE)
    }
}
