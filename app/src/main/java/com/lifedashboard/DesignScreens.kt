package com.lifedashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DayFormat = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

// Small shared line icons keep the UI consistent without a large icon dependency.
@Composable
fun LifeIcon(kind: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(vararg points: Float) {
                val path = Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]) }
                drawPath(path, tint, style = Stroke(1.7f, cap = StrokeCap.Round))
            }
            fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r, Offset(x,y), style = Stroke(1.7f))
            when (kind) {
                "PLACE_VISIT" -> { circle(12f,9f,5f); line(8f,13f,12f,21f,16f,13f); circle(12f,9f,1.5f) }
                "HOME" -> { line(3f,11f,12f,3f,21f,11f); line(5f,10f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,10f) }
                "CALENDAR", "RESERVATION" -> { line(4f,6f,20f,6f,20f,21f,4f,21f,4f,6f); line(8f,3f,8f,8f); line(16f,3f,16f,8f); line(4f,11f,20f,11f); line(8f,15f,11f,15f) }
                "PAYMENT" -> { line(3f,5f,21f,5f,21f,19f,3f,19f,3f,5f); line(3f,10f,21f,10f); line(7f,15f,11f,15f) }
                "DELIVERY" -> { line(2f,5f,14f,5f,14f,17f,2f,17f,2f,5f); line(14f,9f,19f,9f,22f,13f,22f,17f,14f,17f); circle(6f,19f,2f); circle(18f,19f,2f) }
                "SLEEP" -> { val p = Path().apply { moveTo(15f,3f); cubicTo(1f,0f,0f,21f,14f,21f); cubicTo(19f,21f,22f,17f,22f,14f); cubicTo(12f,18f,8f,9f,15f,3f) }; drawPath(p,tint,style=Stroke(1.7f)) }
                "STEP", "STEP_SUMMARY", "HEALTH" -> { line(8f,4f,5f,10f,5f,14f,9f,14f,11f,9f,8f,4f); line(16f,10f,13f,16f,13f,20f,17f,20f,19f,15f,16f,10f) }
                "EXERCISE" -> { line(3f,8f,3f,16f,7f,16f,7f,8f,3f,8f); line(17f,8f,17f,16f,21f,16f,21f,8f,17f,8f); line(7f,12f,17f,12f) }
                "ADVERTISEMENT" -> { line(3f,9f,9f,9f,19f,4f,19f,20f,9f,15f,3f,15f,3f,9f); line(7f,15f,9f,21f,12f,21f,10f,16f) }
                "SETTINGS" -> {
                    val gear = Path().apply {
                        repeat(8) { tooth ->
                            listOf(-22.5 to 7.5, -10.0 to 7.5, -10.0 to 10.0, 10.0 to 10.0, 10.0 to 7.5, 22.5 to 7.5).forEachIndexed { index, (offset, radius) ->
                                val angle = Math.toRadians(tooth * 45.0 + offset)
                                val x = 12f + (kotlin.math.cos(angle) * radius).toFloat()
                                val y = 12f + (kotlin.math.sin(angle) * radius).toFloat()
                                if (tooth == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }
                        close()
                    }
                    drawPath(gear, tint, style = Stroke(1.5f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    circle(12f,12f,3f)
                }
                "NEXT" -> line(9f,5f,16f,12f,9f,19f)
                "BACK" -> line(15f,5f,8f,12f,15f,19f)
                "REFRESH" -> { line(20f,8f,17f,4f,9f,4f,4f,9f,4f,15f,9f,20f,16f,20f,20f,16f); line(20f,3f,20f,9f,14f,9f) }
                "BRIEFING" -> { circle(12f,12f,5f); line(12f,1f,12f,4f); line(12f,20f,12f,23f); line(1f,12f,4f,12f); line(20f,12f,23f,12f); line(4f,4f,6f,6f); line(18f,18f,20f,20f) }
                else -> { for (y in listOf(6f,12f,18f)) { circle(4f,y,0.6f); line(9f,y,21f,y) } }
            }
        }
    }
}

@Composable
fun LifeIconButton(kind: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        LifeIcon(kind, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun IconTile(type: String) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { LifeIcon(type, tint = colors.onPrimaryContainer) }
}

@Composable
private fun SectionTitle(title: String, action: String = "전체 보기", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick) { Text(action); LifeIcon("NEXT", Modifier.size(16.dp)) }
    }
}

@Composable
internal fun StatCard(title: String, value: String, type: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val featured = type == "CALENDAR"
    Card(onClick, modifier, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (featured) colors.primaryContainer else colors.surface, contentColor = colors.onSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (featured) colors.primaryContainer else colors.outlineVariant)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LifeIcon(type, tint = colors.primary); Text(title, style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (featured) colors.onPrimaryContainer else colors.onSurface)
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SummaryRow(type: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(type)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LifeIcon("NEXT", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


fun eventTime(event: LifeEvent): String = if (event.calendarDate != null) "종일" else Instant.ofEpochMilli(if (event.type == "SLEEP") event.endedAt ?: event.occurredAt else event.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun eventStatus(status: String) = when(status) { "ACTIVE" -> ""; "CANCELLED" -> "취소"; "UNVERIFIED" -> "확인 필요"; "NO_DATA" -> "데이터 없음"; else -> status }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineContent(events: List<LifeEvent>, query: TimelineQuery, onQuery: (TimelineQuery) -> Unit, onOpen: (LifeEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var pickDate by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query.date,query.type,query.inbox,query.oldestFirst) { listState.scrollToItem(0) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "전체", "CALENDAR" to "일정", "HEALTH" to "건강", "PAYMENT" to "결제", "DELIVERY" to "배송", "RESERVATION" to "예약", "PLACE_VISIT" to "방문", "ADVERTISEMENT" to "광고", "SLEEP" to "수면", "STEP_SUMMARY" to "걸음", "EXERCISE" to "운동", "NOTIFICATION" to "기타").forEach { (type,label) ->
                FilterChip(query.type == type, { onQuery(query.copy(type = type,limit = 100)) }, label = { Text(label) }, shape = RoundedCornerShape(50), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primary, selectedLabelColor = colors.onPrimary))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LifeIconButton("BACK","이전 날짜") { onQuery(query.copy(date = query.date.minusDays(1),limit = 100)) }
            TextButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                Text(query.date.format(DayFormat), fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); LifeIcon("CALENDAR",Modifier.size(18.dp))
            }
            LifeIconButton("NEXT","다음 날짜") { onQuery(query.copy(date = query.date.plusDays(1),limit = 100)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onQuery(query.copy(oldestFirst = !query.oldestFirst, limit = 100)) }) {
                Text("${events.size}${if (events.size >= query.limit) "+" else ""}건 · ${if (query.oldestFirst) "시간순" else "최신순"}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onQuery(query.copy(date = LocalDate.now(),limit = 100)) }) { Text("오늘") }
        }
        LazyColumn(modifier = Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 20.dp,end = 20.dp,bottom = 24.dp)) {
            if (events.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LifeIcon(if (query.inbox) "NOTIFICATION" else "TIMELINE",Modifier.size(32.dp))
                        Text("이 날짜에 저장된 기록이 없어요.", style = MaterialTheme.typography.titleSmall)
                        Text("다른 날짜나 분류를 선택해 보세요.\n수집 권한과 상태는 설정에서 확인할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(events, key = { _,event -> event.id }) { index,event ->
                val shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp, topEnd = if(index == 0) 18.dp else 0.dp, bottomStart = if(index == events.lastIndex) 18.dp else 0.dp,bottomEnd = if(index == events.lastIndex) 18.dp else 0.dp)
                Row(Modifier.fillMaxWidth().background(colors.surface,shape).clickable { onOpen(event) }.height(IntrinsicSize.Min).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(eventTime(event), Modifier.width(43.dp).padding(top = 24.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Canvas(Modifier.width(8.dp).fillMaxHeight()) {
                        val center = 30.dp.toPx()
                        drawLine(colors.outlineVariant,Offset(size.width / 2,if(index == 0) center else 0f),Offset(size.width / 2,if(index == events.lastIndex) center else size.height),2.dp.toPx())
                        drawCircle(colors.primary.copy(alpha = 0.5f),3.dp.toPx(),Offset(size.width / 2,center))
                    }
                    Row(Modifier.weight(1f).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(event.type)
                        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,maxLines = 2,overflow = TextOverflow.Ellipsis)
                            if (!event.summary.isNullOrBlank()) Text(event.summary,style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant,maxLines = 2,overflow = TextOverflow.Ellipsis)
                            val status = eventStatus(event.status)
                            if(status.isNotEmpty()) Text(status,style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.error)
                        }
                        LifeIcon("NEXT",Modifier.size(14.dp),colors.onSurfaceVariant)
                    }
                }
            }
            if(events.size >= query.limit) item { TextButton(onClick = { onQuery(query.copy(limit = query.limit + 100)) },modifier = Modifier.fillMaxWidth()) { Text("기록 더 보기") } }
            item { TextButton(onClick = { onQuery(query.copy(date = query.date.minusDays(1),limit = 100)) },modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("이전 날짜 기록 보기") } }
        }
    }
    if(pickDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = query.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { onQuery(query.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(),limit = 100)) }; pickDate = false
        }) { Text("이동") } }, dismissButton = { TextButton(onClick = { pickDate = false }) { Text("취소") } }) { DatePicker(picker) }
    }
}
