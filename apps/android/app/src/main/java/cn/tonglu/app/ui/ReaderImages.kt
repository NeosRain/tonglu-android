/*
 * Adapted from mikepenz/multiplatform-markdown-renderer v0.45.0, Coil3ImageTransformerImpl.
 * Copyright 2022 Mike Penz, Apache-2.0. Full upstream license: assets/licenses/markdown-renderer.txt.
 * Changes: HTTPS-only image URLs, relative/GitHub path resolution and bounded decode size.
 */
package cn.tonglu.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import java.net.URI

internal class ReaderImages(private val sourceUrl: String) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData {
        val url = remember(link, sourceUrl) { runCatching {
            val resolved = if (sourceUrl.isNotBlank()) URI(sourceUrl).resolve(link) else URI(link)
            if (resolved.scheme != "https" || resolved.host.isNullOrBlank() || resolved.userInfo != null) null
            else if (resolved.host == "github.com" && "/blob/" in resolved.path)
                "https://raw.githubusercontent.com" + resolved.rawPath.replaceFirst("/blob/", "/")
            else resolved.toString()
        }.getOrNull() }
        return rememberAsyncImagePainter(ImageRequest.Builder(LocalPlatformContext.current)
            .data(url).size(1200).build()).let { ImageData(it) }
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size {
        var size by remember(painter) { mutableStateOf(painter.intrinsicSize) }
        if (painter is AsyncImagePainter) {
            val painterState by painter.state.collectAsState()
            painterState.painter?.intrinsicSize?.also { size = it }
        }
        return size
    }
}
