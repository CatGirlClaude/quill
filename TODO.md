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

## Phase 4: IDE Productivity & Language Features (Completed)

- [x] **`.mcfunction` & `.json` Syntax Highlighting**: Tokenize command names, `execute` subcommands, selectors (`@a`, `@e[...]`), coordinates (`~ ~ ~`, `^ ^ ^`), SNBT payloads, identifiers, comments (`#`), macro lines (`$`, `$(var)`), and JSON keys/values with VS Code Dark+ colors.
- [x] **Brigadier Command Autocomplete & Hover Signatures**: Query the client's live `CommandDispatcher` tree to show real-time argument suggestions (`Tab`/`Enter` to accept, `Ctrl+Space` to invoke) and syntax usage tooltips at the cursor/hover position.
- [x] **Command Palette (`Ctrl+Shift+P` / `F1`)**: Quick fuzzy-search modal for running IDE actions (New Datapack, Apply Preset, Reload Datapacks, Run Current Function, View switches, etc.).
- [x] **Quick Open File Switcher (`Ctrl+P`)**: Fuzzy filename search across all datapacks in the current world (with seamless `>` toggle to Command Palette).
- [x] **Integrated Reload & Function Output Log Panel**: Bottom panel (VS Code `PROBLEMS` / `OUTPUT` tabs, toggleable via `Ctrl+J`) displaying scrollable datapack parse exceptions with clickable line jump, live reload/command output log, and a "Run Function Now (`/function ...`)" button.
- [x] **Search & Replace Across Files (`Ctrl+Shift+F`)**: Full-text (plain/case-sensitive/regex) search and replace across all `.mcfunction` and `.json` files in `datapacks/`, honoring live unsaved tab buffers and undo history.
- [x] **Macro & Scoreboard Variable Inspector**: Sidebar panel listing detected scoreboard objectives, storage namespaces/keys, and macro parameters used across datapacks with click-to-jump occurrences.
- [x] **JSON Schema Templates for Other Datapack Registries**: Expand the resource-driven preset system beyond function tags (`minecraft:load`, `minecraft:tick`) to include MC `26.3`-valid shaped/shapeless recipes, loot tables, advancements, and predicates.

## Phase 5: UX, Visual Studio Code Polish & Client Configuration (Completed)

- [x] **Enhanced Text Selection**: Full text selection across code editor and all single-line inputs (`SingleLineTextInput`): mouse drag selection with high-contrast highlight, double-click word / triple-click line / gutter line selection, `Shift+Arrows`, `Ctrl+Shift+Arrows`, `Shift+Home/End`, `Shift+Click`, `Ctrl+Backspace/Delete`, preferred vertical column, smart `Home`, `Tab`/`Shift+Tab` indent/unindent, and matching bracket highlight.
- [x] **Higher-Fidelity Visual Studio Code UI**: Centered Command Center search pill in title bar, vector geometric Activity Bar icons (Explorer, Search, Inspector, Presets, bottom-pinned Settings gear) with hover tooltips, clickable Breadcrumbs bar, colored file-type badges, VS Code tab dirty dot (`●` -> `×` on hover), Status Bar (`>< Local/Remote` accent badge, selection char count, `Spaces: 4 | UTF-8 | LF | Language`, autosave/reload warning pill), and live-shortcut Welcome screen.
- [x] **Inline File Explorer Folder/File Creation & Rename**: Inline editable rows inside `FileExplorerTreeWidget` for `+Dir`, `+File`, and `Ren`/`F2` with pre-selected file stems, live red inline validation banners, and SDL3 `TextInputManager` integration (no popup modals).
- [x] **In-IDE Global Client-Side Config (`SettingsPanelWidget` & `config/quill-client.json`)**: 7 built-in VS Code color palettes (Dark+, Dark Modern, Monokai, One Dark Pro, Dracula, GitHub Dark, Light+), 5 font families (including bundled Roboto / DejaVu Sans / DejaVu Mono TrueType fonts), editor text size (`7–16 px`), `pauseGameWhileOpen` singleplayer toggle, customizable keybind shortcuts with live recorder, default datapack template names, and window layout reset.
- [x] **Autosave & Reload Trigger Config Options**: `AutosaveMode` (`OFF`, `ON_GUI_CLOSE`, `ON_KEYPRESS` with per-tab coalesced saves preventing SHA-256 race conflicts), `ReloadTriggerMode` (`ON_SAVE`, `ON_GUI_CLOSE`), `QuillIdeScreen.removed()` close hooks, plus prominent Settings warning banner (with quick-fix button) and clickable Status Bar warning pill when `ON_KEYPRESS` and `ON_SAVE` are enabled together.
- [x] **Movable & Resizable IDE Window**: Floating window title-bar drag (double-click to maximize/restore), 8-edge/corner resize handles (`IdeLayout.hitTestResizeEdge` + native `CursorTypes.RESIZE_*`), draggable Sidebar and Bottom Panel splitters, and persisted window bounds in `config/quill-client.json`.
- [x] **Datapack Creation Template Options (`NewDatapackModal`)**: `"Use template"` checkbox revealing a Namespace field and expandable `> More` dropdown for customizing `load` and `tick` function names, each paired with a default-checked `"Use default"` checkbox referencing client config defaults (works in both SP and MP via `PresetRegistry`'s `${functionName}` variable).



## Bugfixes & Post-Alpha Polish

- [x] **Whole-GUI & Font Zoom (`Ctrl+-` / `Ctrl+=` / `Ctrl+0`)**: Scale the entire `QuillIdeScreen` (title bar, sidebar, explorer, tabs, breadcrumbs, code editor, bottom panel, settings, modals, quick pick, status bar) uniformly via `QuillClientConfig.uiScale()` while keeping custom floating window bounds anchored in real screen pixels; remove double-scaling from `CodeEditorWidget`; add configurable `ZOOM_IN`/`ZOOM_OUT`/`ZOOM_RESET` shortcuts (`Ctrl+=` / `Ctrl++` / `Ctrl+Numpad+`, `Ctrl+-` / `Ctrl+Numpad-`, `Ctrl+0` / `Ctrl+Numpad0`), Command Palette actions (`View: Zoom In`, `View: Zoom Out`, `View: Reset Zoom`), Settings zoom label/hint, and Status Bar zoom badge.
- [x] **Crisp Non-Pixelated Font Rendering**: Replace MC `26.3`'s hardcoded `FilterMode.NEAREST` glyph sampling with `FilterMode.LINEAR` for `quill:*` TrueType fonts and scaled IDE text via `GlyphRenderStateMixin`; zero-initialize `quill:*` font atlases via `FontTextureMixin` to eliminate edge artifacts; tune TTF `oversample` (`2.5`) in `sans_serif.json`, `arial.json`, and `monospace.json` for `<2x` downscaling on unmipmapped `256x256` atlases; switch default `FontChoice` from `MINECRAFT_DEFAULT` to `SANS_SERIF` (Roboto).

## Feedback po Fazie 5

Źródło: uwagi użytkowniczki po wersji finalnej Fazy 5. Nie implementować bez
zgody przy pozycjach oznaczonych "DECYZJA". Pozycje P0 najpierw, z testem
odtwarzającym błąd przed poprawką.

### P0: błędy do naprawienia najpierw
- [x] Najechanie kursorem na plik w explorerze chowa drzewo tego pliku.
- [x] Kursor pisania pojawia się także w edytorze, gdy wpisujesz tekst w explorerze. Ma być widoczny tylko w aktywnym polu.
- [x] Skróty gry (np. F3) działają w GUI. Mają być wyłączone, gdy otwarte jest IDE.
- [x] Po utworzeniu datapacka widok przełącza się automatycznie na File Explorer.
- [x] Pole tworzenia nowego pliku: różowe pole jest za duże i nie widać w nim znaku `_`.
- [x] Rebinding: ESC przypisuje pusty klawisz zamiast anulować.
- [x] Ctrl+Z rozdzielone na dwa stosy: w edytorze cofa zmiany w kodzie, poza edytorem cofa operacje na plikach (usunięcia, zmiany nazw itd.).
- [x] Wcięcie przed pierwszą literą w edytorze jest zbyt duże (zajmuje szerokość całej litery).
- [x] Kolor czerwony dla niezapisanych zmian w sidebarze wygląda jak błąd. Zmienić na neutralny znacznik `*`.
- [x] Search: tekst jest ucinany przez "..." (np. `Triggers /reload on every keystroke`). Rozszerzyć pole albo zawijać tekst.

### P1: wygląd i ogólne UX
- [ ] Ogólny styl: GUI wygląda zbyt Minecraftowo. Cel: wrażenie prawdziwej aplikacji. Zaproponować system motywu (kolory, odstępy, obramowania) przed poprawkami pojedynczych elementów.
- [ ] Ikony wyższej jakości (np. lupa): większa rozdzielczość lub wektory (SVG).
- [ ] Strzałki w explorerze: zamiast tekstowych `>` i `v` (oraz zamiast znaków ▸ ▾) narysować wektorowe ikony chevron w stylu VS Code (Codicons: cienka, lekko pochylona kreska, zwijana w prawo, rozwijana w dół). Rysować je geometrią w kodzie albo jako SVG/tekstura, nie jako znak tekstowy. Rozmiar i grubość mają pasować do wysokości wiersza przy każdym GUI Scale.
- [x] Znacznik niezapisanego pliku: kropkę zamienić na `*`.
- [ ] Plik funkcji: zamienić tekst "fn" na własną ikonę.
- [ ] Więcej ikon typów plików. Dla typów widocznych, ale nieobsługiwanych, pokazać komunikat na górze ekranu w stylu notyfikacji VS Code.
- [ ] Nazwy plików i folderów zawsze białe. Kolor pochodzi tylko z prefiksu typu pliku (np. `{}` dla JSON, `function` dla funkcji, `js` dla skryptów).
- [ ] Podgląd JSON: `{}` z większym odstępem między klamrami, np. `{ }`, ale mniejszym niż zwykła spacja. Pokazać dwie wersje (węższa spacja / osobny glif) i wybrać jedną.
- [ ] UI jest za duże i zajmuje za dużo ekranu. Zwiększyć zoom out lub naprawić skalowanie. DECYZJA: zwiększyć czy naprawić. Nie może się psuć przy różnym GUI Scale.
- [ ] Skala czcionki różna od domyślnej psuje layout. Opis z screenshotu: [uzupełnić: które elementy nachodzą lub są ucinane].
- [ ] Status bar na dole: skompaktować i usunąć zbędne informacje.
- [ ] Command palette: usunąć skróty już widoczne w innych miejscach (np. "CTRL + P" przy samym pasku).
- [ ] Command palette rozwija się z góry zamiast otwierać nowe okno nad wszystkim.
- [ ] Ustawienia: lewy panel zastąpić listą kategorii. Główna lista zostaje, a kliknięcie kategorii przeskakuje do sekcji.
- [ ] Ustawienia: usunąć znak `*` przy zaznaczonej opcji.
- [ ] Ustawienia: zawijanie tekstu tam, gdzie opisy nie mieszczą się w linii (np. `Triggers /reload on every keystroke`).
- [ ] Ustawienia, Font Family: podgląd "Minecraft Uniform" jest mały i ma niski kontrast. Wszystkie podglądy mają ten sam rozmiar i kontrast, żeby porównanie było uczciwe.
- [ ] Ustawienia, Font Family: zaznaczona opcja (Monospace) ma `*`. Usunąć razem z pozycją o `*` w ustawieniach.
- [ ] Więcej motywów kolorystycznych.
- [ ] Animacje w całym UI z globalnym przełącznikiem w ustawieniach.

### P1: explorer plików
- [ ] Usunąć przyciski pokroju `+file` i `+dir`.
- [ ] Menu kontekstowe (prawy klik) na pliku i folderze. Minimum: Run function, Open to side, Reveal in file explorer, Find file references, Cut, Copy, Copy path, Delete, Rename.
- [ ] Skrót F2 zmienia nazwę zaznaczonego pliku.
- [ ] Skróty Delete oraz Ctrl+C / Ctrl+X / Ctrl+V na zaznaczonym pliku.
- [x] Najechanie na plik nie chowa drzewa (patrz P0).

### P1: edytor kodu
- [ ] Prawy klik w edytorze: cut, copy, paste oraz podstawowa nawigacja.
- [ ] Prawy klik w edytorze działa także na tekście.
- [ ] Ctrl+F: wyszukiwanie w aktywnym pliku, wzorem VS Code.
- [ ] Shift + kółko myszy: przewijanie poziome.
- [ ] Zawijanie tekstu w edytorze: Alt+Z.
- [ ] Ctrl+klik na tekst przenosi do definicji (osobny punkt, wymaga źródeł danych):
  - scoreboard, tag, storage itd.: lista wszystkich miejsc użycia,
  - zwykła komenda: link do dokumentacji.

### P1: zakładki (tabs)
- [ ] Prawy klik na zakładce: Pin, Close, Close all but this, Close all oraz standardowe opcje z menu pliku.
- [ ] Z menu zakładki wykluczyć Cut i Delete. Copy path zostaje.

### P2: wyszukiwanie w folderze
- [ ] Search w folderze z polami include i exclude.
- [ ] Oba pola ukryte domyślnie, rozwijane przyciskiem.

### P2: output i konsola
- [ ] Zaznaczanie i kopiowanie tekstu z output.
- [ ] Konsola Minecrafta w output: logi na żywo, wpisany tekst wykonywany jako komenda od gracza. DECYZJA: zakres (sama wysyłka i logi, czy od razu podpowiedzi komend).

### P2: konfiguracja
- [ ] Ctrl+Z i Ctrl+Shift+Z konfigurowalne w ustawieniach.

### P2: komendy klienta
- [ ] Komenda clientside `/quill` otwiera menu IDE.
- [ ] Subkomendy z argumentami otwierają konkretne miejsce w IDE, np. `/quill config` otwiera od razu ustawienia. DECYZJA: pełna lista subkomend (start: `/quill`, `/quill config`).
- [ ] Komendy działają po stronie klienta, bez wysyłania na serwer.

### P3: przyszłość (nie do MVP)
- [ ] Syntax: obsługa NBT i podświetlanie błędów na bieżąco.
- [ ] Własne definicje komend, na wzór rozszerzenia Spyglass.
- [ ] Autouzupełnianie dla plików JSON i `pack.mcmeta`.
- [ ] Źródła danych między innymi: https://github.com/SpyglassMC/vanilla-mcdoc (sprawdzić licencję przed użyciem).

## Proposals (Propozycje)

> User-submitted UX, visual, and configuration proposals queued for future phases. **Do not implement without explicit user approval.**

*(None pending)*

## Inbox

> New ideas submitted during active tasks are logged here (`YYYY-MM-DD — summary`) before assessment.

*(Empty)*
