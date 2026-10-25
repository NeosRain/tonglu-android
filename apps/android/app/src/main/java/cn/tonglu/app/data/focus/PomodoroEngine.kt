package cn.tonglu.app.data.focus

/** No clocks, storage, UI or network inside the state machine. All durations are milliseconds. */
data class PomodoroPreset(val focusMinutes: Int = 25, val restMinutes: Int = 5, val rounds: Int = 4) {
    fun validated(): PomodoroPreset = apply {
        require(focusMinutes in 5..120 && restMinutes in 1..30 && rounds in 1..12) { "请检查专注、休息时长与轮数" }
    }
}

enum class PomodoroPhase { Preparing, Focus, Rest, Paused, Review, Complete, Stopped }

data class PomodoroClock(val elapsed: Long, val wall: Long, val boot: Int)

data class PomodoroState(
    val id: String,
    val owner: String,
    val preset: PomodoroPreset,
    val taskId: String?,
    val label: String,
    val phase: PomodoroPhase,
    val round: Int = 1,
    val completedFocus: Int = 0,
    val sequence: Long = 0,
    val createdWall: Long,
    val updatedWall: Long,
    val boot: Int,
    val phaseStartedElapsed: Long,
    val deadlineElapsed: Long,
    val remainingAtStart: Long,
    val focusMillis: Long = 0,
    val resumePhase: PomodoroPhase? = null,
    val interruption: String? = null,
) {
    val running: Boolean get() = phase in listOf(PomodoroPhase.Preparing, PomodoroPhase.Focus, PomodoroPhase.Rest)
    val unfinished: Boolean get() = running || phase == PomodoroPhase.Paused || phase == PomodoroPhase.Review
    fun remaining(elapsed: Long): Long = if (running) (deadlineElapsed - elapsed).coerceAtLeast(0) else remainingAtStart
    fun spentFocus(elapsed: Long): Long = focusMillis + if (phase == PomodoroPhase.Focus)
        (elapsed - phaseStartedElapsed).coerceIn(0, remainingAtStart) else 0L
}

object PomodoroEngine {
    fun start(id: String, owner: String, preset: PomodoroPreset, clock: PomodoroClock,
              taskId: String? = null, label: String = "学习"): PomodoroState {
        preset.validated()
        require(id.isNotBlank() && owner.isNotBlank())
        return PomodoroState(id, owner, preset, taskId?.takeIf { it.isNotBlank() }?.take(200),
            label.trim().take(80).ifBlank { "学习" }, PomodoroPhase.Preparing,
            createdWall = clock.wall, updatedWall = clock.wall, boot = clock.boot,
            phaseStartedElapsed = clock.elapsed, deadlineElapsed = clock.elapsed + 3_000, remainingAtStart = 3_000)
    }

    private fun duration(state: PomodoroState, phase: PomodoroPhase): Long = when (phase) {
        PomodoroPhase.Preparing -> 3_000L
        PomodoroPhase.Focus -> state.preset.focusMinutes * 60_000L
        PomodoroPhase.Rest -> state.preset.restMinutes * 60_000L
        else -> 0L
    }

    private fun at(state: PomodoroState, phase: PomodoroPhase, clock: PomodoroClock,
                   startElapsed: Long = clock.elapsed, remaining: Long = duration(state, phase)): PomodoroState =
        state.copy(phase = phase, sequence = state.sequence + 1, updatedWall = clock.wall, boot = clock.boot,
            phaseStartedElapsed = startElapsed, deadlineElapsed = startElapsed + remaining,
            remainingAtStart = remaining, resumePhase = null, interruption = null)

    /** Advance at most one boundary. Never invent a sequence of completed rounds while absent. */
    fun advance(state: PomodoroState, clock: PomodoroClock): PomodoroState {
        if (!state.running) return state
        if (clock.boot != state.boot || clock.elapsed < state.phaseStartedElapsed) {
            return review(state, clock, "设备已重启，请确认本轮进度")
        }
        if (clock.elapsed < state.deadlineElapsed) return state
        val next = when (state.phase) {
            PomodoroPhase.Preparing -> PomodoroPhase.Focus
            PomodoroPhase.Focus -> PomodoroPhase.Rest
            PomodoroPhase.Rest -> if (state.round == state.preset.rounds) PomodoroPhase.Complete else PomodoroPhase.Focus
            else -> return state
        }
        // A delayed notification may shorten the next break, but cannot silently count a missed focus.
        val late = clock.elapsed - state.deadlineElapsed
        val nextSpan = if (next == PomodoroPhase.Complete) state.preset.focusMinutes * 60_000L else duration(state, next)
        if (late >= nextSpan) return review(state, clock, "计时中断较久，请确认后继续")
        return when (state.phase) {
            PomodoroPhase.Preparing -> at(state, next, clock, state.deadlineElapsed)
            PomodoroPhase.Focus -> at(state.copy(completedFocus = state.completedFocus + 1,
                focusMillis = state.focusMillis + state.remainingAtStart), next, clock, state.deadlineElapsed)
            PomodoroPhase.Rest -> if (next == PomodoroPhase.Complete)
                at(state, next, clock, remaining = 0) else at(state.copy(round = state.round + 1), next, clock, state.deadlineElapsed)
            else -> state
        }
    }

    fun pause(input: PomodoroState, clock: PomodoroClock): PomodoroState {
        val state = advance(input, clock)
        if (!state.running) return state
        return state.copy(phase = PomodoroPhase.Paused, resumePhase = state.phase,
            focusMillis = state.spentFocus(clock.elapsed), remainingAtStart = state.remaining(clock.elapsed),
            deadlineElapsed = 0, sequence = state.sequence + 1, updatedWall = clock.wall)
    }

    fun resume(state: PomodoroState, clock: PomodoroClock): PomodoroState {
        if (state.phase != PomodoroPhase.Paused) return state
        return at(state, state.resumePhase ?: PomodoroPhase.Focus, clock, remaining = state.remainingAtStart)
    }

    private fun review(state: PomodoroState, clock: PomodoroClock, reason: String): PomodoroState =
        state.copy(phase = PomodoroPhase.Review, resumePhase = state.phase, remainingAtStart = duration(state, state.phase),
            deadlineElapsed = 0, phaseStartedElapsed = clock.elapsed, boot = clock.boot,
            sequence = state.sequence + 1, updatedWall = clock.wall, interruption = reason)

    /** The user chooses to restart the interrupted phase. Existing completed rounds remain intact. */
    fun restartInterrupted(state: PomodoroState, clock: PomodoroClock): PomodoroState =
        if (state.phase == PomodoroPhase.Review) at(state, state.resumePhase ?: PomodoroPhase.Focus, clock) else state

    /** Explicit user confirmation, used only after an interruption; not an automatic completion. */
    fun confirmInterrupted(state: PomodoroState, clock: PomodoroClock): PomodoroState {
        if (state.phase != PomodoroPhase.Review) return state
        return when (state.resumePhase) {
            PomodoroPhase.Focus -> at(state.copy(completedFocus = state.completedFocus + 1), PomodoroPhase.Rest, clock)
            PomodoroPhase.Rest -> if (state.round >= state.preset.rounds) at(state, PomodoroPhase.Complete, clock, remaining = 0)
                else at(state.copy(round = state.round + 1), PomodoroPhase.Focus, clock)
            else -> at(state, PomodoroPhase.Focus, clock)
        }
    }

    fun skipRest(input: PomodoroState, clock: PomodoroClock): PomodoroState {
        val state = advance(input, clock)
        if (state.phase != PomodoroPhase.Rest) return state
        return if (state.round >= state.preset.rounds) at(state, PomodoroPhase.Complete, clock, remaining = 0)
            else at(state.copy(round = state.round + 1), PomodoroPhase.Focus, clock)
    }

    fun stop(state: PomodoroState, clock: PomodoroClock): PomodoroState = if (state.phase==PomodoroPhase.Stopped) state else
        state.copy(phase = PomodoroPhase.Stopped, focusMillis = state.spentFocus(clock.elapsed),
            remainingAtStart = 0, deadlineElapsed = 0, resumePhase = null, interruption = null,
            sequence = state.sequence + 1, updatedWall = clock.wall)
}
