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

## Phase 2: MVP Implementation (In Progress — see [plans/001-mvp.md](plans/001-mvp.md))

- [x] **Step 1 — Backend Core & Security (`src/main`)**: Define `EditorBackend` contract, DTOs, `PathSandbox` security validator, and `LocalEditorBackend` for singleplayer integrated server.
- [x] **Step 2 — Preset & Scaffolding Engine (`src/main`)**: Implement `pack.mcmeta` generator (`min_format: 121, max_format: 121`), namespace/function scaffolding, and resource-driven presets (`minecraft:load`, `minecraft:tick`).
- [x] **Step 3 — Non-Pausing VS Code Shell & Keybinding (`src/client`)**: Register keybinding (`K` / configurable) to open `QuillIdeScreen` (`isPauseScreen() == false`) with dark VS Code layout (Activity Bar, Sidebar, Editor Area, Status Bar).
- [x] **Step 4 — World & Datapack File Explorer (`src/client`)**: Build interactive tree view for `datapacks/` supporting open, create file/folder, rename, and delete actions via `EditorBackend`.
- [ ] **Step 5 — Quick Actions & Presets UI (`src/client`)**: Wire "New Datapack", "New Namespace", "New `.mcfunction`", and `data/minecraft` preset insertion (`load.json` / `tick.json`) in the IDE UI.
- [ ] **Step 6 — Multi-Tab `.mcfunction` Editor, Save & Live Reload (`src/client` & `src/main`)**: Multi-line text editor with line numbers, dirty-state tabs, `Ctrl+S` save, automatic datapack reload, and reload result/error feedback in the status bar / diagnostics bar.

## Phase 3: Multiplayer Networking (Planned — Post-MVP)

- [ ] Implement `CustomPacketPayload` C2S/S2C protocol (`RemoteEditorBackend` on client, `ServerNetworkEditorHandler` on server).
- [ ] Enforce server-side permission checks (operator permission level >= 2 or dedicated Quill ACL), path traversal guards, rate limits, and payload size caps.
- [ ] Support chunked file transfers and optimistic concurrency (`sha256` revision hashes).

## Proposals (Propozycje)

> These are optional enhancement ideas. **Do not implement any of these without explicit user approval.**

1. **`.mcfunction` Syntax Highlighting**: Tokenize command names, selectors (`@a`, `@e[...]`), coordinates (`~ ~ ~`, `^ ^ ^`), SNBT payloads, comments (`#`), and macro lines (`$`) with VS Code Dark+ colors.
2. **Brigadier Command Autocomplete & Hover Signatures**: Query the client's live `CommandDispatcher` tree to show real-time argument suggestions and syntax tooltips at the cursor position.
3. **Command Palette (`Ctrl+Shift+P` / `F1`)**: Quick fuzzy-search modal for running IDE actions (New Datapack, Apply Preset, Reload Datapacks, Run Current Function).
4. **Quick Open File Switcher (`Ctrl+P`)**: Fuzzy filename search across all datapacks in the current world.
5. **Integrated Reload & Function Output Log Panel**: Bottom panel (like VS Code's Problems / Output tabs) displaying detailed datapack parse exceptions with clickable line numbers and a "Run Function Now (`/function ...`)" button.
6. **Search & Replace Across Files (`Ctrl+Shift+F`)**: Full-text search across all `.mcfunction` and `.json` files in `datapacks/`.
7. **Macro & Scoreboard Variable Inspector**: Sidebar panel listing detected scoreboard objectives, storage namespaces, and macro parameters used across the active datapack.
8. **JSON Schema Templates for Other Datapack Registries**: Expand the extensible preset system beyond `function` tags to include recipes, loot tables, advancements, and predicates.

## Inbox

> New ideas submitted during active tasks are logged here (`YYYY-MM-DD — summary`) before assessment.

*(Empty)*
