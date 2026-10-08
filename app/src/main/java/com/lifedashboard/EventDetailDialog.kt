package com.lifedashboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

@Composable
fun EventDetailDialog(detail: EventDetail, busy: Boolean, message: String, onClassify: (String?, Long?, Boolean) -> Unit, onDismiss: () -> Unit) {
    var editing by remember(detail.event) { mutableStateOf(false) }
    val manual = remember(detail.event.dataJson) { JSONObject(detail.event.dataJson).has("manualClassification") }
    if (editing) {
        ClassificationDialog(detail.event, manual, busy, message, onClassify, onDismiss = { editing = false })
        return
    }
    var expanded by remember(detail.event.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.event.title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(detail.event.summary?.takeIf { it.isNotBlank() } ?: "표시할 내용이 없어요.") }
                if (detail.event.sourceType == "NOTIFICATION") {
                    Text("${notificationClassifications[detail.event.type] ?: detail.event.type} · ${if (manual) "직접 분류" else "자동 분류"}", style = MaterialTheme.typography.labelMedium)
                    if (!manual) {
                        val parsedData = JSONObject(detail.event.dataJson)
                        parsedData.optString("parserRuleName").takeIf { it.isNotBlank() }?.let { Text("적용 규칙: $it", style = MaterialTheme.typography.bodySmall) }
                        parsedData.optString("ruleWarning").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text("분류 변경") }
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" }
                ) { Text(if (expanded) "상세 정보 접기" else "상세 보기") }
                if (expanded) {
                    HorizontalDivider()
                    Text("시작: ${Instant.ofEpochMilli(detail.event.occurredAt).atZone(ZoneId.systemDefault())}")
                    detail.event.endedAt?.let { Text("종료: ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())}") }
                    Text("태그: ${detail.tags.joinToString { it.tag }.ifEmpty { "없음" }}")
                    Text("엔티티: ${detail.entities.joinToString { it.name }.ifEmpty { "없음" }}")
                    JsonDetailBlock("구조화 데이터", detail.event.dataJson)
                    detail.raw?.let { raw ->
                        JsonDetailBlock("원본 데이터 · 버전 ${raw.revision}", raw.rawJson)
                    } ?: Text("별도 원본 데이터가 없는 기록이에요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
private fun JsonDetailBlock(label: String, json: String) {
    val formatted = remember(json) {
        try { JSONObject(json).toString(2) } catch (_: JSONException) { json }
    }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(formatted, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ClassificationDialog(event: LifeEvent, manual: Boolean, busy: Boolean, message: String,
    onSave: (String?, Long?, Boolean) -> Unit, onDismiss: () -> Unit) {
    val data = remember(event.dataJson) { JSONObject(event.dataJson) }
    var type by remember(event.id) { mutableStateOf(event.type) }
    var amount by remember(event.id) { mutableStateOf(if (event.type == "PAYMENT" && data.has("amount")) data.getLong("amount").toString() else "") }
    var cancelled by remember(event.id) { mutableStateOf(data.optString("paymentKind") == "CANCELLATION") }
    val amountValue = amount.replace(",", "").toLongOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("알림 분류 변경") },
        confirmButton = { TextButton(onClick = { onSave(type,amountValue,cancelled) }, enabled = !busy && (type != "PAYMENT" || amountValue != null)) { Text(if (busy) "저장 중…" else "저장") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("이 알림의 분류만 바꿔요. 다시 분석해도 선택한 분류를 유지해요.", style = MaterialTheme.typography.bodySmall)
                notificationClassifications.forEach { (value,label) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = type == value, enabled = !busy, role = Role.RadioButton, onClick = { type = value }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == value, onClick = null, enabled = !busy)
                        Text(label,Modifier.padding(start = 12.dp,top = 8.dp,bottom = 8.dp))
                    }
                }
                if (type == "PAYMENT") {
                    OutlinedTextField(value = amount, onValueChange = { value -> if (value.all { it in '0'..'9' || it == ',' }) amount = value }, label = { Text("결제 금액 (원)") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = amount.isNotEmpty() && amountValue == null,
                        supportingText = { Text("인센티브·잔액을 제외한 결제 금액을 입력해 주세요.") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cancelled,onCheckedChange = { cancelled = it },enabled = !busy)
                        Text("취소 내역 (합계에서 차감)",style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (manual) TextButton(onClick = { onSave(null,null,false) }, enabled = !busy) { Text("자동 분류로 되돌리기") }
                if (message.startsWith("처리 실패")) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    )
}