package com.lifedashboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.*

enum class SettingsPage(val title: String, val description: String, val icon: String) {
    COLLECTION("데이터 수집 및 권한", "일정 · 알림 · 건강", "HEALTH"),
    HOME_APPS("홈 카드 연결 앱", "일정 · 수면 · 걸음수 · 운동", "HOME"),
    NOTIFICATIONS("알림 분류 및 보관", "분류 규칙 · 자동 정리 기준", "NOTIFICATION"),
    DATA("데이터 관리", "원본 다시 분석 · 전체 삭제", "TIMELINE"),
    ABOUT("앱 정보", "버전 · 앱 업데이트", "SETTINGS")
}

@Composable
fun SettingsMenuRow(title: String, description: String, icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LifeIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsOverview(enabled: Boolean, permissions: PermissionStates, states: Map<String, String>, onOpen: (SettingsPage) -> Unit) {
    Card(onClick = { onOpen(SettingsPage.COLLECTION) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (enabled) "생활 데이터 수집 켜짐" else "생활 데이터 수집 꺼짐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (enabled) "권한과 수집 결과 확인하기 ›" else "이용 안내 확인하고 수집 시작하기 ›", style = MaterialTheme.typography.bodySmall)
        }
    }
    val permissionNotes = listOf("일정" to permissions.calendar, "알림" to permissions.notification, "건강" to permissions.health)
        .filter { !it.second.allowed }
    val failures = states.filterValues { it.contains("실패") }
    if (enabled && (permissionNotes.isNotEmpty() || failures.isNotEmpty())) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                permissionNotes.forEach { (name, state) -> Text("$name · ${state.label}", style = MaterialTheme.typography.bodySmall) }
                failures.forEach { (source, _) -> Text("${sourceLabel(source)} · 처리 내역 확인 필요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    listOf("생활 데이터와 화면" to SettingsPage.entries.take(3), "관리" to SettingsPage.entries.drop(3)).forEach { (label, pages) ->
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            pages.forEachIndexed { index, page ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SettingsMenuRow(page.title, page.description, page.icon) { onOpen(page) }
            }
        }
    }
    Text("Life Dashboard · ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

data class PermissionState(val label: String = "확인 중", val allowed: Boolean = false)
data class PermissionStates(
    val calendar: PermissionState = PermissionState(), val notification: PermissionState = PermissionState(),
    val health: PermissionState = PermissionState(), val background: PermissionState = PermissionState()
)
fun healthPermissionState(granted: Set<String>): PermissionState {
    val count = HealthCollector.permissions.count { it in granted }
    return when (count) {
        HealthCollector.permissions.size -> PermissionState("허용됨", true)
        0 -> PermissionState("허용 필요")
        else -> PermissionState("일부 허용 $count/${HealthCollector.permissions.size}")
    }
}
private suspend fun readPermissionStates(graph: AppGraph): PermissionStates {
    fun state(allowed: Boolean) = PermissionState(if (allowed) "허용됨" else "허용 필요", allowed)
    val calendar = state(ContextCompat.checkSelfPermission(graph.context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    val notification = state(graph.context.packageName in NotificationManagerCompat.getEnabledListenerPackages(graph.context))
    return try {
        when (graph.health.availability()) {
            HealthConnectClient.SDK_AVAILABLE -> {
                val granted = graph.health.client().permissionController.getGrantedPermissions()
                PermissionStates(calendar, notification, healthPermissionState(granted),
                    if (graph.health.backgroundSupported()) state(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted) else PermissionState("지원 안 됨"))
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> PermissionStates(calendar, notification, PermissionState("설치·업데이트 필요"), PermissionState("설치·업데이트 필요"))
            else -> PermissionStates(calendar, notification, PermissionState("지원 안 됨"), PermissionState("지원 안 됨"))
        }
    } catch (e: CancellationException) { throw e }
      catch (_: Exception) { PermissionStates(calendar, notification, PermissionState("확인 실패"), PermissionState("확인 실패")) }
}
@Composable
fun rememberPermissionStates(graph: AppGraph, revision: Int): PermissionStates {
    var states by remember(graph) { mutableStateOf(PermissionStates()) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(graph, revision) {
        states = PermissionStates()
        val job = scope.launch { states = withContext(Dispatchers.IO) { readPermissionStates(graph) } }
        onPauseOrDispose { job.cancel() }
    }
    return states
}
@Composable
fun SettingsCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LifeIcon(icon)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}
@Composable
fun PermissionAction(title: String, description: String, state: PermissionState, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Surface(color = if (state.allowed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text("${if (state.allowed) "✓ " else ""}${state.label}", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (state.allowed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
