package com.lifedashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*
import java.util.Locale

@Composable
fun DashboardContent(summaries: List<DailySummary>, schedules: List<LifeEvent>, notifications: List<LifeEvent>, states: Map<String,String>, onLaunchApp: (String) -> Unit, onSelect: (String,LocalDate) -> Unit, onInbox: () -> Unit, onOpen: (LifeEvent) -> Unit, vm: LifeViewModel, cards: List<DashboardCardSpec>, onEdit: () -> Unit, onCard: (DashboardCardSpec) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true) { now=System.currentTimeMillis();kotlinx.coroutines.delay(30_000) } }
    val today=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val current=summaries.find { it.date==today.toString() }
    val healthNote=if(states["HEALTH_CONNECT"]?.startsWith("수집 완료")==true) "저장된 건강 기록" else "최신 수집 미확인"
    val calendarNote=if(states["CALENDAR"]?.startsWith("수집 완료")==true) "오늘의 저장 일정" else "최신 수집 미확인"
    @Composable fun render(spec: DashboardCardSpec, modifier: Modifier) {
        when(spec.kind) {
            "CUSTOM","PLACE_VISIT" -> QueryCard(vm,spec,modifier,onCard)
            "CALENDAR" -> StatCard(spec.title,current?.let { "${it.calendarCount}개" } ?: "집계 대기","CALENDAR",HomeCardDetails.schedule(schedules,now,ZoneId.systemDefault())+"\n$calendarNote",modifier) { onLaunchApp("CALENDAR") }
            "SLEEP" -> StatCard(spec.title,current?.sleepMinutes?.let { "${it/60}시간 ${it%60}분" } ?: "기록 없음","SLEEP","오늘 종료된 수면 기록 기준\n$healthNote",modifier) { onLaunchApp("SLEEP") }
            "STEP_SUMMARY" -> StatCard(spec.title,current?.stepCount?.let { "${String.format(Locale.KOREAN,"%,d",it)}보" } ?: "기록 없음","STEP_SUMMARY","오늘 누적 걸음수\n$healthNote",modifier) { onLaunchApp("STEP_SUMMARY") }
            "EXERCISE" -> StatCard(spec.title,current?.exerciseMinutes?.let { "${it}분" } ?: "기록 없음","EXERCISE","오늘 기록된 운동 시간\n$healthNote",modifier) { onLaunchApp("EXERCISE") }
            "PAYMENT" -> StatCard(spec.title,current?.let { "${String.format(Locale.KOREAN,"%,d",it.paymentAmount)}원" } ?: "집계 대기","PAYMENT",current?.let { "결제·취소 알림 ${it.paymentCount}건\n"+(notifications.firstOrNull { it.type=="PAYMENT" }?.let { e -> "최근 · ${e.title}" } ?: "저장된 결제 알림 없음") } ?: "저장된 기록을 확인하고 있어요",modifier) { onSelect("PAYMENT",today) }
            "DELIVERY" -> StatCard(spec.title,current?.let { "${it.deliveryCount}건" } ?: "집계 대기","DELIVERY",HomeCardDetails.delivery(notifications)+"\n건수는 물품 수가 아닌 알림 수예요",modifier) { onSelect("DELIVERY",today) }
            "RESERVATION" -> StatCard(spec.title,current?.let { "${it.reservationCount}건" } ?: "집계 대기","RESERVATION",notifications.firstOrNull { it.type=="RESERVATION" }?.let { "최근 · ${it.title}\n${it.summary.orEmpty()}" } ?: "저장된 예약 알림 없음",modifier) { onSelect("RESERVATION",today) }
            "SCHEDULE_LIST" -> Column(modifier) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(spec.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick={onSelect("CALENDAR",today)}) { Text("전체 보기") } }
                Card {
                    if(schedules.isEmpty()) Text("저장된 일정이 없어요.\n$calendarNote",Modifier.padding(20.dp))
                    schedules.take(3).forEach { e -> SummaryRow("CALENDAR","${eventTime(e)} ${e.title}",e.summary.orEmpty()) { onOpen(e) } }
                    TextButton(onClick={onSelect("CALENDAR",today.plusDays(1))},modifier=Modifier.align(Alignment.End)) { Text("내일 일정 보기 ›") }
                }
            }
        }
    }
    val rows=remember(cards) { buildList<List<DashboardCardSpec>> {
        var index=0
        while(index<cards.size) {
            if(cards[index].halfWidth && cards.getOrNull(index+1)?.halfWidth==true) { add(cards.subList(index,index+2));index+=2 }
            else {add(listOf(cards[index]));index++}
        }
    } }
    LazyColumn(contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {TextButton(onClick=onInbox) {Text("알림 보관함")};TextButton(onClick=onEdit) {Text("카드 편집")} } }
        if(cards.isEmpty()) item { Card {Column(Modifier.padding(20.dp)) {Text("홈에 표시할 카드를 추가해 보세요.");TextButton(onClick=onEdit) {Text("카드 추가")}}} }
        items(rows,key={row->row.joinToString {it.id}}) { row ->
            if(row.size==2) Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {row.forEach { spec -> key(spec.id) {render(spec,Modifier.weight(1f).fillMaxHeight())} }}
            else render(row.single(),Modifier.fillMaxWidth())
        }
    }
}
