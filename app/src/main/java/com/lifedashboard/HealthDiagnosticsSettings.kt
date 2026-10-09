package com.lifedashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HealthDiagnosticsSettings(vm: LifeViewModel) {
    val report by vm.graph.health.diagnostics.reports.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var copied by remember(report) { mutableStateOf(false) }
    SettingsCard("건강 연결 진단", "HEALTH") {
        Text("건강 수집을 다시 실행하고 실패 단계를 기록해요. 건강 수치·기록 내용·토큰 원문은 진단 정보에 포함하지 않아요.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.work { vm.graph.diagnoseHealth() }; show = true }, enabled = !busy && vm.graph.status.enabled(), modifier = Modifier.fillMaxWidth()) { Text("진단 실행") }
        if (!vm.graph.status.enabled()) Text("생활 데이터 수집에 동의하면 진단을 실행할 수 있어요.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("진단 결과 보기 · 복사") }
        Text("최근 시도와 마지막 실패를 기기에 보관해요. 내용을 확인한 뒤 복사해서 전달할 수 있어요.", style = MaterialTheme.typography.bodySmall)
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("건강 수집 진단 정보") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (busy) Text("진단 처리 중이에요. 완료 후 복사해 주세요.")
            SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("건강 연결 진단", report))
            copied = true
        }) { Text(if (copied) "복사됨" else "진단 정보 복사") } }, dismissButton = { TextButton(onClick = { show = false }) { Text("닫기") } })
}
