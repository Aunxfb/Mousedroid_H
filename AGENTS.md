# Mousedroid Development Guide

## Project Structure

- **Server** (`server/`): C++17 desktop app using wxWidgets + Asio. Handles input injection and network server on port 6969.
- **Client** (`client/`): Android app (Kotlin, API 28–34) that connects to the server.

### Server entry point

`server/src/wxApplication.cpp` → `wxApplication::OnInit()` creates `Server` and `wxMain`, calls `server->Start()`.

### Client entry point

`client/app/src/main/java/com/darusc/mousedroid/MainActivity.kt` → `BluetoothAdapterWrapper.initialize()`.

### Platform-specific input managers

`server/src/input/win32/` — Windows (uses SendInput via user32).
`server/src/input/linux/` — Linux (uses uinput).

### Network protocol

Server uses TCP for command channel and UDP for video streaming (server picks client to stream). Protocol opcodes are in `server/src/net/server.h` (FRAME=0x00 through EFFECT=0x06).

---

## Server Build (Windows)

```powershell
# Prerequisites: Visual Studio 2022 + "Desktop development with C++"
# Prerequisites: vcpkg
vcpkg install asio:x64-windows wxwidgets:x64-windows
vcpkg integrate install

# Open in Visual Studio → select x64-Release → Build All
# or from command line:
cmake -B"out\build\x64-Release" -T v143 -DCMAKE_BUILD_TYPE=Release
cmake --build "out\build\x64-Release" --config Release

# Deploy to dist/
.\server\release.bat
```

## Server Build (Linux)

```bash
# Prerequisites: GCC 11+/Clang, CMake, libwxgtk3.3-dev, libasio-dev, adb
sudo apt install build-essential cmake libwxgtk3.3-dev libasio-dev adb

cmake -Bcmake -DCMAKE_BUILD_TYPE=Release
cd cmake && make

cd ../..
chmod +x release.sh && ./release.sh
chmod +x install.sh && ./install.sh   # sets udev rules + desktop entry
```

**Linux gotcha:** Session must be restarted after `install.sh` for udev rules (`/etc/udev/rules.d/50-uinput.rules`) to take effect. Mouse control requires `uinput` permissions.

---

## Client Build

Open `client/` in Android Studio or:

```bash
cd client
./gradlew assembleDebug    # debug APK
./gradlew assembleRelease  # release APK
```

Min SDK: 28, target SDK: 34. Uses Kotlin 2.1.0, AGP 8.9.1, data binding.

---

## Current Focus

- **Active work target:** Android client (`client/app/`)
- **Current feature:** Add a simple DuckyScript editor to the client that parses scripts and replays them as HID events through the emulated keyboard, sending each line's commands via the existing TCP connection to the server. Supports file picker to load `.txt` scripts from storage.
- **Connection focus:** Bluetooth connection method only — defer Wi-Fi and USB/ADB support for now.

---

## Key conventions

- `CMakeSettings.json` defines `x64-Debug` and `x64-Release` configs (Ninja generator). VS auto-detects these.
- Server build outputs to `out/build/<config>/bin/`; `release.bat` copies to `dist/` (alias: `mousedroid_win64/`).
- `server/src/input/input.h` typedefs `INPUT_MANAGER` to the platform-specific subclass via preprocessor.
- `server/src/adb.hpp` is Windows-only; spawns `adb.exe` for port reversal during USB mode.
- `server/src/logger.h` logs to both a wxListView widget and a file via `LOG(...)`.
- `server/.gitignore` ignores `cmake/`, `out/`, `resource.o`, `.vs/` and `.vscode/` build artifacts.
