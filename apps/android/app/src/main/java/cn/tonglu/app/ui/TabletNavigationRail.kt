package cn.tonglu.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.glass.GlassBottomActionPanel
import cn.tonglu.app.ui.glass.GlassTokens
import com.kyant.backdrop.Backdrop

/** Existing compact pages keep their floating bottom bar clearance. */
internal val LocalMainNavigationBottomPadding = staticCompositionLocalOf { 116.dp }

@Composable
internal fun TabletNavigationRail(
    tabs: List<String>, filledIcons: List<ImageVector>, outlinedIcons: List<ImageVector>,
    selectedTab: Int, onSelect: (Int) -> Unit, backdrop: Backdrop, glass: Boolean,
    modifier: Modifier = Modifier,
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("苔米林", color = AppColors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                val fill by animateColorAsState(if (selected) AppColors.Accent.copy(alpha = .14f) else Color.Transparent, label = "rail-selection")
                val tint by animateColorAsState(if (selected) GlassTokens.AccentOnGlass else AppColors.TextSecondary, label = "rail-icon")
                Column(Modifier.fillMaxWidth().height(64.dp).background(fill, RoundedCornerShape(20.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(if (selected) filledIcons[index] else outlinedIcons[index], null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(title, color = AppColors.TextPrimary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
    val panel = modifier.width(84.dp).semantics { contentDescription = "主导航侧栏" }
    if (glass) GlassBottomActionPanel(backdrop, modifier = panel, content = content)
    else Surface(modifier = panel, shape = RoundedCornerShape(24.dp), color = AppColors.SurfaceOpaque) { Column(content = content) }
}
