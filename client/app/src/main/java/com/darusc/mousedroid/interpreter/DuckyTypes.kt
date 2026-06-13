package com.darusc.mousedroid.interpreter

/**
 * Valid tokens recognized by the Ducky Lexer.
 * Alternate forms (WINDOWS/GO, CTRL/CONTROL) are normalized at parse time.
 */
enum class DuckyCommand {
    REM,
    STRING,
    DELAY,
    DEFAULTDELAY,
    KEYBOARD,
    MODIFIER,
    REPEAT,
    UNKNOWN
}

/**
 * Representation of a single, compiled execution instruction.
 */
data class DuckyInstruction(
    val command: DuckyCommand,
    val normalized: String = "",
    val argument: String = "",
    val rawToken: String = ""
)
