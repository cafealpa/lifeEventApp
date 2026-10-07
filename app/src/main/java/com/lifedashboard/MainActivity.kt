package com.lifedashboard

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter

data class TimelineQuery(val date: LocalDate = LocalDate.now(), val type: String = "", val inbox: Boolean = false, val limit: Int = 100)
data class EventDetail(val event: LifeEvent, val raw: RawEvent?, val tags: List<EventTag>, val entities: List<EventEntity>)
@OptIn(ExperimentalCoroutinesApi::class)
class LifeViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as LifeApplication).graph
    val message = MutableStateFlow("")
    private fun <T> Flow<T>.reportReadFailure(): Flow<T> = catch { e ->
        if (e is CancellationException) throw e
        message.value = "저장 데이터 조회 실패: ${e.javaClass.simpleName} · 앱을 다시 열어 주세요"
    }
    val query = MutableStateFlow(TimelineQuery())
    val events = query.flatMapLatest { q ->
        val zone = ZoneId.systemDefault()
        graph.repository.dao.timeline(q.date.atStartOfDay(zone).toInstant().toEpochMilli(), q.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), q.date.toString(), q.type, q.inbox, q.limit)
    }.reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val summaries = graph.repository.dao.summaries().reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val briefing = graph.repository.dao.briefing().reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val busy = MutableStateFlow(false)
    val detail = MutableStateFlow<EventDetail?>(null)
    fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try { block(); message.value = "처리 완료" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "처리 실패: ${e.javaClass.simpleName}" }
            finally { busy.value = false }
        }
    }
    fun refresh() = work { graph.refresh() }
    fun open(event: LifeEvent) = work { detail.value = EventDetail(event, event.rawEventId?.let { graph.repository.dao.raw(it) }, graph.repository.dao.tagsFor(event.id), graph.repository.dao.entitiesFor(event.id)) }
    fun filter(type: String = "", inbox: Boolean = false, date: LocalDate = LocalDate.now()) { query.value = TimelineQuery(date, type, inbox) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { LifeScreen() } } }
}
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Column(Modifier.padding(20.dp)) { Text("생활 데이터 이용 안내", style = MaterialTheme.typography.headlineSmall); Text(PRIVACY); Button(onClick = { finish() }) { Text("닫기") } } } } }
    }
}
const val PRIVACY = "일정, 접근 가능한 알림 원문, 걸음·수면·운동 기록을 이 기기에 저장하고 Timeline과 요약에 사용해요. 생활 데이터의 서버 전송과 자동 백업은 하지 않아요. 업데이트 확인과 APK 다운로드에만 인터넷을 사용해요. 알림에는 결제 등 민감한 내용이 포함될 수 있어요. 권한은 기능별로 선택할 수 있고, 설정에서 수집 중지 및 저장 데이터 전체 삭제를 할 수 있어요. 수집 중단 이전의 알림 전체 복원은 지원하지 않아요. 건강 기록은 의료 판단에 사용하지 않아요."

@Composable
fun LifeScreen(vm: LifeViewModel = viewModel()) {
    val context = LocalContext.current
    val events by vm.events.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val briefing by vm.briefing.collectAsStateWithLifecycle()
    val states by vm.graph.status.states.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(vm.graph.status.enabled()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    if (showUpdates) { UpdateScreen(onBack = { showUpdates = false }); return }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val healthPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refresh() }
    LifecycleResumeEffect(Unit) {
        if (vm.graph.status.enabled()) vm.refresh()
        onPauseOrDispose { }
    }
    fun select(type: String, date: LocalDate = LocalDate.now()) { vm.filter(type, date = date); tab = 1 }
    Scaffold(Modifier.safeDrawingPadding(), bottomBar = {
        NavigationBar { listOf("대시보드", "타임라인", "알림함", "설정").forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 1 || i == 2) vm.filter(inbox = i == 2) }, icon = { Text(listOf("◷", "≡", "☷", "⚙")[i]) }, label = { Text(label) }) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Life Dashboard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 14.dp))
                TextButton(onClick = { vm.refresh() }, enabled = enabled && !busy) { Text("새로고침") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (!enabled && tab != 3) { Text("설정에서 이용 안내를 확인하고 수집을 시작해 주세요.", Modifier.padding(16.dp)); TextButton(onClick = { tab = 3 }) { Text("설정 열기") } }
            when (tab) {
                0 -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val today = LocalDate.now()
                    val current = summaries.find { it.date == today.toString() }
                    val yesterday = summaries.find { it.date == today.minusDays(1).toString() }
                    item {
                        Text("저장된 생활 기록", style = MaterialTheme.typography.titleMedium)
                        if (!states.getValue("CALENDAR").startsWith("수집 완료")) Text("일정: 최신 수집 미확인 · 저장된 기록만 표시", style = MaterialTheme.typography.bodySmall)
                        if (!states.getValue("HEALTH_CONNECT").startsWith("수집 완료")) Text("건강: 최신 수집 미확인 · 저장된 기록만 표시", style = MaterialTheme.typography.bodySmall)
                        if (!enabled) Text("수집 중지됨", style = MaterialTheme.typography.bodySmall)
                    }
                    item { Metric("오늘 일정", current?.let { "${it.calendarCount}개" } ?: "집계 대기", { select("CALENDAR") }) }
                    item { Metric("내일 일정", summaries.find { it.date == today.plusDays(1).toString() }?.let { "${it.calendarCount}개" } ?: "집계 대기", { select("CALENDAR", today.plusDays(1)) }) }
                    item { Metric("어제 종료된 수면", yesterday?.sleepMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "기록 없음", { select("SLEEP", today.minusDays(1)) }) }
                    item { Metric("오늘 걸음수", current?.stepCount?.let { "${it}보" } ?: "미수집 / 기록 없음", { select("STEP_SUMMARY") }) }
                    item { Metric("오늘 운동", current?.exerciseMinutes?.let { "${it}분" } ?: "기록 없음", { select("EXERCISE") }) }
                    item { Metric("오늘 결제 (취소 차감)", current?.let { "${it.paymentCount}건 · ${it.paymentAmount}원" } ?: "집계 대기", { select("PAYMENT") }) }
                    item { Metric("배송 알림", current?.let { "${it.deliveryCount}건" } ?: "집계 대기", { select("DELIVERY") }) }
                    item { Metric("예약 알림", current?.let { "${it.reservationCount}건" } ?: "집계 대기", { select("RESERVATION") }) }
                    item { Metric("중요 생활 알림", "결제·배송·예약은 위 카드에서 확인", { vm.filter(inbox = true); tab = 2 }) }
                    item { Text("수집 상태", style = MaterialTheme.typography.titleMedium); states.forEach { (source, state) -> Text("${sourceLabel(source)}: $state", style = MaterialTheme.typography.bodySmall) } }
                    item { HorizontalDivider(); Text(briefing?.title ?: "아침 브리핑", style = MaterialTheme.typography.titleMedium); Text(briefing?.summary ?: "데이터 수집 후 생성돼요.") }
                }
                1, 2 -> {
                    var dateInput by remember(query.date) { mutableStateOf(query.date.toString()) }
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.query.value = query.copy(date = query.date.minusDays(1), limit = 100) }) { Text("이전") }
                        OutlinedTextField(dateInput, { dateInput = it }, label = { Text("날짜 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { runCatching { LocalDate.parse(dateInput) }.onSuccess { vm.query.value = query.copy(date = it, limit = 100) }.onFailure { vm.message.value = "날짜 형식을 확인해 주세요" } }) { Text("이동") }
                        TextButton(onClick = { vm.query.value = query.copy(date = query.date.plusDays(1), limit = 100) }) { Text("다음") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "전체", "CALENDAR" to "일정", "HEALTH" to "건강", "SLEEP" to "수면", "STEP_SUMMARY" to "걸음", "EXERCISE" to "운동", "PAYMENT" to "결제", "DELIVERY" to "배송", "RESERVATION" to "예약", "NOTIFICATION" to "기타", "BRIEFING" to "브리핑").forEach { (type, label) -> FilterChip(query.type == type, onClick = { vm.query.value = query.copy(type = type, limit = 100) }, label = { Text(label) }) }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (events.isEmpty()) item { Text("이 날짜에 저장된 기록이 없어요.") }
                        items(events, key = { it.id }) { event -> Card(Modifier.fillMaxWidth().clickable { vm.open(event) }) { Column(Modifier.padding(12.dp)) {
                            Text("${if (event.calendarDate != null) "종일" else Instant.ofEpochMilli(if (event.type == "SLEEP") event.endedAt ?: event.occurredAt else event.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} · ${event.type} · ${event.status}", style = MaterialTheme.typography.labelMedium)
                            Text(event.title, style = MaterialTheme.typography.titleMedium); Text(event.summary.orEmpty(), maxLines = 3)
                        } } }
                        if (events.size >= query.limit) item { TextButton(onClick = { vm.query.value = query.copy(limit = query.limit + 100) }) { Text("더 보기") } }
                    }
                }
                3 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showUpdates = true }) { Text("앱 업데이트 · ${BuildConfig.VERSION_NAME}") }
                    Text("생활 데이터 이용 안내", style = MaterialTheme.typography.titleMedium); Text(PRIVACY)
                    Button(onClick = { enabled = !enabled; vm.graph.status.enable(enabled); if (enabled) { vm.graph.schedule(); vm.refresh() } }, enabled = !busy) { Text(if (enabled) "수집 중지" else "동의하고 수집 시작") }
                    Button(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }, enabled = enabled) { Text("일정 읽기 권한") }
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, enabled = enabled) { Text("알림 접근 설정") }
                    Button(onClick = {
                        if (vm.graph.health.availability() == HealthConnectClient.SDK_AVAILABLE) healthPermissions.launch(HealthCollector.permissions)
                        else vm.message.value = "Health Connect 설치 또는 업데이트가 필요해요"
                    }, enabled = enabled) { Text("건강 데이터 권한") }
                    Button(onClick = { if (vm.graph.health.backgroundSupported()) healthPermissions.launch(HealthCollector.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else vm.message.value = "이 기기는 백그라운드 건강 읽기를 지원하지 않아요" }, enabled = enabled) { Text("건강 백그라운드 권한 (선택)") }
                    Text("일정: 최근 30일~향후 90일 · 건강: 최근 29일 · 자동 갱신: 약 6시간 간격과 앱 실행 시. OS 제약으로 실행이 지연될 수 있어요.")
                    states.forEach { (source, state) -> Text("${sourceLabel(source)}: $state") }
                    OutlinedButton(onClick = { vm.work { vm.graph.reprocess() } }, enabled = !busy) { Text("저장된 원본 다시 분석") }
                    OutlinedButton(onClick = { deleteConfirm = true }, enabled = !busy) { Text("모든 로컬 데이터 삭제") }
                }
            }
        }
    }
    detail?.let { d -> AlertDialog(onDismissRequest = { vm.detail.value = null }, confirmButton = { TextButton(onClick = { vm.detail.value = null }) { Text("닫기") } }, title = { Text(d.event.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(d.event.summary.orEmpty()); Text("시작: ${Instant.ofEpochMilli(d.event.occurredAt).atZone(ZoneId.systemDefault())}")
        d.event.endedAt?.let { Text("종료: ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())}") }
        Text("태그: ${d.tags.joinToString { it.tag }}"); Text("엔티티: ${d.entities.joinToString { it.name }}")
        Text("구조화 데이터\n${d.event.dataJson}"); Text("원본 버전: ${d.raw?.revision ?: "파생 데이터"}\n${d.raw?.rawJson.orEmpty()}")
    } }) }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("저장된 데이터를 모두 삭제할까요?") }, text = { Text("이 앱의 원본·이벤트·집계를 삭제하고 수집을 중지해요. 원래 캘린더와 건강 앱의 데이터는 삭제하지 않아요.") }, confirmButton = { TextButton(onClick = { deleteConfirm = false; enabled = false; vm.work { vm.graph.clear() }; vm.detail.value = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("취소") } })
}
fun sourceLabel(source: String) = when (source) { "CALENDAR" -> "일정"; "HEALTH_CONNECT" -> "건강"; "NOTIFICATION" -> "알림"; else -> "집계/브리핑" }
@Composable fun Metric(title: String, value: String, click: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = click)) { Column(Modifier.padding(14.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.titleMedium) } } }
