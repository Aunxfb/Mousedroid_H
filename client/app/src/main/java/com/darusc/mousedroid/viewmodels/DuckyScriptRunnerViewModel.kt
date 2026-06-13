package com.darusc.mousedroid.viewmodels

import com.darusc.mousedroid.interpreter.DuckyInterpreter
import com.darusc.mousedroid.interpreter.DuckyExecutor
import com.darusc.mousedroid.interpreter.TcpDuckyExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class DuckyScriptRunnerViewModel : BaseViewModel<DuckyScriptRunnerViewModel.State, DuckyScriptRunnerViewModel.Event>(State.Idle) {

    private val logBuffer = mutableListOf<String>()
    private val MAX_LOGS = 200

    fun getLogs(): List<String> = logBuffer.toList()

    fun clearLogs() {
        logBuffer.clear()
        _logText.value = ""
    }

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    private fun addLog(message: String) {
        synchronized(logBuffer) {
            if (logBuffer.size >= MAX_LOGS) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(message)
        }
        _logText.value = logBuffer.joinToString("\n")
    }

    private val executor: DuckyExecutor = TcpDuckyExecutor(
        onLog = { msg ->
            addLog(msg)
        }
    )

    sealed class State : BaseViewModel.State() {
        object Idle : State()
        object Executing : State()
        object Complete : State()
        data class Error(val message: String) : State()
    }

    sealed class Event : BaseViewModel.Event() {
        data class StateChanged(val state: State) : Event()
    }

    fun compileAndRun(script: String) {
        if (script.isBlank()) {
            setState(State.Error("Script is empty"))
            return
        }

        setState(State.Executing)
        clearLogs()
        addLog("Starting script execution (${script.length} chars)")

        viewModelScope.launch {
            try {
                val interpreter = DuckyInterpreter(executor)
                interpreter.executeScript(script)
                addLog("Script completed successfully")
                setState(State.Complete)
            } catch (e: Exception) {
                val errorMsg = "Execution error: ${e.message ?: "Unknown error"}"
                addLog(errorMsg)
                addLog("Stack trace: ${e.stackTraceToString()}")
                setState(State.Error(errorMsg))
            }
        }
    }

    override fun setState(state: State) {
        _state.value = state
    }
}
