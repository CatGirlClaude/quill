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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomPanelWidgetTest {

	private static ClientEditorSession newTestSession() {
		EditorBackend backend = new EditorBackend() {
			@Override
			public CompletableFuture<FileTreeNode> listTree() {
				return CompletableFuture.completedFuture(new FileTreeNode("datapacks", "", EntryKind.ROOT, List.of()));
			}

			@Override
			public CompletableFuture<FileDocument> readFile(String relativePath) {
				return CompletableFuture.completedFuture(new FileDocument(relativePath, "", "sha", 0L));
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
		return new ClientEditorSession(backend, Runnable::run);
	}

	@Test
	void outputTabSupportsMouseDragSelectionCtrlASelectAllAndCtrlCCopy() {
		ClientEditorSession session = newTestSession();
		session.clearOutputLog();
		session.appendOutput(ClientEditorSession.LogLevel.INFO, "Reload started");
		session.appendOutput(ClientEditorSession.LogLevel.SUCCESS, "Executed demo:main");
		session.openBottomPanel(ClientEditorSession.BottomPanelTab.OUTPUT);

		BottomPanelWidget widget = new BottomPanelWidget(session);
		AtomicReference<String> clipboard = new AtomicReference<>("");
		widget.setClipboardSetterForTest(clipboard::set);
		widget.setLayoutBoundsForTest(
				new IdeLayout.Rect(0, 100, 400, 120),
				new IdeLayout.Rect(0, 118, 400, 102)
		);

		String line0 = BottomPanelWidget.formatEntryLine(session.outputLog().get(0));
		String line1 = BottomPanelWidget.formatEntryLine(session.outputLog().get(1));
		String line2 = BottomPanelWidget.formatEntryLine(session.outputLog().get(2));

		// Click on line 0 and drag down to line 1 -> multi-line selection
		assertTrue(widget.mouseClicked(new MouseButtonEvent(
				24, 124, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))));
		assertTrue(widget.isOutputFocused());
		assertTrue(widget.mouseDragged(new MouseButtonEvent(
				120, 136, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))));
		widget.mouseReleased(new MouseButtonEvent(
				120, 136, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		assertTrue(widget.hasOutputSelection(), "Dragging across output lines must select text.");

		int ctrl = net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER;
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_C, 'c', ctrl)));
		assertTrue(clipboard.get().contains("\n"), "Multi-line copy must include newline between lines.");
		assertFalse(clipboard.get().isBlank());

		// Ctrl+A selects all output lines cleanly and Ctrl+C copies exact full log
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_A, 'a', ctrl)));
		assertTrue(widget.keyPressed(new KeyEvent(InputConstants.KEY_C, 'c', ctrl)));
		assertEquals(line0 + "\n" + line1 + "\n" + line2, clipboard.get());
	}
}
