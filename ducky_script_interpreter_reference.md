```
   ┌──────────────────────────────────────────────────┐
   │               Raw Ducky Script File              │
   └────────────────────────┬─────────────────────────┘
                            │ Streamed / Line-by-Line
                            ▼
   ┌──────────────────────────────────────────────────┐
   │                DuckyLexer / Parser               │
   │  - Sanitizes and normalizes strings              │
   │  - Splits tokens into Command and Payload        │
   └────────────────────────┬─────────────────────────┘
                            │ Creates Data Clones
                            ▼
   ┌──────────────────────────────────────────────────┐
   │             DuckyInterpreter Engine              │
   │  - Manages internal state (e.g., DEFAULT_DELAY)   │
   │  - Handles structural flows & execution timers   │
   └────────────────────────┬─────────────────────────┘
                            │ Decoupled Event Dispatches
                            ▼
   ┌──────────────────────────────────────────────────┐
   │            <<interface>> DuckyExecutor           │
   └────┬───────────────────┼────────────────────┬────┘
        │                   │                    │
        ▼                   ▼                    ▼
   ┌───────────────┐ ┌───────────────┐ ┌──────────────────┐
   │Accessibility│ │  TCP/HID  │ │  BluetoothHID  │
   │   Executor  │ │  Executor │ │   Executor   │
   └───────────────┘ └───────────────┘ └──────────────────┘
```

---

## 2. Formal Grammar & Lexical Specification

### Supported Core Syntax Map

| Command Token | Argument Constraints | Operation / Behavior |
| :--- | :--- | :--- |
| `REM` | Arbitrary String | Comment block. The parser completely discards the line. |
| `DEFAULTDELAY` / `DEFAULT_DELAY` | Positive Integer (ms) | Sets the implicit delay injected after every subsequent key stroke. |
| `DELAY` | Positive Integer (ms) | Blocks execution thread explicitly for the specified duration. |
| `STRING` | Arbitrary String | Types each character as a separate keystroke. |
| `GUI` / `WINDOWS` | Key (e.g. `r`) | Fires the Super/Windows modifier + the given key. Use `WINDOWS` alone to press just the Win key. |
| `CTRL` / `CONTROL` | Key (e.g. `c`) | Fires Control modifier + key. |
| `ALT` | Key (e.g. `TAB`) | Fires Alt modifier + key. |
| `SHIFT` | Key (e.g. `a`) | Fires Shift modifier + key. |
| `ENTER` / `SPACE` / `TAB` | None | Dispatches the corresponding hardware keycode. |
| `ESCAPE` | None | Dispatches Escape keycode. |
| `ARROWUP` / `ARROWDOWN` / `ARROWLEFT` / `ARROWRIGHT` | None | Dispatches the corresponding arrow keycode. |
| `UP` / `DOWN` / `LEFT` / `RIGHT` | None | Alias for the corresponding `ARROW*` command. |
| `INSERT` / `DELETE` / `HOME` / `END` | None | Dispatches the corresponding keycode. |
| `PAGEUP` / `PAGEDOWN` | None | Dispatches the corresponding keycode. |
| `F1` – `F12` | None | Dispatches function key keycodes. |
| `REPEAT` | Positive Integer | Repeats the immediately previous key-press action the given number of times. |

> **Note:** `REPEAT` applies only to key-press commands (`GUI`, `CTRL`, `ALT`, `SHIFT` with a key argument, or standalone keycodes like `ENTER`). It does not apply to `STRING` or `DELAY`.


---

## 3. Reference Implementation (Kotlin)

This implementation leverages Kotlin Coroutines to provide an asynchronous, non-blocking evaluation framework that preserves UI-responsiveness on mobile operating systems.

### 3.1 Domain Model Definition

```kotlin
package com.hardware.ducky.interpreter

/**
 * Valid tokens recognized by the Ducky Lexer.
 * Alternate forms (e.g. WINDOWS/GO, CTRL/CONTROL) are normalized at parse time.
 */
enum class DuckyCommand {
    REM,
    STRING,
    DELAY,
    DEFAULTDELAY,
    KEYBOARD,           // standalone key: ENTER, SPACE, TAB, ESCAPE, etc.
    MODIFIER,           // modifier+key: WINDOWS r, CTRL c, SHIFT a, etc.
    REPEAT,
   UNKNOWN
}

/**
 * Representation of a singular, compiled execution instruction.
 */
data class DuckyInstruction(
    val command: DuckyCommand,
    val normalized: String = "",    // canonical token name (e.g. "GUI", "CONTROL")
    val argument: String = "",     // payload (e.g. "r" for "WINDOWS r", or "1000" for DELAY 1000)
    val rawToken: String = ""      // original unmodified token from source
)

```

### 3.2 The Parsing Engine

```kotlin
package com.hardware.ducky.interpreter

import java.io.BufferedReader
import java.io.StringReader
import java.util.Locale
import kotlinx.coroutines.delay

class DuckyInterpreter(private val executor: DuckyExecutor) {

    private var defaultDelayMs: Long = 0L
    private var lastInstruction: DuckyInstruction? = null

    /**
     * Entry-point for evaluating a raw text stream script sequentially.
     * Marked as suspend to force execution inside a non-blocking Coroutine Scope.
     */
    suspend fun executeScript(scriptContent: String) {
        val reader = BufferedReader(StringReader(scriptContent))
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val sanitizedLine = line?.trim() ?: continue
            if (sanitizedLine.isEmpty()) continue

            val instruction = parseLine(sanitizedLine)
            executeInstruction(instruction)
        }
    }

    /**
     * Normalizes alternate token names (WINDOWS→GUI, CTRL→CONTROL, etc.)
     * then categorises the instruction.
     */
    private fun parseLine(line: String): DuckyInstruction {
        val tokens = line.split(Regex("\\s+"), 2)
        val rawToken = tokens[0].uppercase(Locale.ROOT)

        // Normalize aliases to canonical forms
        val normalized = when (rawToken) {
            "WINDOWS" -> "GUI"
            "CTRL" -> "CONTROL"
            "GO" -> "GUI"
            "UP" -> "ARROWUP"
            "DOWN" -> "ARROWDOWN"
            "LEFT" -> "ARROWLEFT"
            "RIGHT" -> "ARROWRIGHT"
            "PUP" -> "PAGEUP"
            "PDOWN" -> "PAGEDOWN"
            "ESCAPE" -> "ESCAPE"
            else -> rawToken
        }

        val payload = if (tokens.size > 1) tokens[1].trim() else ""

        // Categorise: modifiers take a key argument, standalone keys don't
        val category = when (normalized) {
            "REM" -> DuckyCommand.REM
            "DEFAULTDELAY", "DEFAULT_DELAY" -> DuckyCommand.DEFAULTDELAY
            "DELAY" -> DuckyCommand.DELAY
            "STRING" -> DuckyCommand.STRING
            "REPEAT" -> DuckyCommand.REPEAT
            in listOf("GUI", "WINDOWS", "CONTROL", "CTRL", "ALT", "SHIFT") -> DuckyCommand.MODIFIER
            in listOf("ENTER", "SPACE", "TAB", "ESCAPE", "ARROWUP", "ARROWDOWN",
                      "ARROWLEFT", "ARROWRIGHT", "INSERT", "DELETE", "HOME", "END",
                      "PAGEUP", "PAGEDOWN") -> DuckyCommand.KEYBOARD
            in listOf("F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12") -> DuckyCommand.KEYBOARD
            else -> DuckyCommand.UNKNOWN
        }

        return DuckyInstruction(category, normalized, payload)
    }

    /**
     * Core Execution Loop. Interacts directly with the bound executor implementation.
     */
    private suspend fun executeInstruction(instruction: DuckyInstruction) {
        when (instruction.command) {
            DuckyCommand.REM -> {
                // No-op for structural comments
            }

            DuckyCommand.DEFAULTDELAY -> {
                defaultDelayMs = instruction.argument.toLongOrNull() ?: 0L
            }

            DuckyCommand.DELAY -> {
                val explicitDelay = instruction.argument.toLongOrNull() ?: 0L
                delay(explicitDelay)
            }

            DuckyCommand.STRING -> {
                executor.injectText(instruction.argument)
                applyDefaultDelay()
            }

            DuckyCommand.MODIFIER -> {
                val modifierKey = instruction.argument
                executor.injectModifierCombo(instruction.normalized, modifierKey)
                applyDefaultDelay()
                lastInstruction = instruction
            }

            DuckyCommand.KEYBOARD -> {
                val keyName = when {
                    instruction.argument.isNotEmpty() -> instruction.argument.uppercase(Locale.ROOT)
                    else -> instruction.normalized
                }
                executor.injectKeycode(keyName)
                applyDefaultDelay()
                lastInstruction = instruction
            }

            DuckyCommand.REPEAT -> {
                val count = instruction.argument.toIntOrNull() ?: 1
                lastInstruction?.let { prev ->
                    repeat(count) {
                        when (prev.command) {
                            DuckyCommand.MODIFIER -> {
                                executor.injectModifierCombo(prev.normalized, prev.argument)
                                applyDefaultDelay()
                            }
                            DuckyCommand.KEYBOARD -> {
                                executor.injectKeycode(prev.normalized)
                                applyDefaultDelay()
                            }
                            else -> {}
                        }
                    }
                }
            }

            DuckyCommand.UNKNOWN -> {
                // Graceful failure or error logging hook for complex custom scripts
                android.util.Log.e("DuckyInterpreter", "Syntax Error: Unknown command: ${instruction.rawToken}")
            }
        }
    }

    private suspend fun applyDefaultDelay() {
        if (defaultDelayMs > 0L) {
            delay(defaultDelayMs)
        }
    }
}
```

### 3.3 Hardware & Context Decoupling Layer

```kotlin
package com.hardware.ducky.interpreter

/**
 * Abstraction layer for executing parsed DuckyScript commands over
 * whatever transport the app is using (TCP socket → server, Bluetooth HID, etc.).
 *
 * The server protocol expects uint8_t keycodes and modifiers matching
 * the opcodes in `inputmanager.hpp` (KEYPRESS = 0x07).
 * Map command names (ARROWUP, ENTER, F1…) to these values here.
 */
interface DuckyExecutor {
    /** Type each character as a separate keystroke (for STRING commands) */
    fun injectText(text: String)

    /** Inject a modifier+key combo (e.g. CONTROL + c).  The [keyName] is the key part only (e.g. "c", "r"). */
    fun injectModifierCombo(keyName: String, modifier: String)

    /** Inject a standalone keycode (ENTER, ESCAPE, ARROWUP, F1, etc.) */
    fun injectKeycode(keyName: String)

    /**
     * Map common DuckyScript token names to the server's expected keycode strings.
     * Example implementations live in the TCP/HID/Bluetooth executor classes.
     */
    companion object {
        fun normalizeKeycode(token: String): String = when (token) {
            "ARROWUP", "UP" -> "ARROWUP"
            "ARROWDOWN", "DOWN" -> "ARROWDOWN"
            "ARROWLEFT", "LEFT" -> "ARROWLEFT"
            "ARROWRIGHT", "RIGHT" -> "ARROWRIGHT"
            else -> token.uppercase()
        }
    }
}
```

---

## 4. Execution Patterns inside Android Frameworks

### Implementation via Kotlin Coroutines

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.hardware.ducky.interpreter.DuckyInterpreter

class DuckyScriptRunnerViewModel(private val hardwareExecutor: DuckyExecutor) : ViewModel() {

    fun compileAndRun(scriptPayload: String) {
        // Dispatched away from the Main UI thread to avoid Application Not Responding (ANR) flags
        viewModelScope.launch(Dispatchers.Default) {
            val interpreter = DuckyInterpreter(hardwareExecutor)
            interpreter.executeScript(scriptPayload)
        }
    }
}

```

---

## 5. Security & Framework Sandboxing Design Notes

When writing handlers for the `DuckyExecutor` interface on Android devices, specific constraints must be addressed depending on the desired target platform:

1. **Accessibility Services (On-Device UI Automation):**
* Requires manual system permission validation from users.
* Leverages `AccessibilityService.dispatchGesture()` for motion paths or `ACTION_SET_TEXT` payload bindings directly into active context fields.


2. **Linux Gadget Device Paths (`/dev/hidgX` Kernel Hooks):**
* Requires a **rooted** device context.
* System requires parsing text directly into raw USB HID scan-code buffers (8-byte matrix array sequences) transmitted through character device file streams.


3. **Bluetooth Peripheral API Frameworks (`BluetoothHidDevice`):**
 * Restricted to API level 28 (Android 9 Pie) and superior implementations.
 * Requires initialization of standard SDP (Service Discovery Protocol) configuration templates to cleanly register the host application wrapper as an implicit hardware input peripheral.
