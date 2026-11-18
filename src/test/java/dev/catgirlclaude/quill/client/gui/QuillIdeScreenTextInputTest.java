package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuillIdeScreenTextInputTest {

	@AfterEach
	void resetClientConfig() {
		QuillClientConfig.setCurrent(QuillClientConfig.createDefault());
	}

	private static final class RecordingTextInputBridge implements QuillIdeScreen.TextInputBridge {
		private Object activeOwner = null;

		@Override
		public void startTextInput(Object owner) {
			this.activeOwner = owner;
		}

		@Override
		public void stopTextInput(Object owner) {
			if (this.activeOwner == owner) {
				this.activeOwner = null;
			}
		}

		boolean isActiveFor(Object owner) {
			return this.activeOwner == owner;
		}
	}

	private record AppliedPresetCall(String packName, String presetId, Map<String, String> variables) {
	}

	private static class StubEditorBackend implements EditorBackend {
		String createdPackName;
		String createdPackDescription;
		String createdEntryPath;
		EntryKind createdEntryKind;
		String renamedSourcePath;
		String renamedTargetPath;
		int renameCount;
		int writeCount;
		boolean lastWriteReload;
		int reloadCount;
		final java.util.List<AppliedPresetCall> appliedPresets = new java.util.ArrayList<>();

		@Override
		public CompletableFuture<FileTreeNode> listTree() {
			return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of()));
		}

		@Override
		public CompletableFuture<FileDocument> readFile(String relativePath) {
			return CompletableFuture.completedFuture(new FileDocument(relativePath, "", "hash", 0L));
		}

		@Override
		public CompletableFuture<SaveResult> writeFile(
				String relativePath,
				String content,
				String expectedBaseSha256,
				boolean reloadAfterSave
		) {
			this.writeCount++;
			this.lastWriteReload = reloadAfterSave;
			return CompletableFuture.completedFuture(new SaveResult(new FileDocument(relativePath, content, "hash", 0L), null));
		}

		@Override
		public CompletableFuture<Void> createEntry(String relativePath, EntryKind kind) {
			this.createdEntryPath = relativePath;
			this.createdEntryKind = kind;
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
			this.renamedSourcePath = sourceRelativePath;
			this.renamedTargetPath = targetRelativePath;
			this.renameCount++;
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> deleteEntry(String relativePath) {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<String> createDatapack(String packName, String description) {
			this.createdPackName = packName;
			this.createdPackDescription = description;
			return CompletableFuture.completedFuture(packName);
		}

		@Override
		public CompletableFuture<String> createNamespace(String packName, String namespace) {
			return CompletableFuture.completedFuture(packName + "/data/" + namespace + "/function");
		}

		@Override
		public CompletableFuture<String> createFunction(String packName, String namespace, String functionPath) {
			return CompletableFuture.completedFuture(packName + "/data/" + namespace + "/function/" + functionPath + ".mcfunction");
		}

		@Override
		public CompletableFuture<List<PresetDescriptor>> listPresets() {
			return CompletableFuture.completedFuture(List.of());
		}

		@Override
		public CompletableFuture<FileDocument> applyPreset(String packName, String presetId, Map<String, String> variables) {
			this.appliedPresets.add(new AppliedPresetCall(packName, presetId, Map.copyOf(variables)));
			String ns = variables.getOrDefault("namespace", "demo");
			String fn = variables.getOrDefault("functionName", "minecraft:tick".equals(presetId) ? "tick" : "load");
			return CompletableFuture.completedFuture(new FileDocument(
					packName + "/data/" + ns + "/function/" + fn + ".mcfunction",
					"# " + ns + ":" + fn + "\n",
					"hash",
					0L
			));
		}

		@Override
		public CompletableFuture<ReloadReport> reloadDatapacks() {
			this.reloadCount++;
			return CompletableFuture.completedFuture(new ReloadReport(true, 1L, 1, List.of()));
		}
	}

	@Test
	void newDatapackModalActivatesSdlTextInputAndAllowsTypingNewPackName() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.tick();

		assertFalse(bridge.isActiveFor(screen), "Welcome screen with no open tabs or modals does not need text input yet.");
		assertFalse(screen.isInputCaptured());

		screen.quickActions().promptNewDatapack();
		assertTrue(bridge.isActiveFor(screen), "Opening modal with text fields must start SDL text input.");
		assertTrue(screen.isInputCaptured(), "Screen must report captured input while text modal is active.");

		// Clear default "my_pack" using Backspace (KeyEvent always arrives even without SDL_StartTextInput)
		for (int i = 0; i < "my_pack".length(); i++) {
			screen.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, InputConstants.KEYCODE_BACKSPACE, 0));
		}
		// Simulate SDL3 text input (only emits CharacterEvent when SDL_StartTextInput is active)
		simulateSdlTextInput(screen, bridge, "test");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertEquals("test", backend.createdPackName);
		assertEquals("Created with Quill", backend.createdPackDescription);
	}

	@Test
	void openCodeTabActivatesSdlTextInputAndScreenRemovalStopsIt() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.tick();

		session.openOrFocusDocument(new FileDocument("test/data/demo/function/hello.mcfunction", "say hi", "h1", 0L));
		screen.tick();

		assertTrue(bridge.isActiveFor(screen), "Active code tab must enable SDL text input.");
		assertTrue(screen.isInputCaptured());

		screen.keyPressed(new KeyEvent(InputConstants.KEY_END, InputConstants.KEYCODE_END, 0));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
		simulateSdlTextInput(screen, bridge, "say hello");

		assertEquals("say hi\nsay hello", session.activeTab().buffer().getText());

		screen.removed();
		assertFalse(bridge.isActiveFor(screen), "Closing screen must stop SDL text input.");
	}

	@Test
	void confirmationModalStopsTextInputAndInitRestoresItForOpenTab() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.openOrFocusDocument(new FileDocument("test/pack.mcmeta", "{}", "h1", 0L));

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();
		assertTrue(bridge.isActiveFor(screen), "init() must immediately activate text input when an editor tab is already open.");
		assertTrue(screen.isInputCaptured());

		screen.openModal(ModalPromptOverlay.confirm(
				"Delete File",
				"Confirm delete?",
				"Delete",
				true,
				screen::closeModal,
				screen::closeModal
		));
		assertFalse(bridge.isActiveFor(screen), "Confirmation modal without text fields must not hold SDL text input.");
		assertFalse(screen.isInputCaptured());

		screen.closeModal();
		assertTrue(bridge.isActiveFor(screen), "Closing confirmation modal must restore SDL text input for the active editor tab.");
		assertTrue(screen.isInputCaptured());
	}

	@Test
	void revisionConflictOpensModalStopsTextInputAndOverwriteForcesSave() {
		java.util.concurrent.atomic.AtomicReference<String> lastExpectedSha = new java.util.concurrent.atomic.AtomicReference<>("unset");
		StubEditorBackend backend = new StubEditorBackend() {
			boolean firstAttempt = true;

			@Override
			public CompletableFuture<SaveResult> writeFile(
					String relativePath,
					String content,
					String expectedBaseSha256,
					boolean reloadAfterSave
			) {
				lastExpectedSha.set(expectedBaseSha256);
				if (firstAttempt) {
					firstAttempt = false;
					return CompletableFuture.failedFuture(
							new dev.catgirlclaude.quill.api.RevisionConflictException(relativePath, expectedBaseSha256, "server_new_sha")
					);
				}
				return CompletableFuture.completedFuture(new SaveResult(new FileDocument(relativePath, content, "saved_sha", 0L), null));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run, "Remote");
		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/a.mcfunction", "say hi", "base_sha", 0L));

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();
		assertTrue(bridge.isActiveFor(screen));

		session.activeTab().buffer().insertText("# edited\n");
		session.saveActiveTab(false);
		assertEquals("base_sha", lastExpectedSha.get());
		screen.tick();

		// Revision Conflict modal is now open (no text fields -> SDL text input paused)
		assertFalse(bridge.isActiveFor(screen));
		assertFalse(screen.isInputCaptured());

		// Confirming modal ("Overwrite") forces save with expectedBaseSha256 == null and restores code editor text input
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
		assertEquals(null, lastExpectedSha.get());
		assertTrue(bridge.isActiveFor(screen));
		assertFalse(session.activeTab().isDirty());
		assertEquals("saved_sha", session.activeTab().sha256());
	}

	@Test
	void quickOpenCtrlPActivatesTextInputAndOpensMatchedFile() {
		StubEditorBackend backend = new StubEditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode tick = new FileTreeNode("tick.mcfunction", "my_pack/data/demo/function/tick.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode load = new FileTreeNode("load.mcfunction", "my_pack/data/demo/function/load.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode fnDir = new FileTreeNode("function", "my_pack/data/demo/function", EntryKind.DIRECTORY, List.of(load, tick));
				FileTreeNode pack = new FileTreeNode("my_pack", "my_pack", EntryKind.DATAPACK, List.of(fnDir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(pack)));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(new FileDocument(relativePath, "# loaded " + relativePath, "sha", 0L));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();
		assertFalse(bridge.isActiveFor(screen));

		// Ctrl+P opens Quick Open file switcher and captures SDL3 text input
		screen.keyPressed(new KeyEvent(0, 'p', net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER));
		assertTrue(bridge.isActiveFor(screen));
		assertEquals(QuickPickOverlay.Mode.FILES, screen.activeQuickPick().activeMode());

		simulateSdlTextInput(screen, bridge, "tick");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertEquals(null, screen.activeQuickPick());
		assertEquals("my_pack/data/demo/function/tick.mcfunction", session.activeTab().relativePath());
		assertTrue(bridge.isActiveFor(screen), "Opened code tab should keep SDL text input active.");
	}

	@Test
	void commandPaletteF1RunsActionAndCtrlJTogglesBottomPanel() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		assertFalse(session.isBottomPanelOpen());
		screen.keyPressed(new KeyEvent(0, 'j', net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER));
		assertTrue(session.isBottomPanelOpen());

		// F1 opens Command Palette in COMMANDS mode
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F1, 0, 0));
		assertTrue(bridge.isActiveFor(screen));
		assertEquals(QuickPickOverlay.Mode.COMMANDS, screen.activeQuickPick().activeMode());

		simulateSdlTextInput(screen, bridge, "toggle bottom");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertEquals(null, screen.activeQuickPick());
		assertFalse(session.isBottomPanelOpen(), "Running 'View: Toggle Bottom Panel' should collapse the panel.");
		assertFalse(bridge.isActiveFor(screen), "Closing Command Palette with no open tabs stops SDL text input.");
	}

	@Test
	void searchSidebarCtrlShiftFActivatesTextInputAndSearchesFiles() {
		StubEditorBackend backend = new StubEditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode fn = new FileTreeNode("hello.mcfunction", "pack/data/demo/function/hello.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode dir = new FileTreeNode("function", "pack/data/demo/function", EntryKind.DIRECTORY, List.of(fn));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(dir)));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(new FileDocument(relativePath, "say hello_world", "sha", 0L));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();
		assertFalse(bridge.isActiveFor(screen));

		// Ctrl+Shift+F opens Search sidebar and captures SDL3 text input even with no open tabs
		int ctrlShift = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER | 1;
		screen.keyPressed(new KeyEvent(0, InputConstants.KEYCODE_F, ctrlShift));
		assertEquals(ClientEditorSession.SidebarView.SEARCH, session.sidebarView());
		assertTrue(bridge.isActiveFor(screen), "Focused search input must activate SDL3 text input.");

		simulateSdlTextInput(screen, bridge, "hello_world");
		assertEquals("hello_world", screen.searchSidebar().queryText());
		assertEquals(1, screen.searchSidebar().lastOutcome().totalMatches());

		// Pressing Escape unfocuses search input and stops SDL3 text input when no editor tab is open
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
		assertFalse(screen.searchSidebar().hasFocusedTextInput());
		assertFalse(bridge.isActiveFor(screen));
	}

	@Test
	void inlineFolderCreationActivatesSdlTextInputValidatesAndCreatesFolder() {
		StubEditorBackend backend = new StubEditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode fnDir = new FileTreeNode("function", "my_pack/data/demo/function", EntryKind.DIRECTORY, List.of());
				FileTreeNode pack = new FileTreeNode("my_pack", "my_pack", EntryKind.DATAPACK, List.of(fnDir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(pack)));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();
		session.setSelectedTreePath("my_pack/data/demo/function");

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();
		assertFalse(bridge.isActiveFor(screen));

		screen.explorerTree().beginInlineCreateFolder();
		screen.tick();
		assertTrue(screen.explorerTree().hasActiveInlineInput());
		assertEquals(FileExplorerTreeWidget.InlineEditKind.NEW_FOLDER, screen.explorerTree().activeInlineEditKind());
		assertTrue(bridge.isActiveFor(screen), "Inline explorer folder input must activate SDL3 text input.");

		// Invalid path traversal name must show inline validation error and remain open
		simulateSdlTextInput(screen, bridge, "../bad");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
		assertTrue(screen.explorerTree().hasActiveInlineInput());
		assertTrue(screen.explorerTree().activeInlineValidationError() != null);
		assertEquals(null, backend.createdEntryPath);

		// Select all, replace with valid folder name, confirm with Enter
		screen.keyPressed(new KeyEvent(InputConstants.KEY_A, 'a', net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER));
		simulateSdlTextInput(screen, bridge, "sub_folder");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertFalse(screen.explorerTree().hasActiveInlineInput());
		assertEquals("my_pack/data/demo/function/sub_folder", backend.createdEntryPath);
		assertEquals(EntryKind.DIRECTORY, backend.createdEntryKind);
		assertFalse(bridge.isActiveFor(screen), "Closing inline input with no open tabs must stop SDL3 text input.");
	}

	@Test
	void inlineFileCreationPreSelectsStemAndCreatesFile() {
		StubEditorBackend backend = new StubEditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode fnDir = new FileTreeNode("function", "my_pack/data/demo/function", EntryKind.DIRECTORY, List.of());
				FileTreeNode pack = new FileTreeNode("my_pack", "my_pack", EntryKind.DATAPACK, List.of(fnDir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(pack)));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();
		session.setSelectedTreePath("my_pack/data/demo/function");

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		screen.explorerTree().beginInlineCreateFile();
		screen.tick();
		assertTrue(bridge.isActiveFor(screen));

		// Pre-selected stem "new_fn" should be replaced by "greet", keeping ".mcfunction"
		simulateSdlTextInput(screen, bridge, "greet");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertFalse(screen.explorerTree().hasActiveInlineInput());
		assertEquals("my_pack/data/demo/function/greet.mcfunction", backend.createdEntryPath);
		assertEquals(EntryKind.FILE, backend.createdEntryKind);
	}

	@Test
	void inlineRenameViaF2RenamesEntryAndEscapeCancels() {
		StubEditorBackend backend = new StubEditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode file = new FileTreeNode("hello.mcfunction", "my_pack/data/demo/function/hello.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode fnDir = new FileTreeNode("function", "my_pack/data/demo/function", EntryKind.DIRECTORY, List.of(file));
				FileTreeNode pack = new FileTreeNode("my_pack", "my_pack", EntryKind.DATAPACK, List.of(fnDir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(pack)));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();
		session.setSelectedTreePath("my_pack/data/demo/function/hello.mcfunction");

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		// F2 begins inline rename with stem "hello" pre-selected
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F2, 0, 0));
		assertTrue(screen.explorerTree().hasActiveInlineInput());
		assertEquals(FileExplorerTreeWidget.InlineEditKind.RENAME, screen.explorerTree().activeInlineEditKind());
		assertTrue(bridge.isActiveFor(screen));

		simulateSdlTextInput(screen, bridge, "world");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertFalse(screen.explorerTree().hasActiveInlineInput());
		assertEquals("my_pack/data/demo/function/hello.mcfunction", backend.renamedSourcePath);
		assertEquals("my_pack/data/demo/function/world.mcfunction", backend.renamedTargetPath);
		assertEquals(1, backend.renameCount);

		// F2 again then Escape cancels without renaming
		session.setSelectedTreePath("my_pack/data/demo/function/hello.mcfunction");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F2, 0, 0));
		assertTrue(bridge.isActiveFor(screen));
		simulateSdlTextInput(screen, bridge, "cancelled");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));

		assertFalse(screen.explorerTree().hasActiveInlineInput());
		assertFalse(bridge.isActiveFor(screen));
		assertEquals(1, backend.renameCount);
	}

	@Test
	void pauseScreenFlagDynamicallyReflectsClientConfig() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);

		assertFalse(screen.isPauseScreen(), "Default config must keep game running while IDE is open.");
		QuillClientConfig.current().setPauseGameWhileOpen(true);
		assertTrue(screen.isPauseScreen(), "Enabling pauseGameWhileOpen must make isPauseScreen() return true.");
		QuillClientConfig.current().setPauseGameWhileOpen(false);
		assertFalse(screen.isPauseScreen());
	}

	@Test
	void removedTriggersAutosaveAndReloadOnCloseWhenConfigured() {
		QuillClientConfig cfg = QuillClientConfig.current();
		cfg.setAutosaveMode(QuillClientConfig.AutosaveMode.ON_GUI_CLOSE);
		cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);

		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/a.mcfunction", "say hi", "sha1", 0L));

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		session.activeTab().buffer().insertText("# changed\n");
		assertEquals(0, backend.writeCount);
		assertEquals(0, backend.reloadCount);

		screen.removed();

		assertEquals(1, backend.writeCount);
		assertFalse(backend.lastWriteReload, "Reload-on-close mode must save without per-file reload first.");
		assertEquals(1, backend.reloadCount, "Closing IDE must trigger reloadDatapacks once.");
		assertFalse(bridge.isActiveFor(screen));
	}

	@Test
	void settingsShortcutOpensSettingsCapturesTextInputAndRecordsCustomKeybind() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();
		assertFalse(screen.isSettingsOpen());

		// Ctrl+, opens Settings panel
		screen.keyPressed(new KeyEvent(InputConstants.KEY_COMMA, ',', net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER));
		assertTrue(screen.isSettingsOpen());
		assertFalse(bridge.isActiveFor(screen), "Settings without focused text field does not capture SDL text input.");

		// Focusing template default namespace input captures SDL text input and live-updates config
		screen.settingsPanel().focusTemplateField(0);
		screen.tick();
		assertTrue(bridge.isActiveFor(screen));
		simulateSdlTextInput(screen, bridge, "my_ns");
		assertEquals("my_ns", QuillClientConfig.current().defaultTemplateNamespace());

		// Recording a custom shortcut (F6) for Toggle Bottom Panel
		screen.settingsPanel().beginRecordingKeybind(QuillClientConfig.ShortcutAction.TOGGLE_BOTTOM_PANEL);
		screen.tick();
		assertTrue(screen.isInputCaptured(), "Keybind recorder must keep screen input captured.");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F6, 0, 0));
		assertFalse(screen.settingsPanel().isRecordingKeybind());
		assertEquals("F6", QuillClientConfig.current().shortcut(QuillClientConfig.ShortcutAction.TOGGLE_BOTTOM_PANEL).displayLabel());

		// Escape closes Settings, then pressing newly bound F6 toggles bottom panel
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
		assertFalse(screen.isSettingsOpen());
		assertFalse(session.isBottomPanelOpen());
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F6, 0, 0));
		assertTrue(session.isBottomPanelOpen());
	}

	@Test
	void newDatapackModalUseTemplateAppliesConfigDefaultLoadAndTick() {
		QuillClientConfig cfg = QuillClientConfig.current();
		cfg.setDefaultTemplateNamespace("rpg");
		cfg.setDefaultLoadFunctionName("init");
		cfg.setDefaultTickFunctionName("heartbeat");

		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		screen.quickActions().promptNewDatapack();
		assertTrue(screen.activeModal() instanceof NewDatapackModal);
		NewDatapackModal modal = (NewDatapackModal) screen.activeModal();
		assertFalse(modal.useTemplate(), "Use template must default to unchecked.");
		assertTrue(modal.useDefaultLoad(), "Use default load must default to checked.");
		assertTrue(modal.useDefaultTick(), "Use default tick must default to checked.");

		modal.setUseTemplate(true);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertEquals("my_pack", backend.createdPackName);
		assertEquals(2, backend.appliedPresets.size());
		assertEquals("minecraft:load", backend.appliedPresets.get(0).presetId());
		assertEquals(Map.of("namespace", "rpg", "functionName", "init"), backend.appliedPresets.get(0).variables());
		assertEquals("minecraft:tick", backend.appliedPresets.get(1).presetId());
		assertEquals(Map.of("namespace", "rpg", "functionName", "heartbeat"), backend.appliedPresets.get(1).variables());
		assertEquals("my_pack/data/rpg/function/init.mcfunction", session.activeTab().relativePath());
	}

	@Test
	void newDatapackModalMoreDropdownAllowsCustomLoadAndTickFunctionNames() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		screen.quickActions().promptNewDatapack();
		NewDatapackModal modal = (NewDatapackModal) screen.activeModal();
		modal.setUseTemplate(true);
		modal.setMoreExpanded(true);

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		modal.focusField(NewDatapackModal.FieldId.NAMESPACE);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_A, 'a', ctrl));
		simulateSdlTextInput(screen, bridge, "quest");

		// Typing into Load function automatically unchecks Use default
		modal.focusField(NewDatapackModal.FieldId.LOAD_FN);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_A, 'a', ctrl));
		simulateSdlTextInput(screen, bridge, "boot/start");
		assertFalse(modal.useDefaultLoad());

		// Typing into Tick function automatically unchecks Use default
		modal.focusField(NewDatapackModal.FieldId.TICK_FN);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_A, 'a', ctrl));
		simulateSdlTextInput(screen, bridge, "loop/update");
		assertFalse(modal.useDefaultTick());

		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));

		assertEquals(2, backend.appliedPresets.size());
		assertEquals(Map.of("namespace", "quest", "functionName", "boot/start"), backend.appliedPresets.get(0).variables());
		assertEquals(Map.of("namespace", "quest", "functionName", "loop/update"), backend.appliedPresets.get(1).variables());
		assertEquals("my_pack/data/quest/function/boot/start.mcfunction", session.activeTab().relativePath());
	}

	@Test
	void wholeGuiZoomShortcutsAndCommandPaletteScaleEntireIde() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		QuillClientConfig cfg = QuillClientConfig.current();
		assertEquals(QuillClientConfig.DEFAULT_TEXT_SIZE, cfg.textSize());
		assertEquals(1.0f, cfg.uiScale(), 0.0001f);

		// Ctrl+- zooms out the whole GUI even from Welcome / Editor views
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_MINUS, '-', ctrl)));
		assertEquals(8, cfg.textSize());
		assertEquals(8f / 9f, cfg.uiScale(), 0.0001f);

		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_MINUS, '-', ctrl)));
		assertEquals(QuillClientConfig.MIN_TEXT_SIZE, cfg.textSize());

		// Clamped at MIN_TEXT_SIZE
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_MINUS, '-', ctrl)));
		assertEquals(QuillClientConfig.MIN_TEXT_SIZE, cfg.textSize());

		// Ctrl+= and Ctrl+Shift+= (Ctrl++) zoom in
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_EQUALS, '=', ctrl)));
		assertEquals(8, cfg.textSize());
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_EQUALS, '+', ctrl | 1)));
		assertEquals(9, cfg.textSize());
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_ADD, 0, ctrl)));
		assertEquals(10, cfg.textSize());

		// Ctrl+0 resets zoom to 100%
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_0, '0', ctrl)));
		assertEquals(QuillClientConfig.DEFAULT_TEXT_SIZE, cfg.textSize());
		assertEquals(1.0f, cfg.uiScale(), 0.0001f);

		// Command Palette "View: Zoom In" triggers whole-GUI zoom
		screen.keyPressed(new KeyEvent(InputConstants.KEY_F1, 0, 0));
		simulateSdlTextInput(screen, bridge, "zoom in");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
		assertEquals(QuillClientConfig.DEFAULT_TEXT_SIZE + 1, cfg.textSize());
	}

	@Test
	void editorCaretHidesWhileTypingInExplorerOrSearchInput() {
		StubEditorBackend backend = new StubEditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode file = new FileTreeNode("a.mcfunction", "pack/data/demo/function/a.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode dir = new FileTreeNode("function", "pack/data/demo/function", EntryKind.DIRECTORY, List.of(file));
				FileTreeNode pack = new FileTreeNode("pack", "pack", EntryKind.DATAPACK, List.of(dir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(pack)));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();
		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/a.mcfunction", "say hi", "sha1", 0L));

		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		assertTrue(screen.isCodeEditorFocused());
		assertTrue(screen.codeEditorWidget().shouldDrawCaret(screen.isCodeEditorFocused()));

		// Starting inline file creation in Explorer must steal caret focus from code editor
		screen.explorerTree().beginInlineCreateFile();
		screen.tick();
		assertTrue(screen.explorerTree().hasActiveInlineInput());
		assertFalse(screen.isCodeEditorFocused(), "Code editor must not be considered focused while Explorer inline input is active.");
		assertFalse(screen.codeEditorWidget().shouldDrawCaret(screen.isCodeEditorFocused()),
				"Code editor must never draw its caret when unfocused.");

		// Canceling inline input returns focus/caret back to the code editor
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
		assertFalse(screen.explorerTree().hasActiveInlineInput());
		assertTrue(screen.isCodeEditorFocused());
		assertTrue(screen.codeEditorWidget().shouldDrawCaret(screen.isCodeEditorFocused()));

		// Focusing Search sidebar input must also hide code editor caret
		screen.openSearchSidebar();
		assertFalse(screen.isCodeEditorFocused());
		assertFalse(screen.codeEditorWidget().shouldDrawCaret(screen.isCodeEditorFocused()));
	}

	@Test
	void gameShortcutsLikeF3AreConsumedWhileIdeScreenIsOpen() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		RecordingTextInputBridge bridge = new RecordingTextInputBridge();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, bridge);
		screen.init();

		// Welcome view (no open tabs): F3 press & release, movement/inventory keys must all be consumed
		KeyEvent f3Event = new KeyEvent(InputConstants.KEY_F3, 0, 0);
		assertTrue(screen.keyPressed(f3Event), "Welcome screen must consume F3 press so debug overlay cannot toggle.");
		assertTrue(screen.keyReleased(f3Event), "Welcome screen must consume F3 release so KeyboardHandler does not toggle F3.");
		assertTrue(screen.keyPressed(new KeyEvent(InputConstants.KEY_E, 'e', 0)));
		assertTrue(screen.keyReleased(new KeyEvent(InputConstants.KEY_E, 'e', 0)));

		// Settings view: F3 press & release must also be consumed
		screen.openSettings();
		assertTrue(screen.keyPressed(f3Event), "Settings view must consume F3 press.");
		assertTrue(screen.keyReleased(f3Event), "Settings view must consume F3 release.");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
		assertFalse(screen.isSettingsOpen());

		// Active code tab view: unhandled function keys like F3 / F5 must still be consumed
		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/a.mcfunction", "say hi", "sha1", 0L));
		screen.tick();
		assertTrue(screen.keyPressed(f3Event), "Code editor view must consume F3 press.");
		assertTrue(screen.keyReleased(f3Event), "Code editor view must consume F3 release.");
	}

	@Test
	void codeEditorLeftIndentIsCompact() {
		assertTrue(CodeEditorWidget.codeLeftPadding() <= 3,
				"Left padding before column 0 must not look like a full-character indent (<= 3px), was: "
						+ CodeEditorWidget.codeLeftPadding());
	}

	@Test
	void creatingDatapackSwitchesSidebarToFileExplorer() {
		StubEditorBackend backend = new StubEditorBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.setSidebarView(ClientEditorSession.SidebarView.PRESETS);
		session.setSidebarCollapsed(true);

		session.createDatapack("my_pack", "desc").join();
		assertEquals(ClientEditorSession.SidebarView.EXPLORER, session.sidebarView());
		assertFalse(session.isSidebarCollapsed());

		session.setSidebarView(ClientEditorSession.SidebarView.SEARCH);
		session.createDatapackWithTemplate("tpl_pack", "desc", true, "demo", "load", "tick").join();
		assertEquals(ClientEditorSession.SidebarView.EXPLORER, session.sidebarView());
		assertFalse(session.isSidebarCollapsed());
	}

	private static void simulateSdlTextInput(QuillIdeScreen screen, RecordingTextInputBridge bridge, String text) {
		if (!bridge.isActiveFor(screen)) {
			return;
		}
		text.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp)));
	}
}
