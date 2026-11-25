package cn.tonglu.app.ui.profile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.*
import cn.tonglu.app.data.community.communityErrorMessage
import cn.tonglu.app.data.community.communityResult
import cn.tonglu.app.ui.*
import cn.tonglu.app.ui.community.CommunityPageHeader
import cn.tonglu.app.ui.community.CommunityReportDialog
import cn.tonglu.app.ui.community.CommunityStatus
import cn.tonglu.app.ui.community.rememberCommunityResource
import cn.tonglu.app.ui.glass.DarkTopBarBackground
import cn.tonglu.app.ui.glass.topBarFadeFraction
import cn.tonglu.app.ui.glass.topBarScrollPx
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalOpenPublicProfile = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }
private data class ProfileDestination(val id: String, val edit: Boolean)

@Composable
internal fun Modifier.publicProfileLink(id: String?, corner: androidx.compose.ui.unit.Dp = 0.dp): Modifier {
    val open = LocalOpenPublicProfile.current
    return if (id.isNullOrBlank() || open == null) this
    else launchClickable(id, corner) { open(id, false) }
}

@Composable
internal fun PublicProfileNavigation(viewModel: AppViewModel, sessionId: String?, content: @Composable () -> Unit) {
    val pages = remember { mutableStateListOf<ProfileDestination>() }
    CompositionLocalProvider(LocalOpenPublicProfile provides { id, edit ->
        if (pages.lastOrNull()?.id != id) pages.add(ProfileDestination(id, edit))
    }) {
        Box(Modifier.fillMaxSize()) {
            content()
            pages.forEachIndexed { index, page -> key(index, page, sessionId) {
                OverlayLayerHost(backEnabled = index == pages.lastIndex, onClose = {
                    OverlayDismissGuard.arm()
                    pages.removeAt(index)
                }) { back -> PublicProfileRoute(page, sessionId, viewModel, back, index == pages.lastIndex) }
            } }
        }
    }
}

private enum class ProfileSection(val path: String, val title: String) {
    Posts("posts", "帖子"), Likes("liked-posts", "获得点赞的帖子"), Following("following", "关注的人"),
    Followers("followers", "粉丝"), Boards("followed-boards", "关注的圈子"),
}

private sealed interface ProfileEntry {
    val id: String
    data class Post(val value: ProfilePostDto) : ProfileEntry { override val id get() = value.id }
    data class Person(val value: ProfilePersonDto) : ProfileEntry { override val id get() = value.id }
    data class Board(val value: ProfileBoardDto) : ProfileEntry { override val id get() = value.id }
}

@Composable
private fun PublicProfileRoute(destination: ProfileDestination, sessionId: String?, viewModel: AppViewModel, back: () -> Unit, active: Boolean) {
    val id = destination.id
    val resource = rememberCommunityResource("主页加载失败") { viewModel.readPublicProfile(sessionId) { publicProfile(id, it) } }
    val profile = resource.value
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(destination.edit) }
    var reporting by remember { mutableStateOf(false) }
    var posted by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var openPost by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf(ProfileSection.Posts) }
    var listRevision by remember { mutableIntStateOf(0) }

    if (profile == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            CommunityPageHeader("个人主页", back)
            CommunityStatus(busy = resource.error == null, error = resource.error) { resource.attempt++ }
        }
        return
    }
    val cover = key(profile.coverUrl) {
        profile.coverUrl?.let { path ->
            rememberCommunityResource("背景图加载失败") {
                val bytes = viewModel.readPublicProfile(sessionId) { profileCover(profile.id, path.substringAfterLast('/'), it) }
                withContext(Dispatchers.IO) {
                    bytes.use { BitmapFactory.decodeStream(it.byteStream())?.asImageBitmap() ?: error("背景图无法读取") }
                }
            }
        }
    }
    val list = key(profile.id, section, listRevision) { rememberProfileList(profile.id, section, sessionId, viewModel) }

    PublicProfilePage(profile, back, cover = cover?.value, actions = {
        if (profile.own) ProfileAction("编辑资料", enabled = active) { editing = true }
        else if (sessionId == null) ProfileAction("登录后关注或举报", onClick = viewModel::openLogin)
        else {
            ProfileAction(if (profile.viewerFollowing == true) "已关注" else "关注", enabled = !following) {
                following = true
                actionError = null
                scope.launch {
                    communityResult {
                        viewModel.communityAuthorized(sessionId) {
                            if (profile.viewerFollowing == true) unfollowProfile(it, profile.id) else followProfile(it, profile.id)
                        }
                    }.onSuccess { result ->
                        val changed = result.following != profile.viewerFollowing
                        resource.value = profile.copy(viewerFollowing = result.following, stats = profile.stats.copy(
                            followers = profile.stats.followers?.let { count ->
                                if (changed) (count + if (result.following) 1 else -1).coerceAtLeast(0) else count
                            },
                        ))
                        if (section == ProfileSection.Followers) listRevision++
                    }.onFailure { actionError = communityErrorMessage(it, "关注操作失败") }
                    following = false
                }
            }
            TextButton(onClick = { reporting = true; posted = false }) { Text("举报", color = AppColors.TextSecondary) }
        }
    }, onPosts = { section = ProfileSection.Posts }, onLikes = { section = ProfileSection.Likes },
        onFollowing = { section = ProfileSection.Following }, onFollowers = { section = ProfileSection.Followers }) {
        item("tabs") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                cover?.error?.let { Text(it, color = AppColors.TextSecondary) }
                resource.error?.let { Text(it, color = AppColors.Danger) }
                actionError?.let { Text(it, color = AppColors.Danger) }
                if (posted) Text("举报已提交", color = AppColors.TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = section == ProfileSection.Posts, onClick = { section = ProfileSection.Posts }, label = { Text("帖子") })
                    if (profile.followedBoardsVisible) FilterChip(selected = section == ProfileSection.Boards,
                        onClick = { section = ProfileSection.Boards }, label = { Text("关注的圈子") })
                }
                if (section != ProfileSection.Posts && section != ProfileSection.Boards) {
                    Text(section.title, modifier = Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = AppColors.Divider)
            }
        }
        items(list.rows, key = { "${section.path}:${it.id}" }) { row ->
            ProfileListEntry(row) { openPost = it }
        }
        item("list-status") {
            CommunityStatus(busy = list.busy, error = list.error, more = list.more,
                empty = "暂无内容".takeIf { list.rows.isEmpty() }) { list.request++ }
        }
    }

    if (editing && sessionId != null && profile.privacy != null) {
        OverlayLayerHost(backEnabled = active, onClose = { OverlayDismissGuard.arm(); editing = false }) { close ->
            EditPublicProfileScreen(profile, cover?.value, sessionId, viewModel, close) { updated ->
                resource.value = updated
                section = ProfileSection.Posts
                listRevision++
                close()
            }
        }
    }
    if (reporting && sessionId != null) {
        CommunityReportDialog(profile.id, "举报主页", profile.displayName, onDismiss = { reporting = false }) { category, details, done ->
            scope.launch {
                communityResult {
                    viewModel.communityAuthorized(sessionId) { reportProfile(it, profile.id, CreateReportRequest(category.label, details, category.name)) }
                }.onSuccess { reporting = false; posted = true; done(null) }
                    .onFailure { done(communityErrorMessage(it, "举报提交失败")) }
            }
        }
    }
    openPost?.let { postId ->
        OverlayLayerHost(backEnabled = active, onClose = { OverlayDismissGuard.arm(); openPost = null }) { close ->
            val postResource = rememberCommunityResource("帖子加载失败") { viewModel.apiOrNull().postDetail(postId) }
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                CommunityPageHeader("帖子", close)
                val post = postResource.value
                if (post == null) CommunityStatus(busy = postResource.error == null, error = postResource.error) { postResource.attempt++ }
                else Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    post.title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                    MarkdownBody(post.body, textColor = AppColors.TextPrimary, mutedColor = AppColors.TextSecondary, accentColor = AppColors.Accent)
                }
            }
        }
    }
}

private class ProfileListState {
    var rows by mutableStateOf(emptyList<ProfileEntry>())
    var page by mutableIntStateOf(0)
    var more by mutableStateOf(false)
    var busy by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var request by mutableIntStateOf(0)
}

@Composable
private fun rememberProfileList(id: String, section: ProfileSection, sessionId: String?, viewModel: AppViewModel): ProfileListState {
    val list = remember { ProfileListState() }
    LaunchedEffect(list.request) {
        list.busy = true
        list.error = null
        communityResult {
            viewModel.readPublicProfile(sessionId) { bearer ->
                when (section) {
                    ProfileSection.Posts, ProfileSection.Likes -> profilePosts(id, section.path, list.page, bearer).map { ProfileEntry.Post(it) }
                    ProfileSection.Boards -> profileBoards(id, list.page, bearer).map { ProfileEntry.Board(it) }
                    ProfileSection.Followers, ProfileSection.Following -> profilePeople(id, section.path, list.page, bearer).map { ProfileEntry.Person(it) }
                }
            }
        }.onSuccess { result ->
            list.rows = (list.rows + result.items).distinctBy { it.id }
            list.more = result.hasMore
            list.page++
        }.onFailure { list.error = communityErrorMessage(it, "列表加载失败") }
        list.busy = false
    }
    return list
}

@Composable
private fun ProfileListEntry(row: ProfileEntry, openPost: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        when (row) {
                is ProfileEntry.Person -> {
                    val person = row.value
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        UserAvatar(person.displayName, person.avatarUrl, Modifier.size(42.dp).publicProfileLink(person.id, 21.dp))
                        Column(Modifier.weight(1f).publicProfileLink(person.id)) {
                            Text(person.displayName)
                            Text("@${person.username}", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                is ProfileEntry.Post -> {
                    val post = row.value
                    Column(Modifier.fillMaxWidth().launchClickable(post.id, 8.dp) { openPost(post.id) }.padding(vertical = 12.dp)) {
                        post.title?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                        Text(post.body, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        post.likes?.let { Text("$it 赞", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                is ProfileEntry.Board -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(row.value.name, fontWeight = FontWeight.SemiBold)
                    Text(row.value.description, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
        }
        HorizontalDivider(color = AppColors.Divider)
    }
}

private fun <T> ProfilePageDto<T>.map(transform: (T) -> ProfileEntry) = ProfilePageDto(items.map(transform), hasMore)

@Composable
internal fun PublicProfilePage(
    profile: PublicProfileDto, back: () -> Unit, cover: ImageBitmap? = null, example: Boolean = false,
    actions: @Composable () -> Unit = {},
    onPosts: (() -> Unit)? = null, onLikes: (() -> Unit)? = null,
    onFollowing: (() -> Unit)? = null, onFollowers: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit = {},
) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val fadeDistance = with(density) { 60.dp.toPx() }
    val scope = rememberCoroutineScope()
    val motion = scope.coroutineContext[MotionDurationScale]
    val fraction = { topBarFadeFraction(list.topBarScrollPx(), fadeDistance, motion?.scaleFactor == 0f) }
    val canvas = AppColors.CanvasOpaque
    Box(Modifier.fillMaxSize().background(canvas)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            item("cover") {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    ProfileCover(profile, cover, parallax = {
                        if (motion?.scaleFactor == 0f || list.firstVisibleItemIndex != 0) 0f
                        else (list.firstVisibleItemScrollOffset * .18f).coerceAtMost(fadeDistance)
                    })
                    ProfileIdentity(profile, actions = actions)
                    if (example) Text("示例数据 · 调试预览", color = AppColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    ProfileStatistics(profile.stats, onPosts, onLikes, onFollowing, onFollowers, onSelect = {
                        scope.launch {
                            if (motion?.scaleFactor == 0f) list.scrollToItem(1) else list.animateScrollToItem(1)
                        }
                    })
                }
            }
            content()
        }
        Box(Modifier.fillMaxWidth()) {
            if (AppTheme.darkState) DarkTopBarBackground(null, { list.topBarScrollPx() }, Modifier.matchParentSize())
            else Box(Modifier.matchParentSize().graphicsLayer { alpha = fraction() }
                .background(Brush.verticalGradient(listOf(canvas, canvas.copy(alpha = .94f), canvas.copy(alpha = 0f)))))
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, modifier = Modifier.background(canvas.copy(alpha = .94f), CircleShape)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = AppColors.TextPrimary)
                }
                Text(profile.displayName, modifier = Modifier.padding(start = 8.dp).graphicsLayer { alpha = fraction() },
                    color = AppColors.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ProfileCover(profile: PublicProfileDto, cover: ImageBitmap?, localCover: Any? = null, parallax: () -> Float = { 0f }) {
    val tint = profile.avatarColor?.let { Color(android.graphics.Color.parseColor(it)) } ?: AppColors.Accent
    val surface = AppColors.SurfaceOpaque
    Box(Modifier.fillMaxWidth().aspectRatio(3f).clipToBounds()
        .background(Brush.linearGradient(listOf(tint.copy(alpha = .34f), surface, AppColors.ChipIdle)))) {
        val imageModifier = Modifier.fillMaxSize().graphicsLayer { translationY = parallax() }
        if (localCover != null) AsyncImage(localCover, null, modifier = imageModifier, contentScale = ContentScale.Crop)
        else if (cover != null) Image(cover, null, modifier = imageModifier, contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun ProfileIdentity(profile: PublicProfileDto, localAvatar: Any? = null, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.requiredSize(76.dp).offset(y = (-12).dp).clip(CircleShape)
                    .background(AppColors.CanvasOpaque).border(1.dp, AppColors.Divider, CircleShape).padding(4.dp)) {
                    if (localAvatar != null) AsyncImage(localAvatar, "头像预览", Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    else UserAvatar(profile.displayName, profile.avatarUrl, Modifier.fillMaxSize())
                }
            }
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { actions() }
        }
        Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("@${profile.username}", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.padding(top = 5.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (profile.verifiedStudent) ProfileBadge("校园认证", AppColors.Accent)
            profile.level?.let { ProfileBadge("Lv$it", AppColors.TextSecondary) }
            profile.profileTags.forEach { ProfileBadge(it, AppColors.TextSecondary) }
        }
    }
}

@Composable
private fun ProfileBadge(label: String, color: Color) {
    Text(label, color = color, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(CircleShape).background(AppColors.ChipIdle).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
internal fun ProfileAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent)) { Text(label) }
}

@Composable
internal fun ProfileStatistics(
    stats: ProfileStatsDto, onPosts: (() -> Unit)? = null, onLikes: (() -> Unit)? = null,
    onFollowing: (() -> Unit)? = null, onFollowers: (() -> Unit)? = null, onSelect: () -> Unit = {},
) {
    fun action(callback: (() -> Unit)?): (() -> Unit)? = callback?.let { { it(); onSelect() } }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ProfileStat("帖子", stats.posts, action(onPosts))
        stats.likes?.let { ProfileStat("获赞", it, action(onLikes)) }
        stats.following?.let { ProfileStat("关注", it, action(onFollowing)) }
        stats.followers?.let { ProfileStat("粉丝", it, action(onFollowers)) }
    }
}

@Composable
private fun ProfileStat(label: String, count: Int, onClick: (() -> Unit)?) {
    val size = Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp)
    Row((if (onClick == null) size else Modifier.clickable(onClick = onClick).then(size)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
        Text(label, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
