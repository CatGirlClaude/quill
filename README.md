# Quill

**Quill** is an in-game IDE for Minecraft (Fabric) that lets you browse, create, edit, and live-reload datapack functions (`.mcfunction`) and metadata without leaving the game.

Inspired by Minecraft Education Edition's Code Builder and modeled after **Visual Studio Code**, Quill provides a modern dark-themed workspace directly inside Minecraft without pausing gameplay.

## Features

- **VS Code-Style Non-Pausing UI**: Activity bar (Explorer, Search, Inspector, Presets), collapsible sidebar, multi-tab code editor, toggleable `PROBLEMS` / `OUTPUT` bottom panel (`Ctrl+J`), Command Palette (`Ctrl+Shift+P` / `F1`), Quick Open (`Ctrl+P`), and status bar.
- **`.mcfunction` & `.json` Language Support**: VS Code Dark+ syntax highlighting plus live Brigadier command autocomplete (`Ctrl+Space`, `Tab`/`Enter`) and syntax usage tooltips backed by the connected server's command tree.
- **World & Datapack File Explorer & Workspace Tools**: Browse, open, create, rename, and delete files inside `datapacks/`; workspace-wide Search & Replace (`Ctrl+Shift+F`, plain/case-sensitive/regex) honoring unsaved tabs and undo; and a Macro & Scoreboard Variable Inspector listing scoreboard objectives, command storage namespaces/paths, and `$(var)` macro parameters with click-to-jump navigation.
- **Quick Actions & MC `26.3` Presets**: Scaffold new datapacks (`min_format: 121, max_format: 121`), namespaces, and `.mcfunction` files, or apply built-in templates for `#minecraft:load`, `#minecraft:tick`, shaped/shapeless recipes, loot tables, advancements, and predicates.
- **Live Save, Reload & Function Runner**: Save open files (`Ctrl+S`) with automatic datapack reload, clickable parse diagnostics in `PROBLEMS`, live command output in `OUTPUT`, and one-click `/function` execution.
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
