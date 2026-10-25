package cn.tonglu.app.data.speech

/** 首次进入不重复绑定；离开系统设置再回来时，重新查询引擎与语音数据。 */
internal class SpeechResumeRefresh {
    private var paused = false

    fun onPause() { paused = true }

    fun onResume(): Boolean {
        val refresh = paused
        paused = false
        return refresh
    }
}
