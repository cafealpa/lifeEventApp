package com.lifedashboard

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SpotTraceSettings(vm: LifeViewModel) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val states by vm.graph.status.states.collectAsStateWithLifecycle()
    var reconnect by remember { mutableStateOf(false) }
    // A successful setting write also emits a collection state change.
    val enabled=remember(states,busy) { vm.graph.spotTrace.enabled() }
    SettingsCard("SpotTrace 방문 기록", "PLACE_VISIT") {
        Text("같은 휴대폰의 SpotTrace에 저장된 장소 이름과 진입·이탈 기록을 가져와요. 두 앱 모두 연동 지원 버전이어야 해요.",style=MaterialTheme.typography.bodySmall)
        Text(states["SPOTTRACE"] ?: "미연동")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("방문 기록 가져오기",Modifier.weight(1f))
            Switch(enabled,onCheckedChange={value -> vm.work { vm.graph.setSpotTraceEnabled(value) }},enabled=!busy)
        }
        OutlinedButton(onClick={
            try { context.startActivity(vm.graph.spotTrace.settingsIntent()) }
            catch (_: ActivityNotFoundException) { vm.message.value="SpotTrace를 설치하거나 업데이트해 주세요" }
            catch (_: SecurityException) { vm.message.value="SpotTrace 설정을 열 수 없어요" }
        },modifier=Modifier.fillMaxWidth()) { Text("SpotTrace에서 연동 허용") }
        Button(onClick={vm.refresh()},enabled=enabled && vm.graph.status.enabled() && !busy,modifier=Modifier.fillMaxWidth()) { Text("지금 가져오기") }
        if(!vm.graph.status.enabled()) Text("위의 생활 데이터 수집을 켜면 가져올 수 있어요.",style=MaterialTheme.typography.bodySmall)
        if(states["SPOTTRACE"]?.contains("교체")==true) TextButton(onClick={reconnect=true},enabled=!busy) {Text("새 데이터 연결")}
        Text("연동 해제는 수집만 중지해요. 원본 앱에서 삭제한 기록은 다음 동기화 후 집계에서 제외하고 가져온 원본 이력은 보존해요. 카드 제거도 생활 기록을 삭제하지 않아요.",style=MaterialTheme.typography.bodySmall)
    }
    if(reconnect) AlertDialog(onDismissRequest={reconnect=false},title={Text("새 SpotTrace 데이터에 연결할까요?")},text={Text("기존 데이터셋의 기록은 보존하지만 집계에서 제외하고 확인 필요로 표시해요. 새 데이터의 기록으로 다시 연결해요.")},confirmButton={TextButton(onClick={reconnect=false;vm.work {vm.graph.reconnectSpotTrace();vm.graph.refresh()}}) {Text("연결")}},dismissButton={TextButton(onClick={reconnect=false}) {Text("취소")}})
}
