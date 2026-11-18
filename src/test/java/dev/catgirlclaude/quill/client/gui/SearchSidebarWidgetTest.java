package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.editor.WorkspaceFileCache;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchSidebarWidgetTest {

	@Test
	void includeAndExcludeFilterFieldsStartHiddenToggleOnButtonAndFilterLiveResults() {
		Map<String, FileDocument> docs = Map.of(
				"pack/data/demo/function/main.mcfunction",
				new FileDocument("pack/data/demo/function/main.mcfunction", "say target", "s1", 1L),
				"pack/data/demo/function/ignored/sub.mcfunction",
				new FileDocument("pack/data/demo/function/ignored/sub.mcfunction", "say target", "s2", 1L),
				"pack/data/demo/tags/function/tick.json",
				new FileDocument("pack/data/demo/tags/function/tick.json", "{\"target\":true}", "s3", 1L)
		);
		EditorBackend backend = new EditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				List<FileTreeNode> nodes = docs.keySet().stream()
						.map(p -> new FileTreeNode(p.substring(p.lastIndexOf('/') + 1), p, EntryKind.FILE, List.of()))
						.toList();
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, nodes));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(docs.get(relativePath));
			}

			@Override
			public CompletableFuture<SaveResult> writeFile(String p, String c, String s, boolean r) {
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletableFuture<Void> createEntry(String p, EntryKind k) {
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletableFuture<Void> renameEntry(String s, String t) {
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletableFuture<Void> deleteEntry(String p) {
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletableFuture<String> createDatapack(String n, String d) {
				return CompletableFuture.completedFuture(n);
			}

			@Override
			public CompletableFuture<String> createNamespace(String p, String n) {
				return CompletableFuture.completedFuture(p);
			}

			@Override
			public CompletableFuture<String> createFunction(String p, String n, String f) {
				return CompletableFuture.completedFuture(p);
			}

			@Override
			public CompletableFuture<List<PresetDescriptor>> listPresets() {
				return CompletableFuture.completedFuture(List.of());
			}

			@Override
			public CompletableFuture<FileDocument> applyPreset(String p, String id, Map<String, String> v) {
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public CompletableFuture<ReloadReport> reloadDatapacks() {
				return CompletableFuture.completedFuture(new ReloadReport(true, 1L, 1, List.of()));
			}
		};

		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();
		SearchSidebarWidget widget = new SearchSidebarWidget(session, new WorkspaceFileCache(), m -> {}, () -> {});

		assertFalse(widget.isFiltersExpanded(), "Include/Exclude fields must be collapsed by default.");
		widget.focusSearchInput("target");
		assertEquals(3, widget.lastOutcome().files().size());

		// Toggle filters expanded via button rect
		widget.setLayoutBoundsForTest(
				new IdeLayout.Rect(160, 44, 18, 14),
				new IdeLayout.Rect(6, 84, 168, 16),
				new IdeLayout.Rect(6, 114, 168, 16)
		);
		assertTrue(widget.mouseClicked(new MouseButtonEvent(
				168, 50, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))));
		assertTrue(widget.isFiltersExpanded(), "Clicking toggle button must expand include/exclude inputs.");

		// Focus include field and type "*.mcfunction" -> drops .json result
		assertTrue(widget.mouseClicked(new MouseButtonEvent(
				20, 90, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))));
		assertEquals(SearchSidebarWidget.FocusedField.INCLUDE, widget.focusedField());
		for (int cp : "*.mcfunction".codePoints().toArray()) {
			assertTrue(widget.charTyped(new CharacterEvent(cp)));
		}
		assertEquals("*.mcfunction", widget.includeText());
		assertEquals(2, widget.lastOutcome().files().size());

		// Tab moves from INCLUDE -> EXCLUDE, type "**/ignored/**" -> drops ignored/sub.mcfunction
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0)));
		assertEquals(SearchSidebarWidget.FocusedField.EXCLUDE, widget.focusedField());
		for (int cp : "**/ignored/**".codePoints().toArray()) {
			assertTrue(widget.charTyped(new CharacterEvent(cp)));
		}
		assertEquals("**/ignored/**", widget.excludeText());
		assertEquals(1, widget.lastOutcome().files().size());
		assertEquals("pack/data/demo/function/main.mcfunction", widget.lastOutcome().files().getFirst().relativePath());
	}
}
