# Phase 0 & Phase 1 Review Report (`phase-1-review.md`)

- **Date**: 2026-11-17
- **Outcome**: ADR-003 (**Option A**), [plans/001-mvp.md](../../plans/001-mvp.md), and the **MIT** license have been approved by the user.

---

## 1. Fabric Loom Version Resolution (`1.19-SNAPSHOT` vs `1.19.4`)

- **Why `1.19-SNAPSHOT (1.19.4)` appeared in the initial report**:
  The official upstream template `FabricMC/fabric-example-mod` (`26.3` branch) declared `loom_version=1.19-SNAPSHOT` in `gradle.properties`. During Gradle project configuration, Gradle resolved that snapshot coordinate from `https://maven.fabricmc.net/` to release version **`1.19.4`** (logging `Fabric Loom: 1.19.4`).
- **Pinned configuration in repository**:
  To guarantee deterministic, reproducible builds without relying on a moving `-SNAPSHOT` pointer, the exact release version `1.19.4` is pinned:
  - [build.gradle](../../build.gradle):
    ```groovy
    plugins {
    	id 'net.fabricmc.fabric-loom' version "${loom_version}"
    	id 'maven-publish'
    }
    ```
  - [gradle.properties](../../gradle.properties):
    ```properties
    minecraft_version=26.3
    loader_version=0.20.1
    loom_version=1.19.4
    ```
- **Build verification**:
  Verified via `./gradlew build --no-daemon` on JDK 25 (`temurin-25.0.5+8`) — Gradle configures `Fabric Loom: 1.19.4` and completes with `BUILD SUCCESSFUL`.

---

## 2. Project License Confirmation

- **Approved License**: **`MIT`**
- **Files verified/updated**:
  - [LICENSE](../../LICENSE) — Standard MIT License (`Copyright (c) 2026 CatGirlClaude`).
  - [src/main/resources/fabric.mod.json](../../src/main/resources/fabric.mod.json) — `"license": "MIT"`.
  - [TODO.md](../../TODO.md) — Marked license approval task as completed (`[x]`).
  - [docs/DECISIONS.md](../DECISIONS.md) — Recorded `MIT` license approval in ADR-003 / ADR-005.

---

## 3. ADR-003 Evidence, Custom UI Effort Breakdown & Risks

See [docs/DECISIONS.md](../DECISIONS.md) (`ADR-003`) for the authoritative decision record.

### 3a) Bytecode Source Confirming `GuiGraphicsExtractor` and `Screen.extractRenderState` in MC `26.3`

- **Verified JAR Path**: `/home/antigravity/.gradle/caches/fabric-loom/26.3/minecraft-client-only.jar`
- **`javap` output for `net.minecraft.client.gui.screens.Screen`**:
  ```text
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
  ```
- **`javap` output for `net.minecraft.client.gui.GuiGraphicsExtractor`**:
  ```text
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
  }
  ```

### 3b) Custom UI Components & Effort Estimates (Option A)

| UI Component | Scope to Build from Scratch | Estimated Size | Estimated Effort |
| :--- | :--- | :--- | :--- |
| **1. Docked Layout & Scroll Container (`QuillPanel`, `ScrollViewport`)** | Region geometry calculation (Activity Bar, Sidebar, Editor, Status Bar) on resize, 2D scroll offsets, scrollbar thumb drag, `enableScissor` clipping, mouse hit-testing. | ~200–250 LOC | **Small–Medium** (~2–3 h) |
| **2. File Explorer Tree (`FileExplorerTreeWidget`)** | Flattening visible `FileTreeNode` rows, indent guides, expand/collapse toggles, active row selection, CRUD actions (open, create, rename, delete), vertical scrolling. | ~350–450 LOC | **Medium** (~4–5 h) |
| **3. Tab Bar (`EditorTabBarWidget`)** | Horizontal tab strip, active/inactive tab styling, unsaved dirty dot (`●`), close (`×`) button hit-testing, horizontal overflow scrolling. | ~180–220 LOC | **Small** (~2 h) |
| **4. Multi-Line Code Editor (`CodeEditorWidget`, `TextBuffer`)** | Line-list text buffer, line-number gutter, caret positioning & blink, mouse click/drag selection, keyboard navigation (`Arrows`, `Home`/`End`, `PageUp`/`PageDown`, `Ctrl+Left/Right`), `Shift` selection, clipboard (`Ctrl+C/X/V`), `Undo`/`Redo` stack (`Ctrl+Z/Y`), `Tab` indentation, auto-scroll to keep caret visible. | ~650–850 LOC | **Large (Highest cost)** (~8–10 h) |
| **5. Status Bar & Diagnostics Banner (`StatusBarWidget`)** | Backend mode indicator, save/reload status & latency (`ms`), expandable reload error banner with line jump, `Ln X, Col Y` indicator. | ~150–200 LOC | **Small** (~1.5–2 h) |
| **6. Quick Actions, Presets & Modal Prompts (`ModalPromptOverlay`)** | Input modal for "New Datapack", "New Namespace", "New `.mcfunction`", preset target selector (`minecraft:load`, `minecraft:tick`), delete confirmation dialog, `Identifier` validation feedback. | ~250–350 LOC | **Medium** (~3–4 h) |
| **Total Custom UI Effort (`src/client`)** | | **~1,800–2,300 LOC** | **~20–26 hours** |

### 3c) Concrete Risks of Option A & Mitigations

1. **Multi-Line Text Editor Edge Cases (High Risk)**:
   - *Risk*: Off-by-one bugs during multi-line selection deletion, CRLF vs. LF clipboard pasting, horizontal scrolling on long lines, and cursor restoration on `Undo`/`Redo`.
   - *Mitigation*: Decouple pure text manipulation (`TextDocumentBuffer`, testable with standard JUnit tests without booting Minecraft) from `CodeEditorWidget` rendering, and normalize all line endings to `\n`.
2. **Proportional Minecraft Font vs. Code Alignment (Medium Risk)**:
   - *Risk*: Minecraft's default `Font` is proportional rather than monospaced, so character columns do not have uniform pixel widths.
   - *Mitigation*: Compute all caret X positions and mouse click-to-column mappings via `font.width(line.substring(0, col))` and `font.plainSubstrByWidth(...)` rather than fixed character-width multiplication.
3. **Manual Layout Scaling Without CSS/Flexbox (Medium Risk)**:
   - *Risk*: Small game windows or high `GUI Scale` (`3x`/`4x`) can squeeze panels or clip labels.
   - *Mitigation*: Centralize layout bounds calculation in `IdeLayout`, enforce minimum panel widths, allow collapsing the sidebar (`Ctrl+B` or Activity Bar toggle), and clip every panel with `graphics.enableScissor(...)`.
4. **Input & Shortcut Capture Conflicts (Low–Medium Risk)**:
   - *Risk*: Key presses leaking to game controls or `Esc` closing the entire screen while a modal prompt is open.
   - *Mitigation*: Route `keyPressed(KeyEvent)` hierarchically so active modal overlays consume `Esc` and `Enter` before `Screen.shouldCloseOnEsc()`.
5. **Vanilla GUI Refactoring Across Future MC Releases (`26.4+`) (Low–Medium Risk)**:
   - *Risk*: Mojang periodically refactors GUI rendering classes (such as `GuiGraphics` -> `GuiGraphicsExtractor`).
   - *Mitigation*: Wrap drawing primitives in a thin helper utility so future signature changes remain localized.

---

## 4. Substantiation of Compatibility and Artifact Size Claims

- **Artifact Size**:
  - **Measured Skeleton Output**:
    - `build/libs/quill-0.1.0.jar`: **`6,618 bytes` (`6.5 KB`)**
    - `build/libs/quill-0.1.0-sources.jar`: **`4,508 bytes` (`4.5 KB`)**
  - **Full MVP Size (`< 250 KB`)**: Explicitly documented in [docs/DECISIONS.md](../DECISIONS.md) as a **hypothesis** based on bundling zero third-party libraries or native binaries (only compiled project classes and small JSON/mcfunction preset templates).
- **Compatibility Scope**:
  - Replaced absolute "100% compatibility" phrasing in [docs/DECISIONS.md](../DECISIONS.md) with accurate technical scope: Option A uses Minecraft `26.3`'s built-in `Screen` and `GuiGraphicsExtractor` APIs directly without third-party GUI mod dependencies (avoiding blockers like `owo-lib` only supporting up to `26.2`), while noting that vanilla GUI class changes in future Minecraft releases (`26.4+`) will still require routine maintenance.

---

## 5. Repository Hygiene & `.gitignore` Audit

- **`.gitignore` coverage**:
  [`.gitignore`](../../.gitignore) excludes `.gradle/`, `build/`, `out/`, `classes/`, `run/`, Eclipse (`*.launch`), IntelliJ IDEA (`.idea/`, `*.iml`, `*.ipr`, `*.iws`), VS Code (`.settings/`, `.vscode/`, `bin/`, `.classpath`, `.project`), macOS (`*.DS_Store`), and JVM crash/profiling logs (`hs_err_*.log`, `replay_*.log`, `*.hprof`, `*.jfr`).
- **Tracked files audit (`git ls-files`)**:
  Verified that zero local build artifacts, caches, or environment files are tracked in git:
  - `.agents/rules/project.md`
  - `.gitattributes`
  - `.github/workflows/build.yml`
  - `.gitignore`
  - `AGENTS.md`
  - `LICENSE`
  - `README.md`
  - `TODO.md`
  - `build.gradle`
  - `docs/ARCHITECTURE.md`
  - `docs/DECISIONS.md`
  - `docs/MC_NOTES.md`
  - `docs/reports/phase-1-review.md`
  - `gradle.properties`
  - `gradle/wrapper/gradle-wrapper.jar`
  - `gradle/wrapper/gradle-wrapper.properties`
  - `gradlew`
  - `gradlew.bat`
  - `plans/001-mvp.md`
  - `settings.gradle`
  - `src/client/java/dev/catgirlclaude/quill/client/QuillClient.java`
  - `src/client/java/dev/catgirlclaude/quill/client/mixin/QuillClientMixin.java`
  - `src/client/resources/quill.client.mixins.json`
  - `src/main/java/dev/catgirlclaude/quill/Quill.java`
  - `src/main/java/dev/catgirlclaude/quill/mixin/QuillMixin.java`
  - `src/main/resources/assets/quill/icon.png`
  - `src/main/resources/fabric.mod.json`
  - `src/main/resources/quill.mixins.json`
