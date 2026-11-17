# AGENTS.md — Quill Developer & Agent Guide

## 1. Tech Stack

- **Minecraft**: `26.3` (unobfuscated, official Mojang names)
- **Loader**: Fabric Loader `0.20.1`
- **Fabric API**: `0.166.2+26.3`
- **Build System**: Gradle `9.8.2` + Fabric Loom `1.19-SNAPSHOT` (`net.fabricmc.fabric-loom`)
- **Java**: JDK `25` (`sourceCompatibility` / `targetCompatibility` = `25`)
- **Source Sets**: Split environment (`src/main` for common/server logic, `src/client` for client-only UI/rendering)

## 2. Commands

> In the cloud VM, use JDK 25 installed at `/home/antigravity/.jdks/temurin-25`:
> `export JAVA_HOME=/home/antigravity/.jdks/temurin-25`

- **Build & verify**: `./gradlew build`
- **Compile only**: `./gradlew compileJava compileClientJava`
- **Run client (desktop)**: `./gradlew runClient`
- **Run client (headless VM smoke test)**: `xvfb-run -a ./gradlew runClient`
- **Inspect MC/Fabric bytecode signatures**: `javap -cp <jar> <fully.qualified.ClassName>`

## 3. Code & Repository Conventions

- **Language**: All code, comments, commit messages, and repository documentation MUST be in **English**. Conversation with the user follows the language of the user's latest message.
- **Package Structure**:
  - `dev.catgirlclaude.quill.*` (`src/main/java`) — common data models, `EditorBackend` interface, local/server backend implementations, path/security validators, networking payloads, preset registry.
  - `dev.catgirlclaude.quill.client.*` (`src/client/java`) — keybindings, IDE `Screen`, UI layout/widgets, client backend adapter.
- **Strict Client/Server Boundary**: Never reference `net.minecraft.client.*` from `src/main`. Even in singleplayer, all client UI file/reload actions MUST go through the `EditorBackend` interface.
- **Commits**: Small, descriptive commits following **Conventional Commits** (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`). Never `git push` without explicit user approval.

## 4. Workflow Rules

1. **No API Guessing**: Never guess Minecraft `26.3` or Fabric API signatures. Verify classes and methods in the Gradle cache JARs (`javap`) or official Fabric documentation before writing code.
2. **Keep Truth in One Place**:
   - After completing any task, update [TODO.md](TODO.md).
   - Record every significant architectural or technical choice in [docs/DECISIONS.md](docs/DECISIONS.md).
   - Do not duplicate content across docs; link between files instead.
3. **Handling New User Ideas Mid-Task**:
   - Do **NOT** interrupt the current task (unless the user explicitly says `"do this now"` / `"zrób to teraz"` or `"priority"` / `"priorytet"`).
   - Append the idea to the `Inbox` section in [TODO.md](TODO.md) (1 sentence + `YYYY-MM-DD` date).
   - Briefly assess in 1–2 sentences whether it conflicts with the current architecture/plan or needs an entry in [docs/DECISIONS.md](docs/DECISIONS.md).
   - Finish the active task first, then ask if and when to tackle the new idea.
4. **Stay Within Workspace**: Never modify files outside the project directory without explicit permission.

## 5. When to Read Which File

| File | When to Read |
| :--- | :--- |
| [TODO.md](TODO.md) | At the start and end of every task to check current progress, active scope, proposals, and `Inbox`. |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Before adding/modifying packages, `EditorBackend` methods, client-server flows, or security checks. |
| [docs/DECISIONS.md](docs/DECISIONS.md) | When checking why MC `26.3`, Mojang mappings, or the UI approach were chosen, or before proposing new dependencies. |
| [docs/MC_NOTES.md](docs/MC_NOTES.md) | Before writing datapack scaffolding (`pack.mcmeta`, `function/` folders, tags) or Minecraft `26.3` `Screen` / `GuiGraphicsExtractor` / reload code. |
| [plans/001-mvp.md](plans/001-mvp.md) | When implementing or reviewing any step of the MVP vertical slice. |
