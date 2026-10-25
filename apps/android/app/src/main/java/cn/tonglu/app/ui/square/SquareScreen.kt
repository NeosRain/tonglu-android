package cn.tonglu.app.ui.square

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.LinkedPackDto
import cn.tonglu.app.data.PostDto
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.PostPackCard
import cn.tonglu.app.ui.SquarePullRefresh
import cn.tonglu.app.ui.UserAvatar
import cn.tonglu.app.ui.markdownExcerpt
import cn.tonglu.app.ui.pressable
import cn.tonglu.app.ui.relativeIsoTime
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

private val Accent: Color get() = AppColors.Accent
private val Muted: Color get() = AppColors.TextMuted

/**
 * 卡片圆角：酷安真机上是大圆角卡片（不是小圆角方块）。
 *
 * 录屏里第一屏能同时看到 3 条左右的完整卡片，说明它的卡片是**紧凑**的
 * （plan §三.1「减少套卡片、冗长介绍、大标题」）。所以内边距与行距都比上一版收了一档。
 */
private val CardCorner = 14.dp

/** 卡片内边距。比原来 16dp → 14dp → **12dp**，把宽度与高度都让给正文。 */
private val CardInside = PaddingValues(12.dp)

/**
 * 广场：一个纵向信息流 + 顶部吸顶分类（plan/61 §2 / `docs/community/DESIGN-REFS.md` §2）。
 *
 * 结构对齐**真机酷安主页**（不是模拟器上那版实色绿头 + 整块白面板）：
 *  - 头部（头像 + 搜索）已经在全局玻璃顶栏里，本页只留标题，**不加消息/通知假入口**（已拍板）；
 *  - 分类行 = **文字 Tab + 短下划线**，滚动**吸顶**（`stickyHeader`）；
 *  - 信息流 = 白卡直铺浅灰底（不用整块内容面板）；深色主题下卡自动变深卡。
 *
 * 两件事必须分清，否则用户会以为「搜索没反应」：
 *  - **分类**是页面内的筛选（`SquareCategories`，走既有 `PostCategory` 的映射）；
 *  - **搜索**是顶栏那个全局输入框，它过滤的是本页列表，界面要**明说**这一点。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SquareScreen(
    state: AppUiState,
    search: String,
    topInset: Dp = 0.dp,
    onRefresh: (String?) -> Unit,
    onReport: (PostDto) -> Unit,
    onOpen: (PostDto) -> Unit,
    onOpenPack: (LinkedPackDto) -> Unit = {},
) {
    // 存标签而不是存整个对象：rememberSaveable 只能存 Bundle 认识的类型，
    // 而且分类表一旦改动，存下来的下标/枚举会错位。
    var categoryLabel by rememberSaveable { mutableStateOf(SquareCategories.all.first().label) }
    val category = SquareCategories.byLabel(categoryLabel)

    val posts = state.posts.filter { post ->
        SquareCategories.matches(category, post.category) &&
            (
                search.isBlank() ||
                    post.title.orEmpty().contains(search, true) ||
                    post.body.contains(search, true) ||
                    post.author?.displayName.orEmpty().contains(search, true)
                )
    }
    // 列表失败必须读**帖子自己的** `postsError`，不能读全 App 共用的 `error`：
    // 任何并发请求成功都会把 `error` 清回 null，失败会被静默抹掉，
    // 界面就会从「没能拉到」变成「这里还没有内容」—— 假空态（2026-10-09 MuMu 实撞）。
    // 原文显示，不自己编「网络异常」—— 编出来的话用户拿去搜也搜不到真因。
    val phase = squareListPhase(
        loading = state.postsLoading,
        totalCount = state.posts.size,
        shownCount = posts.size,
        hasError = state.postsError != null && state.posts.isEmpty(),
    )

    SquarePullRefresh(
        onRefresh = { onRefresh(SquareCategories.serverQuery(category)) },
        topInset = topInset,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            // 卡与卡的间距也收一档（8 → 6dp）：录屏里信息流是「密」的，
            // 但**不留到 0** —— 卡是白底直铺浅灰底，间距归零就分不出卡边界了。
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (search.isNotBlank()) item(key = "search-hint") {
                Surface(color = AppColors.ChipIdle, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(16.dp))
                        Text(
                            "正在按「$search」过滤本页列表 · 清空搜索框可看全部",
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            // 「平台不代收款」只挂在二手分类下：它是一条交易提示，
            // 在「全部」里常驻会把真正的信息挤出第一屏。
            // 分类行**吸顶**（酷安：搜索区滚走、Tab 行留在顶上）。
            // 它自带 `PaddingValues(top = topInset)`：吸顶位置是列表视口 y=0，
            // 也就是玻璃顶栏底下，不加这一下就会被顶栏整条盖住。
            // 下划线那条带子**只铺在 Tab 行自己的高度上**，上面那段留透明 ——
            // 这样内容从顶栏底下滚过去仍然是「玻璃后面能看见内容」，不至于糊成一块实心板。
            stickyHeader(key = "categories") {
                Column(Modifier.fillMaxWidth().padding(top = topInset)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(AppColors.Canvas)
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 10.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        SquareCategories.all.forEach { item ->
                            val selected = item.label == categoryLabel
                            CategoryTab(
                                label = item.label,
                                selected = selected,
                                onClick = {
                                    categoryLabel = item.label
                                    onRefresh(SquareCategories.serverQuery(item))
                                },
                            )
                        }
                    }
                    // 细分割线：吸顶后把 Tab 行和滚过去的正文分开，避免文字贴在一起
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.Divider))
                }
            }

            if (search.isBlank()) item(key = "feature-banner") { SquareBanner { label ->
                categoryLabel = label; onRefresh(SquareCategories.serverQuery(SquareCategories.byLabel(label)))
            } }
            if (category == SquareCategories.SECOND_HAND) item(key = "trade-notice") {
                MiuixCard(insideMargin = CardInside, cornerRadius = CardCorner) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("让闲置流动起来", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("约定线下交接，平台不代收款、不担保", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        Icon(Icons.Outlined.Storefront, null, tint = Accent, modifier = Modifier.size(26.dp))
                    }
                }
            }

            when (phase) {
                SquareListPhase.LOADING -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Accent)
                    }
                }

                SquareListPhase.ERROR -> item(key = "error") {
                    // 酷安：失败态是**整块可点**（不是角落里一个小按钮）。
                    // 顺手把服务端原文也显示出来 —— 不自己编「网络异常」。
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().clickable { onRefresh(SquareCategories.serverQuery(category)) },
                        insideMargin = PaddingValues(20.dp),
                        cornerRadius = CardCorner,
                    ) {
                        Text("没能拉到广场内容", fontWeight = FontWeight.SemiBold)
                        Text("点这块重试", color = Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                        Text(state.postsError.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }

                SquareListPhase.EMPTY_ALL -> item(key = "empty-all") {
                    MiuixCard(insideMargin = PaddingValues(20.dp), cornerRadius = CardCorner) {
                        Text("这里还没有内容", fontWeight = FontWeight.SemiBold)
                        Text("点顶部 + 发布校园动态、求助或二手信息。", color = Muted)
                        TextButton(onClick = { onRefresh(null) }) { Text("刷新") }
                    }
                }

                SquareListPhase.EMPTY_FILTERED -> item(key = "empty-filtered") {
                    MiuixCard(insideMargin = PaddingValues(20.dp), cornerRadius = CardCorner) {
                        Text("这个分类下暂时没有内容", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (search.isNotBlank()) "当前还有搜索词「$search」在生效，清空后再看一次。" else "换个分类，或下拉刷新试试。",
                            color = Muted,
                        )
                        Row {
                            if (categoryLabel != SquareCategories.all.first().label) {
                                TextButton(onClick = { categoryLabel = SquareCategories.all.first().label; onRefresh(null) }) { Text("看全部") }
                            }
                            TextButton(onClick = { onRefresh(SquareCategories.serverQuery(category)) }) { Text("刷新") }
                        }
                    }
                }

                SquareListPhase.CONTENT -> items(posts, key = { it.id }) { post ->
                    SquarePostCard(
                        post = post,
                        onOpen = { onOpen(post) },
                        onReport = { onReport(post) },
                        onOpenPack = onOpenPack,
                    )
                }
            }
        }
    }
}

/**
 * 分类 Tab：酷安是**文字 + 短下划线**（不是胶囊）。
 *
 * 下划线用一个**固定 18dp 宽**的盒子画，未选中时只把颜色置透明 ——
 * 用 0dp 宽会让整行高度跟着变，点一下就上下跳一格。
 */
@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            label,
            color = if (selected) AppColors.TextPrimary else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.5.dp)
                .background(if (selected) AppColors.TextPrimary else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 帖子卡（对齐 `DESIGN-REFS.md` §2 第 5 条：头像 / 昵称 / 来源行 → 正文 → 附件 →
 * 卡内快捷评论 → 互动栏）。
 *
 * 点赞与「热评外显」**这轮故意不画**：两者都要服务端计数与排序，
 * 先摆一个点了没反应、或者显示本地假计数的按钮，就是拿假数据当已完成。
 * 见 `DESIGN-REFS.md` §5「已拍板、依赖后端」一节。
 *
 * 密度按录屏收紧（plan §三.1）：头像 36→**34dp**、卡片内边距 14→**12dp**、
 * 正文行高 23→**21sp**、摘要上限 6→**4 行**，条与条间距 8→**6dp**。
 */
@Composable
internal fun SquarePostCard(
    post: PostDto,
    onOpen: () -> Unit,
    onReport: () -> Unit,
    onOpenPack: (LinkedPackDto) -> Unit = {},
    /** 社团帖传 false：C1 没有社团举报路由，按钮不显示而不是点了 404（无权按钮不显示）。 */
    showReport: Boolean = true,
    /** 来源行覆写：社团帖传「社团名 · 频道名」（复用卡片默认写分类名，对社团帖是错误文案）。 */
    sourceOverride: String? = null,
) {
    val context = LocalContext.current
    val categoryName = remember(post.category) { post.category.toCategoryName() }
    // 摘要统一走 markdownExcerpt：原来这里堆了一串正则，漏掉公式和列表，
    // 从笔记发过来的帖子在广场里直接显示 `$$ \frac{a}{b} $$`、`- 第一点` 这种源码。
    val excerpt = remember(post.body) { markdownExcerpt(post.body) }
    val when_ = remember(post.createdAt) { relativeIsoTime(post.createdAt) }
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    // 🔴 只置真、**永不置假**：展开后排版不再溢出，判据一旦跟着变 false，
    // 「收起」会当场消失，用户就收不回去了（见 expandToggleLabel 的注释）。
    var truncated by rememberSaveable(post.id) { mutableStateOf(false) }
    val toggle = expandToggleLabel(truncated = truncated, expanded = expanded)

    MiuixCard(insideMargin = CardInside, cornerRadius = CardCorner) {
        // 整块卡片可点进详情（楼层）；卡内的快捷评论框、查看更多、举报都是内层 clickable，不会冒泡上来。
        Column(Modifier.fillMaxWidth().pressable(scale = 0.99f, onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(post.author?.displayName, post.author?.avatarUrl, Modifier.size(34.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(post.author?.displayName ?: "同学", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 来源行（酷安是「来自头条推荐 · 设备」）：这里如实写分类 + 发布时间。
                    // 社团帖（sourceOverride）如实写「社团名 · 频道名」——「校园动态」是分类名，
                    // 对社团帖是误导。
                    Text(
                        when {
                            sourceOverride != null && when_.isNotBlank() -> "来自 $sourceOverride · $when_"
                            sourceOverride != null -> "来自 $sourceOverride"
                            when_.isNotBlank() -> "来自 $categoryName · $when_"
                            else -> "来自 $categoryName"
                        },
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp),
                    )
                }
                // 分类用带色 chip：一眼能把求助 / 二手 / 学习资源分开（plan/61 §3）。
                val tint = categoryTint(post.category)
                Surface(color = tint.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        categoryName,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            if (!post.title.isNullOrBlank()) Text(post.title, style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp))
            Text(
                text = remember(excerpt) { excerptWithTopics(excerpt) },
                modifier = Modifier.padding(top = 6.dp),
                maxLines = excerptMaxLines(expanded),
                overflow = TextOverflow.Ellipsis,
                lineHeight = 21.sp,
                fontSize = 14.sp,
                // 用**实际排版结果**判有没有被截断，不猜字数：猜字数在不同字号/缩放下会
                // 「短帖冒出查看更多、长帖反而不给」。
                onTextLayout = { if (it.hasVisualOverflow) truncated = true },
            )
            if (toggle != null) {
                Text(
                    toggle,
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 3.dp).clickable { expanded = !expanded },
                )
            }
            // 图片：真实比例（单图限高、不缩成小色块），规格统一在 PostMediaGrid。
            PostMediaGrid(post.body)
            // 贴附的资料包（47 §3 P4）。卡片自己吞掉点击，不会冒泡到「进详情」。
            if (post.linkedPack != null) {
                Box(Modifier.padding(top = 8.dp).clickable { onOpenPack(post.linkedPack!!) }) {
                    PostPackCard(pack = post.linkedPack)
                }
            }
            // 卡内快捷评论框（酷安「说点儿什么吧」）。点了进详情 —— 输入框自动聚焦还没接，
            // 记在 DESIGN-REFS §5，不假装已经聚焦了。
            Surface(
                color = AppColors.Canvas,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onOpen),
            ) {
                Text("说点儿什么吧", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            // 互动栏：这轮只有「评论（进详情）」与「分享（系统面板）」两个**真能用**的动作。
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionItem(
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Accent, modifier = Modifier.size(16.dp)) },
                    label = commentCountLabel(post._count?.comments),
                    tint = Accent,
                    onClick = onOpen,
                )
                Spacer(Modifier.width(16.dp))
                ActionItem(
                    icon = { Icon(Icons.Outlined.Share, null, tint = Muted, modifier = Modifier.size(16.dp)) },
                    label = "分享",
                    tint = Muted,
                    onClick = { sharePost(context, post, categoryName, excerpt, truncated) },
                )
                Spacer(Modifier.weight(1f))
                if (showReport) TextButton(onClick = onReport, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Outlined.Flag, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("举报", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 互动栏里的一项：图标 + 文案，整体可点。 */
@Composable
private fun ActionItem(icon: @Composable () -> Unit, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

/** `#话题#` 染成强调色（酷安正文里的蓝色话题）。范围由纯函数 `topicSpans` 给，可单测。 */
private fun excerptWithTopics(text: String): AnnotatedString {
    val spans = topicSpans(text)
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        spans.forEach { range ->
            // 双保险：Range 越界会直接抛异常，把整页打成空白 —— 宁可不染色
            if (range.first >= 0 && range.last < text.length) {
                addStyle(SpanStyle(color = Accent), range.first, range.last + 1)
            }
        }
    }
}

/**
 * 系统分享面板。**只发纯文本，不发链接** —— 帖子没有对外可访问的 URL，
 * 编一个出来就是假链接（见 `shareText` 的单测）。
 */
private fun sharePost(
    context: android.content.Context,
    post: PostDto,
    categoryName: String,
    excerpt: String,
    truncated: Boolean,
) {
    val text = shareText(categoryName, post.title, excerpt, truncated = truncated)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        post.title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    try {
        context.startActivity(Intent.createChooser(intent, "分享到"))
    } catch (e: android.content.ActivityNotFoundException) {
        // 真机上没装任何能接收文本的应用时才会走到这里（模拟器常见）。
        // 说清楚「没有可分享的应用」，不要静默失败。
        android.widget.Toast.makeText(context, "没有找到可以分享的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun ReportDialog(post: PostDto, onDismiss: () -> Unit, onSubmit: (String, String?) -> Unit) {
    var reason by remember { mutableStateOf("不实信息") }
    var details by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("举报内容") },
        text = {
            Column {
                Text(post.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Muted)
                OutlinedTextField(reason, { reason = it.take(40) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("举报原因") }, singleLine = true)
                OutlinedTextField(details, { details = it.take(1000) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("补充说明（可选）") }, maxLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { if (reason.trim().length >= 2) onSubmit(reason.trim(), details.trim().ifBlank { null }) }) { Text("提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun String.toCategoryName() = when (this) {
    "WALL" -> "校园动态"
    "SECOND_HAND" -> "二手"
    "HELP" -> "求助"
    "LOST_FOUND" -> "失物招领"
    "NOTES" -> "学习资源"
    else -> this
}
