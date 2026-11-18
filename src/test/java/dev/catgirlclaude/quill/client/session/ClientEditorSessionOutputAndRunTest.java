package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientEditorSessionOutputAndRunTest {

	@AfterEach
	void resetConfig() {
		QuillClientConfig.setCurrent(QuillClientConfig.createDefault());
	}

	private static class MockBackend implements EditorBackend {
		ReloadReport nextReloadReport = new ReloadReport(true, 12L, 2, List.of());
		int writeCount = 0;
		int writeWithReloadCount = 0;
		int reloadCount = 0;
		String lastWrittenContent = null;
		String lastExpectedSha = null;
		boolean deferWrites = false;
		final List<CompletableFuture<SaveResult>> pendingWrites = new ArrayList<>();
		final List<String> pendingPaths = new ArrayList<>();
		final List<String> pendingContents = new ArrayList<>();

		@Override
		public CompletableFuture<FileTreeNode> listTree() {
			return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of()));
		}

		@Override
		public CompletableFuture<FileDocument> readFile(String relativePath) {
			return CompletableFuture.completedFuture(new FileDocument(relativePath, "say hello\nsay world", "sha", 0L));
		}

		@Override
		public CompletableFuture<SaveResult> writeFile(String relativePath, String content, String expectedBaseSha256, boolean reloadAfterSave) {
			writeCount++;
			if (reloadAfterSave) {
				writeWithReloadCount++;
			}
			lastWrittenContent = content;
			lastExpectedSha = expectedBaseSha256;
			if (deferWrites) {
				CompletableFuture<SaveResult> future = new CompletableFuture<>();
				pendingWrites.add(future);
				pendingPaths.add(relativePath);
				pendingContents.add(content);
				return future;
			}
			ReloadReport report = reloadAfterSave ? nextReloadReport : null;
			return CompletableFuture.completedFuture(new SaveResult(new FileDocument(relativePath, content, "sha_" + writeCount, 0L), report));
		}

		void completeNextPendingWrite(boolean withReload) {
			CompletableFuture<SaveResult> f = pendingWrites.removeFirst();
			String path = pendingPaths.removeFirst();
			String content = pendingContents.removeFirst();
			ReloadReport report = withReload ? nextReloadReport : null;
			f.complete(new SaveResult(new FileDocument(path, content, "sha_async_" + writeCount, 0L), report));
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
			return CompletableFuture.completedFuture(new FileDocument(packName + "/pack.mcmeta", "{}", "sha", 0L));
		}

		@Override
		public CompletableFuture<ReloadReport> reloadDatapacks() {
			reloadCount++;
			return CompletableFuture.completedFuture(nextReloadReport);
		}
	}

	@Test
	void runCurrentFunctionDispatchesImmediatelyWhenCleanAndCapturesGameMessages() {
		MockBackend backend = new MockBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		List<String> sent = new ArrayList<>();
		session.setCommandDispatcher(sent::add);

		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/sub/hello.mcfunction", "say hi", "sha", 0L));
		assertEquals("demo:sub/hello", session.activeFunctionId());

		session.runCurrentFunction();

		assertEquals(List.of("function demo:sub/hello"), sent);
		assertEquals(0, backend.writeCount);
		assertTrue(session.isBottomPanelOpen());
		assertEquals(ClientEditorSession.BottomPanelTab.OUTPUT, session.bottomPanelTab());

		session.onGameMessageReceived("Executed 1 commands from function 'demo:sub/hello'");
		assertTrue(session.outputLog().stream().anyMatch(e -> e.message().contains("[MC] Executed 1 commands")));
	}

	@Test
	void runCurrentFunctionSavesAndReloadsFirstWhenDirtyAndAbortsOnReloadErrors() {
		MockBackend backend = new MockBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		List<String> sent = new ArrayList<>();
		session.setCommandDispatcher(sent::add);

		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/init.mcfunction", "say a", "sha", 0L));
		session.activeTab().buffer().insertText("say b\n");
		assertTrue(session.activeTab().isDirty());

		// First attempt: reload fails -> function should NOT be executed, and panel switches to PROBLEMS
		backend.nextReloadReport = new ReloadReport(
				false,
				8L,
				1,
				List.of(new ReloadDiagnostic("pack/data/demo/function/init.mcfunction", 1, "Unknown command"))
		);
		session.runCurrentFunction();
		assertEquals(1, backend.writeCount);
		assertTrue(sent.isEmpty(), "Must not run function when reload produced errors.");
		assertEquals(ClientEditorSession.BottomPanelTab.PROBLEMS, session.bottomPanelTab());
		assertTrue(session.outputLog().stream().anyMatch(e -> e.level() == ClientEditorSession.LogLevel.ERROR && e.message().contains("Unknown command")));

		// Second attempt: edit cleanly and reload succeeds -> saves, reloads, and runs /function
		session.activeTab().buffer().insertText("# fixed\n");
		backend.nextReloadReport = new ReloadReport(true, 5L, 1, List.of());
		session.runCurrentFunction();
		assertEquals(2, backend.writeCount);
		assertEquals(List.of("function demo:init"), sent);
	}

	@Test
	void nonFunctionFileCannotBeRunAndOpenFileAndSelectHighlightsMatch() {
		MockBackend backend = new MockBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		List<String> sent = new ArrayList<>();
		session.setCommandDispatcher(sent::add);

		session.openOrFocusDocument(new FileDocument("pack/pack.mcmeta", "{}", "sha", 0L));
		assertNull(session.activeFunctionId());

		session.runCurrentFunction();
		assertTrue(sent.isEmpty());
		assertTrue(session.statusIsError());

		session.openFileAndSelect("pack/data/demo/function/demo.mcfunction", 2, 4, 9);
		assertEquals("world", session.activeTab().buffer().getSelectedText());
		assertFalse(session.activeTab().isDirty());
	}

	@Test
	void keypressAutosaveCoalescesRapidEditsAndReloadsOnceOnClose() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		cfg.setAutosaveMode(QuillClientConfig.AutosaveMode.ON_KEYPRESS);
		cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);
		QuillClientConfig.setCurrent(cfg);

		MockBackend backend = new MockBackend();
		backend.deferWrites = true;
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		OpenTabState tab = session.openOrFocusDocument(
				new FileDocument("pack/data/demo/function/a.mcfunction", "say ", "sha_0", 0L));

		// Rapid typing while first write is still in flight
		tab.buffer().setCursor(0, 4, false);
		tab.buffer().insertText("1");
		session.notifyTabBufferEdited(tab);
		assertEquals(1, backend.writeCount);
		assertEquals("sha_0", backend.lastExpectedSha);
		assertEquals(0, backend.writeWithReloadCount);

		tab.buffer().insertText("2");
		session.notifyTabBufferEdited(tab);
		tab.buffer().insertText("3");
		session.notifyTabBufferEdited(tab);
		// Must coalesce without launching overlapping writes
		assertEquals(1, backend.writeCount);

		// Completing first write launches second write with fresh updated SHA
		backend.completeNextPendingWrite(false);
		assertEquals(2, backend.writeCount);
		assertEquals("sha_async_1", backend.lastExpectedSha);
		assertEquals("say 123", backend.lastWrittenContent);

		backend.completeNextPendingWrite(false);
		assertFalse(tab.isDirty());
		assertTrue(session.isReloadPendingOnClose());
		assertEquals(0, backend.reloadCount);

		session.onIdeClosed();
		assertEquals(1, backend.reloadCount);
		assertFalse(session.isReloadPendingOnClose());
	}

	@Test
	void autosaveOnGuiCloseSavesAllDirtyTabsAndTriggersSingleReload() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		cfg.setAutosaveMode(QuillClientConfig.AutosaveMode.ON_GUI_CLOSE);
		cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);
		QuillClientConfig.setCurrent(cfg);

		MockBackend backend = new MockBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		OpenTabState t1 = session.openOrFocusDocument(new FileDocument("p/a.mcfunction", "a", "sha1", 0L));
		OpenTabState t2 = session.openOrFocusDocument(new FileDocument("p/b.mcfunction", "b", "sha2", 0L));
		t1.buffer().insertText("1");
		t2.buffer().insertText("2");

		// Not saved on keypress
		session.notifyTabBufferEdited(t1);
		assertEquals(0, backend.writeCount);

		// Saved & reloaded once on close
		session.onIdeClosed();
		assertEquals(2, backend.writeCount);
		assertEquals(0, backend.writeWithReloadCount);
		assertEquals(1, backend.reloadCount);
		assertFalse(t1.isDirty());
		assertFalse(t2.isDirty());
	}

	@Test
	void manualSaveWithReloadOnCloseDefersReloadUntilGuiCloses() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		cfg.setAutosaveMode(QuillClientConfig.AutosaveMode.OFF);
		cfg.setReloadTriggerMode(QuillClientConfig.ReloadTriggerMode.ON_GUI_CLOSE);
		QuillClientConfig.setCurrent(cfg);

		MockBackend backend = new MockBackend();
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		OpenTabState tab = session.openOrFocusDocument(new FileDocument("p/a.mcfunction", "a", "sha1", 0L));
		tab.buffer().insertText("!");

		session.saveActiveTabAccordingToConfig();
		assertEquals(1, backend.writeCount);
		assertEquals(0, backend.writeWithReloadCount);
		assertEquals(0, backend.reloadCount);
		assertTrue(session.isReloadPendingOnClose());

		session.onIdeClosed();
		assertEquals(1, backend.reloadCount);
		assertFalse(session.isReloadPendingOnClose());
	}
}
