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

private val Teal = Color(0xFF007F7A)
private val Ink = Color(0xFF172B3A)
private val Muted = Color(0xFF627580)
private val Mist = Color(0xFFE2F2EF)
private val Paper = Color(0xFFF6F8F6)
private val DayFormat = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
            primaryContainer = Mist, onPrimaryContainer = Color(0xFF005B57),
            secondary = Muted, secondaryContainer = Mist, onSecondaryContainer = Teal,
            background = Paper, onBackground = Ink, surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFEDF2EF), onSurfaceVariant = Muted,
            outline = Color(0xFF788B8A), outlineVariant = Color(0xFFDCE5E0)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}

// Small shared line icons keep the UI consistent without a large icon dependency.
@Composable
fun LifeIcon(kind: String, modifier: Modifier = Modifier, tint: Color = Teal) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(vararg points: Float) {
                val path = Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]) }
                drawPath(path, tint, style = Stroke(1.7f, cap = StrokeCap.Round))
            }
            fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r, Offset(x,y), style = Stroke(1.7f))
            when (kind) {
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
        LifeIcon(kind, tint = if (enabled) Teal else Muted.copy(alpha = 0.4f))
    }
}

@Composable
private fun IconTile(type: String) {
    val color = when(type) { "PAYMENT" -> Color(0xFFB86725); "CALENDAR", "RESERVATION" -> Color(0xFF3E75B4); else -> Teal }
    Box(Modifier.size(40.dp).background(color.copy(alpha = 0.09f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { LifeIcon(type, tint = color) }
}

@Composable
private fun SectionTitle(title: String, action: String = "전체 보기", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick) { Text(action); LifeIcon("NEXT", Modifier.size(16.dp)) }
    }
}

@Composable
private fun StatCard(title: String, value: String, type: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick, modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LifeIcon(type); Text(title, style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun SummaryRow(type: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(type)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LifeIcon("NEXT", Modifier.size(16.dp), Muted)
    }
}

@Composable
fun DashboardContent(summaries: List<DailySummary>, briefing: LifeEvent?, schedules: List<LifeEvent>, states: Map<String,String>, onLaunchApp: (String) -> Unit, onSelect: (String, LocalDate) -> Unit, onInbox: () -> Unit, onOpen: (LifeEvent) -> Unit) {
    val today = LocalDate.now()
    val current = summaries.find { it.date == today.toString() }
    val healthNote = if (states["HEALTH_CONNECT"]?.startsWith("수집 완료") == true) "저장된 건강 기록" else "최신 수집 미확인"
    val calendarNote = if (states["CALENDAR"]?.startsWith("수집 완료") == true) "오늘의 저장 일정" else "최신 수집 미확인"
    val todayBriefing = briefing?.takeIf { Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BriefingCard(todayBriefing)
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("오늘 일정", current?.let { "${it.calendarCount}개" } ?: "집계 대기", "CALENDAR", calendarNote, Modifier.weight(1f).fillMaxHeight()) { onLaunchApp("CALENDAR") }
                StatCard("오늘 종료된 수면", current?.sleepMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "기록 없음", "SLEEP", healthNote, Modifier.weight(1f).fillMaxHeight()) { onLaunchApp("SLEEP") }
            }
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("오늘 걸음수", current?.stepCount?.let { "${String.format(Locale.KOREAN,"%,d",it)}보" } ?: "기록 없음", "STEP_SUMMARY", healthNote, Modifier.weight(1f).fillMaxHeight()) { onLaunchApp("STEP_SUMMARY") }
                StatCard("오늘 운동", current?.exerciseMinutes?.let { "${it}분" } ?: "기록 없음", "EXERCISE", healthNote, Modifier.weight(1f).fillMaxHeight()) { onLaunchApp("EXERCISE") }
            }
        }
        item {
            SectionTitle("오늘 일정") { onSelect("CALENDAR",today) }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                if (schedules.isEmpty()) Text("저장된 일정이 없어요.\n$calendarNote", Modifier.fillMaxWidth().padding(20.dp), color = Muted)
                schedules.take(3).forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Paper)
                    SummaryRow("CALENDAR", "${eventTime(event)}  ${event.title}", event.summary.orEmpty()) { onOpen(event) }
                }
                TextButton(onClick = { onSelect("CALENDAR",today.plusDays(1)) }, modifier = Modifier.align(Alignment.End)) { Text("내일 일정 보기 ›") }
            }
        }
        item {
            SectionTitle("생활 알림", "알림 보관함") { onInbox() }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                SummaryRow("DELIVERY", "배송 알림", current?.let { "오늘 ${it.deliveryCount}건" } ?: "집계 대기") { onSelect("DELIVERY",today) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Paper)
                SummaryRow("PAYMENT", "오늘 결제", current?.let { "${it.paymentCount}건 · ${String.format(Locale.KOREAN,"%,d",it.paymentAmount)}원" } ?: "집계 대기") { onSelect("PAYMENT",today) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Paper)
                SummaryRow("RESERVATION", "예약 알림", current?.let { "오늘 ${it.reservationCount}건" } ?: "집계 대기") { onSelect("RESERVATION",today) }
            }
        }
    }
}

fun eventTime(event: LifeEvent): String = if (event.calendarDate != null) "종일" else Instant.ofEpochMilli(if (event.type == "SLEEP") event.endedAt ?: event.occurredAt else event.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun eventStatus(status: String) = when(status) { "ACTIVE" -> ""; "CANCELLED" -> "취소"; "UNVERIFIED" -> "확인 필요"; "NO_DATA" -> "데이터 없음"; else -> status }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineContent(events: List<LifeEvent>, query: TimelineQuery, onQuery: (TimelineQuery) -> Unit, onOpen: (LifeEvent) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query.date,query.type,query.inbox,query.oldestFirst) { listState.scrollToItem(0) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "전체", "CALENDAR" to "일정", "HEALTH" to "건강", "PAYMENT" to "결제", "DELIVERY" to "배송", "RESERVATION" to "예약", "ADVERTISEMENT" to "광고", "SLEEP" to "수면", "STEP_SUMMARY" to "걸음", "EXERCISE" to "운동", "NOTIFICATION" to "기타", "BRIEFING" to "브리핑").forEach { (type,label) ->
                FilterChip(query.type == type, { onQuery(query.copy(type = type,limit = 100)) }, label = { Text(label) }, shape = RoundedCornerShape(50), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Color.White))
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
                Text("${events.size}${if (events.size >= query.limit) "+" else ""}건 · ${if (query.oldestFirst) "시간순" else "최신순"}", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onQuery(query.copy(date = LocalDate.now(),limit = 100)) }) { Text("오늘") }
        }
        LazyColumn(modifier = Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 20.dp,end = 20.dp,bottom = 24.dp)) {
            if (events.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LifeIcon(if (query.inbox) "NOTIFICATION" else "TIMELINE",Modifier.size(32.dp))
                        Text("이 날짜에 저장된 기록이 없어요.", style = MaterialTheme.typography.titleSmall)
                        Text("다른 날짜나 분류를 선택해 보세요.\n수집 권한과 상태는 설정에서 확인할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
            itemsIndexed(events, key = { _,event -> event.id }) { index,event ->
                val shape = RoundedCornerShape(topStart = if (index == 0) 18.dp else 0.dp, topEnd = if(index == 0) 18.dp else 0.dp, bottomStart = if(index == events.lastIndex) 18.dp else 0.dp,bottomEnd = if(index == events.lastIndex) 18.dp else 0.dp)
                Row(Modifier.fillMaxWidth().background(Color.White,shape).clickable { onOpen(event) }.height(IntrinsicSize.Min).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(eventTime(event), Modifier.width(43.dp).padding(top = 24.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
                    Canvas(Modifier.width(8.dp).fillMaxHeight()) {
                        val center = 30.dp.toPx()
                        drawLine(Mist,Offset(size.width / 2,if(index == 0) center else 0f),Offset(size.width / 2,if(index == events.lastIndex) center else size.height),2.dp.toPx())
                        drawCircle(Teal.copy(alpha = 0.5f),3.dp.toPx(),Offset(size.width / 2,center))
                    }
                    Row(Modifier.weight(1f).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(event.type)
                        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,maxLines = 2,overflow = TextOverflow.Ellipsis)
                            if (!event.summary.isNullOrBlank()) Text(event.summary,style = MaterialTheme.typography.bodySmall,color = Muted,maxLines = 2,overflow = TextOverflow.Ellipsis)
                            val status = eventStatus(event.status)
                            if(status.isNotEmpty()) Text(status,style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.error)
                        }
                        LifeIcon("NEXT",Modifier.size(14.dp),Muted)
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
