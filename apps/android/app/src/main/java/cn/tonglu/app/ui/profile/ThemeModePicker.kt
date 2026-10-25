package cn.tonglu.app.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.ThemeMode
import cn.tonglu.app.ui.AppColors

@Composable
internal fun ThemeModePicker() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("主题模式", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(selected = AppTheme.modeState == mode,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.Accent,
                        selectedLabelColor = AppColors.OnAccent,
                        labelColor = AppColors.TextSecondary,
                        containerColor = AppColors.Surface),
                    onClick = { AppTheme.setMode(context, mode) }, label = { Text(mode.label) })
            }
        }
        Text("页面、文章和玻璃使用同一主题", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
