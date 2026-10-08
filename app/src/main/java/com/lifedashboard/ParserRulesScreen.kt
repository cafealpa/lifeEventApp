package com.lifedashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@Composable
fun ParserRulesScreen(vm: LifeViewModel, onBack: () -> Unit) {
    val set by vm.graph.parserRules.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ParserRule?>(null) }
    var deleting by remember { mutableStateOf<ParserRule?>(null) }
    fun save(rules: List<ParserRule>) { vm.work { vm.graph.saveParserRules(rules) } }
    BackHandler { if (!busy) { if (editing != null) editing = null else onBack() } }
    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LifeIconButton("BACK", "뒤로", !busy) { if (editing != null) editing = null else onBack() }
                Text(if (editing == null) "알림 분류 규칙" else "규칙 편집", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (editing == null) TextButton(onClick = { editing = ParserRule() }, enabled = !busy) { Text("규칙 추가") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("규칙 적용 및 저장된 원본 재분석 중…", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
            if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            val draft = editing
            if (draft != null) {
                key(draft.id) {
                    ParserRuleEditor(draft, set, busy, onSave = { rule ->
                        val updated = if (set.rules.any { it.id == rule.id }) set.rules.map { if (it.id == rule.id) rule else it } else set.rules + rule
                        save(updated)
                        editing = null
                    })
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("위쪽 규칙부터 적용해요", style = MaterialTheme.typography.titleMedium)
                        Text("직접 분류한 알림과 (광고) 접두사가 우선해요. 나머지는 첫 번째로 일치하는 규칙을 사용하고, 없으면 기본 파서가 처리해요.", style = MaterialTheme.typography.bodyMedium)
                        Text("저장·켜기/끄기·순서 변경·삭제 시 기존 알림도 재분석해요. 원본과 직접 분류는 유지돼요.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (set.rules.isEmpty()) item {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                            Text("아직 만든 규칙이 없어요", style = MaterialTheme.typography.titleMedium)
                            Text("예: ‘결제가 완료되었습니다’가 포함된 알림을 결제로 분류", Modifier.padding(top = 8.dp))
                        } }
                    }
                    itemsIndexed(set.rules, key = { _, rule -> rule.id }) { index, rule ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. ${rule.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(checked = rule.enabled, onCheckedChange = { value -> save(set.rules.map { if (it.id == rule.id) it.copy(enabled = value) else it }) }, enabled = !busy)
                            }
                            Text("${notificationClassifications[rule.type]} · ${if (rule.packageName.isBlank()) "모든 앱" else rule.packageName}", style = MaterialTheme.typography.labelMedium)
                            Text("‘${rule.phrase}’ ${if (rule.match == "PREFIX") "로 시작" else "포함"}")
                            Row {
                                TextButton(onClick = { editing = rule }, enabled = !busy) { Text("수정") }
                                TextButton(onClick = { deleting = rule }, enabled = !busy) { Text("삭제") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { val rows = set.rules.toMutableList(); rows.add(index - 1, rows.removeAt(index)); save(rows) }, enabled = !busy && index > 0) { Text("위로") }
                                TextButton(onClick = { val rows = set.rules.toMutableList(); rows.add(index + 1, rows.removeAt(index)); save(rows) }, enabled = !busy && index < set.rules.lastIndex) { Text("아래로") }
                            }
                        } }
                    }
                    item { OutlinedButton(onClick = { vm.work { vm.graph.reprocess() } }, enabled = !busy) { Text("저장된 원본 다시 분석") } }
                }
            }
        }
    }
    deleting?.let { rule -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("규칙을 삭제할까요?") },
        text = { Text("${rule.name} 규칙을 삭제하고 남은 규칙으로 기존 알림을 다시 분석해요.") },
        confirmButton = { TextButton(onClick = { deleting = null; save(set.rules.filterNot { it.id == rule.id }) }, enabled = !busy) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
}

@Composable
private fun RuleChoice(label: String, value: String, options: Map<String, String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label · ${options[value]}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(key) }) }
        }
    }
}

@Composable
private fun ParserRuleEditor(initial: ParserRule, set: ParserRuleSet, busy: Boolean, onSave: (ParserRule) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var sourcePackage by rememberSaveable { mutableStateOf(initial.packageName) }
    var field by rememberSaveable { mutableStateOf(initial.field) }
    var match by rememberSaveable { mutableStateOf(initial.match) }
    var phrase by rememberSaveable { mutableStateOf(initial.phrase) }
    var exclude by rememberSaveable { mutableStateOf(initial.exclude) }
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var amountLabel by rememberSaveable { mutableStateOf(initial.amountLabel) }
    var kind by rememberSaveable { mutableStateOf(initial.paymentKind) }
    var testTitle by rememberSaveable { mutableStateOf("") }
    var testBody by rememberSaveable { mutableStateOf("") }
    var testPackage by rememberSaveable { mutableStateOf(initial.packageName) }
    var preview by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val links = remember(context) { HomeAppLinks(context) }
    val rule = initial.copy(name = name.trim(), packageName = sourcePackage.trim(), field = field, match = match,
        phrase = phrase.trim(), exclude = exclude.trim(), type = type, amountLabel = amountLabel.trim(), paymentKind = kind)
    val valid = name.isNotBlank() && phrase.isNotBlank() && (type != "PAYMENT" || amountLabel.isNotBlank())
    LaunchedEffect(rule, testTitle, testBody, testPackage) { preview = "" }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("규칙 이름") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourcePackage, { sourcePackage = it }, label = { Text("보낸 앱 패키지 · 비우면 모든 앱") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Row { TextButton(onClick = { picking = true }, enabled = !busy) { Text("앱 목록에서 선택") }; TextButton(onClick = { sourcePackage = "" }, enabled = !busy) { Text("모든 앱") } }
        RuleChoice("검사 위치", field, linkedMapOf("BOTH" to "제목 또는 본문", "TITLE" to "제목", "BODY" to "본문"), !busy) { field = it }
        RuleChoice("일치 방식", match, linkedMapOf("CONTAINS" to "문구 포함", "PREFIX" to "문구로 시작"), !busy) { match = it }
        OutlinedTextField(phrase, { phrase = it }, label = { Text("찾을 문구") }, supportingText = { Text("입력한 문구 그대로 비교해요. 영문 대소문자는 구분하지 않아요.") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exclude, { exclude = it }, label = { Text("제외 문구 · 선택") }, supportingText = { Text("제목이나 본문에 이 문구가 있으면 건너뛰어요.") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        RuleChoice("분류", type, notificationClassifications, !busy) { type = it }
        if (type == "PAYMENT") {
            OutlinedTextField(amountLabel, { amountLabel = it }, label = { Text("금액 앞 문구") }, supportingText = { Text("예: 결제금액 12,300원 → ‘결제금액’. 원 단위 금액이 하나일 때만 집계해요.") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            RuleChoice("결제 구분", kind, linkedMapOf("APPROVAL" to "승인", "CANCELLATION" to "취소"), !busy) { kind = it }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("저장 전 테스트", style = MaterialTheme.typography.titleMedium)
        Text("예시 입력은 저장하지 않아요. 직접 분류를 제외한 자동 파서 결과를 확인해요.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(testPackage, { testPackage = it }, label = { Text("테스트 알림의 앱 패키지") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(testTitle, { testTitle = it }, label = { Text("알림 제목") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(testBody, { testBody = it }, label = { Text("알림 본문") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            val rows = if (set.rules.any { it.id == rule.id }) set.rules.map { if (it.id == rule.id) rule else it } else set.rules + rule
            val raw = RawEvent("preview", "NOTIFICATION", "preview", 1, 0, 0, JSONObject().put("title", testTitle).put("text", testBody).put("package", testPackage.trim()).toString(), "")
            val result = NotificationParser { ParserRuleSet(set.version, rows) }.parse(raw).single()
            preview = "결과: ${notificationClassifications[result.type]}\n적용: ${result.data.optString("parserRuleName", "기본 파서")}\n${result.data.optString("ruleWarning").ifBlank { result.summary }}"
        }, enabled = valid && !busy) { Text("분류 결과 확인") }
        if (preview.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(preview, Modifier.padding(16.dp)) }
        Button(onClick = { onSave(rule) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) { Text("저장하고 기존 알림에도 적용") }
    }
    if (picking) HomeAppPicker("CALENDAR", links, onChoose = { sourcePackage = it.orEmpty(); testPackage = sourcePackage; picking = false }, onDismiss = { picking = false },
        pickerTitle = "보낸 앱 선택", pickerDescription = "이 앱에서 온 알림에만 규칙을 적용해요", showConnection = false)
}
