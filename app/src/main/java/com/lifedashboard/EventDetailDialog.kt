package com.lifedashboard

import androidx.compose.foundation.layout.Arrangement
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
fun EventDetailDialog(detail: EventDetail, onDismiss: () -> Unit) {
    var expanded by remember(detail.event.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.event.title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(detail.event.summary?.takeIf { it.isNotBlank() } ?: "표시할 내용이 없어요.") }
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
