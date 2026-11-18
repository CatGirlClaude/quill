package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuillIdeScreenTextInputTest {

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

	private static class StubEditorBackend implements EditorBackend {
		String createdPackName;
		String createdPackDescription;
		String createdEntryPath;
		EntryKind createdEntryKind;
		String renamedSourcePath;
		String renamedTargetPath;
		int renameCount;

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
			return CompletableFuture.completedFuture(new FileDocument(packName + "/pack.mcmeta", "{}", "hash", 0L));
		}

		@Override
		public CompletableFuture<ReloadReport> reloadDatapacks() {
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

	private static void simulateSdlTextInput(QuillIdeScreen screen, RecordingTextInputBridge bridge, String text) {
		if (!bridge.isActiveFor(screen)) {
			return;
		}
		text.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp)));
	}
}
