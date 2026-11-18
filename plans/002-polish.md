# Plan 002: Quill UI Polish, Theme System & GUI-Scale-Independent Geometry

**Goal**: Transform Quill from a Minecraft-proportioned overlay into a crisp, desktop-grade VS Code / Datapack IDE while keeping layout rock-solid across every Minecraft GUI Scale (`1x`–`4x+`), window resolution, and zoom setting.

- **Previous plan**: [plans/001-mvp.md](001-mvp.md)
- **Architecture reference**: [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)
- **Technical decisions**: [docs/DECISIONS.md](../docs/DECISIONS.md)
- **Minecraft `26.3` API notes**: [docs/MC_NOTES.md](../docs/MC_NOTES.md)
- **Progress tracker**: [TODO.md](../TODO.md)

---

## 1. Design System: `QuillTheme` Tokens & Geometry Constants

To move away from "blocky Minecraft UI" toward a clean native IDE aesthetic, every widget must consume centralized tokens rather than ad-hoc pixel numbers or hardcoded colors.

### 1.1 Surface, Elevation & Border Tokens (`QuillColorPalette` / `QuillTheme`)

| Token Group | Current Gap | Proposed Token / Standard |
| :--- | :--- | :--- |
| **Window Frame** | Hardcoded `0x55000000` shadow + `1px` `BORDER_SUBTLE` | Subtle multi-step alpha drop shadow (`SHADOW_OUTER` / `SHADOW_INNER`) + hairline `BORDER_WINDOW` (`1px`) |
| **Pane Dividers** | Ad-hoc `hLine` / `vLine` with identical color | `BORDER_SUBTLE` (`1px` structural split), `BORDER_FOCUS` (`ACCENT_BLUE` on active pane/input), `DIVIDER_SOFT` (section separators) |
| **Dirty Indicator** | Status bar turns red/orange (`BG_STATUS_DIRTY`); tab uses dot/color | Neutral `*` suffix/prefix indicator in `TEXT_PRIMARY`/`TEXT_MUTED`; keep error red (`TEXT_ERROR`/`BG_STATUS_ERROR`) strictly for reload/validation errors |
| **Filename Colors** | Filenames tinted by extension in explorer/tabs | Always white/primary (`TEXT_PRIMARY` / `TEXT_BRIGHT`); color lives exclusively on the compact file-type prefix badge (`{}`, `fn`, etc.) |
| **Notification Toast** | Errors go to bottom status bar or modal | Top-right/top-center non-blocking VS Code-style toast banner (`BG_NOTIFICATION`, `BORDER_SUBTLE`, accent stripe, dismiss button + auto-timeout) |
| **Settings Category Rail** | Single long scroll list with no navigation | Left category rail (`150px` fixed in virtual space) with active left accent bar jumping directly to scroll anchor (`Appearance`, `Gameplay`, `Templates`, `Layout`, `Keybindings`) |

### 1.2 Spacing & Typography Rhythm (`QuillMetrics`)

Centralize geometry constants into `QuillTheme` (or a nested `QuillMetrics` record) so widgets share one visual cadence:

- **Chrome heights**: `TITLE_BAR_HEIGHT = 24`, `TAB_BAR_HEIGHT = 24`, `BREADCRUMB_HEIGHT = 18`, `STATUS_BAR_HEIGHT = 18`, `EXPLORER_ROW_HEIGHT = 17` (up from tight `15px` so glyphs + `_` descenders + chevrons have clean breathing room).
- **Padding & gaps**: `PAD_XS = 2`, `PAD_SM = 4`, `PAD_MD = 8`, `PAD_LG = 12`, `SCROLLBAR_SIZE = 5` (auto-dimmed when idle, brightened on hover).
- **Typography parity**:
  - Remove `* ` prefix from active Theme and Font Family cards (selection is indicated cleanly by `ROW_SELECTED` background + `ACCENT_BLUE` border + subtle `"Active"` pill badge if desired).
  - Normalize `FontChoice` preview rendering so `"Minecraft Default"` and `"Minecraft Uniform"` render at matching visual cap-height (~`7–8px` scaled compensation for `uniform`'s tiny `0.5x` grid) and identical high contrast (`TEXT_PRIMARY` instead of `TEXT_MUTED`).

### 1.3 Vector Graphics & Iconography

- **Explorer Chevrons**: Replace ASCII `>` / `v` with crisp Codicons-style geometric chevrons drawn via geometry or high-DPI `16x16` / `32x32` linear-filtered icon textures (`stroke = 1.25px`, `45°` arms, collapsed pointing right, expanded pointing down), scaled cleanly to row height.
- **UI & File-Type Icons**: Render Search loupe, Function (`fx`/`fn` wave-block icon), JSON (`{}` with calibrated spacing — pending decision), Folder, Settings gear, and Close/Maximize glyphs cleanly via crisp vector/bitmap primitives rather than ASCII stand-ins.

---

## 2. GUI-Scale-Independent `IdeLayout` Geometry

### Root Cause of Current Layout Breakage

Currently `Screen.width` and `Screen.height` in Minecraft `26.3` are already divided by `window.getGuiScale()` (`1, 2, 3, 4...`). On top of that, `QuillIdeScreen` applies `graphics.pose().scale(uiScale, uiScale)` where `uiScale = cfg.textSize() / 9.0f`:
- At **MC GUI Scale `4` on `1920x1080`**, `Screen.width = 480`, `Screen.height = 270`. At `uiScale = 1.0`, Quill looks oversized and covers almost the entire screen; zooming in (`1.5x+`) shrinks the virtual viewport to `320x180`, triggering `MIN_WINDOW_HEIGHT = 200` and collapsing/overlapping the sidebar and Settings grid.
- At **MC GUI Scale `1` on `1920x1080`**, `Screen.width = 1920`, `Screen.height = 1080`. The IDE becomes minuscule unless zoomed up to `16px`.

### Proposed GUI-Scale-Independent Formula

Anchor Quill's virtual coordinate space to **physical framebuffer pixels** (`window.getWidth()`, `window.getHeight()`) normalized to a crisp desktop baseline (`TARGET_DENSITY = 2.0f`, representing comfortable `~960x540` virtual space on a `1080p` display):

```text
mcGuiScale     = max(1, minecraft.getWindow().getGuiScale())
userZoom       = cfg.zoomFactor()                  // independent of MC GUI Scale
effectiveScale = (TARGET_DENSITY * userZoom) / mcGuiScale
virtualWidth   = round(Screen.width  / effectiveScale)
virtualHeight  = round(Screen.height / effectiveScale)
```

Properties of this formula:
1. Changing Minecraft's `GUI Scale` (`1x`, `2x`, `3x`, `4x`, `Auto`) **cancels out cleanly** — Quill keeps the exact same screen proportion, readable font density, sidebar width, and window bounds!
2. `IdeLayout.compute(...)` clamps floating/docked window rectangles cleanly within `[minWindowWidth, virtualWidth] x [minWindowHeight, virtualHeight]` and enforces responsive breakpoints (e.g., Settings 1-column vs 2-column grid, auto-hiding optional status segments on very small windows `< 640px` physical equivalent).
3. Custom window bounds (`windowX/Y/W/H`) remain stored in **physical pixels** so switching MC GUI Scale or zoom never jumps or clips the window off-screen.
4. **Pending Decision (Zoom Out)**: Determine whether to expand zoom-out range (e.g., down to `0.6x` now that baseline GUI Scale is normalized) or replace fine zoom-out with discrete comfortable density presets (`Compact / Default / Comfortable` or `0.75x–1.5x`).

---

## 3. Vertical Slice Delivery Plan

Each slice produces a self-contained, testable improvement with unit tests and a Conventional Commit.

### Slice 1 — Core Theme Tokens, GUI-Scale-Independent `IdeLayout` & Animation State
- **Scope**:
  - Normalize `QuillIdeScreen` scaling against `Window.getGuiScale()` so MC GUI Scale changes leave layout intact.
  - Add responsive layout tests in `IdeLayoutTest` verifying layout invariants across simulated MC GUI Scales `1..4` and zoom steps.
  - Add `QuillMetrics` constants (`EXPLORER_ROW_HEIGHT = 17`, etc.) and `QuillClientConfig` global `animationsEnabled` toggle + animation interpolator utility.
- **Touches**: `QuillTheme`, `IdeLayout`, `QuillClientConfig`, `QuillIdeScreen`.

### Slice 2 — Vector Icons, Explorer Visuals, File-Type Notifications & Compact Status Bar
- **Scope**:
  - Geometric VS Code Codicons chevrons (`>` / `v`), crisp Search/Function/File-type icons, white filenames with colored type prefixes, neutral `*` dirty markers across tabs/sidebar, compact bottom Status Bar.
  - Visual parity fix for Font Family previews (equal cap-height & `TEXT_PRIMARY` contrast; remove `* `).
  - Top VS Code-style notification banner for visible unsupported file types (pending approved type matrix & message text).
  - Calibrated `{}` JSON badge spacing (pending approved spacing option).
- **Touches**: `QuillDraw`, `FileExplorerTreeWidget`, `EditorTabBarWidget`, `SettingsPanelWidget`, `SearchSidebarWidget`, `QuillIdeScreen`.

### Slice 3 — Top-Anchored Command Palette & Quick Open
- **Scope**:
  - Drop down `QuickPickOverlay` seamlessly from the top Command Center bar (`width` tied to top bar/window, anchored below title bar instead of full-screen modal).
  - Remove redundant shortcut pills already shown elsewhere (e.g. `CTRL+P` beside Command Center).
  - Optional smooth slide-in/fade when animations are enabled.
- **Touches**: `QuickPickOverlay`, `QuillIdeScreen`.

### Slice 4 — Settings Category Navigation, Extra Color Themes & Configurable Undo/Redo Shortcuts
- **Scope**:
  - Replace single monolithic Settings header with a left category rail (`Appearance`, `Behavior`, `Templates`, `Window & Scale`, `Keybindings`) that scrolls smoothly/jumps to the selected section.
  - Add additional curated VS Code color palettes (`GitHub Dark`, `Catppuccin Mocha`, `Nord`, `Monokai`, `One Light`, etc.) to `QuillColorPalette`.
  - Expose `UNDO` (`Ctrl+Z`) and `REDO` (`Ctrl+Shift+Z`) in `QuillClientConfig.ShortcutAction`.
- **Touches**: `QuillColorPalette`, `QuillClientConfig`, `SettingsPanelWidget`.

### Slice 5 — Explorer Cleanup, Context Menu & Keyboard File Ops (`F2`, `Delete`, `Ctrl+C/X/V`)
- **Scope**:
  - Remove `+File` / `+Dir` toolbar button clutter from the Explorer header cleanly once context menu + shortcuts cover all actions.
  - Right-click context menu on files and folders (`Run function`, `Open to side`, `Reveal in file explorer`, `Find file references`, `Cut`, `Copy`, `Copy path`, `Delete`, `Rename`) respecting server vs local capability safely.
  - Keyboard shortcuts when Explorer has focus: `F2`, `Delete`, `Ctrl+C`, `Ctrl+X`, `Ctrl+V` + Explorer undo stack (`Ctrl+Z` outside editor).
- **Touches**: `FileExplorerTreeWidget`, `ClientEditorSession`, `QuillIdeScreen`, reusable `ContextMenuOverlay`.

### Slice 6 — Code Editor & Tab Context Menus, `Ctrl+F`, `Alt+Z`, `Shift+Scroll`, `Ctrl+Click`
- **Scope**:
  - Right-click context menu in editor (`Cut`, `Copy`, `Paste`, navigation items) working directly over selected/hovered text.
  - Right-click context menu on editor tabs (`Pin`, `Close`, `Close all but this`, `Close all`, file actions excluding `Cut` & `Delete`, retaining `Copy path`).
  - `Ctrl+F` in-file find bar inside `CodeEditorWidget`, `Alt+Z` soft word-wrap toggle, `Shift + Mouse Wheel` horizontal scrolling.
  - Separate feature step: `Ctrl+Click` go-to-definition (`scoreboard`, `tag`, `storage` usage list via `WorkspaceSymbolIndex`; vanilla commands linked to documentation URLs).
- **Touches**: `CodeEditorWidget`, `EditorTabBarWidget`, `WorkspaceSymbolIndex`, `QuillIdeScreen`.

### Slice 7 — Search `Include`/`Exclude` Filters & Output Console
- **Scope**:
  - Collapsible `Files to include` / `Files to exclude` glob inputs hidden by default behind a toggle button in `SearchSidebarWidget`.
  - Selectable/copyable text in `BottomPanelWidget` (`Output` tab) + live Minecraft log stream & player command input (pending scope decision).
- **Touches**: `SearchSidebarWidget`, `WorkspaceSearchEngine`, `BottomPanelWidget`, `ClientEditorSession`.

### Slice 8 — Client-Side `/quill` Command Tree
- **Scope**:
  - Register `/quill` client-only command tree via Fabric API `ClientCommandRegistrationCallback` & `ClientCommands` (`fabric-command-api-v2` `3.1.2`), verified against `26.3` bytecode.
  - Opens specific IDE views/tabs without sending packets to the server (pending approved subcommand list).
- **Touches**: `QuillClient`, `QuillClientCommands`, `QuillIdeScreen`.
