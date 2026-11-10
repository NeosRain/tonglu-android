package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.question.Difficulty
import cn.tonglu.app.data.question.QuestionBank
import cn.tonglu.app.data.question.BankQuestion
import com.google.gson.Gson
import java.security.MessageDigest

internal enum class DiagnosisOutcome { Correct, Incorrect, Unknown }

/** Current-round answers only. Practice history remains in PracticeStore. */
internal data class DiagnosisRound(
    val number: Int = 0,
    val index: Int = 0,
    val finished: Boolean = false,
    val answers: Map<String, DiagnosisOutcome> = emptyMap(),
    val poolSignature: String = "",
) {
    fun answer(key: String, correct: Boolean, unknown: Boolean) = copy(
        answers = answers + (key to when {
            unknown -> DiagnosisOutcome.Unknown
            correct -> DiagnosisOutcome.Correct
            else -> DiagnosisOutcome.Incorrect
        }),
    )
    fun retry(key: String) = copy(answers = answers - key)
    fun next(total: Int) = if (index + 1 < total) copy(index = index + 1) else copy(finished = true)
    fun previous() = copy(index = (index - 1).coerceAtLeast(0))
    fun restart() = DiagnosisRound(number = number + 1, poolSignature = poolSignature)
    fun bindPool(signature: String): DiagnosisRound = when {
        signature.isEmpty() || signature == poolSignature -> this
        poolSignature.isEmpty() -> copy(poolSignature = signature)
        else -> DiagnosisRound(number = number + 1, poolSignature = signature)
    }
    fun snapshot(): List<String> = listOf(number.toString(), index.toString(), finished.toString(), poolSignature) +
        answers.flatMap { (key, value) -> listOf(key, value.name) }

    companion object {
        fun restore(values: List<String>) = DiagnosisRound(
            number = values[0].toInt(), index = values[1].toInt(), finished = values[2].toBooleanStrict(),
            poolSignature = values[3],
            answers = values.drop(4).chunked(2).associate { it[0] to DiagnosisOutcome.valueOf(it[1]) },
        )
    }
}

internal data class DiagnosisTierResult(
    val tier: String, val total: Int, val correct: Int, val incorrect: Int, val unknown: Int, val skipped: Int,
)

internal fun diagnosisResults(pool: List<DiagnosisPick>, round: DiagnosisRound): List<DiagnosisTierResult> =
    Difficulty.order.mapNotNull { tier ->
        val picks = pool.filter { it.question.difficulty == tier }
        if (picks.isEmpty()) null else {
            val outcomes = picks.map { round.answers[it.bank.keyOf(it.question)] }
            DiagnosisTierResult(tier, picks.size,
                outcomes.count { it == DiagnosisOutcome.Correct },
                outcomes.count { it == DiagnosisOutcome.Incorrect },
                outcomes.count { it == DiagnosisOutcome.Unknown },
                outcomes.count { it == null })
        }
    }

private val diagnosisJson = Gson()

/** Only stable scene keys participate in animation equality and hashing. */
internal class LearningAnimationFrame<T>(val key: String, val content: T) {
    override fun equals(other: Any?) = other is LearningAnimationFrame<*> && key == other.key
    override fun hashCode() = key.hashCode()
}

internal fun questionContentFingerprint(question: BankQuestion): String = MessageDigest.getInstance("SHA-256")
    .digest(diagnosisJson.toJson(question).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** Save only identities and content fingerprints, not entire banks in the Activity bundle. */
internal fun diagnosisPoolSignature(pool: List<DiagnosisPick>): String = diagnosisJson.toJson(pool.map {
    diagnosisJson.toJson(listOf(it.bank.id, it.bank.version.toString(), it.question.id, questionContentFingerprint(it.question)))
})

internal fun restoreDiagnosisPool(signature: String, banks: List<QuestionBank>): List<DiagnosisPick>? {
    val identities = diagnosisJson.fromJson(signature, Array<String>::class.java)
    val byBank = banks.associateBy { it.id to it.version.toString() }
    return identities.map { encoded ->
        val identity = diagnosisJson.fromJson(encoded, Array<String>::class.java)
        val bank = byBank[identity[0] to identity[1]] ?: return null
        val question = bank.questionList.firstOrNull { it.id == identity[2] && it.gradable && it.keySafe } ?: return null
        val pick = DiagnosisPick(bank, question)
        if (questionContentFingerprint(question) != identity[3]) return null
        pick
    }
}
