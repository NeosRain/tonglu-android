package cn.tonglu.app.ui.listening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.listening.ListeningMaterial
import cn.tonglu.app.data.listening.ListeningQuestion
import cn.tonglu.app.data.listening.PracticeStore
import cn.tonglu.app.data.listening.PracticeMode
import cn.tonglu.app.data.listening.keyIn

/**
 * 做题区（docs/plan/47 §3 P2 / 48 §5）。
 *
 * 流程严格保持一条线：**题面 → 作答 → 提交 → 正误 / 答案 / 解析 → 再练 / 下一题**。
 * 三条硬约束落在代码里：
 *  1. **未提交不露答案** —— 判分只在 `submitted` 之后读 `question.answer`，
 *     作答前连"这项对不对"的机会都不给（不是靠文案遮，是根本不渲染）。
 *  2. **解析来自资料包，不调模型** —— 看答案是免费的、离线的，不把每次点开变成付费调用。
 *  3. **每次提交都落一条记录** —— 走 [PracticeStore]，不覆盖上一次分数。
 */
@Composable
internal fun QuestionPane(
    material: ListeningMaterial,
    store: PracticeStore,
    onPlaySentence: (Int) -> Unit,
    onNotice: (String) -> Unit,
) {
    val questions = material.questionList
    // 错题 / 已答记录在提交后要立刻反映到界面，用一个自增计数触发重读。
    var revision by remember { mutableIntStateOf(0) }
    val answered = remember(material.id, revision) { store.attempts() }
    // 判定一律走全局题目键（docs/plan/57 §2.3）：只比 questionId 会把别的材料串进来。
    val wrongKeys = remember(material.id, revision) { store.wrongKeys() }

    // 顶部分段：全部题 / 只练错题。错题本为空时按钮禁用，不摆一个点了没反应的入口。
    var onlyWrong by remember { mutableStateOf(false) }
    val wrongHere = questions.filter { it.keyIn(material.id) in wrongKeys }
    val list = if (onlyWrong) wrongHere else questions

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("做题", fontWeight = FontWeight.SemiBold)
                Text(summaryLine(material, answered.size, wrongHere.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 来源标注：合成题 / 示例题必须说清，不称四级真题（47 §3 P2）。
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("题目来源", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        material.sourceNote.ifBlank {
                            "本题组随学习材料一起下发，属于示例练习，不是考试真题。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("答案与解析由材料提供，离线可看；不需要联网或调用 AI。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onlyWrong = false }, enabled = onlyWrong) { Text("全部题") }
                OutlinedButton(onClick = { onlyWrong = true }, enabled = wrongHere.isNotEmpty()) {
                    Text(if (wrongHere.isEmpty()) "错题本（空）" else "只练错题（${wrongHere.size}）")
                }
            }
        }
        if (list.isEmpty()) {
            item {
                Text("错题本里暂时没有题 —— 先做几道，答错的会自动收进来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(list, key = { _, q -> q.id }) { index, question ->
            QuestionCard(
                material = material,
                question = question,
                // 每题自带作答状态：切换「只练错题」时不串。
                practice = store,
                onPlaySentence = onPlaySentence,
                onAnswered = { revision++ },
                onNotice = onNotice,
            )
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

private fun summaryLine(material: ListeningMaterial, answered: Int, wrong: Int): String {
    val total = material.questionList.size
    if (total == 0) return "这份材料没有题目"
    val base = "共 $total 题"
    return if (answered == 0 && wrong == 0) "$base · 还没做过"
    else "$base · 做过 $answered 题" + if (wrong > 0) " · 错题 $wrong 道" else ""
}

private val okGreen = Color(0xFF2E7D32)
private val badRed = Color(0xFFC62828)

@Composable
private fun QuestionCard(
    material: ListeningMaterial,
    question: ListeningQuestion,
    practice: PracticeStore,
    onPlaySentence: (Int) -> Unit,
    onAnswered: () -> Unit,
    onNotice: (String) -> Unit,
) {
    // 本卡的作答状态。key 用**全局题目键**：切题目/切材料时重置，且不同材料的
    // 同名题号（都用 q01）不会互相串状态。
    val questionKey = question.keyIn(material.id)
    var chosen by remember(questionKey) { mutableIntStateOf(-1) }
    var submitted by remember(questionKey) { mutableStateOf(false) }
    // 提交那一刻的判定，冻结下来 —— 之后即使反复「再练」，这块判定文字也不闪。
    var wasCorrect by remember(questionKey) { mutableStateOf(false) }

    val stats = remember(questionKey, submitted) { practice.statsOf(questionKey) }
    val sentenceIndex = material.sentenceIndexOf(question)

    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(question.prompt.ifBlank { "（这道题没有题面）" }, fontWeight = FontWeight.SemiBold)

            if (!question.gradable) {
                // 答不了的题老实说答不了，不摆一排点了没反应的选项。
                Text("这道题的数据不完整（缺选项或答案），本机不做判分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else if (!submitted) {
                // 未提交：只渲染选项，不泄露任何对错信息。
                question.options.forEachIndexed { index, option ->
                    ChoiceRow(
                        label = option,
                        selected = chosen == index,
                        marker = letter(index),
                        state = ChoiceState.Neutral,
                        onClick = { chosen = index },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (chosen < 0) { onNotice("先选一个答案再提交"); return@Button }
                        val correct = chosen == question.answer
                        wasCorrect = correct
                        submitted = true
                        try {
                            practice.record(
                                questionId = question.id, materialId = material.id,
                                materialTitle = material.title, chosen = chosen, correct = correct,
                                questionKey = questionKey,
                                mode = PracticeMode.MATERIAL,
                            )
                            onAnswered()
                        } catch (cause: Exception) {
                            // 落盘失败要明说「这次没记上」—— 不能让人以为错题本里有它。
                            android.util.Log.e("PracticeStore", "作答记录写入失败", cause)
                            onNotice("这次作答没能存下来，答案已显示，但错题本里可能没有它")
                        }
                    }) { Text("提交答案") }
                    if (chosen >= 0) TextButton(onClick = { chosen = -1 }) { Text("清除选择") }
                }
            } else {
                // 已提交：标注每项对错，正确答案一定高亮，并给出解析与来源。
                question.options.forEachIndexed { index, option ->
                    val state = when {
                        index == question.answer -> ChoiceState.Correct
                        index == chosen -> ChoiceState.Wrong
                        else -> ChoiceState.Neutral
                    }
                    ChoiceRow(
                        label = option,
                        selected = chosen == index,
                        marker = letter(index),
                        state = state,
                        onClick = null,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (wasCorrect) Icons.Outlined.Check else Icons.Outlined.Close, null,
                        tint = if (wasCorrect) okGreen else badRed, modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (wasCorrect) "答对了" else "答错了，正确答案是 ${letter(question.answer)}",
                        color = if (wasCorrect) okGreen else badRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (question.explanation.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("解析", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // 来源：能对上原句就给按钮回到那一句听；对不上就说找不到，不硬指。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (sentenceIndex != null) {
                        OutlinedButton(onClick = { onPlaySentence(sentenceIndex) }) {
                            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("听原文第 ${sentenceIndex + 1} 句")
                        }
                    } else {
                        Text("这道题没有绑定原文句", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        // 再练：清空选择、退回作答前；历史记录一条不动。
                        chosen = -1; submitted = false; wasCorrect = false
                    }) { Text("再练这一题") }
                    stats?.let {
                        Text(it.label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private enum class ChoiceState { Neutral, Correct, Wrong }

/** 选项行。`onClick == null` 表示只读（已提交）。 */
@Composable
private fun ChoiceRow(
    label: String, selected: Boolean, marker: String, state: ChoiceState, onClick: (() -> Unit)?,
) {
    val border = when (state) {
        ChoiceState.Correct -> okGreen
        ChoiceState.Wrong -> badRed
        ChoiceState.Neutral -> if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    }
    val container = when (state) {
        ChoiceState.Correct -> okGreen.copy(alpha = 0.12f)
        ChoiceState.Wrong -> badRed.copy(alpha = 0.12f)
        ChoiceState.Neutral -> if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surface
    }
    OutlinedCard(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(containerColor = container),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(marker, fontWeight = FontWeight.SemiBold, color = border)
            Text(label, modifier = Modifier.weight(1f))
            when (state) {
                ChoiceState.Correct -> Icon(Icons.Outlined.Check, "正确", tint = okGreen,
                    modifier = Modifier.size(18.dp))
                ChoiceState.Wrong -> Icon(Icons.Outlined.Close, "你选的", tint = badRed,
                    modifier = Modifier.size(18.dp))
                ChoiceState.Neutral -> Unit
            }
        }
    }
}

private fun letter(index: Int): String =
    if (index in 0..25) ('A' + index).toString() else "${index + 1}"
