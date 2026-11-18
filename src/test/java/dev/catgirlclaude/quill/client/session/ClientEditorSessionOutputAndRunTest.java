package dev.catgirlclaude.quill.client.session;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadDiagnostic;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
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

	private static class MockBackend implements EditorBackend {
		ReloadReport nextReloadReport = new ReloadReport(true, 12L, 2, List.of());
		int writeCount = 0;

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
			ReloadReport report = reloadAfterSave ? nextReloadReport : null;
			return CompletableFuture.completedFuture(new SaveResult(new FileDocument(relativePath, content, "sha_" + writeCount, 0L), report));
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
}
