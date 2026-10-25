package cn.tonglu.app.ui.square

import java.net.URI

/** 正文图片与文本摘要分别处理；最多九张，忽略代码示例和重复地址。 */
internal fun postImageUrls(body: String): List<String> {
    val content = body.replace(Regex("(?s)<!--.*?-->|```.*?```|~~~.*?~~~"), "")
        .replace(Regex("`[^`\n]*`"), "")
    val references = Regex("(?m)^\\s*\\[([^]\\n]+)]\\s*:\\s*(\\S+)").findAll(content)
        .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim('<', '>') }
    val inline = Regex("!\\[[^]\\n]*]\\(\\s*(<[^>\\n]+>|https://(?:[^\\s()]+|\\([^()]*\\))+)\\s*(?:[\"'][^\"'\\n]*[\"'])?\\s*\\)")
    val linked = Regex("!\\[([^]\\n]*)]\\[([^]\\n]*)]")
    val candidates = inline.findAll(content).map { it.range.first to it.groupValues[1].trim('<', '>') }.toList() +
        linked.findAll(content).mapNotNull {
            references[it.groupValues[2].ifBlank { it.groupValues[1] }.lowercase()]?.let { url -> it.range.first to url }
        }.toList()
    return candidates.sortedBy { it.first }.map { it.second }.filter { url ->
        try {
            val uri = URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        } catch (_: java.net.URISyntaxException) {
            // 不合法的地址不是可加载的图片，正文仍保留，避免把整篇帖子隐藏。
            false
        }
    }.distinct().take(9)
}
