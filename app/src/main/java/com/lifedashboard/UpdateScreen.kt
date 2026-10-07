package com.lifedashboard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class UpdateState(
    val release: AppRelease? = null, val busy: Boolean = false, val downloading: Boolean = false,
    val progress: Float = 0f, val ready: Boolean = false, val message: String = ""
)

internal class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val updater = AppUpdater(app)
    val state = MutableStateFlow(UpdateState())
    init { check() }
    fun check() {
        if (state.value.busy) return
        state.update { it.copy(busy = true, message = "최신 버전을 확인하고 있어요") }
        viewModelScope.launch {
            try {
                val release = updater.latest()
                val ready = release.isNewerThan(BuildConfig.VERSION_CODE.toLong()) && updater.hasDownload(release)
                state.value = UpdateState(release = release, ready = ready, message = when {
                    !release.isNewerThan(BuildConfig.VERSION_CODE.toLong()) -> "현재 최신 버전을 사용하고 있어요"
                    release.minSdk > Build.VERSION.SDK_INT -> "새 버전은 Android API ${release.minSdk} 이상이 필요해요"
                    ready -> "받아둔 업데이트를 설치할 수 있어요"
                    else -> "새 버전을 다운로드할 수 있어요"
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(message = "확인 실패: ${e.message ?: e.javaClass.simpleName}") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun download() {
        val release = state.value.release ?: return
        if (state.value.busy || !release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) return
        state.update { it.copy(busy = true, downloading = true, progress = 0f, ready = false, message = "업데이트를 다운로드하고 있어요") }
        viewModelScope.launch {
            try {
                updater.download(release) { progress -> state.update { it.copy(progress = progress) } }
                state.update { it.copy(ready = true, message = "검증 완료 · 업데이트 설치를 눌러 주세요") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(message = "다운로드 실패: ${e.message ?: e.javaClass.simpleName}") } }
            finally { state.update { it.copy(busy = false, downloading = false) } }
        }
    }
    fun message(message: String) { state.update { it.copy(message = message) } }
    fun install(context: Context) {
        val release = state.value.release ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    message("이 앱의 설치 허용을 켠 뒤 다시 설치를 눌러 주세요")
                    return@launch
                }
                val file = updater.readyFile(release)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                message("설치 화면에서 업데이트를 진행해 주세요. 취소했다면 다시 설치할 수 있어요")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message("설치 화면을 열지 못했어요: ${e.message ?: e.javaClass.simpleName}") }
            finally { state.update { it.copy(busy = false) } }
        }
    }
}

@Composable
internal fun UpdateScreen(onBack: () -> Unit, vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) vm.install(context)
        else vm.message("설치 허용을 켜야 업데이트를 설치할 수 있어요")
    }
    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextButton(onClick = onBack) { Text("설정으로 돌아가기") }
            Text("앱 업데이트", style = MaterialTheme.typography.headlineMedium)
            Text("현재 버전  ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            state.release?.let { release ->
                Text("최신 릴리즈  ${release.versionName} (${release.versionCode})", style = MaterialTheme.typography.titleMedium)
            }
            Text(state.message)
            if (state.downloading) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("${(state.progress * 100).toInt()}%")
            } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.check() }, enabled = !state.busy) { Text("최신 버전 확인") }
            state.release?.let { release ->
                if (release.isNewerThan(BuildConfig.VERSION_CODE.toLong()) && release.minSdk <= Build.VERSION.SDK_INT) {
                    if (state.ready) Button(onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) vm.install(context)
                        else try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                        catch (_: Exception) { vm.message("설치 허용 설정을 열지 못했어요. 휴대폰 설정에서 확인해 주세요") }
                    }, enabled = !state.busy) { Text("업데이트 설치") }
                    else Button(onClick = { vm.download() }, enabled = !state.busy) { Text("새 버전 다운로드 · ${release.size / 1024 / 1024} MB") }
                }
                HorizontalDivider()
                Text("변경 내용", style = MaterialTheme.typography.titleMedium)
                Text(release.notes.ifBlank { "등록된 변경 내용이 없어요" })
            }
            TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release?.pageUrl ?: UpdateProject.RELEASES))) }
                catch (_: Exception) { vm.message("GitHub 페이지를 열 수 있는 브라우저가 없어요") }
            }) { Text("GitHub 릴리즈 보기") }
            Text("업데이트 확인과 APK 다운로드에만 인터넷을 사용해요. 알림·일정·건강 기록은 전송하지 않아요.", style = MaterialTheme.typography.bodySmall)
            Text("설치는 Android 확인 화면에서 직접 승인해요. 같은 서명의 새 버전으로 업데이트하면 저장된 기록을 유지해요.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
