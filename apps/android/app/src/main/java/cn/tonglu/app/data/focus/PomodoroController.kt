package cn.tonglu.app.data.focus

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application singleton; collectors observe one session across navigation and transient windows. */
class PomodoroController private constructor(context: Context) {
    private val app=context.applicationContext
    private val store=PomodoroStore(app)
    private val mutex=Mutex()
    private var owner: String?=null
    @Volatile private var readyOwner: String?=null
    fun isBoundTo(value: String)=readyOwner==value
    private val _state=MutableStateFlow<PomodoroState?>(null)
    val state: StateFlow<PomodoroState?> = _state.asStateFlow()
    private val _options=MutableStateFlow(PomodoroStore.Options())
    val options: StateFlow<PomodoroStore.Options> = _options.asStateFlow()
    private val _error=MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _warning=MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private fun publish(next: PomodoroState?, previous: PomodoroState? = null) {
        _state.value=next
        _warning.value=try {
            cn.tonglu.app.notify.PomodoroReminder.sync(app,next,_options.value,previous)
        } catch(e: Exception) {
            Log.w("Pomodoro", "后台提醒安排失败",e)
            "后台提醒安排失败，请保持应用前台并重试"
        }
    }

    fun clock()=PomodoroClock(SystemClock.elapsedRealtime(),System.currentTimeMillis(),
        Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT,0))

    suspend fun bindOwner(value: String)=withContext(Dispatchers.IO) { mutex.withLock {
        require(value.isNotBlank())
        if (owner==value && _error.value==null) return@withLock
        readyOwner=null
        val old=_state.value
        val changing=owner!=value
        _state.value=null; owner=value; _error.value=null
        try {
            if(changing) publish(null)
            if(old?.running==true && changing) store.save(PomodoroEngine.pause(old,clock()))
            _options.value=store.options(value)
            val saved=store.load(value)
            val restored=saved?.let { PomodoroEngine.advance(it,clock()) }
            if (restored!=null && restored!=saved) store.save(restored)
            publish(restored,saved)
        } catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            Log.w("Pomodoro", "计时记录读取失败",e)
            _options.value=PomodoroStore.Options(); _error.value="番茄钟记录读取失败，请重试"
            publish(null)
        } finally { readyOwner=value }
    } }

    suspend fun updateOptions(value: PomodoroStore.Options)=withContext(Dispatchers.IO) { mutex.withLock {
        val key=checkNotNull(owner) { "请先打开番茄钟" }
        store.saveOptions(key,value);_options.value=value;publish(_state.value)
    } }

    suspend fun companion(quiet: Boolean, automatic: Boolean=true): String?=withContext(Dispatchers.IO) { mutex.withLock {
        val current=_state.value ?: return@withLock null
        if(quiet || !_options.value.companion || current.phase in listOf(PomodoroPhase.Paused,PomodoroPhase.Review,PomodoroPhase.Stopped)) return@withLock null
        val now=clock()
        if(automatic && (current.phase!=PomodoroPhase.Focus || now.elapsed-current.phaseStartedElapsed<15*60_000L)) return@withLock null
        try { store.companion(current,now,automatic) }
        catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            Log.w("Pomodoro","学习提醒记录保存失败",e);_warning.value="学习提醒记录保存失败，已停止本次弹幕";null
        }
    } }

    /** False means an unfinished session already exists; callers must show its controls. */
    suspend fun startTask(taskId: String?, label: String, preset: PomodoroPreset=options.value.preset): Boolean =
        withContext(Dispatchers.IO) { mutex.withLock {
            val key=checkNotNull(owner) { "请先打开番茄钟" }
            check(_error.value==null) { "请先重试读取番茄钟记录，避免覆盖原进度" }
            if(_state.value?.unfinished==true) return@withLock false
            val next=PomodoroEngine.start(UUID.randomUUID().toString(),key,preset,clock(),taskId,label)
            store.save(next);publish(next);_error.value=null;true
        } }

    private suspend fun change(operation:(PomodoroState,PomodoroClock)->PomodoroState) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val current=_state.value ?: return@withLock
            val next=operation(current,clock())
            if(next!=current) store.save(next)
            publish(next,current)
        } }

    /** Alarm/actions from older sessions cannot mutate a newer timer. Checked under the same mutex. */
    suspend fun expected(owner: String, id: String, sequence: Long, action: String)=
        withContext(Dispatchers.IO) { mutex.withLock {
            val current=_state.value ?: return@withLock
            if(current.owner!=owner || current.id!=id || current.sequence!=sequence) return@withLock
            val now=clock()
            val next=when(action) {
                "pause" -> PomodoroEngine.pause(current,now)
                "resume" -> PomodoroEngine.resume(current,now)
                "stop" -> PomodoroEngine.stop(current,now)
                else -> PomodoroEngine.advance(current,now)
            }
            if(next!=current) store.save(next)
            publish(next,current)
        } }

    suspend fun refresh() {
        try { change(PomodoroEngine::advance) }
        catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            Log.e("Pomodoro","计时进度保存失败",e)
            _warning.value="计时进度保存失败，请打开番茄钟重试；未将本阶段标记为完成"
        }
    }
    suspend fun pause()=change(PomodoroEngine::pause)
    suspend fun resume()=change(PomodoroEngine::resume)
    suspend fun stop()=change(PomodoroEngine::stop)
    suspend fun skipRest()=change(PomodoroEngine::skipRest)
    suspend fun restartInterrupted()=change(PomodoroEngine::restartInterrupted)
    suspend fun confirmInterrupted()=change(PomodoroEngine::confirmInterrupted)

    companion object {
        @Volatile private var instance: PomodoroController?=null
        fun get(context:Context): PomodoroController = instance ?: synchronized(this) {
            instance ?: PomodoroController(context).also { instance=it }
        }
    }
}
