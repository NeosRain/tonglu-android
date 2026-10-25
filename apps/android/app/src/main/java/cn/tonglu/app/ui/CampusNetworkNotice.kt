package cn.tonglu.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.tonglu.app.data.NetworkEnv

/**
 * 教务导入的网络体检结果。
 *
 * 课表导入（[ScheduleImportScreen]）与成绩导入（[ScoreImportScreen]）**共用这一份** ——
 * 两处都需要在"打不开教务"时给出同一句人话，写两遍迟早文案不一致。
 *
 * 只回答两个问题：开着 VPN 吗（[vpn]）？学校系统够得着吗（[reachable]）？
 * 另外留了 [loadFailed]：WebView 明确报了主框架加载失败时也当"够不着"处理 ——
 * 体检通过但页面照样打不开的情况（例如中途断网）得有个兜底。
 */
internal class CampusNetworkCheck internal constructor() {
    /** 体检还在跑 —— 这时不显示任何东西，别用"可能有问题"吓人 */
    var checking: Boolean by mutableStateOf(true)
        private set

    /** 系统 VPN 是否连着 */
    var vpn: Boolean by mutableStateOf(false)
        private set

    /** 教务站点是否够得着 */
    var reachable: Boolean by mutableStateOf(true)
        private set

    /** WebView 报错（主框架）时置 true */
    var loadFailed: Boolean by mutableStateOf(false)

    /** 重新体检（用户点「重新检测」，或从后台回来） */
    var revision: Int by mutableIntStateOf(0)
        private set

    internal suspend fun probe(context: android.content.Context, url: String) {
        checking = true
        vpn = NetworkEnv.vpnActive(context)
        reachable = NetworkEnv.campusReachable(url)
        checking = false
    }

    internal fun requestRetry() {
        loadFailed = false
        revision++
    }
}

/**
 * 在导入页挂上这份体检：进页面测一次，从后台回来再测一次（用户可能刚开了/关了 VPN）
 * —— 别在页面里循环轮询，那是白烧电。
 */
@Composable
internal fun rememberCampusNetworkCheck(url: String = "https://$JW_HOST/"): CampusNetworkCheck {
    val context = LocalContext.current.applicationContext
    val check = remember { CampusNetworkCheck() }
    LaunchedEffect(check.revision, url) { check.probe(context, url) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, check) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) check.requestRetry()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return check
}

/**
 * 网络不对劲时的那条提示。**正常情况什么都不显示** —— 导入页本来信息就密，
 * 多一条"网络正常"的绿条只会添乱。
 */
@Composable
internal fun CampusNetworkNotice(check: CampusNetworkCheck, modifier: Modifier = Modifier) {
    if (check.checking) return
    val unreachable = !check.reachable || check.loadFailed
    if (!unreachable && !check.vpn) return

    val background: Color
    val foreground: Color
    val message: String
    when {
        unreachable && check.vpn -> {
            background = noticeBackground(Color(0xFFFFEBEE))
            foreground = noticeForeground(Color(0xFFB91C1C))
            message = "检测到 VPN，且学校教务系统（$JW_HOST）在当前网络下访问不到，这次导不进来。" +
                "请关闭 VPN、换到校园网或国内网络后重试。"
        }
        unreachable -> {
            background = noticeBackground(Color(0xFFFFEBEE))
            foreground = noticeForeground(Color(0xFFB91C1C))
            message = "当前网络访问不到学校教务系统（$JW_HOST），无法导入数据。" +
                "境外 IP 会被学校系统的出口策略挡住，请连接校园网或国内网络后重试。"
        }
        else -> {
            // 开着 VPN 但居然还通：不拦，只提个醒 —— 登录跳转和接口照样可能被拦。
            background = noticeBackground(Color(0xFFFFF3E0))
            foreground = noticeForeground(Color(0xFFB26A00))
            message = "当前开着 VPN：教务系统也许能打开，但登录跳转和接口常被拦。" +
                "导入不顺利就关掉 VPN 再试一次。"
        }
    }

    Surface(color = background, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(message, color = foreground, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = check::requestRetry) {
                Text("重新检测", color = foreground)
            }
        }
    }
}
