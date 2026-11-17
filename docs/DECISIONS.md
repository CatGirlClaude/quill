# Architecture Decision Log (DECISIONS.md)

This document records key architectural and technical decisions for **Quill**, along with the evaluated alternatives and rationale.

---

## ADR-001: Minecraft, Fabric Toolchain, and Java Versions

- **Date**: 2026-11-17
- **Status**: Accepted
- **Decision**:
  - **Minecraft**: `26.3`
  - **Fabric Loader**: `0.20.1`
  - **Fabric API**: `0.166.2+26.3`
  - **Fabric Loom**: `1.19-SNAPSHOT` (`net.fabricmc.fabric-loom`, resolved to `1.19.4`)
  - **Gradle**: `9.8.2`
  - **Java**: `JDK 25`
- **Context**:
  We need to select the target Minecraft and Fabric versions based on official Fabric sources (`https://fabricmc.net/develop/template/`, `https://docs.fabricmc.net/`, and `FabricMC/fabric-example-mod`), preferring the latest stable release supported by Fabric API.
- **Rationale**:
  1. **Latest Stable Release**: Minecraft `26.3` is the current stable release supported by both the Fabric template generator and stable Fabric API (`0.166.2+26.3`).
  2. **Unobfuscated Toolchain**: Since Minecraft `26.1`, the game is shipped unobfuscated by Mojang. Fabric Loom (`net.fabricmc.fabric-loom`) no longer requires bytecode remapping (`modImplementation` is replaced by standard Gradle `implementation`), cutting build times and simplifying debugging.
  3. **Java 25 LTS/Current Capabilities**: Minecraft `26.1+` requires Java 25 minimum. Using JDK 25 gives us modern Java features (records, sealed interfaces, pattern matching for `switch`, and clean NIO APIs) ideal for modeling editor states, AST/tokens, and network payloads.

---

## ADR-002: Mappings (Native Unobfuscated Mojang Names)

- **Date**: 2026-11-17
- **Status**: Accepted
- **Decision**: Use **official Mojang names** provided natively by unobfuscated Minecraft `26.3`.
- **Context**:
  Historically, Fabric mods chose between Yarn mappings and Official Mojang Mappings (`loom.officialMojangMappings()`).
- **Rationale**:
  Starting with Minecraft `26.1`, Mojang ships Minecraft unobfuscated and Fabric officially retired Yarn for `26.x+`. No `mappings` dependency block is needed in `build.gradle`; classes such as `net.minecraft.client.Minecraft`, `net.minecraft.client.gui.screens.Screen`, and `net.minecraft.server.MinecraftServer` use official names directly at compile time and runtime.

---

## ADR-003: UI Technology for the In-Game VS Code IDE

- **Date**: 2026-11-17
- **Status**: Proposed (Recommended: **Option A**)
- **Context**:
  Quill requires a modern, dark-themed, VS Code-inspired IDE inside Minecraft (Activity Bar, Explorer Sidebar, Editor Tabs, Code Editor with line numbers, Quick Actions/Presets, and Status Bar) that **does not pause the game** and works seamlessly in both singleplayer and future multiplayer.
  We evaluated three primary UI technology approaches against the project criteria:

### Comparison Matrix

| Criterion | Option A: Custom UI Layer on Vanilla `Screen` + `GuiGraphicsExtractor` | Option B: Embedded Chromium Browser (`MCEF` / `MCEF Modern`) + Web UI | Option C: Hybrid / Third-Party Fabric GUI Lib (`owo-lib` / `LibGui`) |
| :--- | :--- | :--- | :--- |
| **1. Compatibility with MC `26.3`** | **100% Native**. Uses vanilla `Screen`, `GuiGraphicsExtractor`, and Blaze3D primitives directly; zero external mod version lock-in. | **Fragile**. Original `MCEF` stopped at `1.21.4`; community fork `MCEF Modern` lags behind point releases and hooks low-level GL textures. | **Partial / Lagging**. `owo-lib` latest release targets `26.2` (`0.13.1+26.2`) with open issues for `26.3`; `LibGui` is designed for inventory/Cotton style screens rather than full-screen IDEs. |
| **2. Stability & Graphics Backend** | **High**. Works on Windows, macOS (Intel & Apple Silicon), and Linux (X11/Wayland) and is forward-compatible with Mojang's `26.2+` Blaze3D OpenGL/Vulkan backend transition. | **Low–Medium**. Requires native JCEF/Chromium binaries per OS/arch, frequently blocked by antivirus software, breaks on Wayland/ARM setups, and relies on raw OpenGL texture sharing deprecated in `26.2+`. | **Medium**. Third-party libraries often use custom shaders/GL state that break across minor `26.x` rendering refactors. |
| **3. License** | **MIT** (100% own code + Fabric/MC APIs). | **LGPL-3.0** (`MCEF` / `java-cef` + Chromium third-party licenses). Complicates single-JAR redistribution. | `owo-lib` is **MIT**, `LibGui` is **MIT**, but adds external transitive dependency constraints. |
| **4. Artifact & Download Size** | **Tiny (< 250 KB JAR)**. Zero external runtime downloads. | **Huge (~150–300 MB)**. Downloads full Chromium/JCEF native binaries on first launch. | **Small–Medium (~1–2 MB)** if Jar-in-Jar (`include`) is bundled. |
| **5. VS Code UI Capability** | **High (Full Control)**. Crisp 1px borders, custom dark palette (`#1e1e1e`, `#252526`, `#333333`, `#007acc`), scissor-clipped scroll panes, split panels, custom multi-line code buffer, and instant 60+ FPS rendering with zero IPC latency. | **Very High Visually** (could embed Monaco/CodeMirror), **but** suffers from input-focus edge cases (IME, `Esc`/`Ctrl` capture in LWJGL) and texture copy lag. | **Medium**. Built-in layout containers help with buttons/trees, but a custom code editor widget with line numbers, selection, and scrolling still has to be written on `GuiGraphicsExtractor` anyway. |
| **6. Multiplayer Readiness** | **Seamless**. Runs in the same JVM thread as Fabric networking (`ClientPlayNetworking`) and Brigadier `CommandDispatcher` (for future autocomplete). | **Extra Bridge Required**. Requires a Java<->JS IPC bridge (CEF message router or local WebSocket) on top of the Fabric C2S/S2C networking layer. | **Seamless**, same JVM access as Option A. |

### Evaluation Details

1. **Option A — Custom Lightweight Retained-Mode Panel/Widget Engine on Vanilla `Screen` + `GuiGraphicsExtractor` (Recommended)**:
   - Minecraft's `Screen` provides lifecycle (`init`, `resize`, `tick`, `removed`), non-pausing behavior (`isPauseScreen() -> false`), keyboard/mouse event routing (`keyPressed`, `charTyped`, `mouseClicked`, `mouseDragged`, `mouseScrolled`), and clipboard access (`Minecraft.getInstance().keyboardHandler`).
   - `GuiGraphicsExtractor` provides hardware-accelerated flat fills (`fill`), 1px borders (`renderOutline`), hardware scissor clipping (`enableScissor` / `disableScissor`), text rendering (`drawString`), and matrix transformations.
   - Because VS Code's modern flat aesthetic (`#1e1e1e` editor background, `#252526` sidebar, `#333333` activity bar, `#007acc` status bar, `#2d2d2d` inactive tabs) relies on clean flat rectangles, 1px borders, icons, and crisp typography rather than complex 9-slice textures, it maps directly and cleanly to `GuiGraphicsExtractor` primitives.
   - We build a small, focused `dev.catgirlclaude.quill.client.gui.framework` package (docked panels, scrollable tree view, tab strip, multi-line text buffer widget, modal dialog, status bar) with **zero third-party mod dependencies**.

2. **Option B — Embedded Browser (`MCEF` / `MCEF Modern`)**:
   - While embedding HTML/JS would allow reusing web components, requiring players and server admins to download a ~200 MB native Chromium binary that breaks across OSes and conflicts with Minecraft `26.2+`'s Vulkan/Blaze3D transition is disproportionate for editing `.mcfunction` files. Moreover, bridging Brigadier's live in-memory `CommandDispatcher` to an out-of-process Chromium renderer adds unnecessary complexity.

3. **Option C — Hybrid / External UI Library (`owo-lib` / `LibGui`)**:
   - `owo-lib` does not yet have a stable `26.3` release on Modrinth/Maven, which would immediately block our `26.3` build. Even if used, neither `owo-lib` nor `LibGui` provides a multi-tab code editor with gutter line numbers, cursor/selection rendering, or VS Code docking—meaning we would still write the core IDE widgets ourselves while taking on version-upgrade blocking risk.

- **Recommendation**: **Option A** — Build a clean, zero-dependency, modular UI toolkit (`QuillPanel`, `TreeView`, `TabBar`, `CodeEditorWidget`, `StatusBar`) directly on top of Minecraft `26.3`'s `Screen` and `GuiGraphicsExtractor`.

---

## ADR-004: Client/Server Separation & `EditorBackend` Interface

- **Date**: 2026-11-17
- **Status**: Accepted
- **Decision**:
  1. Enforce `loom.splitEnvironmentSourceSets()` (`src/main` vs `src/client`).
  2. Route **all** IDE file, preset, scaffolding, and reload operations through an asynchronous `EditorBackend` interface (`CompletableFuture<...>`).
  3. In MVP (Singleplayer), the client uses `LocalEditorBackend`, which talks to the integrated `MinecraftServer` instance and executes filesystem/reload operations on the server thread / IO pool—never accessing `java.nio.file.Files` directly from UI widgets.
- **Rationale**:
  - Guarantees that UI components in `src/client` make zero assumptions about local disk access or synchronous execution.
  - When multiplayer (`RemoteEditorBackend` using Fabric `CustomPacketPayload` C2S/S2C packets) is added in Phase 3, **zero UI code changes** are required.
