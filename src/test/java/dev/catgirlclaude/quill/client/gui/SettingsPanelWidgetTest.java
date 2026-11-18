package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.catgirlclaude.quill.api.EditorBackend;
import dev.catgirlclaude.quill.api.EntryKind;
import dev.catgirlclaude.quill.api.FileDocument;
import dev.catgirlclaude.quill.api.FileTreeNode;
import dev.catgirlclaude.quill.api.PresetDescriptor;
import dev.catgirlclaude.quill.api.ReloadReport;
import dev.catgirlclaude.quill.api.SaveResult;
import dev.catgirlclaude.quill.client.config.KeyShortcut;
import dev.catgirlclaude.quill.client.config.QuillClientConfig;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPanelWidgetTest {

	@AfterEach
	void resetConfig() {
		QuillClientConfig.setCurrent(QuillClientConfig.createDefault());
	}

	@Test
	void categoryNavigationScrollsToRequestedSectionOffset() {
		SettingsPanelWidget panel = new SettingsPanelWidget(() -> { }, () -> { });
		assertEquals(5, SettingsPanelWidget.Category.values().length);
		assertEquals(SettingsPanelWidget.Category.APPEARANCE, panel.activeCategory());

		panel.setSectionAnchorOffset(SettingsPanelWidget.Category.APPEARANCE, 0);
		panel.setSectionAnchorOffset(SettingsPanelWidget.Category.GAMEPLAY, 320);
		panel.setSectionAnchorOffset(SettingsPanelWidget.Category.TEMPLATES, 540);
		panel.setSectionAnchorOffset(SettingsPanelWidget.Category.WINDOW, 700);
		panel.setSectionAnchorOffset(SettingsPanelWidget.Category.KEYBINDINGS, 860);
		panel.setContentMetrics(1300, 400);

		panel.scrollToCategory(SettingsPanelWidget.Category.KEYBINDINGS);
		assertEquals(860, panel.scrollPixels());
		assertEquals(SettingsPanelWidget.Category.KEYBINDINGS, panel.activeCategory());

		panel.scrollToCategory(SettingsPanelWidget.Category.GAMEPLAY);
		assertEquals(320, panel.scrollPixels());
		assertEquals(SettingsPanelWidget.Category.GAMEPLAY, panel.activeCategory());
	}

	@Test
	void reboundUndoAndRedoShortcutsControlBothFileAndCodeStacks() {
		QuillClientConfig cfg = QuillClientConfig.createDefault();
		cfg.setShortcut(QuillClientConfig.ShortcutAction.UNDO, KeyShortcut.ofChar(true, false, false, 'u'));
		cfg.setShortcut(QuillClientConfig.ShortcutAction.REDO, KeyShortcut.ofChar(true, true, false, 'u'));
		QuillClientConfig.setCurrent(cfg);

		var deletedPaths = new java.util.ArrayList<String>();
		var backend = new EditorBackend() {
			@Override public CompletableFuture<FileTreeNode> listTree() {
				var victim = new FileTreeNode("victim.mcfunction", "pack/data/demo/function/victim.mcfunction", EntryKind.FILE, List.of());
				var fnDir = new FileTreeNode("function", "pack/data/demo/function", EntryKind.DIRECTORY, List.of(victim));
				var demoDir = new FileTreeNode("demo", "pack/data/demo", EntryKind.DIRECTORY, List.of(fnDir));
				var dataDir = new FileTreeNode("data", "pack/data", EntryKind.DIRECTORY, List.of(demoDir));
				var packDir = new FileTreeNode("pack", "pack", EntryKind.DIRECTORY, List.of(dataDir));
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of(packDir)));
			}
			@Override public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(new FileDocument(relativePath, "say hello", "h1", 0L));
			}
			@Override public CompletableFuture<SaveResult> writeFile(String p, String c, String s, boolean r) {
				return CompletableFuture.completedFuture(new SaveResult(new FileDocument(p, c, "h2", 0L), null));
			}
			@Override public CompletableFuture<Void> createEntry(String p, EntryKind k) {
				deletedPaths.add("create:" + p);
				return CompletableFuture.completedFuture(null);
			}
			@Override public CompletableFuture<Void> renameEntry(String s, String t) {
				return CompletableFuture.completedFuture(null);
			}
			@Override public CompletableFuture<Void> deleteEntry(String p) {
				deletedPaths.add("delete:" + p);
				return CompletableFuture.completedFuture(null);
			}
			@Override public CompletableFuture<String> createDatapack(String n, String d) { return CompletableFuture.completedFuture(n); }
			@Override public CompletableFuture<String> createNamespace(String p, String n) { return CompletableFuture.completedFuture(p); }
			@Override public CompletableFuture<String> createFunction(String p, String n, String f) { return CompletableFuture.completedFuture(p); }
			@Override public CompletableFuture<List<PresetDescriptor>> listPresets() { return CompletableFuture.completedFuture(List.of()); }
			@Override public CompletableFuture<FileDocument> applyPreset(String p, String id, Map<String, String> v) {
				return CompletableFuture.failedFuture(new UnsupportedOperationException());
			}
			@Override public CompletableFuture<ReloadReport> reloadDatapacks() {
				return CompletableFuture.completedFuture(new ReloadReport(true, 1L, 0, List.of()));
			}
		};
		ClientEditorSession session = new ClientEditorSession(backend, Runnable::run);
		session.refreshTree();
		QuillIdeScreen screen = new QuillIdeScreen(null, null, session, new QuillIdeScreen.TextInputBridge() {
			@Override public void startTextInput(Object owner) { }
			@Override public void stopTextInput(Object owner) { }
		});
		session.openOrFocusDocument(new FileDocument("pack/data/demo/function/tick.mcfunction", "say hi", "h1", 0L));
		session.activeTab().buffer().setCursor(0, "say hi".length(), false);
		session.activeTab().buffer().insertText("!");
		screen.setSidebarFocused(false);

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		int ctrlShift = ctrl | InputConstants.MOD_SHIFT;
		screen.keyPressed(new KeyEvent(InputConstants.KEY_U, 'u', ctrl));
		assertEquals("say hi", session.activeTab().buffer().getText(), "Rebound Undo should undo code edit.");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_U, 'u', ctrlShift));
		assertEquals("say hi!", session.activeTab().buffer().getText(), "Rebound Redo should redo code edit.");

		// Explorer/outside editor focus -> file stack uses same rebound UNDO/REDO shortcuts
		session.deleteEntry("pack/data/demo/function/victim.mcfunction").join();
		deletedPaths.clear();
		screen.setSidebarFocused(true);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_U, 'u', ctrl));
		assertTrue(deletedPaths.contains("create:pack/data/demo/function/victim.mcfunction"), "Rebound Undo should restore deleted file.");
		deletedPaths.clear();
		screen.keyPressed(new KeyEvent(InputConstants.KEY_U, 'u', ctrlShift));
		assertTrue(deletedPaths.contains("delete:pack/data/demo/function/victim.mcfunction"), "Rebound Redo should re-delete file.");
	}
}
