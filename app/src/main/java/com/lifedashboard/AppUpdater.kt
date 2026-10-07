package com.lifedashboard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal object UpdateProject {
    const val REPOSITORY = "cafealpa/lifeEventApp"
    const val RELEASES = "https://github.com/$REPOSITORY/releases"
    const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val APK_NAME = "LifeDashboard.apk"
}

data class AppRelease(
    val versionCode: Long, val versionName: String, val minSdk: Int,
    val apkUrl: String, val sha256: String, val size: Long, val notes: String, val pageUrl: String
) {
    fun isNewerThan(installedCode: Long) = versionCode > installedCode
}

internal object ReleaseParser {
    fun asset(release: JSONObject, name: String): JSONObject {
        val assets = release.getJSONArray("assets")
        return (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == name }
            ?: throw IOException("릴리즈에 $name 파일이 없거나 중복돼 있어요")
    }
    fun assetUrl(release: JSONObject, asset: JSONObject): String {
        val tag = release.getString("tag_name")
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) { "지원하지 않는 릴리즈 버전 형식이에요" }
        val url = asset.getString("browser_download_url")
        require(url == "${UpdateProject.RELEASES}/download/$tag/${asset.getString("name")}") { "릴리즈 파일 주소가 올바르지 않아요" }
        return url
    }
    fun parse(release: JSONObject, manifest: JSONObject): AppRelease {
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "정식 릴리즈만 설치할 수 있어요" }
        require(manifest.getInt("schemaVersion") == 1 && manifest.getString("applicationId") == "com.lifedashboard") { "이 앱의 업데이트 정보가 아니에요" }
        val name = manifest.getString("versionName")
        require(release.getString("tag_name") == "v$name") { "버전 정보가 일치하지 않아요" }
        require(manifest.getString("apkName") == UpdateProject.APK_NAME) { "APK 파일 이름이 올바르지 않아요" }
        val apk = asset(release, UpdateProject.APK_NAME)
        val size = manifest.getLong("apkSize")
        require(size in 1..268435456 && size == apk.getLong("size")) { "APK 크기 정보가 올바르지 않아요" }
        val hash = manifest.getString("sha256").lowercase()
        require(Regex("[0-9a-f]{64}").matches(hash)) { "검증용 SHA-256 정보가 없어요" }
        val code = manifest.getLong("versionCode")
        require(code > 0) { "버전 코드가 올바르지 않아요" }
        val minSdk = manifest.getInt("minSdk")
        require(minSdk > 0)
        return AppRelease(code, name, minSdk, assetUrl(release, apk), hash, size,
            release.optString("body"), "${UpdateProject.RELEASES}/tag/v$name")
    }
}

/** No tokens or user data are sent; all requests are to the public release endpoints. */
internal class ReleaseHttp {
    fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val uri = URI(current)
            require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "안전한 다운로드 주소가 아니에요" }
            require(uri.host in setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "허용하지 않은 다운로드 서버예요" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "LifeDashboard/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                current = uri.resolve(next ?: throw IOException("다운로드 이동 주소가 없어요")).toString()
            } else {
                if (code != 200) {
                    connection.disconnect()
                    throw IOException(when (code) {
                        404 -> "공개된 업데이트 릴리즈를 찾을 수 없어요"
                        403, 429 -> "GitHub 요청 제한에 도달했어요. 잠시 후 다시 확인해 주세요"
                        else -> "GitHub 연결 실패 (HTTP $code)"
                    })
                }
                return connection
            }
        }
        throw IOException("다운로드 주소 이동이 너무 많아요")
    }
    fun json(url: String): JSONObject {
        val connection = open(url)
        try {
            val bytes = connection.inputStream.use { it.readNBytes(1_048_577) }
            require(bytes.size <= 1_048_576) { "업데이트 정보가 너무 커요" }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

internal object ApkChecks {
    fun matches(file: File, size: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }
}

internal class AppUpdater(private val context: Context) {
    private val http = ReleaseHttp()
    private val directory get() = File(context.filesDir, "updates").also { check(it.isDirectory || it.mkdirs()) }
    private fun apkFile(release: AppRelease) = File(directory, "${release.sha256}.apk")
    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val release = http.json(UpdateProject.LATEST)
        val manifest = http.json(ReleaseParser.assetUrl(release, ReleaseParser.asset(release, "update.json")))
        ReleaseParser.parse(release, manifest)
    }
    suspend fun hasDownload(release: AppRelease): Boolean = withContext(Dispatchers.IO) {
        ApkChecks.matches(apkFile(release), release.size, release.sha256)
    }
    suspend fun download(release: AppRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(release.minSdk <= Build.VERSION.SDK_INT) { "이 업데이트는 Android API ${release.minSdk} 이상이 필요해요" }
        val target = apkFile(release)
        if (ApkChecks.matches(target, release.size, release.sha256)) { verifyPackage(target, release); return@withContext target }
        // Retain a completed matching APK for installer cancellation/retry. Partial files are never installed.
        directory.listFiles()?.filter { it.name.endsWith(".part") || (it.extension == "apk" && it != target) }?.forEach { it.delete() }
        val partial = File(directory, "${release.sha256}.part")
        val connection = http.open(release.apkUrl)
        try {
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                var lastPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= release.size) { "APK 다운로드 크기가 예상과 달라요" }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / release.size).toInt()
                    if (percent != lastPercent) { progress(total.toFloat() / release.size); lastPercent = percent }
                }
            } }
            require(ApkChecks.matches(partial, release.size, release.sha256)) { "다운로드 검증에 실패했어요. 다시 다운로드해 주세요" }
            verifyPackage(partial, release)
            check(!target.exists() || target.delete()) { "기존 다운로드 파일을 교체하지 못했어요" }
            check(partial.renameTo(target)) { "다운로드 파일을 저장하지 못했어요" }
            target
        } finally { connection.disconnect(); partial.delete() }
    }
    suspend fun readyFile(release: AppRelease): File = withContext(Dispatchers.IO) {
        val file = apkFile(release)
        require(ApkChecks.matches(file, release.size, release.sha256)) { "다운로드 파일이 없거나 손상됐어요. 다시 받아 주세요" }
        verifyPackage(file, release)
        file
    }
    private fun verifyPackage(file: File, release: AppRelease) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("설치할 APK를 읽지 못했어요")
        require(archive.packageName == context.packageName && archive.longVersionCode == release.versionCode && archive.versionName == release.versionName) { "APK의 앱 또는 버전 정보가 일치하지 않아요" }
        require(archive.longVersionCode > installed.longVersionCode) { "현재 버전보다 새로운 APK가 아니에요" }
        val oldSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val newSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        require(oldSigners.isNotEmpty() && oldSigners == newSigners) { "기존 앱과 서명이 달라 업데이트할 수 없어요. 앱을 삭제하지 말고 배포 파일을 확인해 주세요" }
    }
}
