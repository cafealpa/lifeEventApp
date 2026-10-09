package com.lifedashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun cardResult(vm: LifeViewModel, query: CardQuery): CardResult {
    var clock by remember { mutableStateOf(LocalDate.now() to ZoneId.systemDefault()) }
    LaunchedEffect(Unit) { while(true) { clock = LocalDate.now() to ZoneId.systemDefault(); kotlinx.coroutines.delay(30_000) } }
    val flow = remember(query,clock) { vm.cardResults(query,clock.first,clock.second) }
    return key(query,clock) { flow.collectAsStateWithLifecycle(CardResult()).value }
}

@Composable
fun QueryCard(vm: LifeViewModel, spec: DashboardCardSpec, modifier: Modifier = Modifier, onOpen: (DashboardCardSpec) -> Unit) {
    val query = spec.query ?: CardQuery("PLACE_VISIT",transition="ENTER")
    val result = cardResult(vm,query)
    val states by vm.graph.status.states.collectAsStateWithLifecycle()
    val source = when(query.type) { "PLACE_VISIT" -> "SPOTTRACE"; "CALENDAR" -> "CALENDAR"; "SLEEP","EXERCISE","STEP_SUMMARY" -> "HEALTH_CONNECT"; else -> "NOTIFICATION" }
    val status = states[source].orEmpty()
    val unknownEmpty=result.records.isEmpty() && !result.error && result.value!="조회 중" && !status.startsWith("수집 완료")
    StatCard(spec.title,if(unknownEmpty) "수집 확인 필요" else result.value,query.type,result.note + "\n" + if(status.startsWith("수집 완료")) "최근 수집 기록 기준" else "최신 수집 미확인 · 설정에서 확인",modifier) { onOpen(spec.copy(query=query)) }
}

@Composable
fun CardRecordsDialog(vm: LifeViewModel, spec: DashboardCardSpec, onDismiss: () -> Unit) {
    val result = cardResult(vm,requireNotNull(spec.query))
    var limit by remember(spec.id) { mutableIntStateOf(100) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(spec.title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=onDismiss) { Text("닫기") }
                }
                Text("${result.value}\n${result.note}",Modifier.padding(16.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(result.records.take(limit),key={it.id}) { e ->
                        SummaryRow(e.type,"${eventDate(e,ZoneId.systemDefault())} ${eventTime(e)} · ${e.title}",e.summary.orEmpty()) { vm.open(e) }
                    }
                    if(result.records.size > limit) item { TextButton(onClick={limit+=100}) { Text("기록 더 보기") } }
                }
            }
        }
    }
}

@Composable
fun Choice(label: String, selected: String, options: List<Pair<String,String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first==selected }?.second ?: selected.ifBlank { "선택" }) }
            DropdownMenu(expanded,onDismissRequest={expanded=false}) { options.forEach { (value,text) ->
                DropdownMenuItem(text={Text(text)},onClick={onSelect(value);expanded=false})
            } }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DashboardEditor(vm: LifeViewModel, onBack: () -> Unit) {
    val cards by vm.graph.cards.cards.collectAsStateWithLifecycle()
    val error by vm.graph.cards.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DashboardCardSpec?>(null) }
    var addBasic by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    fun save(value: List<DashboardCardSpec>) { vm.work { vm.graph.cards.save(value) } }
    BackHandler { if(editing!=null) editing=null else onBack() }
    if(editing!=null) {
        CardForm(vm,editing!!,onCancel={editing=null}) { spec ->
            vm.work {
                val current=vm.graph.cards.cards.value
                vm.graph.cards.save(if(current.any { it.id==spec.id }) current.map { if(it.id==spec.id) spec else it } else current+spec)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { editing=null }
            }
        }
        return
    }
    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) { Text("홈 카드 편집",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall); TextButton(onClick=onBack) { Text("완료") } } }
            item { Text("홈에서 제거해도 생활 기록은 그대로 보관해요. 최대 20개까지 추가할 수 있어요.") }
            error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            if(message.isNotEmpty() && message!="처리 완료") item { Text(message,color=MaterialTheme.colorScheme.error) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={addBasic=true},enabled=!busy && cards.size<20,modifier=Modifier.weight(1f)) { Text("기본 카드 추가") }
                Button(onClick={editing=DashboardCardSpec(kind="CUSTOM",title="나만의 카드",query=CardQuery())},enabled=!busy && cards.size<20,modifier=Modifier.weight(1f)) { Text("나만의 카드") }
            } }
            items(cards,key={it.id}) { card -> Card {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(card.title,style=MaterialTheme.typography.titleMedium)
                    Text(if(card.kind=="CUSTOM") "${card.query!!.period.label} · ${dashboardTypes[card.query.type]} · ${card.query.metric.label}" else "기본 카드",style=MaterialTheme.typography.bodySmall)
                    FlowRow {
                        val index=cards.indexOf(card)
                        TextButton(onClick={save(cards.toMutableList().apply { add(index-1,removeAt(index)) })},enabled=!busy && index>0) { Text("위로") }
                        TextButton(onClick={save(cards.toMutableList().apply { add(index+1,removeAt(index)) })},enabled=!busy && index<cards.lastIndex) { Text("아래로") }
                        TextButton(onClick={editing=card},enabled=!busy) { Text("수정") }
                        TextButton(onClick={save(cards.filterNot { it.id==card.id })},enabled=!busy) { Text("제거") }
                    }
                }
            } }
            item { TextButton(onClick={reset=true},enabled=!busy) { Text("기본 구성 복원") } }
        }
    }
    if(addBasic) AlertDialog(onDismissRequest={addBasic=false},title={Text("기본 카드 추가")},text={
        LazyColumn { items(basicCards.entries.toList()) { (kind,title) -> TextButton(onClick={
            save(cards+DashboardCardSpec(kind=kind,title=title,halfWidth=kind in setOf("CALENDAR","SLEEP","STEP_SUMMARY","EXERCISE"))); addBasic=false
        }) { Text(title) } } }
    },confirmButton={TextButton(onClick={addBasic=false}) { Text("닫기") }})
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text("기본 구성으로 복원할까요?")},text={Text("만든 카드 설정을 기본 구성으로 바꿔요. 생활 기록은 삭제하지 않아요.")},confirmButton={TextButton(onClick={save(DashboardCardStore.defaults());reset=false}) { Text("복원") }},dismissButton={TextButton(onClick={reset=false}) { Text("취소") }})
}

@Composable
private fun CardForm(vm: LifeViewModel, initial: DashboardCardSpec, onCancel: () -> Unit, onSave: (DashboardCardSpec) -> Unit) {
    val context=LocalContext.current
    val appLinks=remember(context) { HomeAppLinks(context) }
    var appPicker by remember { mutableStateOf(false) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var half by remember(initial.id) { mutableStateOf(initial.halfWidth) }
    var query by remember(initial.id) { mutableStateOf(initial.query ?: CardQuery()) }
    var preview by remember { mutableStateOf(false) }
    val placeFlow=remember(vm) { vm.graph.repository.dao.visitPlaces().catch { e ->
        if(e is kotlinx.coroutines.CancellationException) throw e
        vm.message.value="장소 목록을 읽지 못했어요. 화면을 다시 열어 주세요."
        emit(emptyList())
    } }
    val places by placeFlow.collectAsStateWithLifecycle(emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    BackHandler(onBack=onCancel)
    Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("카드 설정",style=MaterialTheme.typography.headlineSmall) }
            item { OutlinedTextField(title,{if(it.length<=60)title=it},label={Text("카드 이름")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
            item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(half,{half=it}); Text("작은 카드 · 2열로 배치") } }
            if(initial.kind=="CUSTOM") {
                item { Choice("데이터 종류",query.type,dashboardTypes.toList()) { query=CardQuery(type=it,period=query.period);preview=false } }
                item { Choice("기간 · 이번 주/달은 오늘까지",query.period.name,CardPeriod.entries.map { it.name to it.label }) { query=query.copy(period=CardPeriod.valueOf(it));preview=false } }
                item { Choice("집계 방식",query.metric.name,cardMetrics(query.type).map { it.name to it.label }) { query=query.copy(metric=CardMetric.valueOf(it),transition=if(it=="ATTENDANCE") "" else query.transition);preview=false } }
                if(query.type=="PAYMENT") {
                    item { OutlinedTextField(query.merchant,{query=query.copy(merchant=it);preview=false},label={Text("가맹점 정확히 일치 · 선택")},modifier=Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(query.cardCompany,{query=query.copy(cardCompany=it);preview=false},label={Text("카드사 정확히 일치 · 선택")},modifier=Modifier.fillMaxWidth()) }
                }
                if(query.type=="PLACE_VISIT") {
                    item { Choice("장소", if(query.placeId.isEmpty() && query.datasetId.isEmpty()) "" else "${query.datasetId}|${query.placeId}|${query.placeName}",
                        listOf("" to "전체 장소")+places.map { "${it.datasetId}|${it.placeId}|${it.name}" to (it.name+if(it.placeId.isEmpty()) " · 과거 이름 기록" else " · ${it.placeId.takeLast(6)}") }) { key ->
                        val place=places.firstOrNull { "${it.datasetId}|${it.placeId}|${it.name}"==key }
                        query=query.copy(datasetId=place?.datasetId.orEmpty(),placeId=place?.placeId.orEmpty(),placeName=place?.name.orEmpty(),includeLegacy=false);preview=false
                    } }
                    if(query.placeId.isEmpty()) item { OutlinedTextField(query.placeName,{query=query.copy(placeName=it);preview=false},label={Text("장소 이름 정확히 일치 · 선택")},modifier=Modifier.fillMaxWidth()) }
                    else item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(query.includeLegacy,{query=query.copy(includeLegacy=it);preview=false}); Text("ID 없는 과거 기록도 같은 이름이면 포함") } }
                    item { Text("동명 장소의 과거 이름 기록은 서로 구분할 수 없어요.",style=MaterialTheme.typography.bodySmall) }
                    if(query.metric!=CardMetric.ATTENDANCE) item { Choice("진입/이탈",query.transition,listOf("" to "모두","ENTER" to "진입만","EXIT" to "이탈만")) { query=query.copy(transition=it);preview=false } }
                }
                if(query.type in setOf("PAYMENT","DELIVERY","RESERVATION","NOTIFICATION","ADVERTISEMENT")) item {
                    val label=remember(query.packageName) {
                        if(query.packageName.isBlank()) "모든 앱" else try {
                            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(query.packageName,0)).toString()
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "삭제된 앱 · ${query.packageName}" }
                    }
                    Text("보낸 앱 · $label",style=MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={appPicker=true}) {Text("앱 선택")}
                        if(query.packageName.isNotBlank()) TextButton(onClick={query=query.copy(packageName="");preview=false}) {Text("조건 해제")}
                    }
                }
                item { OutlinedTextField(query.text,{query=query.copy(text=it);preview=false},label={Text("제목·요약에 포함된 문구 · 선택")},modifier=Modifier.fillMaxWidth()) }
                item { Text("조건은 모두 만족해야 해요. 빈 조건은 제한하지 않아요. 평균은 기록이 있는 날만 계산하고 결제 합계는 취소를 차감해요.",style=MaterialTheme.typography.bodySmall) }
                item { OutlinedButton(onClick={preview=true},enabled=runCatching { query.validate() }.isSuccess) { Text("저장 데이터로 미리보기") } }
                if(preview) item {
                    val result=cardResult(vm,query)
                    Card { Column(Modifier.padding(16.dp)) { Text(result.value,style=MaterialTheme.typography.titleLarge);Text(result.note);result.records.take(3).forEach {Text("${eventDate(it,ZoneId.systemDefault())} · ${it.title}")} } }
                }
            }
            if(message.isNotEmpty() && message!="처리 완료") item { Text(message,color=MaterialTheme.colorScheme.error) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onCancel,enabled=!busy,modifier=Modifier.weight(1f)) { Text("취소") }
                Button(onClick={onSave(initial.copy(title=title.trim(),halfWidth=half,query=if(initial.kind=="CUSTOM")query else initial.query))},enabled=!busy && title.isNotBlank() && runCatching {query.validate()}.isSuccess,modifier=Modifier.weight(1f)) { Text("저장") }
            } }
        }
    }
    if(appPicker) HomeAppPicker("CARD_FILTER",appLinks,onChoose={query=query.copy(packageName=it.orEmpty());preview=false;appPicker=false},onDismiss={appPicker=false},pickerTitle="보낸 앱 선택",pickerDescription="이 앱에서 받은 알림만 카드에 포함해요",showConnection=false)
}
