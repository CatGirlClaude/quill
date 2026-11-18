# Architecture Decision Log (DECISIONS.md)

This document records key architectural and technical decisions for **Quill**, along with the evaluated alternatives, empirical measurements, effort estimates, and risks.

---

## ADR-001: Minecraft, Fabric Toolchain, and Java Versions

- **Date**: 2026-11-17
- **Status**: Accepted
- **Decision**:
  - **Minecraft**: `26.3`
  - **Fabric Loader**: `0.20.1`
  - **Fabric API**: `0.166.2+26.3`
  - **Fabric Loom**: `1.19.4` (plugin `id 'net.fabricmc.fabric-loom' version "${loom_version}"` in `build.gradle`, with `loom_version=1.19.4` pinned in `gradle.properties`)
  - **Gradle**: `9.8.2`
  - **Java**: `JDK 25`
- **Context**:
  We need to select the target Minecraft and Fabric versions based on official Fabric sources (`https://fabricmc.net/develop/template/`, `https://docs.fabricmc.net/`, and `FabricMC/fabric-example-mod`), preferring the latest stable release supported by Fabric API.
  Note on Loom versioning: Upstream `FabricMC/fabric-example-mod` (`26.3` branch) specifies `loom_version=1.19-SNAPSHOT`, which resolves from `https://maven.fabricmc.net/` to release `1.19.4`. To ensure reproducible builds, we pinned `loom_version=1.19.4` in `gradle.properties`.
- **Rationale**:
  1. **Latest Stable Release**: Minecraft `26.3` is the current stable release supported by both the Fabric template generator and stable Fabric API (`0.166.2+26.3`).
  2. **Unobfuscated Toolchain**: Since Minecraft `26.1`, the game is shipped unobfuscated by Mojang. Fabric Loom (`net.fabricmc.fabric-loom`) no longer requires bytecode remapping (`modImplementation` is replaced by standard Gradle `implementation`), cutting build times and simplifying debugging.
  3. **Java 25 Requirement**: Minecraft `26.1+` requires Java 25 minimum. Using JDK 25 gives us modern Java features (records, sealed interfaces, pattern matching for `switch`, and clean NIO APIs) ideal for modeling editor states and network payloads.

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
- **Status**: Accepted (**Option A** — approved during [Phase 1 Review](reports/phase-1-review.md))
- **Context**:
  Quill requires a modern, dark-themed, VS Code-inspired IDE inside Minecraft (Activity Bar, Explorer Sidebar, Editor Tabs, Code Editor with line numbers, Quick Actions/Presets, and Status Bar) that **does not pause the game** and works in both singleplayer and future multiplayer.

### 1. Bytecode Verification of Minecraft `26.3` GUI APIs

Verified via `javap` against the local Loom-cached client JAR:
`~/.gradle/caches/fabric-loom/26.3/minecraft-client-only.jar`

```text
$ ~/.jdks/temurin-25/bin/javap -cp ~/.gradle/caches/fabric-loom/26.3/minecraft-client-only.jar \
    net.minecraft.client.gui.screens.Screen
Compiled from "Screen.java"
public abstract class net.minecraft.client.gui.screens.Screen
    extends net.minecraft.client.gui.components.events.AbstractContainerEventHandler
    implements net.minecraft.client.gui.components.Renderable {
  ...
  public final void extractRenderStateWithTooltipAndSubtitles(net.minecraft.client.gui.GuiGraphicsExtractor, int, int, float);
  public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor, int, int, float);
  public boolean keyPressed(net.minecraft.client.input.KeyEvent);
  public void onClose();
  public final void init(int, int);
  protected void init();
  public boolean isPauseScreen();
}

$ ~/.jdks/temurin-25/bin/javap -cp ~/.gradle/caches/fabric-loom/26.3/minecraft-client-only.jar \
    net.minecraft.client.gui.GuiGraphicsExtractor
Compiled from "GuiGraphicsExtractor.java"
public class net.minecraft.client.gui.GuiGraphicsExtractor {
  public net.minecraft.client.gui.GuiGraphicsExtractor(net.minecraft.client.Minecraft, net.minecraft.client.renderer.state.gui.GuiRenderState, int, int);
  public void requestCursor(com.mojang.blaze3d.platform.cursor.CursorType);
  public void applyCursor(com.mojang.blaze3d.platform.Window);
  public int guiWidth();
  public int guiHeight();
  public org.joml.Matrix3x2fStack pose();
  public void enableScissor(int, int, int, int);
  public void disableScissor();
  public boolean containsPointInScissor(int, int);
  public void horizontalLine(int, int, int, int);
  public void verticalLine(int, int, int, int);
  public void fill(int, int, int, int, int);
  public void fillGradient(int, int, int, int, int, int);
  public void outline(int, int, int, int, int);
  public void textHighlight(int, int, int, int, boolean);
  public void text(net.minecraft.client.gui.Font, java.lang.String, int, int, int);
  public void text(net.minecraft.client.gui.Font, java.lang.String, int, int, int, boolean);
  ...
}
```

### 2. Comparison Matrix

| Criterion | Option A: Custom UI Layer on Vanilla `Screen` + `GuiGraphicsExtractor` | Option B: Embedded Chromium Browser (`MCEF` / `MCEF Modern`) + Web UI | Option C: Hybrid / Third-Party Fabric GUI Lib (`owo-lib` / `LibGui`) |
| :--- | :--- | :--- | :--- |
| **1. Compatibility with MC `26.3`** | **Direct Vanilla API Usage**. Uses `Screen` and `GuiGraphicsExtractor` directly from `minecraft-client-only.jar` without waiting for third-party GUI mod ports. However, future MC updates (`26.4+`) may still refactor vanilla GUI classes. | **Fragile**. Original `MCEF` stopped at `1.21.4`; community fork `MCEF Modern` lags behind point releases and hooks low-level GL textures. | **Blocked on `26.3`**. `owo-lib`'s latest release on Modrinth/GitHub targets `26.2` (`0.13.1+26.2`) with an open issue for `26.3`; `LibGui` targets inventory-style screens rather than full-screen IDEs. |
| **2. Stability & Graphics Backend** | **High within `26.3`**. Uses Mojang's `renderpearl` / `GuiRenderState` pipeline via `GuiGraphicsExtractor` rather than raw OpenGL calls, aligning with Mojang's `26.2+` OpenGL/Vulkan backend transition. | **Low–Medium**. Requires native JCEF/Chromium binaries per OS/arch, frequently blocked by antivirus software, breaks on some Wayland/ARM setups, and relies on native GL texture sharing. | **Medium**. Third-party libraries often use custom shaders/pipelines that require updates across minor `26.x` rendering refactors. |
| **3. License** | **MIT** (100% project code + Fabric/MC APIs; approved by user). | **LGPL-3.0** (`MCEF` / `java-cef` + Chromium third-party notices). Complicates single-JAR redistribution. | `owo-lib` is **MIT**, `LibGui` is **MIT**, but adds transitive dependency maintenance. |
| **4. Artifact & Download Size** | **Measured Skeleton: `6,618 bytes` (`6.5 KB`)** (`build/libs/quill-0.1.0.jar`). **Hypothesis for full MVP**: `< 250 KB` because zero external libraries or native binaries are bundled (pure Java classes + small JSON/preset text assets). | **Huge (~150–300 MB)**. Downloads full Chromium/JCEF native binaries on first launch. | **Small–Medium (~1–2 MB)** if Jar-in-Jar (`include`) is bundled. |
| **5. VS Code UI Capability** | **High Visual Control, High Implementation Effort**. Flat panels (`fill`), 1px borders (`outline`), clipping (`enableScissor`), and native cursor switching (`requestCursor`) allow an authentic VS Code Dark+ look, but every widget must be coded by us. | **Very High Visually** (can embed Monaco/CodeMirror), **but** suffers from input-focus edge cases (IME, `Esc`/`Ctrl` capture in SDL/LWJGL) and texture copy latency. | **Medium**. Built-in layout containers help with buttons/dialogs, but a multi-tab code editor with line numbers and selection still has to be written manually on `GuiGraphicsExtractor`. |
| **6. Multiplayer Readiness** | **Direct**. Runs in the same JVM thread as Fabric networking (`ClientPlayNetworking`) and Brigadier `CommandDispatcher`. | **Extra Bridge Required**. Requires a Java<->JS IPC bridge on top of Fabric C2S/S2C networking. | **Direct**, same JVM access as Option A. |

### 3. UI Components Required From Scratch in Option A & Effort Estimates

Choosing Option A means we do not get browser DOM/CSS or pre-built tree/code widgets. We must build and test the following components ourselves:

| Component | Responsibilities | Estimated Size (LOC) | Estimated Effort |
| :--- | :--- | :--- | :--- |
| **1. Layout & Scroll Container (`QuillPanel`, `ScrollViewport`)** | Docked region calculation (Activity Bar, Sidebar, Editor, Status Bar) on window resize, 2D scroll offsets, scrollbar thumb dragging, `enableScissor` clipping, mouse hit-testing. | ~200–250 LOC | **Small–Medium** (~2–3 hours) |
| **2. File Explorer Tree (`FileExplorerTreeWidget`)** | Recursive `FileTreeNode` flattening for visible expanded rows, indent guides, folder chevron toggle, selection highlight, inline/toolbar actions (open, create, rename, delete), vertical scrolling. | ~350–450 LOC | **Medium** (~4–5 hours) |
| **3. Tab Bar (`EditorTabBarWidget`)** | Horizontal tab strip, active vs. inactive tab styling, unsaved dirty dot (`●`), close (`×`) button hit-testing, horizontal overflow scrolling when many tabs are open. | ~180–220 LOC | **Small** (~2 hours) |
| **4. Multi-Line Code Editor (`CodeEditorWidget`, `TextBuffer`)** | Gap/line list text model, gutter with right-aligned line numbers, caret positioning & blinking, mouse click/drag selection, keyboard navigation (`Arrows`, `Home`/`End`, `PageUp`/`PageDown`, `Ctrl+Left/Right`), `Shift` selection, clipboard (`Ctrl+C/X/V`), undo/redo stack (`Ctrl+Z/Y`), `Tab` indent, horizontal & vertical scroll keeping caret in view. | ~650–850 LOC | **Large (Highest effort)** (~8–10 hours) |
| **5. Status Bar & Diagnostics Banner (`StatusBarWidget`)** | Displays active backend mode, save/reload status & duration, collapsible reload error list with clickable line jump, current `Ln X, Col Y` cursor position. | ~150–200 LOC | **Small** (~1.5–2 hours) |
| **6. Quick Actions, Presets & Modal Prompts (`ModalPromptOverlay`)** | Input modal for "New Datapack", "New Namespace", "New `.mcfunction`", and preset target selector (`minecraft:load`, `minecraft:tick`), delete confirmation dialog, `Identifier` validation feedback. | ~250–350 LOC | **Medium** (~3–4 hours) |
| **Total Custom UI Effort** | Complete MVP IDE frontend in `src/client` | **~1,800–2,300 LOC** | **~20–26 hours** |

### 4. Concrete Risks & Limitations of Option A (and Mitigations)

1. **Text Editor Edge-Case Complexity (High Risk)**:
   - *Risk*: Building a multi-line text editor from scratch is notoriously prone to off-by-one bugs in multi-line selection deletion, clipboard pasting with CRLF vs LF line endings, horizontal scrolling past long lines, and undo/redo cursor restoration.
   - *Mitigation*: Separate the pure data model (`TextDocumentBuffer` in pure Java, unit-testable with JUnit without launching Minecraft) from rendering (`CodeEditorWidget`), and normalize all line endings to `\n` on load/paste.
2. **Proportional Minecraft Font vs. Code Alignment (Medium Risk)**:
   - *Risk*: Minecraft's default `Font` is proportional (not monospaced), which means columns do not align vertically by character index, and converting mouse `(x, y)` clicks to `(line, column)` requires calling `font.plainSubstrByWidth(...)` / `font.width(substring)` rather than dividing by a constant character width.
   - *Mitigation*: Always compute caret X coordinates and mouse hit-testing via `font.width(line.substring(0, col))` and `font.plainSubstrByWidth(...)`. (Optionally, a monospaced font resource can be evaluated later if desired.)
3. **Manual Responsive Layout & GUI Scale Handling (Medium Risk)**:
   - *Risk*: Without CSS/Flexbox, small window sizes or high Minecraft GUI Scale settings (`GUI Scale 3` or `4`) can cause sidebar and editor panels to overlap or clip text if minimum dimensions are not enforced.
   - *Mitigation*: Centralize layout rectangle math in a single `IdeLayout.compute(width, height, sidebarCollapsed)` method, enforce minimum panel widths, allow collapsing the sidebar (`Ctrl+B` or Activity Bar click), and clip every panel strictly with `graphics.enableScissor(...)`.
4. **Keyboard Shortcut & Input Capture Conflicts (Low–Medium Risk)**:
   - *Risk*: Pressing `E` (inventory) or movement keys while typing in the editor, or pressing `Esc` when a modal dialog is open vs. when the main IDE is open.
   - *Mitigation*: Because `QuillIdeScreen` extends `Screen`, Minecraft routes key events to `Screen.keyPressed(KeyEvent)` instead of in-world movement/inventory binds while the screen is focused. Override `keyPressed` so `Esc` closes an active modal first before closing the IDE.
5. **Vanilla GUI Refactoring in Future MC Versions (`26.4+`) (Low–Medium Risk)**:
   - *Risk*: Mojang actively refactors GUI rendering across `26.x` releases (e.g., `GuiGraphics` -> `GuiGraphicsExtractor` in `26.2`/`26.3`).
   - *Mitigation*: Isolate direct `GuiGraphicsExtractor` calls inside thin helper methods (`QuillDraw`) so any future method rename only touches one class.

- **Recommendation**: **Option A** — Despite the higher upfront effort to implement `CodeEditorWidget` and `FileExplorerTreeWidget` (~2,000 LOC total), Option A avoids the ~200 MB Chromium download and OS/driver fragility of MCEF (Option B) and avoids being blocked by third-party library update delays for `26.3` (Option C).

---

## ADR-004: Client/Server Separation & `EditorBackend` Interface

- **Date**: 2026-11-17
- **Status**: Accepted
- **Decision**:
  1. Enforce `loom.splitEnvironmentSourceSets()` (`src/main` vs `src/client`).
  2. Route **all** IDE file, preset, scaffolding, and reload operations through an asynchronous `EditorBackend` interface (`CompletableFuture<...>`).
  3. In MVP (Singleplayer), the client uses `ServerEditorBackend`, which talks to the integrated `MinecraftServer` instance and executes filesystem/reload operations on the server thread / IO pool—never accessing `java.nio.file.Files` directly from UI widgets.
- **Rationale**:
  - Guarantees that UI components in `src/client` make zero assumptions about local disk access or synchronous execution.
  - When multiplayer (`RemoteEditorBackend` using Fabric `CustomPacketPayload` C2S/S2C packets) is added in Phase 3, UI code does not need structural changes.

---

## ADR-005: Post-Reload Explicit Syntax Diagnostics (`DatapackReloadCoordinator`)

- **Date**: 2026-11-17
- **Status**: Accepted
- **Context**:
  In Minecraft `26.3`, `ServerFunctionLibrary.reload(...)` catches and logs per-`.mcfunction` syntax exceptions internally without failing the `CompletableFuture<Void>` returned by `MinecraftServer.reloadResources(...)`.
- **Decision**:
  After `PackRepository.reload()` and `server.reloadResources(...)` complete on the server thread, `DatapackReloadCoordinator` explicitly re-parses enabled directory datapacks in `<world>/datapacks/` using `CommandFunction.fromLines(...)` (with a `GAMEMASTER` compilation source) and Gson `JsonParser.parseString(...)`, capturing parse exceptions and extracted line numbers into `List<ReloadDiagnostic>`.
- **Rationale**:
  - Avoids brittle Log4j appender interception or Mixin hooks into vanilla `ServerFunctionLibrary`.
  - Guarantees deterministic, file-anchored, line-numbered error banners with click-to-jump navigation inside `QuillIdeScreen`.

---

## ADR-006: Pure-Java Client Model Separation for Unit Testing

- **Date**: 2026-11-17
- **Status**: Accepted
- **Context**:
  Fabric Loom's `splitEnvironmentSourceSets()` isolates `src/client` from `src/test` by default, while `CodeEditorWidget` and `IdeLayout` contain critical cursor, selection, undo/redo, CRLF normalization, and viewport math prone to off-by-one regressions.
- **Decision**:
  Keep `TextDocumentBuffer` and `IdeLayout` free of client-only Minecraft/LWJGL class references (`java.*` + `QuillTheme` constants only) and expose `sourceSets.client.output` to `sourceSets.test` in `build.gradle`.
- **Rationale**:
  - Enables fast JUnit 5 regression testing (`./gradlew test`) for editor editing primitives and layout math in headless CI/VMs without spinning up OpenGL/LWJGL.

---

## ADR-007: Explicit SDL3 `TextInputManager` Lifecycle on Custom `Screen` (`QuillIdeScreen`)

- **Date**: 2026-11-17
- **Status**: Accepted
- **Context**:
  Minecraft `26.3` uses SDL3 (`org.lwjgl.sdl.SDLKeyboard`) via `com.mojang.blaze3d.platform.TextInputManager`. Unlike legacy GLFW `glfwSetCharCallback` (which always fired `charTyped`), SDL3 suppresses `SDL_EVENT_TEXT_INPUT` (`KeyboardHandler.textInput` -> `Screen.charTyped(CharacterEvent)`) unless `SDLKeyboard.SDL_StartTextInput(...)` is enabled via `minecraft.textInputManager().startTextInput(owner)`. Only `KeyEvent` (`Backspace`, `Enter`, `Home`, `End`, arrows) fires without active text input, preventing character typing in custom-drawn text inputs (`ModalPromptOverlay` fields and `CodeEditorWidget`) that do not wrap vanilla `EditBox`.
- **Decision**:
  Keep Quill's custom VS Code-styled `ModalPromptOverlay` and `CodeEditorWidget` inputs (consistent with ADR-003 Option A), and manage SDL3 text input directly in `QuillIdeScreen` via a small `TextInputBridge` seam delegating to `minecraft.textInputManager().startTextInput(this)` / `stopTextInput(this)` plus overriding `Screen.isInputCaptured()`:
  - Enable text input when `activeModal != null && activeModal.hasTextFields()`, or when `activeModal == null && session.activeTab() != null`.
  - Synchronize state on `init()`, `tick()`, `extractRenderState(...)`, `openModal(...)`, and `closeModal()`, and unconditionally stop text input in `removed()`.
- **Rationale**:
  - Preserves exact VS Code Dark+ styling and multi-line/modal ergonomics without mixing vanilla `EditBox` widgets into custom scissor-clipped panels.
  - Prevents global keybindings (e.g., `options.keyFriends`) from intercepting characters via `Screen.isInputCaptured()`.
  - Enables headless JUnit verification (`QuillIdeScreenTextInputTest`) through `TextInputBridge` without initializing LWJGL/SDL3 windows.

---

## ADR-008: Layered 24 KB Chunked RPC Protocol, Optimistic Concurrency, and Dedicated Server ACL (`quill-acl.json`)

- **Date**: 2026-11-18
- **Status**: Accepted
- **Context**:
  Phase 3 multiplayer editing requires sending up to `512 KB` `.mcfunction`/JSON files and `5,000`-node file trees across Minecraft `26.3` networking while resisting malformed packets, memory exhaustion, concurrent edit overwrites, and unauthorized edits on dedicated servers. Bytecode verification showed that `ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE` is `32,767` bytes (far below `MAX_FILE_SIZE_BYTES = 524,288`), while `ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE` is `1,048,576` bytes.
- **Decision**:
  1. **Unified 24 KB Chunked Framing (`quill:c2s_frame`, `quill:s2c_frame`) + Broadcast (`quill:s2c_tree_changed`)**:
     - Rather than registering 20+ separate `CustomPacketPayload` types (each still hitting the `32,767`-byte C2S cap for large files), serialize versioned (`v1`) sealed `QuillRpcRequest` / `QuillRpcResponse` messages via `QuillProtocolCodec` and frame them in `24 KB` chunks through `ChunkedTransferAssembler`.
     - Do **not** apply client-controlled gzip/deflate compression on C2S frames, avoiding decompression bomb risks on the server thread while keeping framing simple and strictly bounded (`MAX_MESSAGE_BYTES = 1 MB`, `MAX_CONCURRENT_TRANSFERS_PER_PLAYER = 4`, `MAX_PENDING_TRANSFER_BYTES_PER_PLAYER = 2 MB`, `30s` eviction timeout).
  2. **Optimistic Concurrency via SHA-256 Revision Hashes**:
     - Extend `EditorBackend.writeFile(...)` with `expectedBaseSha256` (keeping the 3-arg `default` method for backward compatibility). `ServerEditorBackend` synchronizes writes per `datapacks/` root and throws `RevisionConflictException` on mismatch, which the client surfaces in a 3-button modal (**Overwrite** / **Reload** / **Cancel**).
  3. **Dedicated File-Backed ACL (`config/quill-acl.json`) + Rate Limiting**:
     - Auto-create `config/quill-acl.json` on `SERVER_STARTING` and hot-reload on file mtime change so server admins can grant non-OP builders access or deny specific OP accounts without restarting the server (`explicit deny > explicit allow > OP level >= 2`).
     - Enforce a per-player token bucket (`120` req/min default, burst `40`) and global reload cooldown (`500 ms` default) in `ServerRateLimiter`.
- **Rationale**:
  - Guarantees every C2S and S2C packet stays comfortably within Minecraft `26.3`'s hard custom payload limits and proxy caps without requiring mixins or custom Netty handlers.
  - Preserves ADR-004's single `EditorBackend` boundary and enables full two-client-to-server wire-format loopback integration tests (`MultiplayerLoopbackIntegrationTest`) in headless JUnit without launching a live server.
