package com.darusc.mousedroid.interpreter

import kotlinx.coroutines.delay

/**
 * Parses and executes DuckyScript lines, converting each command
 * into HID events via the provided DuckyExecutor.
 */
class DuckyInterpreter(
    private val executor: DuckyExecutor
) {

    private var defaultDelayMs = 0L
    private var lastInstruction: DuckyInstruction? = null

    /**
     * Parse and execute a DuckyScript string.
     * Each line is parsed and executed in order.
     */
    suspend fun executeScript(scriptContent: String) {
        defaultDelayMs = 0
        lastInstruction = null

        val lines = scriptContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("//") || it.startsWith("// REM") }
            .iterator()

        while (lines.hasNext()) {
            val line = lines.next()

            // Handle REM (comment) lines
            if (line.startsWith("REM") || line.startsWith("//")) {
                continue
            }

            // Handle quoted strings on their own line (STRING behavior)
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("\"") && trimmedLine.endsWith("\"") && trimmedLine.length > 1) {
                val text = trimmedLine.substring(1, trimmedLine.length - 1)
                executeString(text)
                if (defaultDelayMs > 0) {
                    delay(defaultDelayMs)
                }
                continue
            }

            // Parse the line into tokens
            val tokens = parseLine(trimmedLine)
            if (tokens.isEmpty()) continue

            when (tokens[0].command) {
                DuckyCommand.REM -> continue
                DuckyCommand.DEFAULTDELAY -> executeDefaultDelay(tokens)
                DuckyCommand.DELAY -> executeDelay(tokens)
                DuckyCommand.STRING -> {
                    executeString(tokens[0].argument)
                    if (defaultDelayMs > 0) {
                        delay(defaultDelayMs)
                    }
                }
                DuckyCommand.KEYBOARD, DuckyCommand.MODIFIER -> {
                    val instruction = tokens[0]
                    executeKeyCommand(instruction)
                    if (defaultDelayMs > 0) {
                        delay(defaultDelayMs)
                    }
                    lastInstruction = instruction
                }
                DuckyCommand.REPEAT -> executeRepeat(tokens)
                else -> continue
            }
        }
    }

    private fun parseLine(line: String): List<DuckyInstruction> {
        val instructions = mutableListOf<DuckyInstruction>()

        // Normalize and split the line into tokens
        val tokens = line.split(Regex("\\s+"))
        if (tokens.isEmpty()) return instructions

        // First token is the command
        val firstToken = tokens[0].uppercase()
        val normalizedFirst = normalizeToken(firstToken)

        // Handle DELAY, DEFAULTDELAY, STRING, REPEAT, and other single-token commands
        when (normalizedFirst) {
            "DELAY", "REPEATDELAY" -> {
                val argument = tokens.getOrNull(1)?.trim() ?: ""
                val instruction = DuckyInstruction(
                    command = DuckyCommand.DELAY,
                    normalized = normalizedFirst,
                    argument = argument,
                    rawToken = tokens[0]
                )
                instructions.add(instruction)
                return instructions
            }
            "DEFAULTDELAY", "DEFAULTDELAYN", "DEFAULT_DELAY" -> {
                val argument = tokens.getOrNull(1)?.trim() ?: ""
                val instruction = DuckyInstruction(
                    command = DuckyCommand.DEFAULTDELAY,
                    normalized = normalizedFirst,
                    argument = argument,
                    rawToken = tokens[0]
                )
                instructions.add(instruction)
                return instructions
            }
            "STRING" -> {
                val argument = tokens.subList(1, tokens.size).joinToString(" ")
                val instruction = DuckyInstruction(
                    command = DuckyCommand.STRING,
                    normalized = "STRING",
                    argument = argument,
                    rawToken = tokens[0]
                )
                instructions.add(instruction)
                return instructions
            }
            "REPEAT" -> {
                val argument = tokens.getOrNull(1)?.trim() ?: ""
                val instruction = DuckyInstruction(
                    command = DuckyCommand.REPEAT,
                    normalized = "REPEAT",
                    argument = argument,
                    rawToken = tokens[0]
                )
                instructions.add(instruction)
                return instructions
            }
        }

        when {
            // Standalone key command (e.g., "ENTER", "SPACE", "ESCAPE")
            isModifier(normalizedFirst) -> {
                // Modifier + next token as key
                if (tokens.size >= 2) {
                    val keyToken = normalizeToken(tokens[1].uppercase())
                    val instruction = DuckyInstruction(
                        command = DuckyCommand.MODIFIER,
                        normalized = normalizedFirst,
                        argument = keyToken,
                        rawToken = tokens[0]
                    )
                    instructions.add(instruction)
                } else {
                    // Standalone modifier (e.g. "GUI" to open Start Menu)
                    val instruction = DuckyInstruction(
                        command = DuckyCommand.MODIFIER,
                        normalized = normalizedFirst,
                        argument = "",
                        rawToken = tokens[0]
                    )
                    instructions.add(instruction)
                }
            }
            tokens.size >= 2 -> {
                val secondToken = normalizeToken(tokens[1].uppercase())
                val argument = when {
                    normalizedFirst == "STRING" -> tokens.subList(1, tokens.size).joinToString(" ")
                    else -> tokens.getOrNull(1)?.uppercase() ?: ""
                }
                val instruction = DuckyInstruction(
                    command = if (isModifier(normalizedFirst)) DuckyCommand.MODIFIER else DuckyCommand.KEYBOARD,
                    normalized = normalizedFirst,
                    argument = argument,
                    rawToken = tokens[0]
                )
                instructions.add(instruction)
            }
            else -> {
                // Single token standalone key (ENTER, TAB, ESCAPE, etc.)
                val instruction = DuckyInstruction(
                    command = DuckyCommand.KEYBOARD,
                    normalized = normalizedFirst,
                    argument = "",
                    rawToken = firstToken
                )
                instructions.add(instruction)
            }
        }

        return instructions
    }

    private fun normalizeToken(token: String): String {
        return when (token.uppercase()) {
            "WINDOWS", "GO" -> "GUI"
            "CTRL", "CONTROL" -> "CONTROL"
            "UP" -> "ARROWUP"
            "DOWN" -> "ARROWDOWN"
            "LEFT" -> "ARROWLEFT"
            "RIGHT" -> "ARROWRIGHT"
            "PUP" -> "PAGEUP"
            "PDOWN" -> "PAGEDOWN"
            "BREAK" -> "PAUSE"
            "BROWSER_SEARCH", "BROWSER_HOME", "BROWSER_BACK", "BROWSER_FORWARD",
            "BROWSER_STOP", "BROWSER_REFRESH", "BROWSER_BOOKMARKS",
            "MEDIA" -> token.uppercase()
            "CAPS" -> "CAPSLOCK"
            "PRTSC" -> "PRTSC"
            "SNDB" -> "INSERT"
            else -> token.uppercase()
        }
    }

    private fun isModifier(token: String): Boolean {
        return when (token) {
            "GUI", "CONTROL", "SHIFT", "ALT", "CTRL", "SHIFTED",
            "RIGHTCTRL", "RIGHTSHIFT", "RIGHTALT", "RIGHTGUI",
            "COMMAND", "LEFTSHIFTED", "RIGHTSHIFTED" -> true
            else -> false
        }
    }

    private fun executeDefaultDelay(tokens: List<DuckyInstruction>) {
        val delayStr = tokens[0].argument.trim()
        try {
            defaultDelayMs = delayStr.toLong()
        } catch (e: NumberFormatException) {
            // Ignore invalid delay values
        }
    }

    private suspend fun executeDelay(tokens: List<DuckyInstruction>) {
        val delayStr = tokens[0].argument.trim()
        try {
            val delayMs = delayStr.toLong()
            delay(delayMs)
        } catch (e: NumberFormatException) {
            // Ignore invalid delay values
        }
    }

    private suspend fun executeString(text: String) {
        executor.logMessage("STRING: \"$text\"")
        for (char in text) {
            executor.injectChar(char)
            if (defaultDelayMs > 0) {
                delay(defaultDelayMs)
            }
        }
    }

    private fun executeKeyCommand(instruction: DuckyInstruction) {
        val command = instruction.command
        val normalized = instruction.normalized
        val argument = instruction.argument

        if (command == DuckyCommand.MODIFIER) {
            val modifier = normalized
            val key = when {
                argument.isEmpty() -> ""
                else -> argument
            }
            executor.injectModifierCombo(key.uppercase(), modifier)
        } else {
            executor.injectKeycode(normalized)
        }
    }

    private suspend fun executeRepeat(tokens: List<DuckyInstruction>) {
        val repeatStr = tokens[0].argument.trim()
        val count = try {
            repeat(repeatStr.toInt()) {
                lastInstruction?.let {
                    if (it.command == DuckyCommand.MODIFIER) {
                        executor.injectModifierCombo(it.argument.uppercase(), it.normalized)
                    } else {
                        executor.injectKeycode(it.normalized)
                    }
                    if (defaultDelayMs > 0) {
                        delay(defaultDelayMs)
                    }
                }
            }
        } catch (e: NumberFormatException) {
            // Ignore invalid repeat count
        }
    }
}
