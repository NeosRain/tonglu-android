package cn.tonglu.app.data.update

internal enum class UpdateChannel(val key: String, val label: String, val description: String) {
    Stable("stable", "正式版", "每周日发布，适合日常使用"),
    Preview("preview", "预览版", "每天发布，提前体验新功能，可能有问题");

    val manifestUrl: String get() = "https://dl.useall.top/app-updates/$key.json"
    val sources: List<UpdateChannel> get() = if (this == Preview) listOf(Preview, Stable) else listOf(Stable)
    fun accepts(versionName: String) = this == Preview || !versionName.contains('-')
    companion object {
        fun saved(key: String?, installedName: String) = entries.firstOrNull { it.key == key }
            ?: if ('-' in installedName) Preview else Stable
    }
}
