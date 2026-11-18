package dev.catgirlclaude.quill.client.gui;

import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import dev.catgirlclaude.quill.client.session.OpenTabState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTabBarWidgetTest {

	@Test
	void tabContextMenuSupportsPinCloseOthersCloseAllCopyPathAndExcludesCutAndDelete() {
		EditorBackend backend = new EditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of()));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(new FileDocument(relativePath, "say " + relativePath, "sha", 0L));
			}

			@Override
			public CompletableFuture<SaveResult> writeFile(String relativePath, String content, String expectedBaseSha256, boolean reloadAfterSave) {
				return CompletableFuture.completedFuture(new SaveResult(new FileDocument(relativePath, content, "sha2", 0L), null));
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
				return CompletableFuture.failedFuture(new UnsupportedOperationException());
			}

			@Override
			public CompletableFuture<ReloadReport> reloadDatapacks() {
				return CompletableFuture.completedFuture(new ReloadReport(true, 1L, 0, List.of()));
			}
		};

		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run, "Local", Path.of("/tmp/quill-tab-test"));
		session.openFile("pack/data/demo/function/a.mcfunction");
		session.openFile("pack/data/demo/function/b.mcfunction");
		session.openFile("pack/data/demo/function/c.mcfunction");
		assertEquals(3, session.openTabs().size());

		List<String> copiedClip = new ArrayList<>();
		List<String> revealed = new ArrayList<>();
		List<String> searched = new ArrayList<>();
		List<String> renamed = new ArrayList<>();
		List<ModalPromptOverlay> modals = new ArrayList<>();

		EditorTabBarWidget tabBar = new EditorTabBarWidget(session, modals::add, modals::clear);
		tabBar.setSystemClipboardCopier(copiedClip::add);
		tabBar.setTabActionHooks(
				menu -> {},
				() -> {},
				revealed::add,
				searched::add,
				renamed::add
		);

		List<ContextMenuOverlay.MenuEntry> entries = tabBar.buildTabContextMenuEntries(1);
		List<String> labels = entries.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(e -> ((ContextMenuOverlay.ActionItem) e).label())
				.toList();

		for (String required : List.of("Pin", "Close", "Close All But This", "Close All",
				"Open to Side", "Reveal in File Explorer", "Find File References", "Copy", "Copy Path", "Rename")) {
			assertTrue(labels.contains(required), "Missing required tab context menu item '" + required + "' in " + labels);
		}
		assertFalse(labels.contains("Cut"), "Tab context menu must exclude 'Cut': " + labels);
		assertFalse(labels.contains("Delete"), "Tab context menu must exclude 'Delete': " + labels);

		// Pinning tab index 1 ("b.mcfunction") marks it pinned and moves it to index 0
		findItem(entries, "Pin").action().run();
		OpenTabState bTab = session.findOpenTab("pack/data/demo/function/b.mcfunction");
		assertTrue(bTab.isPinned());
		assertEquals(0, session.openTabs().indexOf(bTab));

		// Unpinned tab 2 ("c.mcfunction") menu now says "Close All But This" -> closes unpinned tabs except c, keeps pinned b and c
		List<ContextMenuOverlay.MenuEntry> pinnedBMenu = tabBar.buildTabContextMenuEntries(0);
		assertTrue(pinnedBMenu.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(e -> ((ContextMenuOverlay.ActionItem) e).label())
				.toList()
				.contains("Unpin"));

		// Copy Path copies relativePath
		findItem(pinnedBMenu, "Copy Path").action().run();
		assertEquals(List.of("pack/data/demo/function/b.mcfunction"), copiedClip);

		// Close All But This from c.mcfunction keeps pinned b.mcfunction + c.mcfunction, removes a.mcfunction
		int cIdx = session.openTabs().indexOf(session.findOpenTab("pack/data/demo/function/c.mcfunction"));
		findItem(tabBar.buildTabContextMenuEntries(cIdx), "Close All But This").action().run();
		assertEquals(2, session.openTabs().size());
		assertTrue(session.findOpenTab("pack/data/demo/function/b.mcfunction") != null);
		assertTrue(session.findOpenTab("pack/data/demo/function/c.mcfunction") != null);
		assertEquals(null, session.findOpenTab("pack/data/demo/function/a.mcfunction"));

		// Close All closes unpinned tabs first? Wait: VS Code "Close All" closes all tabs (or unpinned? Let's close all tabs so Close All actually clears!)
		findItem(tabBar.buildTabContextMenuEntries(0), "Close All").action().run();
		assertEquals(0, session.openTabs().size());
	}

	private static ContextMenuOverlay.ActionItem findItem(List<ContextMenuOverlay.MenuEntry> entries, String label) {
		return entries.stream()
				.filter(ContextMenuOverlay.ActionItem.class::isInstance)
				.map(ContextMenuOverlay.ActionItem.class::cast)
				.filter(a -> a.label().equals(label))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing menu item: " + label));
	}
}
