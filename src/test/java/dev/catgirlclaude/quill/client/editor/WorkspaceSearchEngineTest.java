package dev.catgirlclaude.quill.client.editor;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSearchEngineTest {

	@Test
	void plainAndCaseSensitiveSearchAndMacroDollarReplacement() {
		String text = "say Hello world\nsay hello again\n$say $(name)";
		Pattern ci = WorkspaceSearchEngine.compilePattern(new WorkspaceSearchEngine.SearchOptions("hello", false, false));
		List<WorkspaceSearchEngine.LineMatch> ciMatches = WorkspaceSearchEngine.searchContent("a.mcfunction", text, ci);
		assertEquals(2, ciMatches.size());
		assertEquals(1, ciMatches.get(0).oneBasedLine());
		assertEquals(4, ciMatches.get(0).startCol());
		assertEquals(9, ciMatches.get(0).endCol());
		assertEquals(2, ciMatches.get(1).oneBasedLine());

		Pattern cs = WorkspaceSearchEngine.compilePattern(new WorkspaceSearchEngine.SearchOptions("Hello", true, false));
		assertEquals(1, WorkspaceSearchEngine.searchContent("a.mcfunction", text, cs).size());

		// Plain replacement containing Minecraft macro syntax "$(greeting)" must not be interpreted as regex group
		WorkspaceSearchEngine.ReplaceTextResult replaced = WorkspaceSearchEngine.replaceInContent(
				text,
				new WorkspaceSearchEngine.SearchOptions("hello", false, false),
				"$(greeting)"
		);
		assertEquals(2, replaced.replacementCount());
		assertEquals("say $(greeting) world\nsay $(greeting) again\n$say $(name)", replaced.updatedContent());
	}

	@Test
	void regexSearchHandlesCaptureGroupsInvalidSyntaxAndZeroWidthMatches() {
		String content = "scoreboard players set @a score_a 10\nscoreboard players set @p score_b 20";
		WorkspaceSearchEngine.ReplaceTextResult replaced = WorkspaceSearchEngine.replaceInContent(
				content,
				new WorkspaceSearchEngine.SearchOptions("score_([ab])", true, true),
				"obj_$1"
		);
		assertEquals(2, replaced.replacementCount());
		assertEquals("scoreboard players set @a obj_a 10\nscoreboard players set @p obj_b 20", replaced.updatedContent());

		// Zero-width regex must not loop forever
		Pattern zeroWidth = WorkspaceSearchEngine.compilePattern(new WorkspaceSearchEngine.SearchOptions("a*", false, true));
		List<WorkspaceSearchEngine.LineMatch> matches = WorkspaceSearchEngine.searchContent("a.mcfunction", "bbb", zeroWidth);
		assertTrue(matches.isEmpty());
	}

	@Test
	void workspaceSearchAndReplaceHonorsLiveOpenTabsWithSingleStepUndoAndClosedFilesWithSha() {
		Map<String, FileDocument> disk = new HashMap<>();
		disk.put("pack/data/demo/function/open.mcfunction", new FileDocument("pack/data/demo/function/open.mcfunction", "say old_disk", "sha_open", 1L));
		disk.put("pack/data/demo/function/closed.mcfunction", new FileDocument("pack/data/demo/function/closed.mcfunction", "say target_val\nsay target_val", "sha_closed", 1L));

		int[] reloadCount = {0};
		Map<String, String> seenExpectedShas = new HashMap<>();

		EditorBackend backend = new EditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode f1 = new FileTreeNode("open.mcfunction", "pack/data/demo/function/open.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode f2 = new FileTreeNode("closed.mcfunction", "pack/data/demo/function/closed.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode dir = new FileTreeNode("function", "pack/data/demo/function", EntryKind.DIRECTORY, List.of(f1, f2));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(dir)));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(disk.get(relativePath));
			}

			@Override
			public CompletableFuture<SaveResult> writeFile(String relativePath, String content, String expectedBaseSha256, boolean reloadAfterSave) {
				seenExpectedShas.put(relativePath, expectedBaseSha256);
				FileDocument saved = new FileDocument(relativePath, content, "sha_new", 2L);
				disk.put(relativePath, saved);
				return CompletableFuture.completedFuture(new SaveResult(saved, null));
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
				return CompletableFuture.completedFuture(packName);
			}

			@Override
			public CompletableFuture<String> createFunction(String packName, String namespace, String functionPath) {
				return CompletableFuture.completedFuture(packName);
			}

			@Override
			public CompletableFuture<List<PresetDescriptor>> listPresets() {
				return CompletableFuture.completedFuture(List.of());
			}

			@Override
			public CompletableFuture<FileDocument> applyPreset(String packName, String presetId, Map<String, String> variables) {
				return CompletableFuture.completedFuture(disk.values().iterator().next());
			}

			@Override
			public CompletableFuture<ReloadReport> reloadDatapacks() {
				reloadCount[0]++;
				return CompletableFuture.completedFuture(new ReloadReport(true, 4L, 1, List.of()));
			}
		};

		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();

		// Open one tab and give it unsaved content containing target_val
		session.openFile("pack/data/demo/function/open.mcfunction");
		session.activeTab().buffer().replaceAllText("say target_val unsaved");
		assertTrue(session.activeTab().isDirty());

		WorkspaceFileCache cache = new WorkspaceFileCache();
		WorkspaceSearchEngine.SearchOptions opts = new WorkspaceSearchEngine.SearchOptions("target_val", true, false);

		// Invalid regex reports error safely
		WorkspaceSearchEngine.SearchOutcome badRegex = WorkspaceSearchEngine
				.searchWorkspace(session, new WorkspaceSearchEngine.SearchOptions("[unclosed", false, true), cache)
				.join();
		assertTrue(badRegex.hasError());

		WorkspaceSearchEngine.SearchOutcome outcome = WorkspaceSearchEngine.searchWorkspace(session, opts, cache).join();
		assertFalse(outcome.hasError());
		assertEquals(2, outcome.files().size());
		assertEquals(3, outcome.totalMatches());

		// Replace across both files
		WorkspaceSearchEngine.WorkspaceReplaceSummary summary = WorkspaceSearchEngine.replaceInFiles(
				session,
				List.of("pack/data/demo/function/open.mcfunction", "pack/data/demo/function/closed.mcfunction"),
				opts,
				"renamed_val",
				cache
		).join();

		assertEquals(3, summary.totalReplacements());
		assertEquals(1, summary.modifiedOpenTabs());
		assertEquals(1, summary.modifiedClosedFiles());
		assertTrue(summary.errors().isEmpty());
		assertEquals(1, reloadCount[0], "Closed-file replacement must trigger a single reload.");
		assertEquals("sha_closed", seenExpectedShas.get("pack/data/demo/function/closed.mcfunction"));
		assertEquals("say renamed_val\nsay renamed_val", disk.get("pack/data/demo/function/closed.mcfunction").content());

		// Open tab updated in-place and single Ctrl+Z undoes cleanly!
		assertEquals("say renamed_val unsaved", session.activeTab().buffer().getText());
		assertTrue(session.activeTab().buffer().undo());
		assertEquals("say target_val unsaved", session.activeTab().buffer().getText());
	}
}
