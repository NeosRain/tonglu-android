package cn.tonglu.app.ui.clubs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.CreateClubRequest
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayEnter
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import cn.tonglu.app.ui.overlayMotion

/**
 * 创建社团的**独立全屏页**（UI-REPAIR-2026-10-13 §3），替换旧的 Material3 `AlertDialog`
 * （默认紫色底、大描边输入、滚动困难——用户反馈「无法创建社团」的载体）。
 *
 * 与旧 Dialog 的行为差异（都是验收矩阵点名的）：
 * - 简介输入 4 字时**就近说明**「还需 1 字」，而不是按钮灰着让人猜（判定在 [ClubCreateRules]）。
 * - 分类**只有一个选择入口**：默认一行展示当前分类，点开才展开预设 + 自定义；
 *   不再像旧 Dialog 那样预设 chips 与自定义输入框同时摆着。
 * - 返回保稿：草稿由宿主（TongluApp）持有，关层不丢；创建成功才清。
 * - 提交中防双击：按钮禁用 + 忽略重复点击。
 * - 服务端失败**原样解释**（`onCreate` 回调带 `toFriendlyMessage()` 的结果），
 *   不猜测「大概是重名」；字段全部保留。
 * - 头像上传**不做**：接口没支持，不摆假按钮（DESIGN-SYSTEM §1）。
 */
@Composable
internal fun ClubCreateScreen(
    name: String,
    category: String,
    description: String,
    onNameChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onClose: () -> Unit,
    /** 点击创建。`done(ok, message)` 由宿主在 ViewModel 回调后调用：ok=true 时页面自己关层，ok=false 时 message 是给用户看的真实原因。 */
    onCreate: (CreateClubRequest, done: (Boolean, String?) -> Unit) -> Unit,
) {
    // 提交中：按钮禁用 + 点击忽略。页面自己持有（宿主回调 done 后复位），
    // 不进 ViewModel 状态——它是**这一张表单**的瞬态，不是全局业务状态。
    var submitting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    // 分类「一个选择入口」：默认收起，只看到当前值；点行展开预设 + 自定义输入。
    var categoryOpen by remember { mutableStateOf(false) }

    val ready = clubCreateReady(name, category, description)
    val hint = clubCreateFirstHint(name, category, description)

    // 全屏层与 PostDetail 同一套返回机制：左边缘跟手 + 系统返回键都归这里管，
    // 不另挂 BackHandler（两个 BackHandler = 一次返回退两层，PostDetail 注释里的教训）。
    val motion = rememberOverlayMotion(onClose = onClose, predictive = OverlayMotion.enabledState, selfDrag = false)

    Box(
        Modifier
            .fillMaxSize()
            .overlayMotion(motion, fade = false, enter = OverlayEnter.Right)
            .background(AppColors.Canvas)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- 顶栏：返回 + 标题（表单页不做玻璃顶栏，阅读优先——DESIGN-SYSTEM §2「编辑」行） ----
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = motion::requestClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        tint = AppColors.TextPrimary)
                }
                Text("创建社团", color = AppColors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(4.dp))

            // ---- 名称 ----
            Surface(
                color = AppColors.Surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("社团名称", color = AppColors.TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { onNameChange(it.take(CLUB_NAME_MAX)) },
                        placeholder = { Text("输入名称", color = AppColors.TextMuted, fontSize = 15.sp) },
                        singleLine = true,
                        isError = clubNameError(name) != null && name.isNotBlank(),
                        // 🔴 Material3 默认描边输入框不能直接当视觉规范（DESIGN-SYSTEM §1）——
                        // 全部色槽接 AppColors，跟主题走；PostComposer 也是 OutlinedTextField，
                        // 这里只是把颜色接对，不另造输入组件。
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            cursorColor = AppColors.Accent,
                            focusedBorderColor = AppColors.Accent,
                            unfocusedBorderColor = AppColors.Divider,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    nameErrorLine(name)?.let {
                        Text(it, color = AppColors.TextMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 分类：唯一选择入口 ----
            Surface(
                color = AppColors.Surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { categoryOpen = !categoryOpen }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("分类", color = AppColors.TextSecondary, fontSize = 12.sp)
                            Text(
                                category.ifBlank { "未选择" },
                                color = AppColors.TextPrimary, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(
                            if (categoryOpen) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (categoryOpen) "收起分类选择" else "展开分类选择",
                            tint = AppColors.TextMuted,
                        )
                    }
                    // 展开区：预设 chips（点选即填）+ 自定义输入（仍是同一入口内的同一字段）。
                    if (categoryOpen) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CLUB_CATEGORY_PRESETS.forEach { preset ->
                                    val picked = category == preset
                                    Surface(
                                        color = if (picked) AppColors.Accent.copy(alpha = .15f) else AppColors.ChipIdle,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.clickable {
                                            onCategoryChange(preset)
                                            categoryOpen = false
                                        },
                                    ) {
                                        Text(preset,
                                            color = if (picked) AppColors.Accent else AppColors.TextSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = if (CLUB_CATEGORY_PRESETS.contains(category)) "" else category,
                                onValueChange = { onCategoryChange(it.take(CLUB_CATEGORY_MAX)) },
                                placeholder = { Text("自定义分类（2-30 字）", color = AppColors.TextMuted, fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = AppColors.TextPrimary,
                                    unfocusedTextColor = AppColors.TextPrimary,
                                    cursorColor = AppColors.Accent,
                                    focusedBorderColor = AppColors.Accent,
                                    unfocusedBorderColor = AppColors.Divider,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            categoryErrorLine(category)?.let {
                                Text(it, color = AppColors.TextMuted, fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 介绍 ----
            Surface(
                color = AppColors.Surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("社团介绍", color = AppColors.TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { onDescriptionChange(it.take(CLUB_DESCRIPTION_MAX)) },
                        placeholder = { Text("这个社团做什么、欢迎谁、平时怎么活动……",
                            color = AppColors.TextMuted, fontSize = 14.sp) },
                        minLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            cursorColor = AppColors.Accent,
                            focusedBorderColor = AppColors.Accent,
                            unfocusedBorderColor = AppColors.Divider,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    descriptionErrorLine(description)?.let {
                        Text(it, color = AppColors.TextMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    }
                }
            }

            // ---- 说明 + 错误 + 主按钮 ----
            Text(
                "创建后你会成为该社团的负责人，并自动带上「公告」「闲聊」两个频道；名称与简介会经过安全检查。",
                color = AppColors.TextMuted, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            )

            // 服务端失败：原样解释，字段全保留（onCreate 失败时宿主回调 message）。
            serverError?.let {
                Surface(
                    color = AppColors.Danger.copy(alpha = .12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(it, color = AppColors.Danger, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            // 主操作：禁用必须**看得出**（ChipIdle 底 + 次要字，与 Accent 实底反差），
            // 无效时 hint 已经在字段边上解释了为什么；这里不重复铺红。
            val canTap = ready && !submitting
            Surface(
                color = if (canTap) AppColors.Accent else AppColors.ChipIdle,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .clickable(enabled = canTap) {
                        if (submitting) return@clickable // 防双击：提交中忽略
                        submitting = true
                        serverError = null
                        onCreate(CreateClubRequest(name.trim(), description.trim(), category.trim())) { ok, msg ->
                            submitting = false
                            if (ok) {
                                // 成功：页面自己播退出动画后经 onClose 回宿主（宿主在回调里清草稿）。
                                motion.requestClose()
                            } else {
                                serverError = msg ?: "创建失败，请稍后重试。"
                            }
                        }
                    },
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = AppColors.OnAccent)
                        Spacer(Modifier.size(8.dp))
                        Text("创建中…", color = AppColors.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Text("创建社团", color = if (canTap) AppColors.OnAccent else AppColors.TextMuted,
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 字段下的就近提示：只在**有内容但不足**时出现（空字段用占位符引导，不先喊错）。 */
private fun nameErrorLine(name: String): String? =
    if (name.isBlank()) null else clubNameError(name)

private fun categoryErrorLine(category: String): String? =
    if (category.isBlank()) null else clubCategoryError(category)

private fun descriptionErrorLine(description: String): String? =
    if (description.isBlank()) null else clubDescriptionError(description)
