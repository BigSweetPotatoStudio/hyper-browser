package com.dadigua.hyperbrowser.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserBackupManagerTest {
    @Test
    fun previewBrowserBackupImportCountsBookmarksAndWebApps() {
        val preview = previewBrowserBackupImport(
            backupJson(
                bookmarks = listOf("https://example.com", "https://android.com"),
                webApps = listOf("app-1")
            )
        )

        assertEquals(2, preview.bookmarks)
        assertEquals(1, preview.webApps)
    }

    @Test(expected = IllegalStateException::class)
    fun previewBrowserBackupImportRejectsUnsupportedFormat() {
        previewBrowserBackupImport("""{"type":"other","version":2,"files":{}}""")
    }

    @Test(expected = IllegalStateException::class)
    fun previewBrowserBackupImportRejectsUnsupportedSchema() {
        previewBrowserBackupImport(
            """
            {
              "type": "hyper-browser-backup",
              "version": 2,
              "files": {
                "bookmarks.json": {
                  "schemaVersion": 1,
                  "bookmarks": {},
                  "bookmarkTombstones": {}
                },
                "webapps.json": {
                  "schemaVersion": 2,
                  "apps": {},
                  "appTombstones": {}
                },
                "launcher.json": {
                  "rev": {"updatedAt": 0, "deviceId": ""}
                }
              }
            }
            """.trimIndent()
        )
    }

    private fun backupJson(bookmarks: List<String>, webApps: List<String>): String {
        val bookmarkItems = bookmarks.joinToString(",") { url ->
            """"$url":{"url":"$url","title":"","rev":{"updatedAt":1,"deviceId":"test"}}"""
        }
        val webAppItems = webApps.joinToString(",") { id ->
            """"$id":{"id":"$id","name":"$id","startUrl":"https://example.com","rev":{"updatedAt":1,"deviceId":"test"}}"""
        }
        return """
            {
              "type": "hyper-browser-backup",
              "version": 2,
              "files": {
                "bookmarks.json": {
                  "schemaVersion": 2,
                  "bookmarks": {$bookmarkItems},
                  "bookmarkTombstones": {}
                },
                "webapps.json": {
                  "schemaVersion": 2,
                  "apps": {$webAppItems},
                  "appTombstones": {}
                },
                "launcher.json": {
                  "rev": {"updatedAt": 0, "deviceId": ""}
                }
              }
            }
            """.trimIndent()
    }
}
