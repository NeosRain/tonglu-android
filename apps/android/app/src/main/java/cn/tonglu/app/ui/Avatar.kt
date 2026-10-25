package cn.tonglu.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import cn.tonglu.app.data.avatarAddress
import coil3.compose.AsyncImage

@Composable
internal fun UserAvatar(name: String?, path: String?, modifier: Modifier = Modifier) {
    Surface(modifier.clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name?.take(1) ?: "同", color = MaterialTheme.colorScheme.primary)
            avatarAddress(path)?.let { AsyncImage(it, "头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }
}
