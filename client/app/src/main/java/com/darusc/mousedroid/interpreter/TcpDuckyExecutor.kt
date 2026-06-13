package com.darusc.mousedroid.interpreter

import com.darusc.mousedroid.layouts.Keycode
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.ConnectionManager
import com.darusc.mousedroid.layouts.KeyboardLayout

/**
 * DuckyExecutor implementation that sends HID events through the existing
 * ConnectionManager using InputEvent.KeyPress.
 *
 * This works with both TCP and Bluetooth connections because it uses
 * ConnectionManager.send(InputEvent) rather than sendRawBytes().
 *
 * Each keypress sends a single InputEvent.KeyPress containing the keycode
 * and modifier byte.
 */
class TcpDuckyExecutor(
    private val onLog: ((String) -> Unit)? = null
) : DuckyExecutor {

    private val connectionManager = ConnectionManager.getInstance()

    override fun logMessage(message: String) {
        onLog?.invoke(message)
    }

    private val keycodes = mapOf(
        // Standard keys
        "A" to Keycode.KEY_A, "B" to Keycode.KEY_B, "C" to Keycode.KEY_C,
        "D" to Keycode.KEY_D, "E" to Keycode.KEY_E, "F" to Keycode.KEY_F,
        "G" to Keycode.KEY_G, "H" to Keycode.KEY_H, "I" to Keycode.KEY_I,
        "J" to Keycode.KEY_J, "K" to Keycode.KEY_K, "L" to Keycode.KEY_L,
        "M" to Keycode.KEY_M, "N" to Keycode.KEY_N, "O" to Keycode.KEY_O,
        "P" to Keycode.KEY_P, "Q" to Keycode.KEY_Q, "R" to Keycode.KEY_R,
        "S" to Keycode.KEY_S, "T" to Keycode.KEY_T, "U" to Keycode.KEY_U,
        "V" to Keycode.KEY_V, "W" to Keycode.KEY_W, "X" to Keycode.KEY_X,
        "Y" to Keycode.KEY_Y, "Z" to Keycode.KEY_Z,
        "1" to Keycode.KEY_1, "2" to Keycode.KEY_2, "3" to Keycode.KEY_3,
        "4" to Keycode.KEY_4, "5" to Keycode.KEY_5, "6" to Keycode.KEY_6,
        "7" to Keycode.KEY_7, "8" to Keycode.KEY_8, "9" to Keycode.KEY_9,
        "0" to Keycode.KEY_0,

        // Navigation & Control
        "ENTER" to Keycode.KEY_ENTER,
        "ESCAPE" to Keycode.KEY_ESC,
        "BACKSPACE" to Keycode.KEY_BACKSPACE,
        "DELETE" to Keycode.KEY_DELETE,
        "TAB" to Keycode.KEY_TAB,
        "SPACE" to Keycode.KEY_SPACE,

        "ARROWUP" to Keycode.KEY_UP,
        "ARROWDOWN" to Keycode.KEY_DOWN,
        "ARROWLEFT" to Keycode.KEY_LEFT,
        "ARROWRIGHT" to Keycode.KEY_RIGHT,
        "UP" to Keycode.KEY_UP,
        "DOWN" to Keycode.KEY_DOWN,
        "LEFT" to Keycode.KEY_LEFT,
        "RIGHT" to Keycode.KEY_RIGHT,

        "PAGEUP" to Keycode.KEY_PAGE_UP,
        "PAGEDOWN" to Keycode.KEY_PAGE_DOWN,
        "HOME" to Keycode.KEY_HOME,
        "END" to Keycode.KEY_END,
        "INSERT" to Keycode.KEY_INSERT,

        "F1" to Keycode.KEY_F1,
        "F2" to Keycode.KEY_F2,
        "F3" to Keycode.KEY_F3,
        "F4" to Keycode.KEY_F4,
        "F5" to Keycode.KEY_F5,
        "F6" to Keycode.KEY_F6,
        "F7" to Keycode.KEY_F7,
        "F8" to Keycode.KEY_F8,
        "F9" to Keycode.KEY_F9,
        "F10" to Keycode.KEY_F10,
        "F11" to Keycode.KEY_F11,
        "F12" to Keycode.KEY_F12,

        "PRTSC" to Keycode.KEY_PRINT_SCREEN,
        "PRINTSCREEN" to Keycode.KEY_PRINT_SCREEN,
        "SCROLL" to Keycode.KEY_SCROLL_LOCK,
        "CAPSLOCK" to Keycode.KEY_CAPS_LOCK,
        "PAUSE" to Keycode.KEY_PAUSE,

        // Symbols
        "MINUS" to Keycode.KEY_MINUS,
        "EQUALS" to Keycode.KEY_EQUAL,
        "LEFTBRACKET" to Keycode.KEY_LEFT_BRACE,
        "RIGHTBRACKET" to Keycode.KEY_RIGHT_BRACE,
        "BACKSLASH" to Keycode.KEY_BACKSLASH,
        "SEMICOLON" to Keycode.KEY_SEMICOLON,
        "QUOTE" to Keycode.KEY_QUOTE,
        "GRAVE" to Keycode.KEY_GRAVE,
        "COMMA" to Keycode.KEY_COMMA,
        "PERIOD" to Keycode.KEY_DOT,
        "SLASH" to Keycode.KEY_SLASH,
        "NONUSHASH" to Keycode.KEY_NON_US_NUM,
        "NONUSBACKSLASH" to Keycode.KEY_NON_US,

        // Numpad
        "NUM0" to Keycode.KEYPAD_0,
        "NUM1" to Keycode.KEYPAD_1,
        "NUM2" to Keycode.KEYPAD_2,
        "NUM3" to Keycode.KEYPAD_3,
        "NUM4" to Keycode.KEYPAD_4,
        "NUM5" to Keycode.KEYPAD_5,
        "NUM6" to Keycode.KEYPAD_6,
        "NUM7" to Keycode.KEYPAD_7,
        "NUM8" to Keycode.KEYPAD_8,
        "NUM9" to Keycode.KEYPAD_9,
        "NUMDOT" to Keycode.KEYPAD_DOT,
        "NUMMUL" to Keycode.KEYPAD_MULTIPLY,
        "NUMADD" to Keycode.KEYPAD_ADD,
        "NUMSUB" to Keycode.KEYPAD_SUBTRACT,
        "NUMDIV" to Keycode.KEYPAD_DIVIDE,
        "NUMENTER" to Keycode.KEYPAD_ENTER,

        // Multimedia
        "MEDIASELECT" to 0x82.toByte(),
        "MEDIACOMMAND" to 0x83.toByte(),
        "LAUNCHMAIL" to 0x84.toByte(),
        "LAUNCHAPP2" to 0x85.toByte(),
        "BROWSERSEARCH" to 0x86.toByte(),
        "BROWSERHOME" to Keycode.KEY_BROWSE,
        "BROWSERBACK" to Keycode.KEY_BROWSE_BACK,
        "BROWSERFORWARD" to Keycode.KEY_BROWSE_FORWARD,
        "BROWSERSTOP" to Keycode.KEY_BROWSE_REFRESH,
        "BROWSERREFRESH" to Keycode.KEY_BROWSE_REFRESH,
        "BROWSERBOOKMARKS" to Keycode.KEY_BROWSE_FAVORITES,
        "WAKE" to 0x86.toByte(),

        // Application/Power
        "APPLICATION" to Keycode.KEY_APPLICATION,
        "POWER" to Keycode.KEY_POWER,
        "MUTE" to Keycode.KEY_MUTE,
        "VOLUMEUP" to Keycode.KEY_VOLUME_UP,
        "VOLUME_DOWN" to Keycode.KEY_VOLUME_DOWN,
        "VOLUP" to Keycode.KEY_VOLUME_UP,
        "VOLMUTE" to Keycode.KEY_MUTE
    )

    private fun getModifierByte(modifierName: String): Byte {
        return when (modifierName.uppercase()) {
            "CTRL", "CONTROL" -> Keycode.MOD_LEFT_CTRL
            "SHIFT", "SHIFTED", "LEFTSHIFTED" -> Keycode.MOD_LEFT_SHIFT
            "RIGHTSHIFT" -> Keycode.MOD_RIGHT_SHIFT
            "RIGHTSHIFTED" -> Keycode.MOD_RIGHT_SHIFT
            "ALT" -> Keycode.MOD_LEFT_ALT
            "RIGHTALT" -> Keycode.MOD_RIGHT_ALT
            "ALTGR", "ALTGRA" -> Keycode.MOD_RIGHT_ALT
            "GUI", "WIN", "COMMAND", "LEFTGUI" -> Keycode.MOD_LEFT_GUI
            "RIGHTGUI", "RIGHTWIN" -> Keycode.MOD_RIGHT_GUI
            "RIGHTCTRL" -> Keycode.MOD_LEFT_CTRL
            else -> 0x00.toByte()
        }
    }

    private fun sendKeypress(code: Byte, modifier: Byte) {
        try {
            val keyList = listOf(
                KeyboardLayout.Key(modifier, code)
            )
            connectionManager.send(InputEvent.KeyPress(keyList), withCoroutine = false)
            onLog?.invoke("Sent key: code=0x${code.toString(16)} mod=0x${modifier.toString(16)}")
        } catch (e: Exception) {
            val msg = "Failed to send keypress: ${e.message}"
            onLog?.invoke(msg)
        }
    }

    override fun injectChar(char: Char) {
        val upperChar = char.uppercaseChar()
        var modifierByte = Keycode.MOD_NONE
        var keyByte: Byte

        if (char.isWhitespace() && char.code == ' '.code) {
            keyByte = Keycode.KEY_SPACE
        } else if (char.isLetterOrDigit()) {
            keyByte = findKeyByteForChar(char)
        } else {
            val mappedChar = findCharSymbol(char)
            if (mappedChar != null) {
                keyByte = mappedChar.second
                modifierByte = mappedChar.first
            } else {
                val msg = "Char '$char' has no keycode mapping"
                onLog?.invoke(msg)
                return
            }
        }

        if (char.isUpperCase() && char.isLetter()) {
            modifierByte = Keycode.MOD_LEFT_SHIFT
        }

        if (keyByte != 0x00.toByte()) {
            sendKeypress(keyByte, modifierByte)
        }
    }

    private fun findCharSymbol(char: Char): Pair<Byte, Byte>? {
        val upper = char.uppercaseChar()
        var modifierByte = Keycode.MOD_NONE

        // Apply shift for characters that require it
        when (char) {
            '"' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '#' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '$' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '%' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '&' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '*' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '+' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            ':' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '<' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '>' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '?' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '@' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '_' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '-' -> {
                modifierByte = Keycode.MOD_NONE
                // fall through
            }
            '=' -> {
                modifierByte = Keycode.MOD_NONE
                // fall through
            }
            '[' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            ']' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '\\' -> {
                modifierByte = Keycode.MOD_LEFT_SHIFT
                // fall through
            }
            '|' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '{' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '}' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '~' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '!' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            ',' -> modifierByte = Keycode.MOD_NONE
            '.' -> modifierByte = Keycode.MOD_NONE
            '/' -> modifierByte = Keycode.MOD_NONE
            ';' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '\'' -> modifierByte = Keycode.MOD_LEFT_SHIFT
            '`' -> modifierByte = Keycode.MOD_NONE
        }

        return when (upper) {
            '"' -> Pair(modifierByte, Keycode.KEY_GRAVE)
            '#' -> Pair(modifierByte, Keycode.KEY_3)
            '$' -> Pair(modifierByte, Keycode.KEY_4)
            '%' -> Pair(modifierByte, Keycode.KEY_5)
            '&' -> Pair(modifierByte, Keycode.KEY_7)
            '*' -> Pair(modifierByte, Keycode.KEY_8)
            '+' -> Pair(modifierByte, Keycode.KEY_EQUAL)
            ':' -> Pair(modifierByte, Keycode.KEY_SEMICOLON)
            '<' -> Pair(modifierByte, Keycode.KEY_COMMA)
            '>' -> Pair(modifierByte, Keycode.KEY_DOT)
            '?' -> Pair(modifierByte, Keycode.KEY_SLASH)
            '@' -> Pair(modifierByte, Keycode.KEY_2)
            '_' -> Pair(modifierByte, Keycode.KEY_MINUS)
            '-' -> Pair(Keycode.MOD_NONE, Keycode.KEY_MINUS)
            '=' -> Pair(Keycode.MOD_NONE, Keycode.KEY_EQUAL)
            '[' -> Pair(Keycode.MOD_NONE, Keycode.KEY_LEFT_BRACE)
            ']' -> Pair(Keycode.MOD_NONE, Keycode.KEY_RIGHT_BRACE)
            '\\' -> Pair(Keycode.MOD_NONE, Keycode.KEY_BACKSLASH)
            '|' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_RIGHT_BRACE)
            '{' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_LEFT_BRACE)
            '}' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_RIGHT_BRACE)
            '~' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_GRAVE)
            '!' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_1)
            ',' -> Pair(Keycode.MOD_NONE, Keycode.KEY_COMMA)
            '.' -> Pair(Keycode.MOD_NONE, Keycode.KEY_DOT)
            '/' -> Pair(Keycode.MOD_NONE, Keycode.KEY_SLASH)
            ';' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_SEMICOLON)
            '\'' -> Pair(Keycode.MOD_LEFT_SHIFT, Keycode.KEY_QUOTE)
            '`' -> Pair(Keycode.MOD_NONE, Keycode.KEY_GRAVE)
            else -> null
        }
    }

    private fun findKeyByteForChar(char: Char): Byte {
        val upperChar = char.uppercaseChar()
        return when (upperChar) {
            in 'A'..'Z' -> ((upperChar - 'A' + 0x04).toInt()).toByte()
            in 'a'..'z' -> ((char - 'a' + 0x04).toInt()).toByte()
            in '0'..'9' -> ((char - '0' + 0x27).toInt()).toByte()
            else -> 0x00.toByte()
        }
    }

    override fun injectModifierCombo(keyName: String, modifier: String) {
        val modifierByte = getModifierByte(modifier)
        if (keyName.isEmpty()) {
            sendKeypress(0x00, modifierByte)
            return
        }
        val foundKey = keycodes[keyName.uppercase()] ?: keycodes[keyName] ?: 0x00.toByte()
        if (foundKey != 0x00.toByte()) {
            sendKeypress(foundKey, modifierByte)
        } else {
            val msg = "Key '$keyName' not found in keycode map"
            onLog?.invoke(msg)
        }
    }

    override fun injectKeycode(keyName: String) {
        val normalizedKey = DuckyExecutor.normalizeKeycode(keyName)
        val keyByte = keycodes[normalizedKey] ?: keycodes[keyName] ?: 0x00.toByte()
        if (keyByte != 0x00.toByte()) {
            sendKeypress(keyByte, Keycode.MOD_NONE)
        } else {
            val msg = "Keycode '$keyName' (normalized: '$normalizedKey') not found"
            onLog?.invoke(msg)
        }
    }
}
