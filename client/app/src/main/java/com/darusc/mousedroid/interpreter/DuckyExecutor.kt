package com.darusc.mousedroid.interpreter

/**
 * Abstract interface decoupling the interpreter from the hardware/transport layer.
 * All implementations should use ConnectionManager.send(InputEvent.KeyPress)
 * to work with both TCP and Bluetooth connections.
 */
interface DuckyExecutor {

    /** Inject a single character keystroke (for STRING commands) */
    fun injectChar(char: Char)

    /** Inject a modifier+key combo (e.g. CONTROL + c). The [keyName] is the key part only. */
    fun injectModifierCombo(keyName: String, modifier: String)

    /** Inject a standalone keycode (ENTER, ESCAPE, ARROWUP, F1, etc.) */
    fun injectKeycode(keyName: String)

    /** Emit a debug log message */
    fun logMessage(message: String) { }

    companion object {
        fun normalizeKeycode(token: String): String = when (token.uppercase()) {
            "ARROWUP", "UP" -> "ARROWUP"
            "ARROWDOWN", "DOWN" -> "ARROWDOWN"
            "ARROWLEFT", "LEFT" -> "ARROWLEFT"
            "ARROWRIGHT", "RIGHT" -> "ARROWRIGHT"
            else -> token.uppercase()
        }
    }
}
