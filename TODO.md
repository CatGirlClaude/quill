# TODO — Quill Project Tracker

Single source of truth for project progress, planned tasks, optional feature proposals, and incoming ideas.

## Phase 0: Repository & Toolchain Setup

- [x] Inspect environment (`git 2.39.5`, system OpenJDK 17) and install JDK 25 (`temurin-25.0.5+8`) in user space (`~/.jdks/temurin-25`).
- [x] Confirm project metadata with user (`modid=quill`, `group=dev.catgirlclaude`, `package=dev.catgirlclaude.quill`, `author=CatGirlClaude`).
- [x] Confirm and approve project license with user (`MIT` in `LICENSE` and `fabric.mod.json`).
- [x] Generate Fabric `26.3` project skeleton with split `main` and `client` source sets (`loom.splitEnvironmentSourceSets()`).
- [x] Configure `.gitignore`, `.gitattributes`, `LICENSE`, `fabric.mod.json`, and mixin configs.
- [x] Verify `./gradlew build` and `./gradlew runClient` tasks on pinned Fabric Loom `1.19.4`.
- [x] Create initial local commit for Phase 0 and push to remote repository.

## Phase 1: Architecture, Decisions & Documentation

- [x] Evaluate UI technologies (`Screen` + `GuiGraphicsExtractor` vs MCEF/MCEF Modern vs Hybrid/owo-lib) and document recommendation in [docs/DECISIONS.md](docs/DECISIONS.md).
- [x] Create [AGENTS.md](AGENTS.md) and Antigravity workspace pointer [`.agents/rules/project.md`](.agents/rules/project.md).
- [x] Create [README.md](README.md).
- [x] Create [TODO.md](TODO.md).
- [x] Create [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (module boundaries, `EditorBackend` interface, multiplayer plan, server security).
- [x] Create [docs/DECISIONS.md](docs/DECISIONS.md) (MC `26.3`, official Mojang mappings, custom `Screen` UI engine).
- [x] Create [docs/MC_NOTES.md](docs/MC_NOTES.md) (MC `26.3` datapack folder names, `pack.mcmeta` `min_format`/`max_format`, unobfuscated Loom, GUI rendering, and `Local testing` guide).
- [x] Create [plans/001-mvp.md](plans/001-mvp.md) (vertical-slice MVP implementation plan).
- [x] Record Phase 1 review answers in [docs/reports/phase-1-review.md](docs/reports/phase-1-review.md) and obtain user approval for ADR-003 (Option A) and [plans/001-mvp.md](plans/001-mvp.md).
- [x] Document local testing workflow (`git clone` once, `git pull`, `./gradlew test`, `./gradlew runClient` across Windows/macOS/Linux) in [docs/MC_NOTES.md](docs/MC_NOTES.md) and remove hardcoded VM user paths.

## Phase 2: MVP Implementation (Completed — see [plans/001-mvp.md](plans/001-mvp.md))

- [x] **Step 1 — Backend Core & Security (`src/main`)**: Define `EditorBackend` contract, DTOs, `PathSandbox` security validator, and `LocalEditorBackend` for singleplayer integrated server.
- [x] **Step 2 — Preset & Scaffolding Engine (`src/main`)**: Implement `pack.mcmeta` generator (`min_format: 121, max_format: 121`), namespace/function scaffolding, and resource-driven presets (`minecraft:load`, `minecraft:tick`).
- [x] **Step 3 — Non-Pausing VS Code Shell & Keybinding (`src/client`)**: Register keybinding (`K` / configurable) to open `QuillIdeScreen` (`isPauseScreen() == false`) with dark VS Code layout (Activity Bar, Sidebar, Editor Area, Status Bar).
- [x] **Step 4 — World & Datapack File Explorer (`src/client`)**: Build interactive tree view for `datapacks/` supporting open, create file/folder, rename, and delete actions via `EditorBackend`.
- [x] **Step 5 — Quick Actions & Presets UI (`src/client`)**: Wire "New Datapack", "New Namespace", "New `.mcfunction`", and `data/minecraft` preset insertion (`load.json` / `tick.json`) in the IDE UI.
- [x] **Step 6 — Multi-Tab `.mcfunction` Editor, Save & Live Reload (`src/client` & `src/main`)**: Multi-line text editor with line numbers, dirty-state tabs, `Ctrl+S` save, automatic datapack reload, and reload result/error feedback in the status bar / diagnostics bar.
- [x] **Bugfix — MC `26.3` SDL3 Text Input Capture (`QuillIdeScreen`)**: Activate `TextInputManager.startTextInput(this)` / `stopTextInput(this)` and override `isInputCaptured()` on `QuillIdeScreen` whenever a text-field `ModalPromptOverlay` or code tab is active so printable `CharacterEvent` typing works in MC `26.3`'s SDL3 backend (verified by `QuillIdeScreenTextInputTest`).

## Phase 3: Multiplayer Networking (Completed)

- [x] Implement `CustomPacketPayload` C2S/S2C protocol (`RemoteEditorBackend` on client, `ServerNetworkEditorHandler` on server).
- [x] Enforce server-side permission checks (operator permission level >= 2 or dedicated Quill ACL), path traversal guards, rate limits, and payload size caps.
- [x] Support chunked file transfers and optimistic concurrency (`sha256` revision hashes).

## Phase 4: IDE Productivity & Language Features (In Progress)

- [x] **`.mcfunction` & `.json` Syntax Highlighting**: Tokenize command names, `execute` subcommands, selectors (`@a`, `@e[...]`), coordinates (`~ ~ ~`, `^ ^ ^`), SNBT payloads, identifiers, comments (`#`), macro lines (`$`, `$(var)`), and JSON keys/values with VS Code Dark+ colors.
- [x] **Brigadier Command Autocomplete & Hover Signatures**: Query the client's live `CommandDispatcher` tree to show real-time argument suggestions (`Tab`/`Enter` to accept, `Ctrl+Space` to invoke) and syntax usage tooltips at the cursor/hover position.
- [ ] **Command Palette (`Ctrl+Shift+P` / `F1`)**: Quick fuzzy-search modal for running IDE actions (New Datapack, Apply Preset, Reload Datapacks, Run Current Function, View switches, etc.).
- [ ] **Quick Open File Switcher (`Ctrl+P`)**: Fuzzy filename search across all datapacks in the current world (with seamless `>` toggle to Command Palette).
- [ ] **Integrated Reload & Function Output Log Panel**: Bottom panel (VS Code `PROBLEMS` / `OUTPUT` tabs, toggleable via `Ctrl+J`) displaying scrollable datapack parse exceptions with clickable line jump, live reload/command output log, and a "Run Function Now (`/function ...`)" button.
- [ ] **Search & Replace Across Files (`Ctrl+Shift+F`)**: Full-text (plain/case-sensitive/regex) search and replace across all `.mcfunction` and `.json` files in `datapacks/`, honoring live unsaved tab buffers and undo history.
- [ ] **Macro & Scoreboard Variable Inspector**: Sidebar panel listing detected scoreboard objectives, storage namespaces/keys, and macro parameters used across datapacks with click-to-jump occurrences.
- [ ] **JSON Schema Templates for Other Datapack Registries**: Expand the resource-driven preset system beyond function tags (`minecraft:load`, `minecraft:tick`) to include MC `26.3`-valid shaped/shapeless recipes, loot tables, advancements, and predicates.

## Proposals (Propozycje)

> User-submitted UX, visual, and configuration proposals queued for the next phase. **Do not implement without explicit user approval.**

1. **Enhanced Text Selection**: Full text selection polish across editor and modal inputs (mouse cursor drag selection with high-contrast highlight, double-click word / triple-click line selection, `Shift+Arrows`, `Ctrl+Shift+Arrows`, `Shift+Home/End`, `Shift+Click`).
2. **Higher-Fidelity Visual Studio Code UI**: Refine IDE layout chrome, icons, spacing, breadcrumbs, panels, and styling to more closely match Visual Studio Code.
3. **Inline File Explorer Folder/Entry Creation**: Enter new folder (and file) names directly inline inside the File Explorer tree rather than via a popup modal.
4. **In-IDE Global Client-Side Config**: Client configuration panel inside the IDE GUI covering color palettes (built-in VS Code themes), font choices (e.g., sans-serif, Arial / TrueType fonts), text size, and customizable keybind shortcuts.
5. **Autosave & Reload Trigger Config Options**: Add client config options for autosave on GUI close or on every keypress (instant autosave), choosing whether datapack reload happens on save or on GUI close, plus a visible warning when both instant autosave and reload-on-save are enabled together.
6. **Movable & Resizable IDE Window**: Support dragging the IDE window by its title bar and resizing it from edges/corners within the Minecraft screen.
7. **Datapack Creation Template Options**: Add a `"Use template"` checkbox to the New Datapack dialog that reveals a namespace input box and an expandable `"More"` dropdown to customize `tick` and `load` function names, each with a default-checked `"Use default"` checkbox whose default names can be configured in client config.

## Inbox

> New ideas submitted during active tasks are logged here (`YYYY-MM-DD — summary`) before assessment.

*(Empty)*
