package cn.tonglu.app.data.listening

/**
 * 听力题的时间轴区间（**纯逻辑**，可单测）。
 *
 * 服务端给的 `audioStartMs` / `audioEndMs` 可能缺、可能反（`end <= start`）、可能超出音轨长度
 * —— 尤其**换过音频源**之后（时间轴是照着某一条音轨量的）。
 * 这里统一收一道：**不合法的区间一律返回 null**，界面据此不显示「重听本题」按钮。
 * 宁可少一个按钮，也不要出现「点了跳到 0 秒」或「跳到音轨外」这种看起来像 bug 的行为。
 */
object AudioRange {

    data class Segment(val startMs: Int, val endMs: Int) {
        val lengthMs: Int get() = endMs - startMs
    }

    /**
     * @param startMs 服务端给的起点；负数表示「没有时间轴」
     * @param endMs 服务端给的终点
     * @param durationMs 本机已知的音轨时长；**未知时传 0**（此时只做区间自洽检查，不裁上限）
     */
    fun clamp(startMs: Int, endMs: Int, durationMs: Int): Segment? {
        if (startMs < 0 || endMs <= startMs) return null
        if (durationMs > 0) {
            if (startMs >= durationMs) return null          // 起点已经在轨尾之外 → 这条轴不是这条音轨的
            val e = minOf(endMs, durationMs)
            return if (e > startMs) Segment(startMs, e) else null
        }
        return Segment(startMs, endMs)
    }
}

/** 区间结束只触发一次。定位完成前读到的仍可能是上一次播放的位置。 */
internal class AudioRangePlayback {
    private var segment: AudioRange.Segment? = null
    var isSeeking = false
        private set

    fun begin(segment: AudioRange.Segment) {
        this.segment = segment
        isSeeking = true
    }

    fun onSeekComplete(): Boolean = isSeeking.also { isSeeking = false }

    fun onPosition(positionMs: Int): Boolean {
        val current = segment ?: return false
        if (isSeeking || positionMs < current.endMs) return false
        clear()
        return true
    }

    fun clear() {
        segment = null
        isSeeking = false
    }
}
