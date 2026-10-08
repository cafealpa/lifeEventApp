package com.lifedashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val homeAppLabels = linkedMapOf("CALENDAR" to "일정", "SLEEP" to "수면", "STEP_SUMMARY" to "걸음수", "EXERCISE" to "운동")
data class LaunchableApp(val packageName: String, val label: String)

class HomeAppLinks(private val context: Context) {
    private val preferences = context.getSharedPreferences("home_app_links", Context.MODE_PRIVATE)
    fun selected(type: String): String? = preferences.getString(type, null)
    fun select(type: String, packageName: String?) {
        require(type in homeAppLabels)
        preferences.edit().putString(type, packageName).apply()
    }
    fun label(type: String): String {
        val name = selected(type) ?: return "앱 선택"
        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(name, 0)).toString()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "앱을 다시 선택해 주세요" }
    }
    fun apps(): List<LaunchableApp> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.exported }
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

    fun icon(packageName: String, size: Int): ImageBitmap? = try {
        context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }

    fun open(type: String): Boolean {
        val name = selected(type) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(name) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }
}

@Composable
fun HomeAppLinkSettings(links: HomeAppLinks, revision: Int, onChoose: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("홈 카드 연결 앱", style = MaterialTheme.typography.titleMedium)
            Text("카드를 누르면 선택한 앱을 열어요. 데이터 수집 설정은 그대로 유지돼요.", style = MaterialTheme.typography.bodySmall)
            homeAppLabels.forEach { (type, label) ->
                val appLabel = remember(type, revision) { links.label(type) }
                OutlinedButton(onClick = { onChoose(type) }, modifier = Modifier.fillMaxWidth()) {
                    Text("$label · $appLabel")
                }
            }
        }
    }
}

@Composable
fun HomeAppPicker(type: String, links: HomeAppLinks, onChoose: (String?) -> Unit, onDismiss: () -> Unit, pickerTitle: String = "${homeAppLabels[type]} 연결 앱", pickerDescription: String = "카드를 눌렀을 때 열 앱을 선택해 주세요", showConnection: Boolean = true) {
    var apps by remember(links) { mutableStateOf<List<LaunchableApp>?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var search by rememberSaveable(type) { mutableStateOf("") }
    val selected = if (showConnection) links.selected(type) else null
    val filtered = remember(apps, search) {
        val query = search.trim()
        apps.orEmpty().filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(links, retry) {
        error = false
        try { apps = withContext(Dispatchers.IO) { links.apps() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.9f), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pickerTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(pickerDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onDismiss) { Text("닫기") }
                    }
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        label = { Text("앱 검색") }, placeholder = { Text("앱 이름으로 검색") },
                        shape = RoundedCornerShape(16.dp),
                        trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("지우기") } }
                    )
                    if (apps != null && !error) Text(
                        if (search.isBlank()) "설치된 앱 ${filtered.size}개" else "검색 결과 ${filtered.size}개",
                        Modifier.padding(start = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            error -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("앱 목록을 불러오지 못했어요.")
                                TextButton(onClick = { retry++ }) { Text("다시 시도") }
                            }
                            apps == null -> CircularProgressIndicator()
                            filtered.isEmpty() -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (search.isBlank()) "실행할 수 있는 앱이 없어요" else "검색 결과가 없어요", fontWeight = FontWeight.SemiBold)
                                if (search.isNotBlank()) Text("다른 앱 이름으로 검색해 주세요.", style = MaterialTheme.typography.bodySmall)
                            }
                            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(filtered, key = { it.packageName }) { app ->
                                    val isSelected = selected == app.packageName
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onChoose(app.packageName) })
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        AppPickerIcon(app, links)
                                        Column(Modifier.weight(1f)) {
                                            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (isSelected) Text("현재 연결된 앱", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        RadioButton(selected = isSelected, onClick = null)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (showConnection) "선택한 앱의 기본 화면을 열어요" else "알림을 보낸 앱을 선택해 주세요", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selected != null) TextButton(onClick = { onChoose(null) }) { Text("연결 해제") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerIcon(app: LaunchableApp, links: HomeAppLinks) {
    val size = with(LocalDensity.current) { 44.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, app.packageName, links, size) {
        value = withContext(Dispatchers.IO) { links.icon(app.packageName, size) }
    }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
            ?: Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Text(app.label.take(1), style = MaterialTheme.typography.titleLarge) }
            }
    }
}