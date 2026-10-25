package cn.tonglu.app.notify

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * 「灵岛」适配层。
 *
 * 事实：安卓没有统一的"灵动岛 API"。系统把「符合条件的实时通知」二次呈现到岛区域：
 *  - Android 16+：Live Updates（状态栏实时活动）——常驻 + 进度 + 事件分类即可；
 *  - 小米超级岛 / 焦点通知：需要向小米申请「焦点通知」权限，并在通知 extras 里带
 *    miui.focus.* 参数（下面的参数名与小米官方文档一致，未获授权时系统会忽略）；
 *  - vivo 原子岛 / 原子通知：需要用 vivo 推送 SDK 申请「原子通知」权限；
 *  - OPPO/一加 泛在服务、荣耀 灵动胶囊：同理需要厂商侧申请与 SDK；
 *  - 华为 实况窗：需要 HarmonyOS/华为推送的「实况窗」能力。
 *
 * 所以这一层做两件事：
 *  1. 统一按实时通知的规格构造通知（常驻、进度、分类、高优先级），让系统有机会把它
 *     显示成实时活动；
 *  2. 把各厂商的附加参数集中在这里，拿到授权后只需填参数，不用改业务代码。
 */
object IslandAdapter {

    /** 当前设备是否可以显示实时活动类通知（Android 16+ 原生支持） */
    fun supportsLiveUpdates(): Boolean = Build.VERSION.SDK_INT >= 36

    /** 设备厂商，UI 上用来提示"你的机型怎么开启" */
    fun vendorLabel(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi" -> "小米（超级岛 / 焦点通知）"
        "vivo", "iqoo" -> "vivo（原子岛 / 原子通知）"
        "oppo", "oneplus", "realme" -> "OPPO 系（泛在服务）"
        "honor" -> "荣耀（灵动胶囊）"
        "huawei" -> "华为（实况窗）"
        else -> "通用实时通知"
    }

    /**
     * 附加厂商参数。参数名按各家公开文档填写；没有对应能力/授权时系统直接忽略，不会报错。
     * 拿到厂商授权后在这里补充对应 SDK 的调用即可。
     */
    fun applyVendorExtras(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        content: String,
        stateLabel: String,
    ) {
        builder.setExtras(vendorExtras(title, content, stateLabel))
    }

    fun vendorExtras(title: String, content: String, stateLabel: String): Bundle = Bundle().apply {
        // 小米「焦点通知」（需要小米侧开通，未开通时被忽略）
        putString("miui.focus.param", "class_reminder")
        putString("miui.focus.ticker", title)
        putString("miui.focus.ticker.view", content)
        putString("miui.focus.pic_ticker", content)
        if (stateLabel.isNotBlank()) putString("miui.focus.state", stateLabel)

        // 通用：让系统知道这是一条"可实时更新"的通知
        putBoolean("android.template", true)
        putBoolean("android.allowRealtimeUpdate", true)
    }
}
