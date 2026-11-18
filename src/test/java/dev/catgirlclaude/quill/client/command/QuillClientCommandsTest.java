package dev.catgirlclaude.quill.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.gui.QuillIdeScreen;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuillClientCommandsTest {

	private static final class RecordingActions implements QuillClientCommands.CommandActions {
		final List<String> calls = new ArrayList<>();
		final List<String> suggestions = List.of(
				"my_pack/pack.mcmeta",
				"my_pack/data/demo/function/tick.mcfunction",
				"my_pack/data/demo/function/load.mcfunction"
		);

		@Override
		public void openIde() {
			calls.add("openIde");
		}

		@Override
		public void openSettings() {
			calls.add("openSettings");
		}

		@Override
		public void openExplorer() {
			calls.add("openExplorer");
		}

		@Override
		public void openSearch(String query) {
			calls.add("openSearch:" + (query == null ? "" : query));
		}

		@Override
		public void openInspector() {
			calls.add("openInspector");
		}

		@Override
		public void openPresets() {
			calls.add("openPresets");
		}

		@Override
		public void openFile(String path) {
			calls.add("openFile:" + path);
		}

		@Override
		public void newDatapack() {
			calls.add("newDatapack");
		}

		@Override
		public void reloadDatapacks() {
			calls.add("reloadDatapacks");
		}

		@Override
		public List<String> suggestFilePaths() {
			return suggestions;
		}
	}

	@Test
	void brigadierCommandTreeDispatchesAllApprovedQuillSubcommandsAndPathSuggestions() throws Exception {
		RecordingActions actions = new RecordingActions();
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		QuillClientCommands.registerTree(
				dispatcher,
				LiteralArgumentBuilder::literal,
				name -> RequiredArgumentBuilder.argument(name, StringArgumentType.greedyString()),
				source -> actions
		);

		Object source = new Object();
		dispatcher.execute("quill", source);
		dispatcher.execute("quill config", source);
		dispatcher.execute("quill settings", source);
		dispatcher.execute("quill explorer", source);
		dispatcher.execute("quill search", source);
		dispatcher.execute("quill search scoreboard coins", source);
		dispatcher.execute("quill inspector", source);
		dispatcher.execute("quill presets", source);
		dispatcher.execute("quill open my_pack/data/demo/function/tick.mcfunction", source);
		dispatcher.execute("quill new datapack", source);
		dispatcher.execute("quill reload", source);

		assertEquals(List.of(
				"openIde",
				"openSettings",
				"openSettings",
				"openExplorer",
				"openSearch:",
				"openSearch:scoreboard coins",
				"openInspector",
				"openPresets",
				"openFile:my_pack/data/demo/function/tick.mcfunction",
				"newDatapack",
				"reloadDatapacks"
		), actions.calls);

		Suggestions suggestions = dispatcher.getCompletionSuggestions(
				dispatcher.parse("quill open tick", source)).get();
		List<String> texts = suggestions.getList().stream().map(s -> s.getText()).toList();
		assertTrue(texts.contains("my_pack/data/demo/function/tick.mcfunction"), "Expected path completion, got: " + texts);
	}

	@Test
	void screenLaunchHelpersConfigureSettingsSidebarsNewDatapackAndFile() {
		EditorBackend backend = new EditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				FileTreeNode fn = new FileTreeNode("init.mcfunction", "my_pack/data/demo/function/init.mcfunction", EntryKind.FILE, List.of());
				FileTreeNode dir = new FileTreeNode("function", "my_pack/data/demo/function", EntryKind.DIRECTORY, List.of(fn));
				FileTreeNode pack = new FileTreeNode("my_pack", "my_pack", EntryKind.DATAPACK, List.of(dir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(pack)));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(new FileDocument(relativePath, "say hi", "sha", 0L));
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
				return CompletableFuture.completedFuture(new ReloadReport(true, 3L, 1, List.of()));
			}
		};

		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();

		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, new QuillIdeScreen.TextInputBridge() {
			@Override
			public void startTextInput(Object owner) {
			}

			@Override
			public void stopTextInput(Object owner) {
			}
		});

		screen.openSettings();
		assertTrue(screen.isSettingsOpen());

		session.setSidebarCollapsed(true);
		screen.openExplorer();
		assertFalse(screen.isSettingsOpen());
		assertFalse(session.isSidebarCollapsed());
		assertEquals(ClientEditorSession.SidebarView.EXPLORER, session.sidebarView());

		screen.openSearch("say hi");
		assertEquals(ClientEditorSession.SidebarView.SEARCH, session.sidebarView());
		assertEquals("say hi", screen.searchSidebar().queryText());

		screen.openInspector();
		assertEquals(ClientEditorSession.SidebarView.INSPECTOR, session.sidebarView());

		screen.openPresets();
		assertEquals(ClientEditorSession.SidebarView.PRESETS, session.sidebarView());

		screen.openFileFromCommand("my_pack/data/demo/function/init.mcfunction");
		assertEquals("my_pack/data/demo/function/init.mcfunction", session.activeTab().relativePath());

		screen.openNewDatapackModal();
		assertTrue(screen.activeModal() != null);
	}
}
