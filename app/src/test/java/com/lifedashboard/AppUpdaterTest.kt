package com.lifedashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdaterTest {
    private val hash = "a".repeat(64)
    private fun release() = JSONObject().put("tag_name", "v0.2.0").put("draft", false).put("prerelease", false)
        .put("body", "변경 내용").put("assets", JSONArray().put(JSONObject().put("name", "LifeDashboard.apk")
            .put("size", 100).put("browser_download_url", "${UpdateProject.RELEASES}/download/v0.2.0/LifeDashboard.apk")))
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("applicationId", "com.lifedashboard")
        .put("versionCode", 2).put("versionName", "0.2.0").put("minSdk", 35)
        .put("apkName", "LifeDashboard.apk").put("apkSize", 100).put("sha256", hash)
    private fun rejected(block: () -> Unit) { try { block(); fail("must reject invalid update") } catch (_: IllegalArgumentException) { } }
    @Test fun versionCodeControlsUpdatesRatherThanTextVersion() {
        val parsed = ReleaseParser.parse(release(), manifest())
        assertTrue(parsed.isNewerThan(1)); assertFalse(parsed.isNewerThan(2)); assertFalse(parsed.isNewerThan(3))
        assertEquals("변경 내용", parsed.notes)
    }
    @Test fun rejectsForeignPackage() { rejected { ReleaseParser.parse(release(), manifest().put("applicationId", "other.app")) } }
    @Test fun rejectsTagManifestMismatch() { rejected { ReleaseParser.parse(release(), manifest().put("versionName", "0.3.0")) } }
    @Test fun rejectsPrereleaseAndDraft() {
        rejected { ReleaseParser.parse(release().put("prerelease", true), manifest()) }
        rejected { ReleaseParser.parse(release().put("draft", true), manifest()) }
    }
    @Test fun rejectsChangedAssetUrl() {
        val release = release()
        release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/LifeDashboard.apk")
        rejected { ReleaseParser.parse(release, manifest()) }
    }
    @Test fun rejectsMissingHashAndWrongSize() {
        rejected { ReleaseParser.parse(release(), manifest().put("sha256", "")) }
        rejected { ReleaseParser.parse(release(), manifest().put("apkSize", 101)) }
    }
    @Test fun rejectsUnsupportedTag() {
        rejected { ReleaseParser.parse(release().put("tag_name", "../../file"), manifest()) }
    }
    @Test fun completedDownloadCanBeReusedButTruncatedOrModifiedFileCannot() {
        val file = Files.createTempFile("apk-check", ".apk").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            file.writeBytes(bytes)
            assertTrue(ApkChecks.matches(file, 4, expected))
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(ApkChecks.matches(file, 4, expected))
            file.writeBytes(byteArrayOf(4, 3, 2, 1))
            assertFalse(ApkChecks.matches(file, 4, expected))
        } finally { file.delete() }
    }
}
