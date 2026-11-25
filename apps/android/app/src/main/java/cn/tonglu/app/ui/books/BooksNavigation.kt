package cn.tonglu.app.ui.books

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayLayerHost
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import kotlinx.coroutines.launch

internal data class BooksEntry(
    val course: String? = null,
    val id: String? = null,
    val requestWanted: Boolean = false,
)

internal class BooksAccess(
    val repository: BooksRepository?,
    val revision: Int,
    val open: (BooksEntry) -> Unit,
)
internal val LocalBooksAccess = staticCompositionLocalOf<BooksAccess?> { null }

private sealed interface BooksRoute {
    data object Home : BooksRoute
    data object Mine : BooksRoute
    data object Verify : BooksRoute
    data class Detail(val id: String) : BooksRoute
    data class Publish(val listing: BookListing? = null) : BooksRoute
    data class Wanted(val seed: String) : BooksRoute
}

@Composable
internal fun CourseBooksLink(course: String) {
    val access = LocalBooksAccess.current ?: return
    val repo = access.repository
    val count = if (repo == null) null else bookLoad(repo, course, access.revision) { repo.countForCourse(course) }
    val origin = rememberLaunchSource("course-books-$course", 12.dp, AppColors.SurfaceOpaque)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).launchOrigin(origin).clickable {
            origin.launch { access.open(BooksEntry(course = course, requestWanted = count?.value == 0)) }
        },
        verticalArrangement = Arrangement.Center,
    ) {
        Text("这门课的二手书", fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
        Text(
            when {
                repo == null -> "登录后查看"
                count?.error != null -> "数量加载失败，查看书单"
                count?.value == null -> "正在查询在售数量…"
                count.value == 0 -> "暂无，去求购"
                else -> "${count.value} 本在售"
            },
            color = if (count?.error != null) AppColors.Danger else AppColors.Accent,
        )
    }
}

@Composable
internal fun BooksHost(
    entry: BooksEntry,
    access: BooksAccess,
    state: AppUiState,
    onClose: () -> Unit,
    onLogin: () -> Unit,
    onChanged: () -> Unit,
    verification: @Composable (onClose: () -> Unit) -> Unit,
) {
    val repository = access.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layers = remember { mutableStateListOf<BooksRoute>() }
    val initialRoute = entry.id?.let { BooksRoute.Detail(it) } ?: BooksRoute.Home
    val sessionUserId = state.session?.userId.orEmpty()
    var userAttempt by remember { mutableIntStateOf(0) }
    val user = if (repository != null && sessionUserId.isEmpty()) bookLoad(repository, userAttempt) {
        repository.call { profile(it).id }
    } else BookLoad(value = sessionUserId)
    val userId = user.value
    var checkingIdentity by remember { mutableStateOf(false) }
    val onCampus = {
        Toast.makeText(context, BOOK_CAMPUS_REQUIRED, Toast.LENGTH_SHORT).show()
        if (layers.lastOrNull() != BooksRoute.Verify) layers.add(BooksRoute.Verify)
        Unit
    }
    val verifiedAction: (() -> Unit) -> Unit = { action ->
        val identity = state.campusIdentity
        when {
            repository == null -> onLogin()
            identity != null -> if (identity.studentVerified || identity.emailAssociated) action() else onCampus()
            !checkingIdentity -> {
                checkingIdentity = true
                scope.launch {
                    try {
                        bookResult { repository.call { campusIdentity(it) } }.onSuccess {
                            if (it.studentVerified || it.emailAssociated) action() else onCampus()
                        }.onFailure {
                            Toast.makeText(context, it.toBookError().message, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        checkingIdentity = false
                    }
                }
            }
            else -> Unit
        }
    }
    var autoWanted by remember(entry) { mutableStateOf(entry.requestWanted) }
    LaunchedEffect(repository, userId) {
        if (autoWanted && repository != null && userId != null) {
            autoWanted = false
            verifiedAction { layers.add(BooksRoute.Wanted(seed = "")) }
        }
    }

    val render: @Composable (BooksRoute, () -> Unit) -> Unit = { route, back ->
        if (route == BooksRoute.Home || repository == null) {
            BooksHome(
                repository, entry.course, access.revision, back, onLogin,
                onPublish = { verifiedAction { layers.add(BooksRoute.Publish()) } },
                onMine = { layers.add(BooksRoute.Mine) },
                onDetail = { layers.add(BooksRoute.Detail(it)) },
                onWanted = { seed -> verifiedAction { layers.add(BooksRoute.Wanted(seed)) } },
            )
        } else when (route) {
            BooksRoute.Home -> Unit
            BooksRoute.Verify -> verification(back)
            BooksRoute.Mine -> BooksMine(
                repository, access.revision, back, onDetail = { layers.add(BooksRoute.Detail(it)) },
                onWanted = { verifiedAction { layers.add(BooksRoute.Wanted("")) } }, onChanged = onChanged,
            )
            is BooksRoute.Detail -> {
                if (userId == null) {
                    BooksPage("书籍详情", back) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
                            if (user.error != null) BookErrorText(user.error.message) { userAttempt++ } else BookLoading()
                        }
                    }
                } else {
                    val contactPrefs = remember(userId, context) {
                        context.getSharedPreferences("books_contact", Context.MODE_PRIVATE)
                    }
                    BookDetail(
                        repository, route.id, userId, access.revision, verifiedAction, onCampus, back,
                        onEdit = { layers.add(BooksRoute.Publish(it)) }, onChanged = onChanged,
                        contact = { contactPrefs.getString(userId, "").orEmpty() },
                        rememberContact = { contactPrefs.edit().putString(userId, it).apply() },
                    )
                }
            }
            is BooksRoute.Publish -> BookPublish(
                repository, state.courses.map { it.name }.distinct(), entry.course, back, onCampus,
                onSaved = {
                    onChanged()
                    val id = it
                    if (id != null) {
                        layers[layers.lastIndex] = BooksRoute.Detail(id)
                    } else {
                        back()
                    }
                },
                onConflict = onChanged, editing = route.listing,
            )
            is BooksRoute.Wanted -> BookWantedForm(repository, route.seed, entry.course, back, onCampus) {
                onChanged()
                Toast.makeText(context, "求购已发布", Toast.LENGTH_SHORT).show()
                back()
            }
        }
    }

    val rootOperation = remember { BookOperation() }
    OverlayLayerHost(onClose = onClose, backEnabled = !rootOperation.busy, dialog = entry.course != null) { closeRoot ->
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBookOperation provides rootOperation) { render(initialRoute, closeRoot) }
            layers.forEachIndexed { index, layer ->
                key(index, layer) {
                    val operation = remember { BookOperation() }
                    OverlayLayerHost(backEnabled = !operation.busy, onClose = {
                        OverlayDismissGuard.arm()
                        if (layers.lastIndex == index) layers.removeAt(index)
                    }) { close ->
                        CompositionLocalProvider(LocalBookOperation provides operation) { render(layer, close) }
                    }
                }
            }
        }
    }
}
