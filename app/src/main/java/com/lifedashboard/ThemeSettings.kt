package com.lifedashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun ThemeSettings() {
    val selected = LocalAppTheme.current
    val select = themeSelection()
    Text("선택하면 앱 전체에 바로 적용돼요. 다음 실행에도 유지돼요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTheme.entries.forEach { theme ->
            val palette = theme.colorScheme()
            val active = selected == theme
            Surface(shape = RoundedCornerShape(18.dp), color = palette.background, contentColor = palette.onBackground,
                border = BorderStroke(if (active) 2.dp else 1.dp, if (active) palette.primary else palette.outlineVariant)) {
                Row(Modifier.fillMaxWidth().selectable(selected = active, role = Role.RadioButton, onClick = { select(theme) })
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(theme.title, style = MaterialTheme.typography.titleMedium)
                        Text(theme.description, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(palette.surface, palette.primaryContainer, palette.primary).forEach { color ->
                                Surface(Modifier.size(24.dp), color = color, shape = CircleShape, border = BorderStroke(1.dp, palette.outlineVariant)) {}
                            }
                        }
                    }
                    RadioButton(selected = active, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = palette.primary, unselectedColor = palette.onSurfaceVariant))
                }
            }
        }
    }
}
