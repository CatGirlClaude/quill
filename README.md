# Quill

**Quill** is an in-game IDE for Minecraft (Fabric) that lets you browse, create, edit, and live-reload datapack functions (`.mcfunction`) and metadata without leaving the game.

Inspired by Minecraft Education Edition's Code Builder and modeled after **Visual Studio Code**, Quill provides a modern dark-themed workspace directly inside Minecraft without pausing gameplay.

## Features (MVP Scope)

- **VS Code-Style Non-Pausing UI**: Activity bar, collapsible file explorer sidebar, multi-tab code editor, and status bar with reload diagnostics.
- **World & Datapack File Explorer**: Browse, open, create, rename, and delete files and directories inside the world's `datapacks/` folder (`data/<namespace>/function/...`, `pack.mcmeta`, function tags).
- **Quick Actions**: Scaffold a new datapack (with a valid Minecraft 26.3 `pack.mcmeta`), create a new namespace, or create a new `.mcfunction` file in one click.
- **Extensible Presets (`data/minecraft`)**: Built-in templates loaded from mod resources, starting with `#minecraft:load` and `#minecraft:tick` function tag presets.
- **Live Save & Reload**: Save open `.mcfunction` files and immediately trigger a server datapack reload with visible execution status and parse error feedback.
- **Multiplayer-Ready Architecture**: All file operations flow through an abstract Editor Backend interface (`EditorBackend`), cleanly separating the client UI (`src/client`) from world filesystem and reload logic (`src/main`).

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
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Module boundaries (`main` vs `client`), `EditorBackend` contract, multiplayer protocol plan, and server security guards.
- [docs/DECISIONS.md](docs/DECISIONS.md) — Architecture Decision Records (MC/Fabric versions, mappings, UI technology trade-off analysis).
- [docs/MC_NOTES.md](docs/MC_NOTES.md) — Minecraft 26.3 & Fabric technical notes (datapack singular folder names, `pack.mcmeta` `min_format`/`max_format`, unobfuscated Loom setup, GUI rendering changes).
- [plans/001-mvp.md](plans/001-mvp.md) — Vertical-slice implementation plan for the MVP.
