package com.lifedashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun BriefingCard(briefing: LifeEvent?) {
    var expanded by rememberSaveable(briefing?.id) { mutableStateOf(false) }
    val data = remember(briefing?.dataJson) { briefing?.let { JSONObject(it.dataJson) } }
    val sections = data?.optJSONArray("sections")
    val ink = Color(0xFF153E45)
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFFD5F4EC), Color(0xFFEDF0FF)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LifeIcon("BRIEFING", Modifier.size(32.dp), Color(0xFF007F7A))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("오늘의 브리핑", style = MaterialTheme.typography.labelLarge, color = ink)
                    Text(data?.optString("greeting")?.takeIf { it.isNotBlank() } ?: "나의 하루 한눈에", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                }
            }
            if (sections == null) {
                Text(briefing?.summary ?: "생활 기록이 모이면 오늘의 활동을 보여드려요.", color = ink)
            } else {
                val count = if (expanded) sections.length() else minOf(4, sections.length())
                for (row in 0 until count step 2) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (i in row until minOf(row + 2, count)) {
                            val section = sections.getJSONObject(i)
                            val accent = listOf(Color(0xFF6456AD),Color(0xFF007F7A),Color(0xFFA44C20),Color(0xFF2863AA),Color(0xFF6456AD))[i % 5]
                            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.86f)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LifeIcon(section.getString("icon"), Modifier.size(24.dp), accent)
                                    Text(section.getString("label"), style = MaterialTheme.typography.labelMedium, color = ink)
                                    Text(section.getString("value"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
                                    if (expanded || i == 0) Text(section.getString("detail"), style = MaterialTheme.typography.bodySmall, color = ink)
                                }
                            }
                        }
                        if (row + 1 == count) Spacer(Modifier.weight(1f))
                    }
                }
                if (expanded) Text("저장된 기록 기준이며, 수집되지 않은 데이터는 포함되지 않아요. 배송 건수는 알림 수예요.", style = MaterialTheme.typography.bodySmall, color = ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(briefing?.let { "${Instant.ofEpochMilli(it.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} 갱신 · 저장 기록 기준" } ?: "아직 기록이 없어요", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = ink)
                if (sections != null) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "자세히 보기", color = ink) }
            }
        }
    }
}
