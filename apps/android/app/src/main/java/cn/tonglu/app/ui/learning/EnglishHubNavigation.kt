package cn.tonglu.app.ui.learning

private val destinations = setOf(
    "home", "today", "wordSettings", "readingHistory", "words", "favorites", "listening",
    "voice", "tools", "questions", "wrong", "daily", "plan", "diagnosis", "more",
    "myReading", "myBooks", "intensive",
)

internal fun englishHubStart(destination: String): List<String> =
    if (destination == "home" || destination !in destinations) listOf("home")
    else listOf("home", destination)

internal fun englishHubOpen(path: List<String>, destination: String): List<String> {
    if (destination !in destinations) return path
    val existing = path.indexOf(destination)
    return if (existing >= 0) path.take(existing + 1) else path + destination
}

/** An outgoing ancestor's late animation callback must not pop the current child. */
internal fun englishHubBack(path: List<String>, closing: String): List<String> =
    if (path.last() != closing || path.size == 1) path else path.dropLast(1)
