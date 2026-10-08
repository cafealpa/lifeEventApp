package com.lifedashboard

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

data class TimelineQuery(val date: LocalDate = LocalDate.now(), val type: String = "", val inbox: Boolean = false, val limit: Int = 100, val oldestFirst: Boolean = false)
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
        graph.repository.dao.timeline(q.date.atStartOfDay(zone).toInstant().toEpochMilli(), q.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), q.date.toString(), q.type, q.inbox, q.limit, q.oldestFirst)
    }.reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val summaryState = graph.repository.dao.observeSummaryRows().reportReadFailure()
        .runningFold(DashboardSummaryState()) { previous, rows -> previous.update(rows) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DashboardSummaryState())
    val briefing = graph.repository.dao.briefing().reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val homeDate = MutableStateFlow(LocalDate.now())
    val schedules = homeDate.flatMapLatest { date ->
        val zone = ZoneId.systemDefault()
        graph.repository.dao.observeCalendarDay(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), date.toString())
    }.reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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
    fun refresh(force: Boolean = true) { homeDate.value = LocalDate.now(); work { graph.refresh(force = force) } }
    fun open(event: LifeEvent) = work { detail.value = EventDetail(event, event.rawEventId?.let { graph.repository.dao.raw(it) }, graph.repository.dao.tagsFor(event.id), graph.repository.dao.entitiesFor(event.id)) }
    fun classify(id: String, type: String?, amount: Long?, cancelled: Boolean) = work {
        val updated = graph.classifyNotification(id,type,amount,cancelled)
        if (detail.value?.event?.id == id) detail.value = EventDetail(updated, updated.rawEventId?.let { graph.repository.dao.raw(it) }, graph.repository.dao.tagsFor(id), graph.repository.dao.entitiesFor(id))
    }
    fun filter(type: String = "", inbox: Boolean = false, date: LocalDate = LocalDate.now()) { query.value = TimelineQuery(date, type, inbox, oldestFirst = query.value.oldestFirst) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { LifeTheme { LifeScreen() } } }
}
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LifeTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Column(Modifier.padding(20.dp)) { Text("생활 데이터 이용 안내", style = MaterialTheme.typography.headlineSmall); Text(PRIVACY); Button(onClick = { finish() }) { Text("닫기") } } } } }
    }
}
const val PRIVACY = "일정, 접근 가능한 알림 원문, 걸음·수면·운동 기록을 이 기기에 저장하고 Timeline과 요약에 사용해요. 생활 데이터의 서버 전송과 자동 백업은 하지 않아요. 업데이트 확인과 APK 다운로드에만 인터넷을 사용해요. 알림에는 결제 등 민감한 내용이 포함될 수 있어요. 권한은 기능별로 선택할 수 있고, 설정에서 수집 중지 및 저장 데이터 전체 삭제를 할 수 있어요. 수집 중단 이전의 알림 전체 복원은 지원하지 않아요. 건강 기록은 의료 판단에 사용하지 않아요."

@Composable
fun LifeScreen(vm: LifeViewModel = viewModel()) {
    val context = LocalContext.current
    val events by vm.events.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val summaryState by vm.summaryState.collectAsStateWithLifecycle()
    val summaries = summaryState.summaries
    val aggregating by vm.graph.aggregating.collectAsStateWithLifecycle()
    var showAggregationBadge by remember { mutableStateOf(false) }
    LaunchedEffect(summaryState.pending, aggregating) {
        if (summaryState.pending || aggregating) { delay(700); showAggregationBadge = true }
        else showAggregationBadge = false
    }
    val briefing by vm.briefing.collectAsStateWithLifecycle()
    val states by vm.graph.status.states.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(vm.graph.status.enabled()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val appLinks = remember(context) { HomeAppLinks(context) }
    var appLinkRevision by remember { mutableIntStateOf(0) }
    var appPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var launchAfterPick by rememberSaveable { mutableStateOf(false) }
    var showParserRules by rememberSaveable { mutableStateOf(false) }
    if (showParserRules) { ParserRulesScreen(vm) { showParserRules = false }; return }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    if (showUpdates) { UpdateScreen(onBack = { showUpdates = false }); return }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val healthPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refresh() }
    LifecycleResumeEffect(Unit) {
        vm.refresh(force = false)
        onPauseOrDispose { }
    }
    fun select(type: String, date: LocalDate = LocalDate.now()) { vm.filter(type, date = date); tab = 1 }
    BackHandler(enabled = tab != 0 && detail == null && !deleteConfirm) { tab = 0 }
    Scaffold(Modifier.safeDrawingPadding(), bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("홈", "타임라인").forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 1) vm.filter() },
                    icon = { LifeIcon(if(i == 0) "HOME" else "TIMELINE", tint = if(tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp,end = 8.dp,top = 12.dp,bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if(tab == 2 || tab == 3) LifeIconButton("BACK","뒤로") { tab = 0 }
                Column(Modifier.weight(1f)) {
                    if(tab == 0) Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M월 d일 EEEE", java.util.Locale.KOREAN)),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when(tab) { 0 -> "오늘 한눈에"; 1 -> "타임라인"; 2 -> "알림 보관함"; else -> "설정" }, style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.Bold)
                    if (tab == 0) {
                        val failed = states["PROCESSING"]?.startsWith("집계 실패") == true && !aggregating
                        if (showAggregationBadge || failed) {
                            Surface(
                                color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text(if (failed) "집계 실패 · 이전 값 유지" else "집계 중", Modifier.padding(horizontal = 10.dp,vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if(tab == 1 || tab == 2) Text(if(tab == 1) "차곡차곡 쌓이는 나의 하루" else "수집한 알림과 분류 결과",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LifeIconButton("REFRESH","새로고침",enabled && !busy) { vm.refresh() }
                if(tab != 3) LifeIconButton("SETTINGS","설정") { tab = 3 }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty() && message != "처리 완료") Text(message, Modifier.padding(horizontal = 20.dp,vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            if (!enabled && tab != 3) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer,shape = MaterialTheme.shapes.medium,modifier = Modifier.padding(horizontal = 20.dp,vertical = 8.dp)) {
                    Row(Modifier.padding(start = 12.dp,end = 4.dp),verticalAlignment = Alignment.CenterVertically) {
                        Text("수집을 시작하고 생활 기록을 모아보세요.",Modifier.weight(1f),style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { tab = 3 }) { Text("설정 열기") }
                    }
                }
            }
            when (tab) {
                0 -> DashboardContent(summaries,briefing,schedules,states,onLaunchApp = { type ->
                    if (!appLinks.open(type)) {
                        if (appLinks.selected(type) != null) vm.message.value = "연결한 앱을 열 수 없어요. 앱을 다시 선택해 주세요."
                        launchAfterPick = true
                        appPicker = type
                    }
                },onSelect = { type,date -> select(type,date) },onInbox = { vm.filter(inbox = true); tab = 2 },onOpen = vm::open)
                1, 2 -> TimelineContent(events,query,onQuery = { vm.query.value = it },onOpen = vm::open)
                3 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showUpdates = true }) { Text("앱 업데이트 · ${BuildConfig.VERSION_NAME}") }
                    OutlinedButton(onClick = { showParserRules = true }, enabled = !busy) { Text("알림 파서 규칙") }
                    HomeAppLinkSettings(appLinks, appLinkRevision) { type -> launchAfterPick = false; appPicker = type }
                    Text("자동 삭제: 알림 발생 시각부터 광고 14일 · 기타 30일. 원본도 함께 삭제해요. 앱을 열거나 약 6시간마다 정리하며, 수집 중지 중에도 적용돼요.", style = MaterialTheme.typography.bodySmall)
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
    appPicker?.let { type ->
        HomeAppPicker(type, appLinks, onChoose = { packageName ->
            appLinks.select(type, packageName)
            appLinkRevision++
            appPicker = null
            if (packageName != null && launchAfterPick && !appLinks.open(type)) {
                vm.message.value = "선택한 앱을 열 수 없어요. 설정에서 다른 앱을 선택해 주세요."
            }
            launchAfterPick = false
        }, onDismiss = { appPicker = null; launchAfterPick = false })
    }
    detail?.let { EventDetailDialog(it, busy = busy, message = message, onClassify = { type, amount, cancelled -> vm.classify(it.event.id,type,amount,cancelled) }, onDismiss = { vm.detail.value = null }) }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("저장된 데이터를 모두 삭제할까요?") }, text = { Text("이 앱의 원본·이벤트·집계를 삭제하고 수집을 중지해요. 원래 캘린더와 건강 앱의 데이터는 삭제하지 않아요.") }, confirmButton = { TextButton(onClick = { deleteConfirm = false; enabled = false; vm.work { vm.graph.clear() }; vm.detail.value = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("취소") } })
}
fun sourceLabel(source: String) = when (source) { "CALENDAR" -> "일정"; "HEALTH_CONNECT" -> "건강"; "NOTIFICATION" -> "알림"; else -> "집계/브리핑" }
