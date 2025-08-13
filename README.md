# ChadGPT-for-DDSS2

Helper mod for a Forge 1.16.5 server; chat-triggered assistant that replies with a single `/tellraw` line.

## What it does

When any player's chat message contains `chadgpt` in any case, the mod sends context to OpenAI then makes the server say exactly one line via `tellraw @a .`. There are two routes that never collide.

* **Regular ChadGPT**; if the message contains `chadgpt` but not `silent`.
  Uses the OpenAI **Responses API** with only `model`, `instructions`, and `input`. The instructions include a strict `/tellraw` policy; the model returns a single finished `/tellraw @a [...]` command; the server executes it.

* **ChadGPT; Silent Gear assister**; if the message contains both `chadgpt` and `silent`.
  Uses the OpenAI **Responses API** with `model`, `instructions`, `input`, and `tools:[{type:"file_search", vector_store_ids:[.]}]`. The vector store id comes from an environment variable. The server executes the single returned `/tellraw` line.

The player's chat is never blocked. The mod emits exactly one server command per trigger.

## Output style; `/tellraw` policy

The model formats all replies as one physical line compatible with Minecraft Java 1.16.5:

* Command shape; `/tellraw @a <Component>`.
* Raw JSON Text only; allowed object keys are `"text"`, `"color"`, `"bold"`, `"italic"`.
* Multiple segments use a top-level JSON array.
* Visual line breaks use the literal string `"\n"` as a segment or inside `"text"`.
* No other keys; no hoverEvent; no clickEvent; no translate; no nbt.
* Theme aware coloring; emerald topics look emerald; rainbows vary across colors.

If the model ever returns something that is not a valid `/tellraw @a .` line, the mod falls back to a small red error message that explains what to fix.

## What the model sees

* The last N chat lines before the trigger; oldest first; each as `Name: message`.
* The full triggering chat line exactly as typed.
* For the Silent Gear route; the model is permitted to use your vector store through `file_search`; it is instructed not to expose the existence of files.

## Quick start

1. Drop the built jar into your server's `mods` folder.
2. Set environment variables in the shell that launches the server:

   ```powershell
   $env:OPENAI_API_KEY = 'sk-your-key'
   # Required for the Silent Gear route
   $env:CHADGPT_VECTOR_STORE_ID = 'vs_...'
   ```
3. Start the server with Java 8u462 or newer Java 8 build:

   ```powershell
   & 'D:\OpenJDK8U-jdk_x64_windows_hotspot_8u462b08\jdk8u462-b08\bin\java.exe' `
     -Xms2G -Xmx2G `
     -jar .\forge-1.16.5-36.2.42.jar nogui
   ```

## Build notes

* MDK: Forge 1.16.5.
* Java: compile with JDK 8; Gradle itself runs on a newer JDK.
* Your `build.gradle` already pins `javac.exe` on D drive; nothing else required.
* Build from the mod root:

  ```powershell
  .\gradlew.bat clean build
  ```

  The jar appears in `build\libs\`.

## Build Environment (Windows reference)

Use this as the canonical baseline for building and testing.

- OS: Windows 11 Pro 10.0.22631 (64-bit)
- Gradle: 8.4
- Java: Temurin JRE 21.0.8 for Gradle runtime; target Java 8 for compilation
- Minecraft/Forge: 1.16.5 / 36.2.42; mappings: official 1.16.5
- Tooling: `org.gradle.java.home` points to a local JDK 8 installation (see `gradle.properties`)
- Verified commit: d3e138f (2025-08-12)

Build command:

```powershell
.\gradlew.bat clean build
```

## Triggers; examples

* Regular route:

  ```
  <Player> ChadGPT where do I find cows
  ```

  The server executes exactly one `tellraw @a [...]` with a themed color scheme.

* Silent Gear route:

  ```
  <Player> ChadGPT silent define peridot.
  ```

  The server executes exactly one `tellraw @a [...]` with facts sourced through your vector store.

## Configuration at launch

All via Java system properties; add them after the `java` command and before `-jar`.

* `-Dchadgpt.model=gpt-5-nano`
  Model to use for both routes.

* `-Dchadgpt.history=20`
  Number of prior chat lines sent as context; clamp 0..20.

* `-Dchadgpt.history_cap=200`
  Maximum rolling buffer size; older lines are evicted.

* `-Dchadgpt.cooldown_ms=3000`
  Minimum milliseconds between triggers; shared by both routes.

* `-Dchadgpt.responses_connect_ms=20000`
  Connect timeout for the Responses API.

* `-Dchadgpt.responses_read_ms=120000`
  Read timeout for the Responses API.

* `-Dchadgpt.http_retries=2` and `-Dchadgpt.retry_base_ms=750`
  Light exponential backoff for transient failures.

Example launch with tuning:

```powershell
$env:OPENAI_API_KEY = 'sk-your-key'
$env:CHADGPT_VECTOR_STORE_ID = 'vs_689926f2f0608191b014742e99e012c5'

& 'D:\OpenJDK8U-jdk_x64_windows_hotspot_8u462b08\jdk8u462-b08\bin\java.exe' `
  -Dchadgpt.model=gpt-5-nano `
  -Dchadgpt.history=15 `
  -Dchadgpt.cooldown_ms=1500 `
  -Dchadgpt.responses_read_ms=150000 `
  -jar .\forge-1.16.5-36.2.42.jar nogui
```

## How routing works

* The mod lowercases every chat line and checks for `chadgpt`.
* If it also finds `silent` in the same line, the Silent Gear route runs.
* Otherwise the regular route runs.
* A single shared cooldown prevents double fire per line.

## Safety and limits

* Exactly one physical line per response; server executes it; no player message is suppressed.
* The mod strips carriage returns and newlines from the model output; the model uses `"\n"` text for visual breaks instead.
* The parser validates that the output begins with `/tellraw @a` or `tellraw @a`. If the target is not `@a`, or JSON is missing, a safe fallback is used.

## Troubleshooting

* **Nothing happens**; check cooldown; check that the line actually contains `chadgpt`.
* **Silent Gear route does not answer**; verify `CHADGPT_VECTOR_STORE_ID` is set in the same shell.
* **Timeouts or frequent disconnects**; raise `-Dchadgpt.responses_read_ms` and consider `-Dchadgpt.http_retries=3`.
* **Server shows error fallback lines**; the model returned something that was not a single `/tellraw @a .` command; adjust the prompt or ask again.
* **TLS or HTTP errors on Java 8**; use an updated 1.8 build such as 8u462 for better TLS.

## License

MIT; see `mods.toml`.

## Credits

Chat prompts and vector store by you; code scaffolding by ChadGPT; Minecraft is a trademark of Mojang Studios.
