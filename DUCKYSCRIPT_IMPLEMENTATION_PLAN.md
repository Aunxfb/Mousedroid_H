# DuckyScript Implementation Plan — Task Checklist

> **Status:** In Progress | **Last Updated:** 2026-06-13  
> **Reference:** `ducky_script_interpreter_reference.md`  
> **Goal:** Add a DuckyScript editor to the Android client that parses and executes DuckyScript as keystrokes/mouse inputs via the existing TCP server connection.

---

## Overview

Add "DuckyScript" as a new input method in the side panel. Users can write or load `.txt` DuckyScript files, then execute them. Each line is parsed into HID events sent through the existing TCP connection to the desktop server (opcode 0x07 = KEYPRESS).

---

## Existing Architecture Reference

### Project Structure (`client/app/src/main/java/com/darusc/mousedroid/`)
```
MainActivity.kt                    # Single Activity entry point
fragments/
  Main.kt                          # Home/connection screen
  DeviceList.kt                    # Device selection screen
  Input.kt                         # Main input screen (hosts side drawer + child fragments)
  Touchpad.kt                      # Touchpad child fragment
  Numpad.kt                        # Numpad child fragment
  Extensions.kt                    # Shared extensions
viewmodels/
  BaseViewModel.kt                 # Abstract base for all ViewModels
  ConnectionViewModel.kt           # Central connection state (shared across Activity)
  DeviceListViewModel.kt           # Device list state
  TouchpadViewModel.kt             # Touchpad state
  NumpadViewModel.kt               # Numpad state
  KeyboardViewModel.kt             # Keyboard state
networking/
  ConnectionManager.kt             # Singleton, manages all connections
  Connection.kt                    # Base class, mode enum, Listener interface
  Translators.kt                   # InputEvent → byte array converters
  (subdirs: bluetooth/, sockets/)
```

### Navigation Architecture
- **Single Activity** (`MainActivity`) hosts a `NavHostFragment`
- Top-level nav graph (`res/navigation/navigation.xml`): `Main` → `DeviceList` → `Input`
- Inside `Input` fragment: uses `childFragmentManager` to swap child fragments (Touchpad/Numpad/Keyboard)
- Side panel = Material `NavigationView` defined in `res/menu/input_modes_menu.xml`
- Each menu item in the "Input Methods" section triggers `replaceChildFragment()` in `Input.kt`

### ViewModel Pattern
- All ViewModels extend `BaseViewModel<S: State, E: Event>` (in `viewmodels/BaseViewModel.kt`)
- `BaseViewModel` provides `MutableStateFlow<S>` and `Channel<E>` for state/events
- `setState()` and `sendEvent()` are the standard way to update state and emit events
- ViewModels are shared via `activityViewModels()` delegate

### Connection Flow (simplified)
1. `ConnectionViewModel` holds the active `ConnectionManager`
2. On successful Bluetooth connection, `onConnectionSuccessful()` is called
3. This triggers navigation to the `Input` fragment
4. The TCP socket is opened to send commands to the server
5. Server expects keycodes matching `inputmanager.hpp` opcodes (KEYPRESS = 0x07)

---

## Implementation Plan

### Phase 1: Interpreter Engine (Model Layer)

#### Task 1.1: Create `interpreter/DuckyTypes.kt`

**Purpose:** Define the domain model for DuckyScript commands.

**Contents:**
```kotlin
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
    KEYBOARD,      // standalone key: ENTER, SPACE, TAB, ESCAPE, etc.
    MODIFIER,      // modifier+key: WINDOWS r, CTRL c, SHIFT a, etc.
    REPEAT,
    UNKNOWN
}

/**
 * Representation of a single, compiled execution instruction.
 */
data class DuckyInstruction(
    val command: DuckyCommand,
    val normalized: String = "",    // canonical token name (e.g. "GUI", "CONTROL")
    val argument: String = "",      // payload (e.g. "r" for "WINDOWS r", or "1000" for DELAY 1000)
    val rawToken: String = ""       // original unmodified token from source
)
```

**Location:** `client/app/src/main/java/com/darusc/mousedroid/interpreter/DuckyTypes.kt`

---

#### Task 1.2: Create `interpreter/DuckyInterpreter.kt`

**Purpose:** Lexer/parser that reads raw DuckyScript text and executes instructions.

**Key details:**
- Suspend function `executeScript(scriptContent: String)` — non-blocking execution
- `parseLine(line: String)` — splits line into tokens, normalizes aliases
- Modifier detection uses `isModifier(normalizedFirst)` (centralised function), not a hardcoded token list — new modifier aliases are automatically recognized
- State tracking: `defaultDelayMs` (sets implicit delay after each keystroke), `lastInstruction` (for REPEAT)
- Normalizations: WINDOWS→GUI, CTRL→CONTROL, GO→GUI, UP→ARROWUP, DOWN→ARROWDOWN, LEFT→ARROWLEFT, RIGHT→ARROWRIGHT, PUP→PAGEUP, PDOWN→PAGEDOWN, BREAK→PAUSE, CAPS→CAPSLOCK, PRTSC→PRTSC, SNDB→INSERT
- Commands:
  - `REM` → skip
  - `DEFAULTDELAY N` / `DEFAULT_DELAY N` / `DEFAULTDELAYN N` → sets implicit delay for subsequent commands
  - `DELAY N` → explicit suspend delay (also accepts `REPEATDELAY N` as alias)
  - `STRING text` → each character as separate keystroke + apply `defaultDelayMs` between chars
  - `GUI r`, `CTRL c`, `ALT TAB`, `SHIFT a` → modifier + key combo
  - `GUI`, `ALT`, `CTRL`, etc. alone → standalone modifier press (e.g. `GUI` alone opens Start Menu)
  - `ENTER`, `SPACE`, `TAB`, `ESCAPE`, `ARROWUP/DOWN/LEFT/RIGHT`, `F1-F12` → standalone keycodes
  - `REPEAT N` → repeats previous MODIFIER or KEYBOARD instruction N times (applies `defaultDelayMs` after each repeat)
- Uses `kotlinx.coroutines.delay()` for all timing (never `Thread.sleep()` — that would freeze the main thread)

**Location:** `client/app/src/main/java/com/darusc/mousedroid/interpreter/DuckyInterpreter.kt`

---

#### Task 1.3: Create `interpreter/DuckyExecutor.kt`

**Purpose:** Abstract interface decoupling the interpreter from the hardware/transport layer.

**Contents:**
```kotlin
package com.darusc.mousedroid.interpreter

interface DuckyExecutor {
    /** Type each character as a separate keystroke (for STRING commands) */
    fun injectText(text: String)

    /** Inject a modifier+key combo (e.g. CONTROL + c). The [keyName] is the key part only. */
    fun injectModifierCombo(keyName: String, modifier: String)

    /** Inject a standalone keycode (ENTER, ESCAPE, ARROWUP, F1, etc.) */
    fun injectKeycode(keyName: String)

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

**Location:** `client/app/src/main/java/com/darusc/mousedroid/interpreter/DuckyExecutor.kt`

---

### Phase 2: TCP Executor (Integration with Server Protocol)

#### Task 2.1: Create `interpreter/TcpDuckyExecutor.kt` (part 1)

**Purpose:** Implement `DuckyExecutor` using the existing TCP connection to the desktop server.

**Key details:**
- Uses `ConnectionManager` to get the active TCP socket
- `injectText(text)`: iterates each character, sends as individual KEYPRESS commands
- `injectModifierCombo(keyName, modifier)`: sends modifier press → key press → modifier release
- `injectKeycode(keyName)`: sends single KEYPRESS command with mapped keycode
- Handles connection state — if not connected, buffer commands or log error

**Location:** `client/app/src/main/java/com/darusc/mousedroid/interpreter/TcpDuckyExecutor.kt`

---

#### Task 2.2: Add keycode mapping to `TcpDuckyExecutor.kt` (part 2)

**Purpose:** Map DuckyScript token names to server keycodes matching `inputmanager.hpp`.

**Keycode mapping table:**
```
ENTER        → 68
ESCAPE       → 46
BACKSPACE     → 55
DELETE        → 110
TAB           → 89
SPACE         → 44
F1–F12        → 59–70
ARROWUP       → 80
ARROWDOWN     → 81
ARROWLEFT     → 82
ARROWRIGHT    → 83
```

*(Verify exact values against `server/src/input/inputmanager.hpp` or `server/src/adb.hpp`)

**Location:** Same file as Task 2.1 (`TcpDuckyExecutor.kt`)

---

### Phase 3: UI — DuckyScript Fragment

#### Task 3.1: Add DuckyScript menu item to `input_modes_menu.xml`

**File:** `client/app/src/main/res/menu/input_modes_menu.xml`

**Change:** Add new `<item>` under the "Input Methods" section:
```xml
<item
    android:id="@+id/mode_duckyscript"
    android:icon="@drawable/ic_duckyscript"
    android:title="DuckyScript" />
```

---

#### Task 3.2: Create layout XML for `DuckyScriptFragment`

**File:** `client/app/src/main/res/layout/fragment_ducky_script.xml`

**Contents:**
- Root: `<layout>` with data binding
- Main layout: `LinearLayout` or `ConstraintLayout` (vertical)
- Components:
  - `TextView` title "DuckyScript Editor"
  - `EditText` (multiline, `android:inputType="textMultiLine"`, `android:gravity="top"`) for script text
  - `Button` "Load Script" — triggers file picker (import .txt)
  - `Button` "Execute" — starts execution
  - `Button` "Export Script" — saves editor content to a file via `ActivityResultContracts.CreateDocument`
  - `ProgressBar` / status `TextView` for execution state
  - Debug Console section:
    - Header row with "Debug Console" title + `Button` "Clear" + `Button` "Export Logs"
    - `ScrollView` containing a `TextView` for real-time log output

---

#### Task 3.3: Create `DuckyScriptRunnerViewModel.kt`

**File:** `client/app/src/main/java/com/darusc/mousedroid/viewmodels/DuckyScriptRunnerViewModel.kt`

**Contents:**
- Extends `BaseViewModel<State, Event>`
- `State` sealed class: `Idle`, `Executing`, `Complete`, `Error(String)`
- `logText: StateFlow<String>` — full log text (updated via `MutableStateFlow` on each `addLog()` call)
- `clearLogs()` — clears the log buffer and resets `_logText` to `""`
- `compileAndRun(script: String)` method:
  - Sets state to `Executing`, clears logs, adds "Starting script execution..." entry
  - Creates `DuckyInterpreter` with `TcpDuckyExecutor`
  - Calls `interpreter.executeScript(script)` in a coroutine (`viewModelScope.launch` on `Dispatchers.Main`)
  - Catches errors, sets state to `Error(message)` or `Complete` on success
- Holds reference to `TcpDuckyExecutor` (injected in constructor)

**Log mechanism:**
- `onLog` callback from `TcpDuckyExecutor` calls `addLog(msg)` which appends to `logBuffer` AND sets `_logText.value = logBuffer.joinToString("\n")`
- Fragment collects `logText` as a `StateFlow` — always has the latest full text, survives lifecycle restarts

**Location:** `client/app/src/main/java/com/darusc/mousedroid/viewmodels/DuckyScriptRunnerViewModel.kt`

---

#### Task 3.4: Create `DuckyScriptFragment.kt`

**File:** `client/app/src/main/java/com/darusc/mousedroid/fragments/DuckyScriptFragment.kt`

**Contents:**
- Fragment class with data binding (`FragmentDuckyScriptBinding`)
- `registerForActivityResult` launchers:
  - `getContent` (`GetContent("text/plain")`) — loads `.txt` scripts into the editor
  - `exportScriptLauncher` (`CreateDocument("text/plain")`) — saves editor content to a file
  - `exportLogsLauncher` (`CreateDocument("text/plain")`) — saves debug console text to a file
- In `onViewCreated`:
  - Get `DuckyScriptRunnerViewModel` via `activityViewModels<DuckyScriptRunnerViewModel>()`
  - Setup button click handlers:
    - `Load Script` → launches file picker, reads content into `EditText`
    - `Execute` → calls `viewModel.compileAndRun(editText.text.toString())`
    - `Export Script` → launches `exportScriptLauncher` with filename "script.txt"
    - `Clear` → calls `viewModel.clearLogs()`
    - `Export Logs` → launches `exportLogsLauncher` with filename "duckyscript_logs.txt"
  - Observe `viewModel.state` and update UI (enable/disable Execute button, show status, progress bar)
  - Observe `viewModel.logText` — `StateFlow` collection updates `debugConsole` text reactively
- Uses `lifecycleScope.launch` + `repeatOnLifecycle` for state and log observation

**Location:** `client/app/src/main/java/com/darusc/mousedroid/fragments/DuckyScriptFragment.kt`

---

### Phase 4: Wire-up

#### Task 4.1: Update `Input.kt` to handle DuckyScript menu item

**File:** `client/app/src/main/java/com/darusc/mousedroid/fragments/Input.kt`

**Change:** In `setNavigationItemSelectedListener`, add a case for `R.id.mode_duckyscript`:
```kotlin
R.id.mode_duckyscript -> {
    replaceChildFragment(DuckyScriptFragment())
    true
}
```

**Pattern:** Same as the existing `mode_touchpad` and `mode_numpad` handlers.

---

#### Task 4.2: Wire `TcpDuckyExecutor` to `DuckyScriptRunnerViewModel`

**Location:** `DuckyScriptFragment.kt` (in `onViewCreated` or via ViewModel provider)

**Details:**
- `TcpDuckyExecutor` needs access to the TCP socket from `ConnectionManager`
- Inject `ConnectionManager` into `TcpDuckyExecutor` constructor
- Inject `TcpDuckyExecutor` into `DuckyScriptRunnerViewModel` constructor
- If `ConnectionManager` is a singleton, `TcpDuckyExecutor` can grab it directly

---

## New File Tree

```
client/app/src/main/java/com/darusc/mousedroid/
  interpreter/
    DuckyTypes.kt          ← NEW
    DuckyInterpreter.kt    ← NEW
    DuckyExecutor.kt       ← NEW
    TcpDuckyExecutor.kt    ← NEW
  viewmodels/
    DuckyScriptRunnerViewModel.kt  ← NEW
  fragments/
    DuckyScriptFragment.kt         ← NEW
```

## Modified Files

```
client/app/src/main/res/menu/input_modes_menu.xml  ← ADD MENU ITEM
client/app/src/main/res/layout/fragment_ducky_script.xml  ← NEW + BUTTONS ADDED LATER
client/app/src/main/java/com/darusc/mousedroid/fragments/Input.kt  ← ADD HANDLER
client/app/src/main/java/com/darusc/mousedroid/fragments/DuckyScriptFragment.kt  ← ADD EXPORT/CLEAR BUTTONS
client/app/src/main/java/com/darusc/mousedroid/viewmodels/DuckyScriptRunnerViewModel.kt  ← ADD logText StateFlow
client/app/src/main/java/com/darusc/mousedroid/networking/bluetooth/BluetoothConnection.kt  ← FIXED delay(15) PER BATCH
client/app/src/main/java/com/darusc/mousedroid/interpreter/TcpDuckyExecutor.kt  ← FIXES
client/app/src/main/java/com/darusc/mousedroid/interpreter/DuckyInterpreter.kt  ← FIXES
```

---

## Dependencies & Requirements

- **Kotlin Coroutines** — already in project (used by all ViewModels)
- **Jetpack Navigation** — already in project
- **Data Binding** — already in project (all fragments use it)
- **ActivityResultContracts.GetContent** — for file picker (standard AndroidX)
- **TCP connection** — uses existing `ConnectionManager` singleton

---

## Implementation Order (dependencies)

```
1.1 → 1.2 → 2.1 → 2.2 → 3.3 → 3.4 → 3.2 → 4.1 → 4.2
```

1. **1.1** Types (no deps)
2. **1.2** Interpreter (depends on 1.1)
3. **1.3** Executor interface (depends on 1.1)
4. **2.1** TcpDuckyExecutor (depends on 1.3, needs ConnectionManager)
5. **2.2** Keycode mapping (part of 2.1)
6. **3.3** ViewModel (depends on 2.1)
7. **3.4** Fragment (depends on 3.3, 3.2)
8. **3.2** Layout XML (depends on 3.4 for binding)
9. **4.1** Menu item (no deps)
10. **4.2** Wire TcpDuckyExecutor into ViewModel (depends on 2.1, 3.3)

---



---

## Lessons Learned (2026-06-13)

### BUG: TcpDuckyExecutor used wrong connection path

**Symptom:** Script showed "Completed" but no keys were typed on the target PC.

**Root cause:** `TcpDuckyExecutor` called `ConnectionManager.sendRawBytes()` which only sends via `tcpConn`. Since this project uses **Bluetooth only**, `tcpConn` was null and all keypresses were silently dropped.

**Fix:** Changed `TcpDuckyExecutor` to use `ConnectionManager.send(InputEvent.KeyPress(...))` instead of `sendRawBytes()`. The `send()` method routes through the correct connection (UDP → TCP → Bluetooth), so it works with all connection modes.

**Rule:** Use `ConnectionManager.send(InputEvent)` for all input events. Only use `sendRawBytes()` when you specifically need raw TCP.

### BUG: `DuckyExecutor.injectText()` was called per-character but named confusingly

**Fix:** Renamed interface method from `injectText(String)` to `injectChar(Char)` for clarity. Each character should be sent as a separate `InputEvent.KeyPress` with the correct shift modifier for uppercase.

### BUG: Layout title overlaps with hamburger icon

**Fix:** Added `paddingStart="56dp"` to the title `TextView` to account for the drawer menu button area.

### BUG: Status only shows single-line text

**Fix:** Added a scrollable debug console section (`TextView` with `ScrollView`) that displays detailed per-keypress logs. The `DuckyExecutor` receives an `onLog` callback that feeds into a `logBuffer` in the ViewModel, which the Fragment displays.

---

### BUG: STRING command causes app to freeze — `toHIDReport` generates 4 reports per keypress

**Symptom:** First `STRING` line in a script freezes the app; nothing after it executes.

**Root cause #1 – extraneous key in keyList:** `TcpDuckyExecutor.sendKeypress()` built the keyList as `[Key(mod, code), Key(0, 0)]`. The `Key(0, 0)` was meant to signal release, but `toHIDReport()` iterates each key in the list and produces press+release **for each entry**:
```
Key(mod, code)  → KeyboardReport(mod, code) + KeyboardReport(0, 0)
Key(0, 0)       → KeyboardReport(0, 0)      + KeyboardReport(0, 0)
= 4 reports per keypress instead of 2
```
**Root cause #2 – per-report delay in Bluetooth consumer:** `BluetoothConnection.sendReportJob` called `delay(15)` after **each** `KeyboardReport` in the inner loop, so 4 reports × 15ms = 60ms of IO dispatcher delay per keypress, while the producer (running on `Dispatchers.Main` via `viewModelScope.launch`) floods the unlimited channel instantly.

**Fix #1:** Changed `sendKeypress()` to use a single-key list: `listOf(KeyboardLayout.Key(modifier, code))`. `toHIDReport()` now produces exactly 2 reports (press + release).

**Fix #2:** Moved `delay(15)` outside the inner `for (report in reports)` loop so it fires **once per batch** instead of per report.

**Rule:** Never put a "release" `Key(0,0)` into the keyList — `toHIDReport()` already handles release by emitting `KeyboardReport(0, 0)` after the press report for each key in the list. A `KeyPress` event represents a set of simultaneously-pressed keys, not a press-release sequence.

### BUG: `executeString` ignores `DEFAULTDELAY` between characters

**Symptom:** Characters in `STRING` commands are typed with no delay even when `DEFAULTDELAY N` is set. The `if (defaultDelayMs > 0)` block was empty with only a misleading comment: `// No delay between each character for efficiency`.

**Fix:** Replaced the empty block with `delay(defaultDelayMs)`. Also marked `executeString` as `suspend` since it now calls `delay()`. Added the post-line `delay(defaultDelayMs)` in the main loop's `STRING` case for consistency with `KEYBOARD`/`MODIFIER` commands and the quoted-string handler.

### BUG: `TcpDuckyExecutor` never wired `logMessage` — debug console shows nothing

**Symptom:** The debug console `TextView` on the DuckyScript page stays empty during execution even though the executor's `onLog` callback is connected to the ViewModel's `addLog()`.

**Root cause:** `DuckyExecutor` interface defines `logMessage(message: String)` with a default **empty** body. `DuckyInterpreter.executeString()` calls `executor.logMessage("STRING: ...")` but `TcpDuckyExecutor` never overrides this method — the log is silently swallowed.

**Fix:** Added `override fun logMessage(message: String) { onLog?.invoke(message) }` to `TcpDuckyExecutor`.

### BUG: Polling loop in `observeLogs()` can tight-spin and misses logs

**Symptom:** Debug console fails to show real-time log output. The `while(true)` loop uses `viewModel.getLogs()` polling with `delay(200)`, but a `continue` inside `if (isLayoutRequested)` skips the delay, causing a tight spin.

**Fix:** Replaced polling with a push-based approach:
- Added `Channel<String>(UNLIMITED)` + `Flow<String>` in `DuckyScriptRunnerViewModel`
- `addLog()` now also `trySend`s to the channel
- Fragment's `observeLogs()` uses `viewModel.logEvent.collect` instead of `while(true)` —— each new log updates the `TextView` reactively

### BUG: Debug console text disappears after script finishes — `receiveAsFlow()` loses events on lifecycle restart

**Symptom:** Logs flash briefly during execution but the console goes blank once the script completes.

**Root cause:** `Channel.receiveAsFlow()` is a single-subscriber flow. When `repeatOnLifecycle(STARTED)` restarts the collection (e.g. lifecycle briefly dips below STARTED), the channel from the previous collect is drained and the new collect starts with no pending events. `getLogs()` was still called but returned the buffer content — however the flow emission itself would suspend waiting for new events, effectively freezing the text update until the next `addLog()` call. Since the script is done, no more events arrive, so the text is never re-emitted.

**Fix:** Replaced `Channel<String>(UNLIMITED) + receiveAsFlow()` with `MutableStateFlow("")` that holds the full log text directly. `addLog()` now updates `_logText.value = logBuffer.joinToString("\n")`. StateFlow always has a value — on collection restart it immediately emits the current text. Also updated `clearLogs()` to reset `_logText.value = ""` so the console clears immediately via the StateFlow.

**Rule:** Prefer `StateFlow` over `Channel.receiveAsFlow()` when you need the latest state to survive subscriber restarts. Channels are for one-shot event delivery; StateFlow is for observable state.

### BUG: `DEFAULT_DELAY` (underscore variant) not recognized in parser

**Symptom:** `DEFAULT_DELAY 200` (with underscore) silently treated as an unknown key command; no delay applied.

**Root cause:** The parser branch for DEFAULTDELAY only matched `"DEFAULTDELAY"` and the non-standard `"DEFAULTDELAYN"`. `normalizeToken` passes `"DEFAULT_DELAY"` through unchanged, so it fell to the KEYBOARD/MODIFIER branch and failed with "Keycode not found".

**Fix:** Added `"DEFAULT_DELAY"` to the parse branch alongside `"DEFAULTDELAY"` and `"DEFAULTDELAYN"`.

### BUG: Modifier alone (e.g. `GUI`, `WINDOWS`) silently skipped

**Symptom:** `GUI` (without a key argument, e.g. to open the Start Menu) does nothing. Reference spec explicitly says "Use WINDOWS alone to press just the Win key."

**Root cause:** The modifier branch in `parseLine` only added an instruction when `tokens.size >= 2` — standalone modifiers fell through with no else clause, producing a silent no-op. Even if an instruction had been created, `executeKeyCommand` had `argument.isEmpty() -> normalized` which set `key = "GUI"`, and `injectModifierCombo("GUI", "GUI")` would fail keycodes map lookup.

**Fix #1:** Added `else` clause in the modifier branch that creates a `MODIFIER` instruction with `argument=""` for standalone modifiers.

**Fix #2:** Changed the modifier branch condition from a hardcoded token list to `isModifier(normalizedFirst)` so new modifier aliases are automatically recognized.

**Fix #3:** Changed `executeKeyCommand` empty-argument handling from `argument.isEmpty() -> normalized` to `argument.isEmpty() -> ""` so the keyName is empty.

**Fix #4:** Added empty-keyName guard in `TcpDuckyExecutor.injectModifierCombo` — when `keyName` is empty, sends a press with just the modifier byte and `code=0x00`.

**Rule:** Standalone modifiers (`GUI`, `ALT`, `CTRL`, etc.) are valid DuckyScript — they send a HID report with modifier bits set but no keycode.

### BUG: `REPEAT` doesn't apply `defaultDelayMs` after repetitions

**Symptom:** Repeated keystrokes from `REPEAT N` blast through at full speed with no inter-key delay, ignoring `DEFAULTDELAY`.

**Root cause:** The main loop applies `delay(defaultDelayMs)` after `KEYBOARD`/`MODIFIER` commands, but `executeRepeat` calls the executor directly inside a `repeat(N) { }` block with no delay between calls.

**Fix:** Added `delay(defaultDelayMs)` after each repeated `injectModifierCombo`/`injectKeycode` call inside the `repeat` block. Also marked `executeRepeat` as `suspend` since it now calls `delay()`.

### BUG: `getModifierByte` missing `"LEFTSHIFTED"` mapping

**Symptom:** `LEFTSHIFTED a` types "a" without shift — `isModifier` recognizes `"LEFTSHIFTED"` but `getModifierByte` doesn't map it, so the modifier byte is `MOD_NONE`.

**Root cause:** `getModifierByte` had entries for `"SHIFT"`, `"SHIFTED"` → `MOD_LEFT_SHIFT` and `"RIGHTSHIFT"`, `"RIGHTSHIFTED"` → `MOD_RIGHT_SHIFT`, but `"LEFTSHIFTED"` was missing.

**Fix:** Added `"LEFTSHIFTED"` to the `"SHIFT"`, `"SHIFTED"` entry in `getModifierByte`.

---

## Notes & Gotchas

1. **USE `ConnectionManager.send(InputEvent)`, NOT `sendRawBytes()`** — `sendRawBytes()` only works with TCP. `send()` routes through all connection types (Bluetooth, TCP, UDP).
2. **DuckyExecutor interface methods:** `injectChar(Char)` for individual characters (STRING), `injectModifierCombo(String, String)` for modifier+key, `injectKeycode(String)` for standalone keys.
3. **Each keypress creates ONE `KeyboardLayout.Key` entry:** `KeyboardLayout.Key(modifier, code)`. The `toHIDReport()` converter generates press + release HID reports from this — **do not** add a `Key(0, 0)` release entry to the list, or it will produce 4 reports instead of 2.
4. **CONNECTION LIFECYCLE:** If connection drops mid-script, stop execution and notify user.
5. **MEMORY:** Large scripts (>10KB) should be handled efficiently — use buffered readers.
6. **CONCURRENCY:** If Execute is pressed while already running, cancel previous or queue.
7. **KEYCODE MAPPING:** All keycodes come from `Keycode` object (e.g., `Keycode.KEY_ENTER`, `Keycode.MOD_LEFT_CTRL`).
8. **STRING command:** Each character needs shift modifier for uppercase letters (char != uppercaseChar() && isLetter()).
9. **REPEAT only applies to MODIFIER and KEYBOARD** — not STRING or DELAY (per spec).
10. **DuckyScript uses `DuckyExecutor.logMessage()` for debug output — `TcpDuckyExecutor` must override it** (the interface default is a no-op). Wire it to `onLog` or your logging system.
11. **Layout header overlap:** Add `paddingStart="56dp"` to title TextViews to account for the hamburger icon area.
12. **Always add a debug console** to debug-heavy fragments so you can see what's being sent without checking Logcat.
