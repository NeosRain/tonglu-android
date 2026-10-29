package cn.tonglu.app.data.english.books

/** Article tags are authoritative; do not guess an exam from a word's difficulty. */
internal fun collectionBookName(exam: String?): String = when (exam) {
    "CET4" -> "四级单词书"
    "CET6" -> "六级单词书"
    else -> "阅读单词书"
}

data class WordEncounter(val articleId: String, val position: String, val sentence: String, val at: Long)

internal val PersonalBookWord.collectionEncounters: List<WordEncounter> get() = encounters
    ?: listOf(WordEncounter(articleId.orEmpty(), sourcePosition.orEmpty(), sentence.orEmpty(), addedAt))

/** Gson can leave newly introduced or old optional fields null without using Kotlin defaults. */
internal fun PersonalBookWord.withCompatibleDefaults() = PersonalBookWord(
    surface.orEmpty(), lemma.orEmpty().ifBlank { surface.orEmpty() }, meaning.orEmpty(),
    sentence.orEmpty(), articleId.orEmpty(), sourcePosition.orEmpty(), addedAt, attempts, correct,
    collectionCount, encounters)

internal fun mergeCollectedWords(words: List<PersonalBookWord>): List<PersonalBookWord> =
    words.map { it.withCompatibleDefaults() }.groupBy { it.key }.values.map { group ->
        val first = group.first()
        val count = group.sumOf { it.collectionCount.toLong() }
        require(count <= Int.MAX_VALUE) { "收藏次数超出范围，未合并" }
        first.copy(meaning = group.firstOrNull { it.meaning.isNotBlank() }?.meaning.orEmpty(),
            collectionTimes = count.toInt(), encounters = group.flatMap { it.collectionEncounters },
            attempts = Math.toIntExact(group.sumOf { it.attempts.toLong() }),
            correct = Math.toIntExact(group.sumOf { it.correct.toLong() }))
    }

/** Pure transform shared by atomic storage and regression tests. */
internal fun collectWordInSnapshot(old: PersonalBooksSnapshot, word: PersonalBookWord, exam: String?,
    anotherEncounter: Boolean, maxBooks: Int): Pair<PersonalBooksSnapshot, String> {
    require(word.key.isNotBlank() && word.key.length <= 160 && word.meaning.isNotBlank() && word.meaning.length <= 4000) { "请先查到或填写有效释义" }
    require(word.sentence.length <= 12000) { "来源句子过长" }
    val existing = old.books.firstOrNull { b -> b.words.any { it.key == word.key } }
    if (existing != null && !anotherEncounter) return old to existing.name
    val name = collectionBookName(exam)
    val target = (if (exam == null) existing else null) ?: old.books.firstOrNull { it.name == name }
        ?: PersonalWordBook(java.util.UUID.randomUUID().toString(), name).also {
            require(old.books.size < maxBooks) { "词书数量已达上限，请先合并词书" }
        }
    val member = target.words.firstOrNull { it.key == word.key }
    val encounter = WordEncounter(word.articleId, word.sourcePosition, word.sentence, System.currentTimeMillis())
    val next = if (member == null) target.copy(words = target.words + word.copy(collectionTimes = 1, encounters = listOf(encounter)))
    else target.copy(words = target.words.map { if (it.key == word.key)
        it.copy(collectionTimes = Math.addExact(it.collectionCount, 1), encounters = it.collectionEncounters + encounter) else it })
    val books = if (old.books.any { it.id == target.id }) old.books.map { if (it.id == target.id) next else it } else old.books + next
    return old.copy(books = books) to target.name
}
