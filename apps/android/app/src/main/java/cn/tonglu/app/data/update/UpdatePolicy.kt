package cn.tonglu.app.data.update

/**
 * 判断清单里的版本是不是「比本机新」。
 *
 * 只比 versionCode 不够：versionCode 由 versionName 按公式算出（0.9.4 → 904），
 * 预发布包不占额外号段，于是 `0.9.4-rc.3` 与 `0.9.4` **都是 904**。
 * 装过候选包的设备会一直看到 `904 > 904 == false`，既收不到更新提示，
 * 手动下载也会在 [AppUpdates.verify] 被「版本不匹配」挡回去（BUGS S154）。
 *
 * 因此补一条：**本机是预发布版**时，同号的正式包也算更新。
 * 反向不成立（本机正式、清单同号）——否则已是最新的用户会被反复提示。
 */
internal fun isNewerRelease(release: UpdateRelease, installedCode: Long, installedName: String): Boolean {
    if (release.versionCode != installedCode) return release.versionCode > installedCode
    val installedPrerelease = installedName.contains('-')
    val releasePrerelease = release.versionName.contains('-')
    return installedPrerelease && !releasePrerelease
}
