# Quill

**Quill** is an in-game IDE for Minecraft (Fabric) that lets you browse, create, edit, and live-reload datapack functions (`.mcfunction`) and metadata without leaving the game.

Inspired by Minecraft Education Edition's Code Builder and modeled after **Visual Studio Code**, Quill provides a modern dark-themed workspace directly inside Minecraft without pausing gameplay.

## Features

- **Visual Studio Code-Style Floating & Resizable UI**: Movable floating window (title-bar drag, double-click to maximize/restore), 8-edge/corner resize handles, draggable Sidebar & Bottom Panel splitters, centered Command Center pill, vector Activity Bar icons (Explorer, Search, Inspector, Presets, Settings gear) with tooltips, clickable Breadcrumbs bar, VS Code dirty-dot tabs, toggleable `PROBLEMS` / `OUTPUT` bottom panel (`Ctrl+J`), Command Palette (`Ctrl+Shift+P` / `F1`), Quick Open (`Ctrl+P`), whole-IDE zoom (`Ctrl+-` / `Ctrl+=` / `Ctrl+0`), and VS Code Status Bar.
- **In-IDE Global Client Settings (`Ctrl+,` / `config/quill-client.json`)**: 7 built-in VS Code themes (Dark+, Dark Modern, Monokai, One Dark Pro, Dracula, GitHub Dark, Light+), 5 font choices (default smooth Sans-Serif Roboto, Arial-style DejaVu Sans, Monospace DejaVu Mono, Minecraft Default, Uniform) with bilinear `FilterMode.LINEAR` rendering, configurable whole-UI & font scale (`78%–178%`), singleplayer pause-while-open toggle, customizable keyboard shortcuts with live keybind recorder, configurable autosave (`Off` / `On GUI Close` / `On Every Keypress`) and reload trigger (`On Save` / `On GUI Close`) with instant-reload warning banner & status bar pill, and default datapack template names.
- **Enhanced Editor & Language Support**: `.mcfunction` & `.json` syntax highlighting, live Brigadier command autocomplete (`Ctrl+Space`, `Tab`/`Enter`) & syntax tooltips, matching bracket highlight, plus full text selection across code and input fields (mouse drag, double-click word, triple-click line, gutter selection, `Shift+Arrows`, `Ctrl+Shift+Arrows`, `Shift+Home/End`, `Shift+Click`, `Ctrl+Backspace/Delete`, `Tab`/`Shift+Tab` indent/unindent).
- **Inline File Explorer & Workspace Tools**: Create folders, create files, and rename entries (`F2`) directly inline inside the Explorer tree with live validation; workspace-wide Search & Replace (`Ctrl+Shift+F`, plain/case-sensitive/regex) honoring unsaved tabs and undo; and a Macro & Scoreboard Variable Inspector with click-to-jump navigation.
- **Quick Actions, Templated New Datapack & MC `26.3` Presets**: Scaffold new datapacks (`min_format: 121, max_format: 121`) with an optional `"Use template"` checkbox (Namespace + collapsible `More` dropdown for `load` and `tick` function names with config-backed `"Use default"` checkboxes), namespaces, `.mcfunction` files, and built-in templates for `#minecraft:load`, `#minecraft:tick`, shaped/shapeless recipes, loot tables, advancements, and predicates.
- **Live Save, Reload & Function Runner**: Save open files (`Ctrl+S`), inspect clickable parse diagnostics in `PROBLEMS`, stream command output in `OUTPUT`, and run `/function` with one click.
- **Multiplayer Networking & Server Security**: Chunked C2S/S2C Fabric networking (`quill:c2s_frame` / `quill:s2c_frame` / `quill:s2c_tree_changed`), SHA-256 optimistic concurrency with conflict resolution modal, live sync across connected editors, dedicated server ACL (`config/quill-acl.json`), path sandboxing, and rate limiting.

## Tech Stack

- **Minecraft**: `26.3` (unobfuscated, official Mojang mappings)
- **Mod Loader**: Fabric Loader `0.20.1`
- **Fabric API**: `0.166.2+26.3`
- **Build System**: Gradle `9.8.2` + Fabric Loom `1.19.4`
- **Java**: JDK `25`
- **License**: [MIT](LICENSE)

See [docs/DECISIONS.md](docs/DECISIONS.md) for the rationale behind the version, mappings, and UI technology choices.

## Getting Started

### Prerequisites

- **JDK 25** (e.g., Eclipse Temurin 25)

### Build & Run

```bash
# Build the mod JAR (outputs to build/libs/)
./gradlew build

# Launch the development Minecraft client
./gradlew runClient
```

## Documentation Map

To keep a single source of truth, detailed documentation is split across focused files:

- [AGENTS.md](AGENTS.md) — Developer & AI agent quick reference (commands, conventions, workflow rules, and when to read each doc).
- [TODO.md](TODO.md) — Single source of truth for project tasks, progress, proposals, and inbox ideas.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Module boundaries (`main` vs `client`), `EditorBackend` contract, multiplayer protocol, and server security guards.
- [docs/DECISIONS.md](docs/DECISIONS.md) — Architecture Decision Records (MC/Fabric versions, mappings, UI technology trade-off analysis).
- [docs/MC_NOTES.md](docs/MC_NOTES.md) — Minecraft 26.3 & Fabric technical notes (datapack singular folder names, `pack.mcmeta` `min_format`/`max_format`, unobfuscated Loom setup, GUI rendering changes).
- [plans/001-mvp.md](plans/001-mvp.md) — Vertical-slice implementation plan for the MVP.
