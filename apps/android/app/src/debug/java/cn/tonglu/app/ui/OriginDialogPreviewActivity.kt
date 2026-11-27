package cn.tonglu.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class OriginDialogPreviewActivity : ComponentActivity() {
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) recordMainWindowTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = intent.getStringExtra("theme") == "dark"
        setContent {
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
                    ThemeSystemBars(dark = dark)
                    DialogPreview(dark)
                }
            }
        }
    }
}

@Composable
private fun DialogPreview(dark: Boolean) {
    var opened by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Button(onClick = { opened = "左上" }, modifier = Modifier.align(Alignment.TopStart).launchOrigin()) {
                Text("左上打开")
            }
            TextButton(onClick = { opened = "列表行" }, modifier = Modifier.align(Alignment.Center)
                .fillMaxWidth().launchOrigin()) {
                Text("从列表行打开")
            }
            Button(onClick = { opened = "右下" }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("右下打开")
            }
        }
    }
    if (opened == "右下") {
        OriginDialog(onDismissRequest = { opened = null }) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(24.dp)) {
                    Text("示例 · 普通弹窗")
                    TextButton(onClick = { overlay = true }) { Text("从弹窗内打开覆盖层") }
                    TextButton(onClick = this@OriginDialog::dismiss) { Text("关闭") }
                }
            }
        }
    } else opened?.let { source ->
        OriginAlertDialog(
            onDismissRequest = { opened = null },
            title = { Text("示例 · $source") },
            text = { TextButton(onClick = { overlay = true }) { Text("从弹窗内打开覆盖层") } },
            confirmButton = { TextButton(onClick = this::dismiss) { Text("关闭") } },
        )
    }
    if (overlay) {
        OverlayLayerHost(onClose = { overlay = false }, dialog = true) { close ->
            ThemeSystemBars(dark = dark)
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                TextButton(onClick = close) { Text("关闭覆盖层") }
            }
        }
    }
}
