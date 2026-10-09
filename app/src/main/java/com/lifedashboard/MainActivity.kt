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
    private val cardFlows = mutableMapOf<Triple<CardQuery,LocalDate,ZoneId>, StateFlow<CardResult>>()
    fun cardResults(query: CardQuery, today: LocalDate, zone: ZoneId): StateFlow<CardResult> {
        cardFlows.keys.removeAll { it.second != today || it.third != zone }
        val key=Triple(query,today,zone)
        if(cardFlows.size>=40 && key !in cardFlows) cardFlows.clear()
        return cardFlows.getOrPut(key) { graph.cardQueries.observe(query,today,zone).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),CardResult()) }
    }
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
    private val homeDate = MutableStateFlow(LocalDate.now())
    val homeNotifications = homeDate.flatMapLatest { date ->
        val zone = ZoneId.systemDefault()
        graph.repository.dao.observeHomeNotifications(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }.reportReadFailure().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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
    fun updateHomeDate() {
        if (homeDate.value == LocalDate.now()) return
        homeDate.value = LocalDate.now()
        viewModelScope.launch(Dispatchers.IO) {
            try { graph.derive() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "날짜 갱신 실패: ${e.javaClass.simpleName}" }
        }
    }
    fun open(event: LifeEvent) = work { detail.value = EventDetail(event, event.rawEventId?.let { graph.repository.dao.raw(it) }, graph.repository.dao.tagsFor(event.id), graph.repository.dao.entitiesFor(event.id)) }
    fun classify(id: String, type: String?, amount: Long?, cancelled: Boolean) = work {
        val updated = graph.classifyNotification(id,type,amount,cancelled)
        if (detail.value?.event?.id == id) detail.value = EventDetail(updated, updated.rawEventId?.let { graph.repository.dao.raw(it) }, graph.repository.dao.tagsFor(id), graph.repository.dao.entitiesFor(id))
    }
    fun deleteNotification(id: String) = work {
        try { graph.deleteNotification(id) }
        finally {
            // Deletion may have succeeded even if the following summary refresh failed.
            if (graph.repository.dao.eventById(id) == null && detail.value?.event?.id == id) detail.value = null
        }
    }
    fun filter(type: String = "", inbox: Boolean = false, date: LocalDate = LocalDate.now()) { query.value = TimelineQuery(date, type, inbox, oldestFirst = query.value.oldestFirst) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { prepareLifeTheme(); super.onCreate(savedInstanceState); setContent { LifeTheme { LifeScreen() } } }
}
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        prepareLifeTheme()
        super.onCreate(savedInstanceState)
        setContent { LifeTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Column(Modifier.padding(20.dp)) { Text("생활 데이터 이용 안내", style = MaterialTheme.typography.headlineSmall); Text(PRIVACY); Button(onClick = { finish() }) { Text("닫기") } } } } }
    }
}
const val PRIVACY = "일정, 접근 가능한 알림 원문, 걸음·수면·운동 기록과 선택한 SpotTrace 방문 기록을 이 기기에 저장하고 Timeline과 요약에 사용해요. 생활 데이터의 서버 전송과 자동 백업은 하지 않아요. 업데이트 확인과 APK 다운로드에만 인터넷을 사용해요. 알림에는 결제 등 민감한 내용이 포함될 수 있어요. 권한은 기능별로 선택할 수 있고, 설정에서 수집 중지 및 저장 데이터 전체 삭제를 할 수 있어요. 수집 중단 이전의 알림 전체 복원은 지원하지 않아요. 건강 기록은 의료 판단에 사용하지 않아요."

@Composable
fun LifeScreen(vm: LifeViewModel = viewModel()) {
    val context = LocalContext.current
    val events by vm.events.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val homeNotifications by vm.homeNotifications.collectAsStateWithLifecycle()
    val summaryState by vm.summaryState.collectAsStateWithLifecycle()
    val summaries = summaryState.summaries
    val aggregating by vm.graph.aggregating.collectAsStateWithLifecycle()
    var showAggregationBadge by remember { mutableStateOf(false) }
    LaunchedEffect(summaryState.pending, aggregating) {
        if (summaryState.pending || aggregating) { delay(700); showAggregationBadge = true }
        else showAggregationBadge = false
    }
    val states by vm.graph.status.states.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var enabled by remember { mutableStateOf(vm.graph.status.enabled()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val appLinks = remember(context) { HomeAppLinks(context) }
    var appLinkRevision by remember { mutableIntStateOf(0) }
    var appPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var launchAfterPick by rememberSaveable { mutableStateOf(false) }
    var showParserRules by rememberSaveable { mutableStateOf(false) }
    val cards by vm.graph.cards.cards.collectAsStateWithLifecycle()
    var showCardEditor by rememberSaveable { mutableStateOf(false) }
    var cardRecords by remember { mutableStateOf<DashboardCardSpec?>(null) }
    if(showCardEditor) { DashboardEditor(vm) { showCardEditor=false }; return }
    if (showParserRules) { ParserRulesScreen(vm) { showParserRules = false }; return }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    if (showUpdates) { UpdateScreen(onBack = { showUpdates = false }); return }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permissionStates = rememberPermissionStates(vm.graph, permissionRevision)
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++; vm.refresh() }
    val healthPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { permissionRevision++; vm.refresh() }
    val screenScope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        vm.refresh(force = false)
        val clockJob = screenScope.launch { while (true) { delay(60_000); vm.updateHomeDate() } }
        onPauseOrDispose { clockJob.cancel() }
    }
    fun select(type: String, date: LocalDate = LocalDate.now()) { vm.filter(type, date = date); tab = 1 }
    fun goBack() { if (tab == 3 && settingsPage != null) settingsPage = null else tab = 0 }
    BackHandler(enabled = tab != 0 && detail == null && !deleteConfirm && appPicker == null) { goBack() }
    Scaffold(Modifier.safeDrawingPadding(), bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("홈", "타임라인").forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; settingsPage = null; if (i == 1) vm.filter() },
                    icon = { LifeIcon(if(i == 0) "HOME" else "TIMELINE", tint = if(tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp,end = 8.dp,top = 12.dp,bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if(tab == 2 || tab == 3) LifeIconButton("BACK","뒤로") { goBack() }
                Column(Modifier.weight(1f)) {
                    if(tab == 0) Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M월 d일 EEEE", java.util.Locale.KOREAN)),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when(tab) { 0 -> "오늘 한눈에"; 1 -> "타임라인"; 2 -> "알림 보관함"; else -> settingsPage?.title ?: "설정" }, style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.Bold)
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
                if(tab != 3) LifeIconButton("SETTINGS","설정") { settingsPage = null; tab = 3 }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty() && message != "처리 완료") Text(message, Modifier.padding(horizontal = 20.dp,vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            if (!enabled && tab != 3) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer,shape = MaterialTheme.shapes.medium,modifier = Modifier.padding(horizontal = 20.dp,vertical = 8.dp)) {
                    Row(Modifier.padding(start = 12.dp,end = 4.dp),verticalAlignment = Alignment.CenterVertically) {
                        Text("수집을 시작하고 생활 기록을 모아보세요.",Modifier.weight(1f),style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { settingsPage = null; tab = 3 }) { Text("설정 열기") }
                    }
                }
            }
            when (tab) {
                0 -> DashboardContent(summaries,schedules,homeNotifications,states,onLaunchApp = { type ->
                    if (!appLinks.open(type)) {
                        if (appLinks.selected(type) != null) vm.message.value = "연결한 앱을 열 수 없어요. 앱을 다시 선택해 주세요."
                        launchAfterPick = true
                        appPicker = type
                    }
                },onSelect = { type,date -> select(type,date) },onInbox = { vm.filter(inbox = true); tab = 2 },onOpen = vm::open,vm=vm,cards=cards,onEdit={showCardEditor=true},onCard={cardRecords=it})
                1, 2 -> TimelineContent(events,query,onQuery = { vm.query.value = it },onOpen = vm::open)
                3 -> key(settingsPage) { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (settingsPage == null) SettingsOverview(enabled, permissionStates, states) { settingsPage = it }
                    if (settingsPage == SettingsPage.THEME) ThemeSettings()
                    if (settingsPage == SettingsPage.COLLECTION) {
                    SettingsCard("생활 데이터 이용 안내", "HEALTH") {
                        Text(PRIVACY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(if (enabled) "수집 켜짐" else "수집 꺼짐", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                        Button(onClick = { enabled = !enabled; vm.graph.status.enable(enabled); if (enabled) { vm.graph.schedule(); vm.refresh() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (enabled) "수집 중지" else "동의하고 수집 시작") }
                        Text("권한 상태", style = MaterialTheme.typography.titleSmall)
                        if (!enabled) Text("수집에 동의하면 아래에서 필요한 권한을 허용할 수 있어요.", style = MaterialTheme.typography.bodySmall)
                        PermissionAction("일정 읽기", "캘린더에 저장된 일정을 가져와요", permissionStates.calendar, enabled && !busy) {
                            val permissionPrefs = vm.graph.context.getSharedPreferences("permission-ui", 0)
                            val activity = context as? android.app.Activity
                            val blocked = permissionPrefs.getBoolean("calendarRequested", false) && activity != null &&
                                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
                            if (permissionStates.calendar.allowed || blocked) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                            } else {
                                permissionPrefs.edit().putBoolean("calendarRequested", true).apply()
                                calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                            }
                        }
                        PermissionAction("알림 접근", "다른 앱에서 도착한 알림을 수집해요", permissionStates.notification, enabled && !busy) {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        PermissionAction("건강 데이터", "걸음수 · 수면 · 운동 권한을 확인해요", permissionStates.health, enabled && !busy) {
                            if (vm.graph.health.availability() == HealthConnectClient.SDK_AVAILABLE) {
                                if (permissionStates.health.allowed) context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                                else healthPermissions.launch(HealthCollector.permissions)
                            } else vm.message.value = "Health Connect 설치 또는 업데이트가 필요해요"
                        }
                        PermissionAction("건강 백그라운드 · 선택", "앱을 닫아도 건강 기록을 읽을 수 있어요", permissionStates.background, enabled && !busy) {
                            if (vm.graph.health.backgroundSupported()) {
                                if (permissionStates.background.allowed) context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                                else healthPermissions.launch(HealthCollector.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                            } else vm.message.value = "이 기기는 백그라운드 건강 읽기를 지원하지 않아요"
                        }
                    }
                    SettingsCard("수집 상태", "REFRESH") {
                        Text("일정: 최근 30일~향후 90일 · 건강: 최근 29일\n약 6시간 간격 및 앱 복귀 시 갱신해요. 정상 수집 후 5분 이내 복귀는 생략하며 OS에 따라 실행이 늦어질 수 있어요.", style = MaterialTheme.typography.bodySmall)
                        states.entries.forEachIndexed { index, (source, state) ->
                            if (index > 0) HorizontalDivider()
                            Text(sourceLabel(source), style = MaterialTheme.typography.labelLarge)
                            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HealthDiagnosticsSettings(vm)
                    SpotTraceSettings(vm)
                    }
                    if (settingsPage == SettingsPage.HOME_APPS) {
                        OutlinedButton(onClick={showCardEditor=true},modifier=Modifier.fillMaxWidth()) { Text("카드 추가 · 순서 · 나만의 집계") }
                        HomeAppLinkSettings(appLinks, appLinkRevision) { type -> launchAfterPick = false; appPicker = type }
                    }
                    if (settingsPage == SettingsPage.NOTIFICATIONS) SettingsCard("알림 분류 및 보관", "NOTIFICATION") {
                        SettingsMenuRow("알림 분류 규칙", "규칙 추가 · 수정 · 순서 · 예시 테스트", "NOTIFICATION", !busy) { showParserRules = true }
                        HorizontalDivider()
                        Text("광고 14일 · 기타 30일 후 자동 삭제", style = MaterialTheme.typography.titleSmall)
                        Text("현재 보관 기간은 고정이에요. 알림 발생 시각 기준으로 원본도 함께 삭제해요. 수집 중지 중에도 앱 실행 또는 약 6시간마다 정리하며 OS에 따라 실행이 늦어질 수 있어요.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (settingsPage == SettingsPage.DATA) SettingsCard("데이터 관리", "TIMELINE") {
                        OutlinedButton(onClick = { vm.work { vm.graph.reprocess() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("저장된 원본 다시 분석") }
                        OutlinedButton(onClick = { deleteConfirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("모든 로컬 데이터 삭제") }
                    }
                    if (settingsPage == SettingsPage.ABOUT) SettingsCard("앱 정보", "SETTINGS") {
                        Text("Life Dashboard · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { showUpdates = true }, modifier = Modifier.fillMaxWidth()) { Text("앱 업데이트") }
                    }
                } }
            }
        }
    }
    cardRecords?.let { CardRecordsDialog(vm,it) { cardRecords=null } }
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
    detail?.let { EventDetailDialog(it, busy = busy, message = message, onClassify = { type, amount, cancelled -> vm.classify(it.event.id,type,amount,cancelled) }, onDelete = { vm.deleteNotification(it.event.id) }, onDismiss = { vm.detail.value = null }) }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("저장된 데이터를 모두 삭제할까요?") }, text = { Text("이 앱의 원본·이벤트·집계를 삭제하고 수집과 SpotTrace 연동을 중지해요. 카드 구성은 유지해요. 원래 캘린더·건강·SpotTrace 데이터는 삭제하지 않아요.") }, confirmButton = { TextButton(onClick = { deleteConfirm = false; enabled = false; vm.work { vm.graph.clear() }; vm.detail.value = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("취소") } })
}
fun sourceLabel(source: String) = when (source) { "CALENDAR" -> "일정"; "HEALTH_CONNECT" -> "건강"; "NOTIFICATION" -> "알림"; "SPOTTRACE" -> "SpotTrace 방문"; else -> "집계" }
