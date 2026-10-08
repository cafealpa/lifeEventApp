package com.lifedashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
fun HomeAppPicker(type: String, links: HomeAppLinks, onChoose: (String?) -> Unit, onDismiss: () -> Unit) {
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(links) {
        try { apps = withContext(Dispatchers.IO) { links.apps() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${homeAppLabels[type]} 연결 앱") },
        text = {
            Column {
                Text("앱의 기본 화면을 열어요.")
                when {
                    error -> Text("앱 목록을 불러오지 못했어요. 다시 시도해 주세요.")
                    apps == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    apps!!.isEmpty() -> Text("실행할 수 있는 앱이 없어요.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(apps!!, key = { it.packageName }) { app ->
                            Column(Modifier.fillMaxWidth().clickable { onChoose(app.packageName) }.padding(vertical = 12.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { if (links.selected(type) != null) TextButton(onClick = { onChoose(null) }) { Text("연결 해제") } }
    )
}
