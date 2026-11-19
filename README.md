# MineControl

English | [简体中文](README.zh-CN.md)

A client-side Fabric mod for controlling and inspecting Minecraft through a local API, a dependency-free Python CLI, and a Java SDK. Supports **Minecraft 26.3, 26.2, 1.20.4, and 1.20.1** through separate builds.

[Modrinth](https://modrinth.com/mod/minecontrol) · [Issues](https://github.com/liuzeen1234/MineControl/issues) · [Protocol](docs/PROTOCOL.md) · [Java SDK](java-sdk/README.md)

## Features and version differences

Inspect game state and UI controls, enter text, simulate keyboard and mouse input, automate actions in the background, and run tick-driven JSON macros or external Java programs.

26.2 is the default development target. 1.20.1 and 1.20.4 share the legacy implementation and feature scope.

| Feature | 1.20.1 / 1.20.4 | 26.2 / 26.3 |
| --- | --- | --- |
| Local CLI/TCP API, keyboard/mouse input, background control, UI automation | Supported | Supported |
| JSON macros with loops, conditions, and waits | Supported | Supported |
| Position, inventory, loaded terrain, and NBT inspection | Supported | Supported, including effective item data components |
| Basic external Java SDK control | Supported | Supported |
| Macro role registration, permissions, channel broadcasts and process management | Supported | Supported |
| F8 manager, file import/deletion, Java source/jar execution | Supported | Supported |
| Macro HUD and configurable F9 stop-all shortcut | Supported | Supported |
| Physical input blocking, cursor release/restore, settings screen | Supported | Supported |
| Nearby players, recent damage, dedicated chat-command API | Not yet integrated | Supported |
| Container inspection, item deposits, closing containers | Not yet integrated | Supported |
| Target-block scanning and Xaero waypoint API | Not yet integrated | Supported |

A shared SDK does not make every method or standalone macro compatible with every version. Macros using modern APIs require 26.2 or 26.3. In-game macro integration for 1.20.1 and 1.20.4 is covered by [Issue #1 test records](docs/issue-1/README.md).

**Macro programs are separate executable jars and must be added manually.** The main mod does not bundle or automatically install macro programs. Its hidden shared runtime is not a runnable macro.

See the [test records](docs/TESTING.md) for actual validation scope. Compile compatibility alone does not establish that every feature was tested in-game on every version. Supporting documents are currently primarily in Chinese.

## Installation

1. Install Fabric Loader and Fabric API for your Minecraft version.
2. Put the matching MineControl jar in the client's `mods` folder. No server installation is required. Choose the regular jar; sources jars are for reading code.
3. Start Minecraft. Configuration is created at `config/minecontrol.json`. The log message `MineControl listening on 127.0.0.1:25580` indicates that the API is ready.
4. Install Python 3.9+ and get the CLI from this repository. Run `./minectl ping`, or `python cli/minectl.py ping` on Windows.

| Minecraft | Game runtime | Minimum Fabric Loader | Build artifact |
| --- | --- | --- | --- |
| 26.3 | Java 25 | 0.19.5 | `minecontrol-mc26.3-1.0.0.jar` |
| 26.2 | Java 25 | 0.19.3 | `minecontrol-mc26.2-1.0.0.jar` |
| 1.20.4 | Java 17 | 0.15.11 | `minecontrol-mc1.20.4-1.0.0.jar` |
| 1.20.1 | Java 17 | 0.15.11 | `minecontrol-mc1.20.1-1.0.0.jar` |

The current Modrinth release targets **26.2**; legacy versions can be built from source. Always match the jar to the game version. The CLI uses only the Python standard library; `cli/minectl.py` can be copied and used independently.

## Quick start

```sh
./minectl ping
./minectl status
./minectl ui list
./minectl ui click 4
./minectl ui set 9 on
./minectl ui type 6 'Test world'
./minectl ui type 6 ' more text' --mode append
./minectl key tap ESCAPE
./minectl key down LEFT_SHIFT
./minectl key down W
./minectl key up W
./minectl key up LEFT_SHIFT
./minectl key hold SPACE 10
./minectl mouse click left
./minectl mouse down right
./minectl mouse up right
./minectl mouse hold left 20
./minectl mouse goto 200 120
./minectl mouse move 30 -10
./minectl wait screen OptionsScreen --timeout-ms 10000
./minectl wait screen none
./minectl input reset
./minectl --port 25581 --timeout 20 status
```

UI IDs start at zero and apply only to the current screen. Run `ui list` again after changing screens. Identify controls by class, label, coordinates, and `value`. `ui set` sets checkboxes and boolean cycling buttons idempotently. Disabled/hidden controls, read-only text fields, and Minecraft text filters and length limits are respected.

Keys use case-insensitive GLFW names such as `W`, `SPACE`, and `LEFT_CONTROL`. Aliases include `CTRL`, `SHIFT`, `ALT`, `ESC`, `RETURN`, and `CMD`. Use `ui type` for Unicode text. For chords, press each key with `down`, then release each with `up`.

`tap` and `click` release at the start of the next client tick. `hold` returns after scheduling the release, without waiting for completion. The client normally runs at 20 ticks per second. Exiting the CLI does not release held inputs. Use `up` or `input reset`; inputs also expire or clear when leaving the world. Repeated `down`/`tap` on an already simulated held key returns `INPUT_HELD`.

In a GUI, mouse coordinates use scaled GUI units with the origin at the top left. Outside a GUI, `mouse move` uses raw deltas with normal sensitivity and inverted-Y settings. At default sensitivity 0.5, one unit is 0.15 degrees. View changes are direct, without cinematic smoothing. `mouse goto` requires an open GUI.

## Configuration and local access

```json
{
  "enabled": true,
  "port": 25580,
  "backgroundControl": true,
  "blockPhysicalInputDuringMacros": true,
  "maxHoldTicks": 1200,
  "requestTimeoutMs": 15000,
  "maxRequestBytes": 65536
}
```

The API accepts **local loopback connections only**, at `127.0.0.1:25580` by default. Local programs that connect can operate the game. Only run tools and macros you trust, and follow multiplayer server rules. Connections are processed serially; idle connections time out. The CLI also accepts `MINECONTROL_PORT`.

On all three supported versions, open the macro manager's settings to toggle physical input blocking. Changes apply and save immediately. Detection/scheduler macros alone retain manual controls. Other configuration changes require a client restart.

Background control permits unfocused input and disables pause-on-lost-focus for the session, restoring its original value on normal shutdown. `status.window_focused` reports actual focus. Set `backgroundControl` to `false` for vanilla focus behavior. Menus still follow vanilla pause rules; close the pause menu before moving. `maxHoldTicks` limits both `down` and `hold` durations.

After a request timeout, inspect `status` before retrying: actions already started cannot be rolled back. A `wait screen` timeout must be shorter than `requestTimeoutMs` (the CLI defaults to 10 seconds). `input reset` releases simulated inputs and stops the JSON macro.

## Java macros

Press **F8**, or use the MineControl button on the title/pause menu, to open the macro manager. Add `.java` source files or standalone executable `.jar` macros by path or drag and drop. Name collisions are renamed automatically; deleted files move to `.trash`. Files live in the game instance's `config/minecontrol-programs/`. The shared runtime ships with the mod, so source macros can run without a separate terminal.

The manager supports multiple detection macros, one execution macro, and one scheduler macro in parallel. Programs register roles and the manager checks permissions; detection/scheduler roles cannot inject game input through the API. A JSON macro cannot run alongside a Java execution macro. Starting a macro returns to the game. Leaving a world stops detection, execution, and JSON macros while retaining the scheduler. Closing the client stops all macros. Dimension loading within the same connection preserves macros until the world is ready again. See [macro scheduling](docs/MACRO-SCHEDULING.md) for broadcasts and exit reasons.

The HUD shows running macros and the stop shortcut, **F9** by default, configurable under Controls → Key Binds → Miscellaneous. F9 stops all macros and prevents automatic restarts. F9 remains a fallback if the stop action is unbound.

Execution and JSON macros block physical game input by default, clear manually held input, and release the system cursor without opening a pause menu. Simulated input continues to work. **F8 remains available** to open and operate the manager. After completion or stopping, manual input and cursor capture resume; an unfocused game waits until it regains focus before capturing the cursor. Registered external execution macros participate in this behavior; unregistered programs do not. Cursor handling follows the GLFW approach in [Cursor-Toggle](https://github.com/liuzeen1234/Cursor-Toggle/blob/main/src/main/java/com/cursortoggle/CursorToggleClient.java), preserving Minecraft's logical capture state for simulated clicks.

External Java 17+ programs can use normal variables, loops, functions, and libraries through the SDK:

```java
try (var mc = dev.minecontrol.sdk.MineControl.connect()) {
    mc.key().holdAndWait("w", 20);
    System.out.println(mc.position());
}
```

Enter a world and choose a safe place before running movement examples. Build the SDK with `./gradlew :java-sdk:build`; use `./gradlew :java-sdk:smoke` for a read-only connection check. See the [SDK documentation](java-sdk/README.md) for setup and methods.

## JSON macros

JSON macros execute inside the mod on client ticks and continue after the CLI exits:

```sh
./minectl macro run examples/macro-demo.json
./minectl macro status
./minectl macro stop
```

A macro is a JSON array of steps:

- Command: `{"command":"mouse.click","button":"left"}` using protocol parameters.
- Wait: `{"wait":20}` for 1–1,000,000 client ticks.
- Repeat: `{"repeat":10,"do":[...]}` for 0–1,000,000 iterations.
- Conditional loop: `{"while":{"field":"world_loaded","value":true},"do":[...]}`.
- Branch: `{"if":{"field":"screen","value":null},"then":[...],"else":[...]}`; `else` is optional.
- Break out of the nearest loop: `{"break":true}`.

Conditions read `status` fields and support `==` (default), `!=`, `<`, `<=`, `>`, and `>=`; ordering comparisons require numbers. Use JSON `null` for no screen and the returned screen class name otherwise. Observation conditions support paths such as `observe.main_hand.remaining_durability`, path arrays, `exists`, and `all`/`any`/`not`. Unknown fields cause an error. JSON macros do not provide variables, arithmetic, or game chat commands.

Macros can call existing keyboard, mouse, UI, ping, status, and `wait.screen` commands, but cannot manage macros or call `input.reset`. `hold` only schedules input; add waits for it to complete. At most one internal instruction executes per tick, and loops/branches also consume ticks.

Completion, stopping, errors, or leaving the world release simulated inputs. Dimension loading within the same server connection pauses execution without consuming wait ticks. Outstanding holds are released at completion. Macros are not persisted or resumed after restart. Limits are 4,096 compiled instructions, 32 nesting levels, and the request byte limit. Structure is checked before launch; command arguments are checked during execution.

## Inspecting the world

```sh
./minectl observe
./minectl position
./minectl region -10 60 -10 10 70 10
./minectl region -10 60 -10 10 70 10 --text > terrain.txt
```

`observe` exports main/off-hand items and the crosshair target, including client-serializable block entity/entity NBT as JSON and typed SNBT. Legacy builds expose item NBT; 26.2 also exposes effective item components, including defaults. Raw NBT is in `nbt.data`, and effective components in `components.data`. These are client snapshots: unsynchronized server data is unavailable. Full observation is requested separately to keep `status` lightweight.

Region queries use two inclusive absolute corners, return up to 32,768 blocks from already loaded chunks, and never force chunk loading. Text output uses AI Helper-compatible MCBLUEPRINT v3 block lists, with all block properties and air omitted; AI Helper is not required. Region output excludes container contents and sign text. See the [protocol reference](docs/PROTOCOL.md).

## Optional block detector on 26.2

`./gradlew build` produces the main mod in `build/libs/` and separate `block-detector.jar` and `entity-click-program.jar` macros in `build/macros/`. Import manually through F8 → Add. Existing manually added files are retained; no macros are automatically installed.

The detector scans the chunks loaded around its starting position once, across their full height, then exits with a completion broadcast on `$system`. It detects spawners, end portal frames, lower iron-door halves, iron bars, iron chains, ordinary rails, reinforced deepslate, vaults (including ominous vaults), budding amethyst, crying obsidian, and wet sponges. Powered/detector/activator rails and trial spawners are excluded. Server-hidden or unloaded blocks cannot be detected.

Every distinct spawner position is retained. Other types are deduplicated against saved points of the same type within 70 blocks in 3D; skipped blocks do not become deduplication centers. Records are separated by world/server and dimension, persist after blocks are removed, and are saved atomically under `config/minecontrol-programs/block-detections/<world-id-SHA-256>.json`. Only one detector may use a record directory at a time.

With compatible Xaero Minimap installed, it adds colored waypoints to the actual world's current/default set. Tested with Xaero Minimap 26.6.0 for Minecraft 26.2; other versions may differ. Waypoint failures retain records; a later manual run retries missing waypoints without duplicating existing ones. The hidden `.runtime/sdk.jar` never appears as a runnable macro.

## Mining examples and test tools

The repository includes standalone Python controllers. These require a prepared survival environment and suitable tools. Read the [Chinese README](README.zh-CN.md) and [test records](docs/TESTING.md) for full prerequisites and limitations.

| Script | Purpose and limits |
| --- | --- |
| `cli/mining_benchmark.py` | Timed benchmark in a prepared two-block tunnel; reports inventory changes, durability, and displacement. |
| `cli/straight_miner.py` | Straight tunnel using crosshair-visible blocks only; forbids region queries. Handles some falling blocks and bridging. |
| `cli/shaft_miner.py` | Alternates two shaft cells, confirming visible support before descending; requires no OP and can continue horizontally. Does not build a return route. |
| `cli/fishbone_miner.py` | Fixed main/branch layout with restricted one-block searches outside excavated cells; confirms exposed ores through the crosshair. |
| `cli/vein_miner.py` | Legacy loaded-terrain scanning and 26-neighbor vein tracing, including hidden client-known ore; unsuitable for visible-block-only mining requirements. |

These tools stop on configured health, hunger, durability, terrain, or timeout conditions, but cannot guarantee safe traversal of arbitrary caves or complete collection of drops. Timing may be checked at work-unit boundaries; report actual elapsed time. Inventory gains are not an exact block count, and a cleared vein does not establish that every drop was collected. Use only where automation and the relevant inspection methods are permitted.

## Building and verification

Start Gradle with **Java 25**. Gradle 9.5.1 / Loom 1.17.21 require it; legacy compilation additionally needs a Java 17 toolchain.

```sh
./gradlew build                   # Default: Minecraft 26.2
./gradlew build -Pmc=26.3
./gradlew build -Pmc=26.2
./gradlew build -Pmc=1.20.4
./gradlew build -Pmc=1.20.1
./gradlew runClient               # Default: Minecraft 26.2
python3 -m unittest discover -s tests -v
```

Installable jars collect in `build/libs/`. Development instances are isolated in `run-26.3`, `run-26.2`, `run-1.20.4`, and `run-1.20.1`; use `-PrunDir=path` to override. Select legacy client runs explicitly with `-Pmc=1.20.4` or `-Pmc=1.20.1`.

26.2 and 26.3 use official unobfuscated names and `src/mc26.2/java` / `src/mc26.3/java`; legacy adapters use `src/main/java` with small version-specific bridges in `src/mc1.20.1/java` and `src/mc1.20.4/java`. Shared protocol/configuration code is in `src/common/java`. UI/screen class names differ by version (26.2 uses `EditBox`, `Checkbox`, and `CycleButton`), while CLI commands and the protocol remain consistent. Control changes need corresponding adapters and validation for all four targets.

For an isolated development client at the title screen with no existing worlds, run `python3 tests/live_smoke.py`. After creating and entering a separate test world, run `python3 tests/live_world.py`.

For the 26.2 native-input regression probe, run `./gradlew inputCaptureTestJar`. Install `build/test-mods/minecontrol-input-capture-test.jar` only in an isolated client and create `config/input-capture-test.trigger` before entering the test world. It verifies physical input blocking, simulated input, stop-key rebinding, and restoration; results go to `config/input-capture-test-report.json`. The probe is excluded from the production mod.

Recorded runs include build/protocol checks and CLI unit tests, plus 26.2 in-game validation for GUI automation, background/high-DPI input, Unicode text, macro management, input isolation, scheduling, and block detection. Each record describes its own scope; these results do not imply all legacy features were tested in-game. See [TESTING.md](docs/TESTING.md).

## License

[MIT](LICENSE).

Legacy native-input regression: `./gradlew legacyTestJar -Pmc=1.20.1` (or `1.20.4`) generates a version-specific probe in `build/test-mods/`. Install only in an isolated matching development client, create `config/input-capture-test.trigger`, and enter a test world. Results: `config/input-capture-test-report.json`. Do not run different Minecraft target builds concurrently while a development client is loading; Gradle can clean the previous task output.

### Minecraft 26.3

Build with `./gradlew build -Pmc=26.3` and launch with `./gradlew runClient -Pmc=26.3`. This target uses Java 25, Fabric Loader 0.19.5+, and Fabric API 0.161.0+26.3. The separate SDL input adapter preserves named CLI keys and left/right mouse buttons. Optional macros remain separate jars that users add manually. The input-capture probe can be built with `./gradlew inputCaptureTestJar -Pmc=26.3`; use its trigger and report paths described above.

26.3 build, menu input, and 31 in-world input-capture checks passed. See [26.3 test records](docs/compatibility-26.3/README.md) for scope and limitations.
