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

	private static final class StubEditorBackend implements EditorBackend {
		String createdPackName;
		String createdPackDescription;

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
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> renameEntry(String sourceRelativePath, String targetRelativePath) {
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

	private static void simulateSdlTextInput(QuillIdeScreen screen, RecordingTextInputBridge bridge, String text) {
		if (!bridge.isActiveFor(screen)) {
			return;
		}
		text.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp)));
	}
}
